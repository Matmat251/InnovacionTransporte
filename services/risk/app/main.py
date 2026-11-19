from __future__ import annotations

import logging
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Literal

import joblib
import numpy as np
from fastapi import FastAPI, Request, Response
from pydantic import BaseModel, ConfigDict, Field

from app.train_demo import MODEL_VERSION, artifacts_dir, train_demo_models

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("risk-inference")

PERU_OFFSET = timedelta(hours=-5)
SUPPORTED_ROUTES = {"LIM-HYO-01", "HYO-LIM-01", "HYO-LIM-ALT-01", "LIM-TRM-01", "TRM-LIM-01"}


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
    calibrated: bool
    riskLevel: Literal["BAJO", "MEDIO", "ALTO", "INFORMACION_INSUFICIENTE"]
    missingFeatures: list[str]
    latencyMs: float
    evaluatedAtUtc: datetime


class HealthResponse(BaseModel):
    status: str
    service: str
    mode: Literal["DEMO", "REAL"]
    modelVersion: str
    defaultThresholdMinutes: int


def _load_or_train_model() -> object:
    model_path = artifacts_dir() / "demo_model.joblib"
    if model_path.exists():
        return joblib.load(model_path)
    seed = int(os.getenv("RISK_RANDOM_SEED", "42"))
    threshold = int(os.getenv("APP_DELAY_THRESHOLD_MINUTES", "30"))
    model, _ = train_demo_models(seed=seed, delay_threshold_minutes=threshold, save_artifacts=False)
    return model


MODEL_MODE: Literal["DEMO", "REAL"] = (
    "DEMO" if os.getenv("RISK_MODEL_MODE", "REAL").upper() == "DEMO" else "REAL"
)
DEFAULT_THRESHOLD = int(os.getenv("APP_DELAY_THRESHOLD_MINUTES", "30"))
MODEL = _load_or_train_model()

app = FastAPI(
    title="Servicio Interno de Riesgo de Retraso",
    version="0.1.0",
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
    )


def _classify_risk_level(probability: float) -> Literal["BAJO", "MEDIO", "ALTO"]:
    if probability >= 0.65:
        return "ALTO"
    if probability >= 0.35:
        return "MEDIO"
    return "BAJO"


@app.post("/internal/v1/predictions", response_model=PredictionResponse)
def predict_delay(payload: PredictionRequest) -> PredictionResponse:
    started = time.perf_counter()
    now_utc = datetime.now(timezone.utc)

    missing: list[str] = []
    if payload.routeCode not in SUPPORTED_ROUTES and not payload.routeCode.startswith(("LIM-", "HYO-", "TRM-")):
        missing.append(f"routeCode:{payload.routeCode}")
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
            calibrated=False,
            riskLevel="INFORMACION_INSUFICIENTE",
            missingFeatures=missing,
            latencyMs=round((time.perf_counter() - started) * 1000.0, 2),
            evaluatedAtUtc=now_utc,
        )

    assert payload.weatherSummary is not None
    dep_utc = payload.scheduledDepartureUtc
    if dep_utc.tzinfo is None:
        dep_utc = dep_utc.replace(tzinfo=timezone.utc)
    dep_peru = dep_utc + PERU_OFFSET

    features = np.array(
        [
            [
                float(payload.plannedDurationMinutes),
                float(dep_peru.hour),
                float(dep_peru.weekday()),
                float(payload.weatherSummary.maxPrecipitationMm),
                float(payload.weatherSummary.maxPrecipitationProbabilityPct),
                float(payload.weatherSummary.minTemperatureC),
                float(payload.weatherSummary.maxWindKmh),
                float(payload.weatherSummary.minVisibilityMeters),
            ]
        ],
        dtype=float,
    )

    probability = float(MODEL.predict_proba(features)[0][1])
    # Ajuste conservador cuando se solicita un umbral distinto al de entrenamiento
    if payload.delayThresholdMinutes > 30:
        scale = 30.0 / float(payload.delayThresholdMinutes)
        probability = float(np.clip(probability * scale, 0.01, 0.99))

    probability = round(probability, 4)
    return PredictionResponse(
        status="OK",
        mode=MODEL_MODE,
        modelVersion=MODEL_VERSION,
        thresholdMinutes=payload.delayThresholdMinutes,
        delayProbability=probability,
        calibrated=True,
        riskLevel=_classify_risk_level(probability),
        missingFeatures=[],
        latencyMs=round((time.perf_counter() - started) * 1000.0, 2),
        evaluatedAtUtc=now_utc,
    )
