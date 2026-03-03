from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy.orm import Session

from app.models.organization import Organization
from app.models.venue import Venue
from app.models.event import Event
from app.models.ticket_class import TicketClass
from app.models.capacity_tier import CapacityTier
from app.models.seatmap import SeatMap
from app.models.order import Order
from app.models.attendee import Attendee
from app.models.discount import Discount


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _datetime_tz(utc_str: str, timezone_name: str = "America/Los_Angeles") -> dict:
    return {
        "timezone": timezone_name,
        "utc": utc_str,
        "local": utc_str.replace("Z", ""),
    }


def _datetime_tz_utc(utc_str: str) -> dict:
    return {
        "timezone": "UTC",
        "utc": utc_str,
    }


def seed_data(session: Session) -> None:
    now = datetime.now(timezone.utc)
    start = now + timedelta(days=7)
    end = start + timedelta(hours=2)

    org = Organization(id="org_1", name="Mock Organization")
    session.add(org)

    venue = Venue(
        id="venue_1",
        organization_id=org.id,
        name="Mock Venue",
        capacity=500,
        age_restriction="18+",
        latitude="37.7749",
        longitude="-122.4194",
        address={
            "address_1": "123 Market St",
            "address_2": "",
            "city": "San Francisco",
            "region": "CA",
            "postal_code": "94103",
            "country": "US",
            "latitude": "37.7749",
            "longitude": "-122.4194",
        },
    )
    session.add(venue)

    event = Event(
        id="event_1",
        organization_id=org.id,
        venue_id=venue.id,
        name={"text": "Mock Event", "html": "<p>Mock Event</p>"},
        summary="Mock event summary",
        description={"html": "<p>Mock event description</p>"},
        start=_datetime_tz(start.strftime("%Y-%m-%dT%H:%M:%SZ")),
        end=_datetime_tz(end.strftime("%Y-%m-%dT%H:%M:%SZ")),
        created=_now_iso(),
        changed=_now_iso(),
        published=None,
        status="draft",
        currency="USD",
        online_event=False,
        listed=True,
        shareable=True,
        invite_only=False,
        show_remaining=True,
        password=None,
        capacity=500,
        capacity_is_custom=True,
        is_series=False,
        series_parent_id=None,
        is_reserved_seating=False,
        show_pick_a_seat=False,
        show_seatmap_thumbnail=False,
        show_colors_in_seatmap_thumbnail=False,
        organizer_id=None,
        logo_id=None,
        format_id=None,
        category_id=None,
        subcategory_id=None,
        locale="en_US",
        source="api",
    )
    session.add(event)

    series_parent = Event(
        id="series_1",
        organization_id=org.id,
        venue_id=venue.id,
        name={"text": "Series Parent", "html": "<p>Series Parent</p>"},
        summary="Series parent",
        description={"html": "<p>Series description</p>"},
        start=_datetime_tz(start.strftime("%Y-%m-%dT%H:%M:%SZ")),
        end=_datetime_tz(end.strftime("%Y-%m-%dT%H:%M:%SZ")),
        created=_now_iso(),
        changed=_now_iso(),
        published=None,
        status="draft",
        currency="USD",
        online_event=False,
        listed=True,
        shareable=True,
        invite_only=False,
        show_remaining=False,
        password=None,
        capacity=100,
        capacity_is_custom=True,
        is_series=True,
        series_parent_id=None,
        is_reserved_seating=False,
        show_pick_a_seat=False,
        show_seatmap_thumbnail=False,
        show_colors_in_seatmap_thumbnail=False,
        organizer_id=None,
        logo_id=None,
        format_id=None,
        category_id=None,
        subcategory_id=None,
        locale="en_US",
        source="api",
    )
    session.add(series_parent)

    series_child = Event(
        id="event_2",
        organization_id=org.id,
        venue_id=venue.id,
        name={"text": "Series Child", "html": "<p>Series Child</p>"},
        summary="Series child",
        description={"html": "<p>Series child description</p>"},
        start=_datetime_tz((start + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ")),
        end=_datetime_tz((end + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ")),
        created=_now_iso(),
        changed=_now_iso(),
        published=None,
        status="draft",
        currency="USD",
        online_event=False,
        listed=True,
        shareable=True,
        invite_only=False,
        show_remaining=False,
        password=None,
        capacity=100,
        capacity_is_custom=True,
        is_series=False,
        series_parent_id=series_parent.id,
        is_reserved_seating=False,
        show_pick_a_seat=False,
        show_seatmap_thumbnail=False,
        show_colors_in_seatmap_thumbnail=False,
        organizer_id=None,
        logo_id=None,
        format_id=None,
        category_id=None,
        subcategory_id=None,
        locale="en_US",
        source="api",
    )
    session.add(series_child)

    ticket = TicketClass(
        id="ticket_1",
        event_id=event.id,
        name="General Admission",
        description="GA ticket",
        sorting=1,
        cost={"currency": "USD", "value": 5000, "major_value": "50.00", "display": "$50.00"},
        fee={"currency": "USD", "value": 500, "major_value": "5.00", "display": "$5.00"},
        donation=False,
        free=False,
        minimum_quantity=1,
        maximum_quantity=10,
        capacity=100,
        quantity_sold=5,
        hidden=False,
        sales_start=None,
        sales_end=None,
        sales_end_relative=None,
        include_fee=False,
        split_fee=False,
        hide_description=False,
        hide_sale_dates=False,
        auto_hide=False,
        auto_hide_before=None,
        auto_hide_after=None,
        order_confirmation_message=None,
        delivery_methods=["electronic"],
        inventory_tier_id=None,
        image_id=None,
    )
    session.add(ticket)

    capacity = CapacityTier(
        id="capacity_1",
        event_id=event.id,
        capacity_total=500,
        holds=[{"id": "H1", "name": "Marketing", "quantity_total": 10, "sort_order": 1}],
    )
    session.add(capacity)

    seatmap = SeatMap(
        id="seatmap_1",
        organization_id=org.id,
        venue_id=venue.id,
        name="Main Hall",
        metadata_json={"tiers": 3},
    )
    session.add(seatmap)

    discount = Discount(
        id="discount_1",
        organization_id=org.id,
        code="PROMO10",
        type="coded",
        end_date=None,
        end_date_relative=None,
        amount_off=10,
        percent_off=None,
        quantity_available=100,
        quantity_sold=2,
        start_date=None,
        start_date_relative=None,
        ticket_class_ids=[ticket.id],
        event_id=event.id,
        ticket_group_id=None,
        hold_ids=None,
    )
    session.add(discount)

    order = Order(
        id="order_1",
        organization_id=org.id,
        event_id=event.id,
        created=_now_iso(),
        changed=_now_iso(),
        status="placed",
        name="John Doe",
        first_name="John",
        last_name="Doe",
        email="john@example.com",
        costs={
            "gross": {"currency": "USD", "value": 5500, "major_value": "55.00", "display": "$55.00"},
            "base_price": {"currency": "USD", "value": 5000, "major_value": "50.00", "display": "$50.00"},
            "eventbrite_fee": {"currency": "USD", "value": 500, "major_value": "5.00", "display": "$5.00"},
        },
        promo_code="PROMO10",
        time_remaining=None,
        questions=None,
        answers=None,
        refund_request={
            "from_email": "john@example.com",
            "from_name": "John Doe",
            "status": "completed",
            "message": "Refund processed",
            "reason": "customer_request",
            "items": [
                {
                    "event_id": event.id,
                    "order_id": "order_1",
                    "item_type": "order",
                    "status": "processed",
                }
            ],
        },
    )
    session.add(order)

    attendee = Attendee(
        id="attendee_1",
        event_id=event.id,
        order_id=order.id,
        ticket_class_id=ticket.id,
        created=_now_iso(),
        changed=_now_iso(),
        ticket_class_name=ticket.name,
        quantity=1,
        costs={
            "gross": {"currency": "USD", "value": 5500, "major_value": "55.00", "display": "$55.00"},
        },
        profile={
            "name": "John Doe",
            "email": "john@example.com",
            "first_name": "John",
            "last_name": "Doe",
        },
        addresses=None,
        questions=None,
        answers=None,
        barcodes=[{"barcode": "ABC123", "status": "unused", "created": _now_iso(), "changed": _now_iso(), "is_printed": False}],
        team=None,
        affiliate=None,
        checked_in=False,
        cancelled=False,
        refunded=False,
        status="attending",
        guestlist_id=None,
        invited_by=None,
        delivery_method="electronic",
    )
    session.add(attendee)

    session.commit()
