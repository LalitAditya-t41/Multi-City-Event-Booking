from __future__ import annotations


def test_should_return_dashboard_snapshot_with_seeded_ids(client):
    response = client.get("/mock/dashboard")

    assert response.status_code == 200
    body = response.json()
    assert "event_1" in body["events"]
    assert "series_1" in body["events"]
    assert "venue_1" in body["venues"]
    assert "order_1" in body["orders"]
    assert "attendee_1" in body["attendees"]


def test_should_update_runtime_config(client):
    response = client.post(
        "/mock/config",
        json={
            "transitionDelayMs": 1234,
            "generalRateLimit": 10,
            "dailyRateLimit": 20,
            "eventActionRateLimit": 3,
            "failureRates": {"order": 0.1, "refund": 0.2, "webhook": 0.0},
        },
    )

    assert response.status_code == 200
    config = response.json()["config"]
    assert config["transitionDelayMs"] == 1234
    assert config["generalRateLimit"] == 10
    assert config["dailyRateLimit"] == 20
    assert config["eventActionRateLimit"] == 3
    assert config["failureRates"]["order"] == 0.1


def test_should_reset_mock_state(client):
    response = client.post("/mock/reset")

    assert response.status_code == 200
    body = response.json()
    assert body["message"] == "Mock state cleared."
    assert "defaultConfig" in body
