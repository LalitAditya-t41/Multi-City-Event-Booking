from __future__ import annotations

from tests.conftest import auth_headers


def _base_event_payload():
    return {
        "event": {
            "name": {"html": "Test Event"},
            "start": {"timezone": "UTC", "utc": "2030-01-01T10:00:00Z"},
            "end": {"timezone": "UTC", "utc": "2030-01-01T12:00:00Z"},
            "currency": "USD",
            "venue_id": "venue_1",
        }
    }


def test_should_reject_event_with_venue_and_online_flag(client):
    payload = _base_event_payload()
    payload["event"]["online_event"] = True

    response = client.post("/v3/organizations/org_1/events/", headers=auth_headers(), json=payload)

    assert response.status_code == 400
    body = response.json()
    assert body["error"] == "VENUE_AND_ONLINE"


def test_should_create_and_delete_draft_event(client):
    response = client.post("/v3/organizations/org_1/events/", headers=auth_headers(), json=_base_event_payload())

    assert response.status_code == 200
    event_id = response.json()["id"]

    delete_response = client.delete(f"/v3/events/{event_id}/", headers=auth_headers())

    assert delete_response.status_code == 200
    assert delete_response.json() == {"deleted": True}


def test_should_publish_and_unpublish_event(client):
    publish = client.post("/v3/events/event_1/publish/", headers=auth_headers())

    assert publish.status_code == 200
    assert publish.json() == {"published": True}

    unpublish = client.post("/v3/events/event_1/unpublish/", headers=auth_headers())

    assert unpublish.status_code == 200
    assert unpublish.json() == {"unpublished": True}


def test_should_list_events_by_org_with_pagination(client):
    response = client.get("/v3/organizations/org_1/events/?page_size=2&page=1", headers=auth_headers())

    assert response.status_code == 200
    body = response.json()
    assert body["pagination"]["page_size"] == 2
    assert len(body["events"]) <= 2
