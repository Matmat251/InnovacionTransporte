from __future__ import annotations

import json
import logging
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

import joblib
import numpy as np
from fastapi import FastAPI, Request, Response
from pydantic import BaseModel, ConfigDict, Field

from app.train_demo import (
    FEATURE_ORDER,
    MODEL_VERSION,
    SUPPORTED_THRESHOLDS,
    TRAINED_ROUTES,
    artifacts_dir,
    encode_features,
    train_demo_models,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("risk-inference")

PERU_OFFSET = timedelta(hours=-5)

FEATURE_LABELS_ES: dict[str, str] = {
    "planned_duration_minutes": "Duración programada",
    "departure_hour": "Franja horaria de salida",
    "day_of_week": "Día de semana",
    "max_precipitation_mm": "Precipitación máx.",
    "max_precipitation_prob_pct": "Probabilidad de lluvia",
    "min_temperature_c": "Temperatura mín.",
    "max_wind_kmh": "Viento máx.",
    "min_visibility_m": "Visibilidad mín.",
}

INVERSE_RISK_FEATURES = {"min_temperature_c", "min_visibility_m"}


class WeatherSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    maxPrecipitationMm: float = Field(ge=0)
    maxPrecipitationProbabilityPct: float = Field(ge=0, le=100)
    minTemperatureC: float
    maxWindKmh: float = Field(ge=0)
    minVisibilityMeters: float = Field(ge=0)
    worstWeatherCode: int
    coverageRatio: float = Field(ge=0, le=1)


class PredictionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    routeCode: str = Field(min_length=3, max_length=32)
    scheduledDepartureUtc: datetime
    plannedDurationMinutes: int = Field(ge=30, le=2880)
    delayThresholdMinutes: int = Field(default=30, ge=10, le=240)
    weatherSummary: WeatherSummary | None = None


class PredictionResponse(BaseModel):
    status: Literal["OK", "INFORMACION_INSUFICIENTE"]
    mode: Literal["DEMO", "REAL"]
    modelVersion: str
    thresholdMinutes: int
    delayProbability: float | None
    decisionThreshold: float | None = None
    calibrated: bool
    riskLevel: Literal["BAJO", "MEDIO", "ALTO", "INFORMACION_INSUFICIENTE"]
    topFactors: list[str] = Field(default_factory=list)
    missingFeatures: list[str]
    latencyMs: float
    evaluatedAtUtc: datetime


class HealthResponse(BaseModel):
    status: str
    service: str
    mode: Literal["DEMO", "REAL"]
    modelVersion: str
    defaultThresholdMinutes: int
    trainedRoutes: list[str]
    supportedThresholdsMinutes: list[int]


def _load_or_train_bundle() -> tuple[dict[str, Any], dict[str, Any]]:
    model_path = artifacts_dir() / "demo_model.joblib"
    meta_path = artifacts_dir() / "model_metadata.json"
    if model_path.exists() and meta_path.exists():
        loaded = joblib.load(model_path)
        if isinstance(loaded, dict) and loaded.get("modelVersion") == MODEL_VERSION:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            return loaded, meta
    seed = int(os.getenv("RISK_RANDOM_SEED", "42"))
    threshold = int(os.getenv("APP_DELAY_THRESHOLD_MINUTES", "30"))
    return train_demo_models(seed=seed, delay_threshold_minutes=threshold, save_artifacts=False)


MODEL_MODE: Literal["DEMO", "REAL"] = (
    "DEMO" if os.getenv("RISK_MODEL_MODE", "REAL").upper() == "DEMO" else "REAL"
)
DEFAULT_THRESHOLD = int(os.getenv("APP_DELAY_THRESHOLD_MINUTES", "30"))
MODEL_BUNDLE, MODEL_METADATA = _load_or_train_bundle()

app = FastAPI(
    title="Servicio Interno de Riesgo de Retraso",
    version="0.2.0",
    docs_url="/internal/docs",
    openapi_url="/internal/openapi.json",
)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next) -> Response:
    correlation_id = request.headers.get("X-Correlation-Id") or str(uuid.uuid4())
    started = time.perf_counter()
    response = await call_next(request)
    elapsed_ms = round((time.perf_counter() - started) * 1000.0, 2)
    response.headers["X-Correlation-Id"] = correlation_id
    logger.info(
        "correlationId=%s method=%s path=%s status=%s latencyMs=%s",
        correlation_id,
        request.method,
        request.url.path,
        response.status_code,
        elapsed_ms,
    )
    return response


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="UP",
        service="risk-inference",
        mode=MODEL_MODE,
        modelVersion=MODEL_VERSION,
        defaultThresholdMinutes=DEFAULT_THRESHOLD,
        trainedRoutes=list(TRAINED_ROUTES),
        supportedThresholdsMinutes=list(SUPPORTED_THRESHOLDS),
    )


@app.get("/internal/v1/model-metadata")
def get_model_metadata() -> dict[str, Any]:
    return MODEL_METADATA


def _classify_risk_level(probability: float, op_threshold: float) -> Literal["BAJO", "MEDIO", "ALTO"]:
    if probability >= max(0.60, op_threshold + 0.20):
        return "ALTO"
    if probability >= op_threshold:
        return "MEDIO"
    return "BAJO"


def _explain_top_factors(
    feature_values: list[float],
    threshold_minutes: int,
    weather: WeatherSummary,
) -> list[str]:
    importances: list[float] = MODEL_BUNDLE["featureImportances"][threshold_minutes]
    means: list[float] = MODEL_BUNDLE["featureMeans"]
    stds: list[float] = MODEL_BUNDLE["featureStds"]

    scored: list[tuple[float, str]] = []
    unit_map = {
        "planned_duration_minutes": f"{int(feature_values[0])} min",
        "departure_hour": f"{int(feature_values[1]):02d}:00 PE",
        "day_of_week": f"día {int(feature_values[2]) + 1}",
        "max_precipitation_mm": f"{weather.maxPrecipitationMm:.1f} mm",
        "max_precipitation_prob_pct": f"{int(weather.maxPrecipitationProbabilityPct)}%",
        "min_temperature_c": f"{weather.minTemperatureC:.1f} °C",
        "max_wind_kmh": f"{weather.maxWindKmh:.1f} km/h",
        "min_visibility_m": f"{int(weather.minVisibilityMeters)} m",
    }

    for idx, fname in enumerate(FEATURE_ORDER[:8]):
        z = (feature_values[idx] - means[idx]) / stds[idx]
        signed = -z if fname in INVERSE_RISK_FEATURES else z
        impact = signed * importances[idx]
        direction = "eleva riesgo" if impact >= 0 else "reduce riesgo"
        label = f"{FEATURE_LABELS_ES[fname]} ({unit_map[fname]}): {direction} (peso {importances[idx] * 100:.0f}%)"
        scored.append((abs(impact), label))

    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [item[1] for item in scored[:3]]


@app.post("/internal/v1/predictions", response_model=PredictionResponse)
def predict_delay(payload: PredictionRequest) -> PredictionResponse:
    started = time.perf_counter()
    now_utc = datetime.now(timezone.utc)

    missing: list[str] = []
    if payload.routeCode not in TRAINED_ROUTES:
        missing.append(f"routeCodeNoEntrenada:{payload.routeCode}")
    if payload.delayThresholdMinutes not in SUPPORTED_THRESHOLDS:
        missing.append(f"umbralNoCalibrado:{payload.delayThresholdMinutes}")
    if payload.weatherSummary is None:
        missing.append("weatherSummary")
    elif payload.weatherSummary.coverageRatio < 0.5:
        missing.append("weatherSummary.coverageRatio<0.5")

    if missing:
        return PredictionResponse(
            status="INFORMACION_INSUFICIENTE",
            mode=MODEL_MODE,
            modelVersion=MODEL_VERSION,
            thresholdMinutes=payload.delayThresholdMinutes,
            delayProbability=None,
            decisionThreshold=None,
            calibrated=False,
            riskLevel="INFORMACION_INSUFICIENTE",
            topFactors=[],
            missingFeatures=missing,
            latencyMs=round((time.perf_counter() - started) * 1000.0, 2),
            evaluatedAtUtc=now_utc,
        )

    assert payload.weatherSummary is not None
    dep_utc = payload.scheduledDepartureUtc
    if dep_utc.tzinfo is None:
        dep_utc = dep_utc.replace(tzinfo=timezone.utc)
    dep_peru = dep_utc + PERU_OFFSET

    feat_list = encode_features(
        route_code=payload.routeCode,
        planned_duration_minutes=payload.plannedDurationMinutes,
        departure_hour=dep_peru.hour,
        day_of_week=dep_peru.weekday(),
        max_precip_mm=payload.weatherSummary.maxPrecipitationMm,
        max_precip_prob=payload.weatherSummary.maxPrecipitationProbabilityPct,
        min_temp_c=payload.weatherSummary.minTemperatureC,
        max_wind_kmh=payload.weatherSummary.maxWindKmh,
        min_vis_m=payload.weatherSummary.minVisibilityMeters,
    )
    features = np.array([feat_list], dtype=float)

    model = MODEL_BUNDLE["modelsByThreshold"][payload.delayThresholdMinutes]
    op_threshold = float(MODEL_BUNDLE["operatingThresholds"][payload.delayThresholdMinutes])
    probability = round(float(model.predict_proba(features)[0][1]), 4)
    factors = _explain_top_factors(feat_list, payload.delayThresholdMinutes, payload.weatherSummary)

    return PredictionResponse(
        status="OK",
        mode=MODEL_MODE,
        modelVersion=MODEL_VERSION,
        thresholdMinutes=payload.delayThresholdMinutes,
        delayProbability=probability,
        decisionThreshold=op_threshold,
        calibrated=True,
        riskLevel=_classify_risk_level(probability, op_threshold),
        topFactors=factors,
        missingFeatures=[],
        latencyMs=round((time.perf_counter() - started) * 1000.0, 2),
        evaluatedAtUtc=now_utc,
    )
