from __future__ import annotations

from tests.conftest import auth_headers


def test_should_list_orders_by_event_with_refund_filter(client):
    response = client.get(
        "/v3/events/event_1/orders/?refund_request_statuses=completed",
        headers=auth_headers(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["orders"][0]["id"] == "order_1"
    assert body["orders"][0]["refund_request"]["status"] == "completed"


def test_should_list_attendees_by_event_with_status_filter(client):
    response = client.get(
        "/v3/events/event_1/attendees/?status=attending",
        headers=auth_headers(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["attendees"][0]["id"] == "attendee_1"
    assert body["attendees"][0]["status"] == "attending"
