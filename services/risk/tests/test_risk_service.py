from fastapi.testclient import TestClient

from app.main import app
from app.train_demo import train_demo_models

client = TestClient(app)


def test_health_endpoint() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["mode"] == "DEMO"
    assert body["defaultThresholdMinutes"] == 30
    assert "X-Correlation-Id" in response.headers


def test_prediction_with_complete_weather_returns_demo_probability() -> None:
    response = client.post(
        "/internal/v1/predictions",
        headers={"X-Correlation-Id": "test-corr-123"},
        json={
            "routeCode": "LIM-HYO-01",
            "scheduledDepartureUtc": "2026-11-20T12:30:00Z",
            "plannedDurationMinutes": 480,
            "delayThresholdMinutes": 30,
            "weatherSummary": {
                "maxPrecipitationMm": 5.4,
                "maxPrecipitationProbabilityPct": 85,
                "minTemperatureC": 2.1,
                "maxWindKmh": 36.0,
                "minVisibilityMeters": 3000,
                "worstWeatherCode": 63,
                "coverageRatio": 1.0,
            },
        },
    )
    assert response.status_code == 200
    assert response.headers["X-Correlation-Id"] == "test-corr-123"
    body = response.json()
    assert body["status"] == "OK"
    assert body["mode"] == "DEMO"
    assert body["calibrated"] is True
    assert 0.0 <= body["delayProbability"] <= 1.0
    assert body["riskLevel"] in {"BAJO", "MEDIO", "ALTO"}


def test_prediction_without_weather_returns_insufficient_data() -> None:
    response = client.post(
        "/internal/v1/predictions",
        json={
            "routeCode": "LIM-HYO-01",
            "scheduledDepartureUtc": "2026-11-20T12:30:00Z",
            "plannedDurationMinutes": 480,
            "delayThresholdMinutes": 30,
            "weatherSummary": None,
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "INFORMACION_INSUFICIENTE"
    assert body["riskLevel"] == "INFORMACION_INSUFICIENTE"
    assert body["delayProbability"] is None
    assert "weatherSummary" in body["missingFeatures"]


def test_prediction_rejects_post_trip_fields() -> None:
    response = client.post(
        "/internal/v1/predictions",
        json={
            "routeCode": "LIM-HYO-01",
            "scheduledDepartureUtc": "2026-11-20T12:30:00Z",
            "plannedDurationMinutes": 480,
            "actualArrivalUtc": "2026-11-20T21:10:00Z",
        },
    )
    assert response.status_code == 422


def test_reproducible_demo_training_is_deterministic() -> None:
    _, meta_a = train_demo_models(seed=42, delay_threshold_minutes=30, save_artifacts=False)
    _, meta_b = train_demo_models(seed=42, delay_threshold_minutes=30, save_artifacts=False)
    assert meta_a["datasetSha256"] == meta_b["datasetSha256"]
    assert meta_a["metrics"] == meta_b["metrics"]
    assert meta_a["sampleCounts"]["train"] > 0
    assert meta_a["sampleCounts"]["test"] > 0
