from fastapi.testclient import TestClient

from app.main import app
from app.train_demo import TRAINED_ROUTES, train_demo_models

client = TestClient(app)


def test_health_and_metadata_endpoints() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["mode"] == "REAL"
    assert body["defaultThresholdMinutes"] == 30
    assert set(body["trainedRoutes"]) == set(TRAINED_ROUTES)
    assert "X-Correlation-Id" in response.headers

    meta_res = client.get("/internal/v1/model-metadata")
    assert meta_res.status_code == 200
    meta = meta_res.json()
    winner = meta["metrics"][meta["selectedCandidate"]]
    assert winner["recall"] >= 0.70
    assert winner["rocAuc"] >= 0.75
    assert winner["prAuc"] >= 0.55
    assert "60" in meta["metricsByThreshold"]


def test_prediction_multi_route_returns_calibrated_probability_and_factors() -> None:
    for route_code in ("LIM-HYO-01", "LIM-TRM-01", "HYO-LIM-ALT-01"):
        response = client.post(
            "/internal/v1/predictions",
            headers={"X-Correlation-Id": "test-corr-123"},
            json={
                "routeCode": route_code,
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
        assert body["mode"] == "REAL"
        assert body["calibrated"] is True
        assert 0.0 <= body["delayProbability"] <= 1.0
        assert body["riskLevel"] in {"BAJO", "MEDIO", "ALTO"}
        assert len(body["topFactors"]) == 3


def test_prediction_rejects_untrained_route_and_uncalibrated_threshold() -> None:
    res_route = client.post(
        "/internal/v1/predictions",
        json={
            "routeCode": "CUS-PUN-99",
            "scheduledDepartureUtc": "2026-11-20T12:30:00Z",
            "plannedDurationMinutes": 480,
            "delayThresholdMinutes": 30,
            "weatherSummary": {
                "maxPrecipitationMm": 2.0,
                "maxPrecipitationProbabilityPct": 40,
                "minTemperatureC": 8.0,
                "maxWindKmh": 15.0,
                "minVisibilityMeters": 8000,
                "worstWeatherCode": 3,
                "coverageRatio": 1.0,
            },
        },
    )
    assert res_route.status_code == 200
    assert res_route.json()["status"] == "INFORMACION_INSUFICIENTE"
    assert any("CUS-PUN-99" in m for m in res_route.json()["missingFeatures"])

    res_thr = client.post(
        "/internal/v1/predictions",
        json={
            "routeCode": "LIM-HYO-01",
            "scheduledDepartureUtc": "2026-11-20T12:30:00Z",
            "plannedDurationMinutes": 480,
            "delayThresholdMinutes": 45,
            "weatherSummary": {
                "maxPrecipitationMm": 2.0,
                "maxPrecipitationProbabilityPct": 40,
                "minTemperatureC": 8.0,
                "maxWindKmh": 15.0,
                "minVisibilityMeters": 8000,
                "worstWeatherCode": 3,
                "coverageRatio": 1.0,
            },
        },
    )
    assert res_thr.status_code == 200
    assert res_thr.json()["status"] == "INFORMACION_INSUFICIENTE"
    assert any("45" in m for m in res_thr.json()["missingFeatures"])


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
