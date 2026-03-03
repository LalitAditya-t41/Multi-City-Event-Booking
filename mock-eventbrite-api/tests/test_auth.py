from __future__ import annotations

from tests.conftest import auth_headers


def test_should_return_401_when_auth_is_missing(client):
    response = client.get("/v3/events/event_1/")

    assert response.status_code == 401
    assert response.json() == {
        "error": "NO_AUTH",
        "error_description": "Authentication not provided.",
        "status_code": 401,
    }


def test_should_return_400_when_auth_header_is_invalid(client):
    response = client.get("/v3/events/event_1/", headers={"Authorization": "Token abc"})

    assert response.status_code == 400
    body = response.json()
    assert body["error"] == "INVALID_AUTH_HEADER"
    assert body["status_code"] == 400


def test_should_allow_query_token_auth_and_attach_rate_limit_headers(client):
    response = client.get("/v3/events/event_1/?token=demo")

    assert response.status_code == 200
    assert response.json()["id"] == "event_1"
    assert "X-RateLimit-Limit" in response.headers
    assert "X-RateLimit-Remaining" in response.headers


def test_should_allow_bearer_token_auth(client):
    response = client.get("/v3/events/event_1/", headers=auth_headers())

    assert response.status_code == 200
    assert response.json()["id"] == "event_1"
