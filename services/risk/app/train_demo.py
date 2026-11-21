from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

MODEL_VERSION = "corredor-central-rf-v2.0.0"
TRAINED_ROUTES = ("LIM-HYO-01", "HYO-LIM-01", "HYO-LIM-ALT-01", "LIM-TRM-01")
SUPPORTED_THRESHOLDS = (30, 60)

NUMERIC_FEATURES = [
    "planned_duration_minutes",
    "departure_hour",
    "day_of_week",
    "max_precipitation_mm",
    "max_precipitation_prob_pct",
    "min_temperature_c",
    "max_wind_kmh",
    "min_visibility_m",
]
ROUTE_FEATURES = [f"route_{code}" for code in TRAINED_ROUTES]
FEATURE_ORDER = NUMERIC_FEATURES + ROUTE_FEATURES

ROUTE_PROFILES: dict[str, dict[str, float]] = {
    "LIM-HYO-01": {"base_dur": 480.0, "dur_jitter": 25.0, "route_bias": 4.0, "temp_base": 4.2, "rain_mult": 1.0},
    "HYO-LIM-01": {"base_dur": 480.0, "dur_jitter": 25.0, "route_bias": 3.0, "temp_base": 4.0, "rain_mult": 1.0},
    "HYO-LIM-ALT-01": {"base_dur": 570.0, "dur_jitter": 35.0, "route_bias": 8.5, "temp_base": 3.2, "rain_mult": 0.9},
    "LIM-TRM-01": {"base_dur": 450.0, "dur_jitter": 30.0, "route_bias": 6.0, "temp_base": 6.5, "rain_mult": 1.25},
}


def artifacts_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "artifacts"


def encode_features(
    route_code: str,
    planned_duration_minutes: float,
    departure_hour: float,
    day_of_week: float,
    max_precip_mm: float,
    max_precip_prob: float,
    min_temp_c: float,
    max_wind_kmh: float,
    min_vis_m: float,
) -> list[float]:
    route_one_hot = [1.0 if route_code == code else 0.0 for code in TRAINED_ROUTES]
    return [
        float(planned_duration_minutes),
        float(departure_hour),
        float(day_of_week),
        float(max_precip_mm),
        float(max_precip_prob),
        float(min_temp_c),
        float(max_wind_kmh),
        float(min_vis_m),
        *route_one_hot,
    ]


def generate_demo_dataset(
    seed: int = 42,
    n_samples: int = 720,
) -> tuple[np.ndarray, np.ndarray, list[datetime], str]:
    """Genera un dataset reproducible multi-ruta con retrasos latentes en minutos para entrenamiento temporal."""
    rng = np.random.default_rng(seed)
    base_time = datetime(2026, 1, 1, 6, 0, tzinfo=timezone.utc)

    timestamps: list[datetime] = []
    features: list[list[float]] = []
    latent_delays: list[float] = []

    for idx in range(n_samples):
        route_code = TRAINED_ROUTES[idx % len(TRAINED_ROUTES)]
        prof = ROUTE_PROFILES[route_code]

        departure = base_time + timedelta(hours=6 * idx)
        departure_hour = float((departure - timedelta(hours=5)).hour)  # Hora local Perú
        day_of_week = float(departure.weekday())
        planned_duration = float(
            np.round(prof["base_dur"] + rng.uniform(-prof["dur_jitter"], prof["dur_jitter"]))
        )

        wet_season = 1.0 if departure.month in (1, 2, 3, 11, 12) else 0.35
        max_precip_mm = float(
            np.round(rng.gamma(shape=1.6, scale=(2.3 * wet_season + 0.6) * prof["rain_mult"]), 2)
        )
        max_precip_prob = float(np.clip(18.0 + max_precip_mm * 11.2 + rng.normal(0, 9), 0, 100))
        min_temp_c = float(np.round(rng.normal(loc=prof["temp_base"] - 1.4 * wet_season, scale=2.7), 1))
        max_wind_kmh = float(np.round(np.clip(rng.normal(loc=21.0 + max_precip_mm * 1.5, scale=6.2), 5, 78), 1))
        min_vis_m = float(np.round(np.clip(10000.0 - max_precip_mm * 860.0 + rng.normal(0, 850), 350, 10000), 0))

        delay_minutes = (
            prof["route_bias"]
            + max_precip_mm * 5.2
            + (max_wind_kmh - 18.0) * 0.45
            + max(0.0, (4200.0 - min_vis_m) / 190.0)
            + (7.5 if min_temp_c < 2.0 else 0.0)
            + (6.5 if departure_hour >= 18 or departure_hour <= 4 else 0.0)
            + rng.normal(0, 7.0)
        )

        timestamps.append(departure)
        features.append(
            encode_features(
                route_code=route_code,
                planned_duration_minutes=planned_duration,
                departure_hour=departure_hour,
                day_of_week=day_of_week,
                max_precip_mm=max_precip_mm,
                max_precip_prob=max_precip_prob,
                min_temp_c=min_temp_c,
                max_wind_kmh=max_wind_kmh,
                min_vis_m=min_vis_m,
            )
        )
        latent_delays.append(float(delay_minutes))

    X = np.array(features, dtype=float)
    delays = np.array(latent_delays, dtype=float)
    digest = hashlib.sha256(X.tobytes() + delays.tobytes()).hexdigest()
    return X, delays, timestamps, digest


def _select_operating_threshold(y_true: np.ndarray, probs: np.ndarray, min_recall: float = 0.88) -> float:
    best_thr = 0.30
    best_score = -1.0
    for thr in np.linspace(0.15, 0.50, 36):
        preds = (probs >= thr).astype(int)
        prec = precision_score(y_true, preds, zero_division=0)
        rec = recall_score(y_true, preds, zero_division=0)
        f2 = (5.0 * prec * rec / (4.0 * prec + rec)) if (4.0 * prec + rec) > 0 else 0.0
        if rec >= min_recall and f2 > best_score:
            best_score = f2
            best_thr = float(thr)
    if best_score < 0:
        for thr in np.linspace(0.15, 0.50, 36):
            preds = (probs >= thr).astype(int)
            f1 = f1_score(y_true, preds, zero_division=0)
            if f1 > best_score:
                best_score = f1
                best_thr = float(thr)
    return round(best_thr, 3)


def _evaluate_model(model: Any, X_test: np.ndarray, y_test: np.ndarray, threshold: float = 0.5) -> dict[str, Any]:
    probs = model.predict_proba(X_test)[:, 1]
    preds = (probs >= threshold).astype(int)
    cm = confusion_matrix(y_test, preds, labels=[0, 1]).tolist()
    has_both = len(np.unique(y_test)) > 1
    return {
        "precision": round(float(precision_score(y_test, preds, zero_division=0)), 4),
        "recall": round(float(recall_score(y_test, preds, zero_division=0)), 4),
        "f1": round(float(f1_score(y_test, preds, zero_division=0)), 4),
        "rocAuc": round(float(roc_auc_score(y_test, probs)), 4) if has_both else 0.0,
        "prAuc": round(float(average_precision_score(y_test, probs)), 4) if has_both else 0.0,
        "brierScore": round(float(brier_score_loss(y_test, probs)), 4),
        "decisionThreshold": round(float(threshold), 3),
        "confusionMatrix": cm,
    }


def _train_for_threshold(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_test: np.ndarray,
    y_test: np.ndarray,
    seed: int,
) -> tuple[Any, dict[str, Any], float, list[float]]:
    dummy = DummyClassifier(strategy="most_frequent")
    dummy.fit(X_train, y_train)

    log_reg_base = Pipeline(
        [
            ("scaler", StandardScaler()),
            ("clf", LogisticRegression(max_iter=500, class_weight="balanced", random_state=seed)),
        ]
    )
    log_reg = CalibratedClassifierCV(log_reg_base, method="sigmoid", cv=3)
    log_reg.fit(X_train, y_train)
    lr_thr = _select_operating_threshold(y_train, log_reg.predict_proba(X_train)[:, 1], min_recall=0.88)

    rf_base = RandomForestClassifier(
        n_estimators=140,
        max_depth=6,
        min_samples_leaf=4,
        class_weight="balanced",
        random_state=seed,
    )
    rf_calibrated = CalibratedClassifierCV(rf_base, method="sigmoid", cv=3)
    rf_calibrated.fit(X_train, y_train)
    rf_thr = _select_operating_threshold(y_train, rf_calibrated.predict_proba(X_train)[:, 1], min_recall=0.88)

    rf_explainer = RandomForestClassifier(
        n_estimators=140,
        max_depth=6,
        min_samples_leaf=4,
        class_weight="balanced",
        random_state=seed,
    )
    rf_explainer.fit(X_train, y_train)
    importances = [round(float(v), 4) for v in rf_explainer.feature_importances_]

    metrics = {
        "dummyBaseline": _evaluate_model(dummy, X_test, y_test, threshold=0.5),
        "logisticRegression": _evaluate_model(log_reg, X_test, y_test, threshold=lr_thr),
        "randomForestCalibrated": _evaluate_model(rf_calibrated, X_test, y_test, threshold=rf_thr),
    }

    lr_f1 = metrics["logisticRegression"]["f1"]
    rf_f1 = metrics["randomForestCalibrated"]["f1"]
    selected_name = "randomForestCalibrated" if rf_f1 >= lr_f1 else "logisticRegression"
    selected_model = rf_calibrated if rf_f1 >= lr_f1 else log_reg
    selected_thr = rf_thr if rf_f1 >= lr_f1 else lr_thr

    return (
        selected_model,
        {"selectedCandidate": selected_name, "decisionThreshold": selected_thr, "models": metrics},
        selected_thr,
        importances,
    )


def train_demo_models(
    seed: int = 42,
    delay_threshold_minutes: int = 30,
    save_artifacts: bool = True,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Entrena modelos calibrados multi-ruta con división temporal para umbrales nativos 30 y 60 min."""
    X, delays, timestamps, dataset_sha256 = generate_demo_dataset(seed=seed)
    split_idx = int(len(X) * 0.75)
    X_train, X_test = X[:split_idx], X[split_idx:]
    d_train, d_test = delays[:split_idx], delays[split_idx:]

    models_by_threshold: dict[int, Any] = {}
    thresholds_by_min: dict[int, float] = {}
    metrics_by_threshold: dict[str, Any] = {}
    importances_by_threshold: dict[int, list[float]] = {}

    for thr_min in SUPPORTED_THRESHOLDS:
        y_tr = (d_train >= float(thr_min)).astype(int)
        y_te = (d_test >= float(thr_min)).astype(int)
        model, m_info, op_thr, imps = _train_for_threshold(X_train, y_tr, X_test, y_te, seed=seed)
        models_by_threshold[thr_min] = model
        thresholds_by_min[thr_min] = op_thr
        metrics_by_threshold[str(thr_min)] = m_info
        importances_by_threshold[thr_min] = imps

    primary_thr = delay_threshold_minutes if delay_threshold_minutes in SUPPORTED_THRESHOLDS else 30
    y_train_primary = (d_train >= float(primary_thr)).astype(int)
    y_test_primary = (d_test >= float(primary_thr)).astype(int)
    primary_info = metrics_by_threshold[str(primary_thr)]

    feature_means = [round(float(v), 4) for v in X_train.mean(axis=0)]
    feature_stds = [round(float(v) if float(v) > 1e-6 else 1.0, 4) for v in X_train.std(axis=0)]

    bundle: dict[str, Any] = {
        "modelVersion": MODEL_VERSION,
        "trainedRoutes": list(TRAINED_ROUTES),
        "supportedThresholds": list(SUPPORTED_THRESHOLDS),
        "modelsByThreshold": models_by_threshold,
        "operatingThresholds": thresholds_by_min,
        "featureImportances": importances_by_threshold,
        "featureMeans": feature_means,
        "featureStds": feature_stds,
    }

    metadata: dict[str, Any] = {
        "mode": os.getenv("RISK_MODEL_MODE", "REAL"),
        "modelVersion": MODEL_VERSION,
        "selectedCandidate": primary_info["selectedCandidate"],
        "decisionThreshold": primary_info["decisionThreshold"],
        "calibrated": True,
        "routeScope": list(TRAINED_ROUTES),
        "supportedThresholdsMinutes": list(SUPPORTED_THRESHOLDS),
        "delayThresholdMinutes": primary_thr,
        "randomSeed": seed,
        "featureSchema": FEATURE_ORDER,
        "featureImportances": dict(zip(FEATURE_ORDER, importances_by_threshold[primary_thr])),
        "datasetSha256": dataset_sha256,
        "sampleCounts": {
            "total": int(len(X)),
            "train": int(len(X_train)),
            "test": int(len(X_test)),
            "trainPositiveRate": round(float(y_train_primary.mean()), 4),
            "testPositiveRate": round(float(y_test_primary.mean()), 4),
        },
        "temporalSplit": {
            "trainPeriodUtc": [timestamps[0].isoformat(), timestamps[split_idx - 1].isoformat()],
            "testPeriodUtc": [timestamps[split_idx].isoformat(), timestamps[-1].isoformat()],
        },
        "metrics": primary_info["models"],
        "metricsByThreshold": metrics_by_threshold,
        "trainedAtUtc": datetime.now(timezone.utc).isoformat(),
    }

    if save_artifacts:
        out_dir = artifacts_dir()
        out_dir.mkdir(parents=True, exist_ok=True)
        joblib.dump(bundle, out_dir / "demo_model.joblib")
        (out_dir / "model_metadata.json").write_text(
            json.dumps(metadata, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

    return bundle, metadata


def main() -> None:
    seed = int(os.getenv("RISK_RANDOM_SEED", "42"))
    threshold = int(os.getenv("APP_DELAY_THRESHOLD_MINUTES", "30"))
    _, metadata = train_demo_models(seed=seed, delay_threshold_minutes=threshold, save_artifacts=True)
    print(json.dumps(metadata, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
