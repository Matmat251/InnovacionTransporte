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
from sklearn.metrics import brier_score_loss, confusion_matrix, f1_score, precision_score, recall_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

MODEL_VERSION = "demo-logreg-rf-v0.1.0"
FEATURE_ORDER = [
    "planned_duration_minutes",
    "departure_hour",
    "day_of_week",
    "max_precipitation_mm",
    "max_precipitation_prob_pct",
    "min_temperature_c",
    "max_wind_kmh",
    "min_visibility_m",
]


def artifacts_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "artifacts"


def generate_demo_dataset(
    seed: int = 42,
    n_samples: int = 360,
    delay_threshold_minutes: int = 30,
) -> tuple[np.ndarray, np.ndarray, list[datetime], str]:
    """Genera un dataset sintético determinista para el corredor Lima-Huancayo."""
    rng = np.random.default_rng(seed)
    base_time = datetime(2026, 1, 1, 6, 0, tzinfo=timezone.utc)

    timestamps: list[datetime] = []
    features: list[list[float]] = []
    labels: list[int] = []

    for idx in range(n_samples):
        departure = base_time + timedelta(hours=12 * idx)
        departure_hour = float((departure - timedelta(hours=5)).hour)  # Hora local Perú
        day_of_week = float(departure.weekday())
        planned_duration = float(rng.integers(450, 520))

        # Estacionalidad andina simplificada (mayor lluvia entre enero y marzo / noviembre)
        wet_season = 1.0 if departure.month in (1, 2, 3, 11, 12) else 0.3
        max_precip_mm = float(np.round(rng.gamma(shape=1.6, scale=2.2 * wet_season + 0.5), 2))
        max_precip_prob = float(np.clip(20.0 + max_precip_mm * 11.5 + rng.normal(0, 10), 0, 100))
        min_temp_c = float(np.round(rng.normal(loc=4.5 - 1.5 * wet_season, scale=2.8), 1))
        max_wind_kmh = float(np.round(np.clip(rng.normal(loc=22.0 + max_precip_mm * 1.4, scale=6.5), 5, 75), 1))
        min_vis_m = float(np.round(np.clip(10000.0 - max_precip_mm * 850.0 + rng.normal(0, 900), 400, 10000), 0))

        # Retraso sintético latente en minutos (exclusivamente para generar etiqueta DEMO)
        delay_score = (
            8.0
            + max_precip_mm * 4.8
            + (max_wind_kmh - 20.0) * 0.45
            + max(0.0, (4000.0 - min_vis_m) / 220.0)
            + (6.0 if min_temp_c < 2.0 else 0.0)
            + (5.0 if departure_hour >= 18 or departure_hour <= 4 else 0.0)
            + rng.normal(0, 8.0)
        )
        delayed = 1 if delay_score >= float(delay_threshold_minutes) else 0

        timestamps.append(departure)
        features.append(
            [
                planned_duration,
                departure_hour,
                day_of_week,
                max_precip_mm,
                max_precip_prob,
                min_temp_c,
                max_wind_kmh,
                min_vis_m,
            ]
        )
        labels.append(delayed)

    X = np.array(features, dtype=float)
    y = np.array(labels, dtype=int)
    digest = hashlib.sha256(X.tobytes() + y.tobytes()).hexdigest()
    return X, y, timestamps, digest


def _evaluate_model(model: Any, X_test: np.ndarray, y_test: np.ndarray) -> dict[str, Any]:
    preds = model.predict(X_test)
    probs = model.predict_proba(X_test)[:, 1]
    cm = confusion_matrix(y_test, preds, labels=[0, 1]).tolist()
    return {
        "precision": round(float(precision_score(y_test, preds, zero_division=0)), 4),
        "recall": round(float(recall_score(y_test, preds, zero_division=0)), 4),
        "f1": round(float(f1_score(y_test, preds, zero_division=0)), 4),
        "brierScore": round(float(brier_score_loss(y_test, probs)), 4),
        "confusionMatrix": cm,
    }


def train_demo_models(
    seed: int = 42,
    delay_threshold_minutes: int = 30,
    save_artifacts: bool = True,
) -> tuple[Any, dict[str, Any]]:
    """Entrena con separación temporal y compara línea base, Regresión Logística y Random Forest."""
    X, y, timestamps, dataset_sha256 = generate_demo_dataset(
        seed=seed,
        delay_threshold_minutes=delay_threshold_minutes,
    )
    split_idx = int(len(X) * 0.75)
    X_train, X_test = X[:split_idx], X[split_idx:]
    y_train, y_test = y[:split_idx], y[split_idx:]

    dummy = DummyClassifier(strategy="most_frequent")
    dummy.fit(X_train, y_train)

    log_reg = Pipeline(
        [
            ("scaler", StandardScaler()),
            ("clf", LogisticRegression(max_iter=500, random_state=seed)),
        ]
    )
    log_reg.fit(X_train, y_train)

    rf_base = RandomForestClassifier(
        n_estimators=120,
        max_depth=6,
        min_samples_leaf=4,
        random_state=seed,
    )
    rf_calibrated = CalibratedClassifierCV(rf_base, method="sigmoid", cv=3)
    rf_calibrated.fit(X_train, y_train)

    metrics = {
        "dummyBaseline": _evaluate_model(dummy, X_test, y_test),
        "logisticRegression": _evaluate_model(log_reg, X_test, y_test),
        "randomForestCalibrated": _evaluate_model(rf_calibrated, X_test, y_test),
    }

    # Seleccionar candidato según F1 en test temporal
    lr_f1 = metrics["logisticRegression"]["f1"]
    rf_f1 = metrics["randomForestCalibrated"]["f1"]
    selected_name = "randomForestCalibrated" if rf_f1 >= lr_f1 else "logisticRegression"
    selected_model = rf_calibrated if rf_f1 >= lr_f1 else log_reg

    metadata: dict[str, Any] = {
        "mode": os.getenv("RISK_MODEL_MODE", "DEMO"),
        "modelVersion": MODEL_VERSION,
        "selectedCandidate": selected_name,
        "calibrated": True,
        "routeScope": ["LIM-HYO-01"],
        "delayThresholdMinutes": delay_threshold_minutes,
        "randomSeed": seed,
        "featureSchema": FEATURE_ORDER,
        "datasetSha256": dataset_sha256,
        "sampleCounts": {
            "total": int(len(X)),
            "train": int(len(X_train)),
            "test": int(len(X_test)),
            "trainPositiveRate": round(float(y_train.mean()), 4),
            "testPositiveRate": round(float(y_test.mean()), 4),
        },
        "temporalSplit": {
            "trainPeriodUtc": [timestamps[0].isoformat(), timestamps[split_idx - 1].isoformat()],
            "testPeriodUtc": [timestamps[split_idx].isoformat(), timestamps[-1].isoformat()],
        },
        "metrics": metrics,
        "trainedAtUtc": datetime.now(timezone.utc).isoformat(),
    }

    if save_artifacts:
        out_dir = artifacts_dir()
        out_dir.mkdir(parents=True, exist_ok=True)
        joblib.dump(selected_model, out_dir / "demo_model.joblib")
        (out_dir / "model_metadata.json").write_text(
            json.dumps(metadata, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

    return selected_model, metadata


def main() -> None:
    seed = int(os.getenv("RISK_RANDOM_SEED", "42"))
    threshold = int(os.getenv("APP_DELAY_THRESHOLD_MINUTES", "30"))
    _, metadata = train_demo_models(seed=seed, delay_threshold_minutes=threshold, save_artifacts=True)
    print(json.dumps(metadata, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
