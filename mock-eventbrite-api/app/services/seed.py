from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Callable, TypeVar

from sqlalchemy.orm import Session

from app.models.attendee import Attendee
from app.models.capacity_tier import CapacityTier
from app.models.discount import Discount
from app.models.organization import Organization
from app.models.event import Event
from app.models.order import Order
from app.models.seatmap import SeatMap
from app.models.ticket_class import TicketClass
from app.models.venue import Venue

BASELINE_PROFILE = "baseline"
HEAVY_PROFILE = "heavy"
APPEND_MODE = "append"

_T = TypeVar("_T")


def _ensure(session: Session, model: type[_T], entity_id: str, factory: Callable[[], _T]) -> _T:
    existing = session.get(model, entity_id)
    if existing is not None:
        return existing
    created = factory()
    session.add(created)
    return created

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


def _seed_baseline(session: Session) -> None:
    now = datetime.now(timezone.utc)
    start = now + timedelta(days=7)
    end = start + timedelta(hours=2)

    _ensure(session, Organization, "org_1", lambda: Organization(id="org_1", name="Mock Organization"))

    _ensure(
        session,
        Venue,
        "venue_1",
        lambda: Venue(
            id="venue_1",
            organization_id="org_1",
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
        ),
    )

    _ensure(
        session,
        Event,
        "event_1",
        lambda: Event(
            id="event_1",
            organization_id="org_1",
            venue_id="venue_1",
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
        ),
    )

    _ensure(
        session,
        Event,
        "series_1",
        lambda: Event(
            id="series_1",
            organization_id="org_1",
            venue_id="venue_1",
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
        ),
    )

    _ensure(
        session,
        Event,
        "event_2",
        lambda: Event(
            id="event_2",
            organization_id="org_1",
            venue_id="venue_1",
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
            series_parent_id="series_1",
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
        ),
    )

    _ensure(
        session,
        TicketClass,
        "ticket_1",
        lambda: TicketClass(
            id="ticket_1",
            event_id="event_1",
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
        ),
    )

    _ensure(
        session,
        CapacityTier,
        "capacity_1",
        lambda: CapacityTier(
            id="capacity_1",
            event_id="event_1",
            capacity_total=500,
            holds=[{"id": "H1", "name": "Marketing", "quantity_total": 10, "sort_order": 1}],
        ),
    )

    _ensure(
        session,
        SeatMap,
        "seatmap_1",
        lambda: SeatMap(
            id="seatmap_1",
            organization_id="org_1",
            venue_id="venue_1",
            name="Main Hall",
            metadata_json={"tiers": 3},
        ),
    )

    _ensure(
        session,
        Discount,
        "discount_1",
        lambda: Discount(
            id="discount_1",
            organization_id="org_1",
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
            ticket_class_ids=["ticket_1"],
            event_id="event_1",
            ticket_group_id=None,
            hold_ids=None,
        ),
    )

    _ensure(
        session,
        Order,
        "order_1",
        lambda: Order(
            id="order_1",
            organization_id="org_1",
            event_id="event_1",
            created=_now_iso(),
            changed=_now_iso(),
            status="placed",
            name="John Doe",
            first_name="John",
            last_name="Doe",
            email="john@example.com",
            costs={
                "gross": {"currency": "USD", "value": 5500, "major_value": "55.00", "display": "$55.00"},
                "base_price": {
                    "currency": "USD",
                    "value": 5000,
                    "major_value": "50.00",
                    "display": "$50.00",
                },
                "eventbrite_fee": {
                    "currency": "USD",
                    "value": 500,
                    "major_value": "5.00",
                    "display": "$5.00",
                },
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
                        "event_id": "event_1",
                        "order_id": "order_1",
                        "item_type": "order",
                        "status": "processed",
                    }
                ],
            },
        ),
    )

    _ensure(
        session,
        Attendee,
        "attendee_1",
        lambda: Attendee(
            id="attendee_1",
            event_id="event_1",
            order_id="order_1",
            ticket_class_id="ticket_1",
            created=_now_iso(),
            changed=_now_iso(),
            ticket_class_name="General Admission",
            quantity=1,
            costs={"gross": {"currency": "USD", "value": 5500, "major_value": "55.00", "display": "$55.00"}},
            profile={
                "name": "John Doe",
                "email": "john@example.com",
                "first_name": "John",
                "last_name": "Doe",
            },
            addresses=None,
            questions=None,
            answers=None,
            barcodes=[
                {
                    "barcode": "ABC123",
                    "status": "unused",
                    "created": _now_iso(),
                    "changed": _now_iso(),
                    "is_printed": False,
                }
            ],
            team=None,
            affiliate=None,
            checked_in=False,
            cancelled=False,
            refunded=False,
            status="attending",
            guestlist_id=None,
            invited_by=None,
            delivery_method="electronic",
        ),
    )


def _seed_heavy(session: Session) -> None:
    now = datetime.now(timezone.utc)
    venues = [
        ("venue_2", "Downtown Arena", "New York", "NY", 1800, "40.7128", "-74.0060"),
        ("venue_3", "Lakeside Grounds", "Chicago", "IL", 1200, "41.8781", "-87.6298"),
        ("venue_4", "Bayfront Pavilion", "San Diego", "CA", 900, "32.7157", "-117.1611"),
        ("venue_5", "Skyline Theatre", "Seattle", "WA", 750, "47.6062", "-122.3321"),
        ("venue_6", "Metro Convention Center", "Austin", "TX", 2200, "30.2672", "-97.7431"),
    ]
    for venue_id, name, city, region, capacity, lat, lng in venues:
        _ensure(
            session,
            Venue,
            venue_id,
            lambda venue_id=venue_id, name=name, city=city, region=region, capacity=capacity, lat=lat, lng=lng: Venue(
                id=venue_id,
                organization_id="org_1",
                name=name,
                capacity=capacity,
                age_restriction="All Ages",
                latitude=lat,
                longitude=lng,
                address={
                    "address_1": f"{capacity} Demo Street",
                    "address_2": "",
                    "city": city,
                    "region": region,
                    "postal_code": "00000",
                    "country": "US",
                    "latitude": lat,
                    "longitude": lng,
                },
            ),
        )
        seatmap_id = f"seatmap_{venue_id.split('_')[1]}"
        _ensure(
            session,
            SeatMap,
            seatmap_id,
            lambda seatmap_id=seatmap_id, venue_id=venue_id, name=name: SeatMap(
                id=seatmap_id,
                organization_id="org_1",
                venue_id=venue_id,
                name=f"{name} SeatMap",
                metadata_json={"layout": "mixed", "zones": 4},
            ),
        )

    event_ids = [f"event_{idx}" for idx in range(3, 15)]
    venue_ids = ["venue_1", "venue_2", "venue_3", "venue_4", "venue_5", "venue_6"]

    for idx, event_id in enumerate(event_ids, start=3):
        event_start = now + timedelta(days=idx * 2)
        event_end = event_start + timedelta(hours=3)
        venue_id = venue_ids[(idx - 3) % len(venue_ids)]
        event_name = f"Demo Event {idx}"
        _ensure(
            session,
            Event,
            event_id,
            lambda event_id=event_id, venue_id=venue_id, event_name=event_name, event_start=event_start, event_end=event_end: Event(
                id=event_id,
                organization_id="org_1",
                venue_id=venue_id,
                name={"text": event_name, "html": f"<p>{event_name}</p>"},
                summary=f"Expanded demo seed event {idx}",
                description={"html": f"<p>Extended test catalog event {idx}</p>"},
                start=_datetime_tz_utc(event_start.strftime("%Y-%m-%dT%H:%M:%SZ")),
                end=_datetime_tz_utc(event_end.strftime("%Y-%m-%dT%H:%M:%SZ")),
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
                capacity=500 + (idx * 20),
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
            ),
        )

        for tier in (1, 2):
            ticket_idx = (idx - 2) * 2 + tier
            ticket_id = f"ticket_{ticket_idx}"
            base_value = (20 + (idx * 2) + (tier * 5)) * 100
            _ensure(
                session,
                TicketClass,
                ticket_id,
                lambda ticket_id=ticket_id, event_id=event_id, tier=tier, base_value=base_value: TicketClass(
                    id=ticket_id,
                    event_id=event_id,
                    name=f"{'Standard' if tier == 1 else 'Premium'} {idx}",
                    description=f"Tier {tier} for {event_id}",
                    sorting=tier,
                    cost={
                        "currency": "USD",
                        "value": base_value,
                        "major_value": f"{base_value / 100:.2f}",
                        "display": f"${base_value / 100:.2f}",
                    },
                    fee={"currency": "USD", "value": 300, "major_value": "3.00", "display": "$3.00"},
                    donation=False,
                    free=False,
                    minimum_quantity=1,
                    maximum_quantity=8,
                    capacity=250,
                    quantity_sold=max(tier * 3, 1),
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
                ),
            )

        capacity_id = f"capacity_{idx}"
        _ensure(
            session,
            CapacityTier,
            capacity_id,
            lambda capacity_id=capacity_id, event_id=event_id, idx=idx: CapacityTier(
                id=capacity_id,
                event_id=event_id,
                capacity_total=500 + (idx * 20),
                holds=[{"id": f"H{idx}", "name": "VIP Hold", "quantity_total": 10, "sort_order": 1}],
            ),
        )

    for idx in range(2, 11):
        discount_id = f"discount_{idx}"
        event_id = event_ids[(idx - 2) % len(event_ids)]
        ticket_id = f"ticket_{((idx - 2) * 2) + 3}"
        _ensure(
            session,
            Discount,
            discount_id,
            lambda discount_id=discount_id, idx=idx, event_id=event_id, ticket_id=ticket_id: Discount(
                id=discount_id,
                organization_id="org_1",
                code=f"DEMO{idx}OFF",
                type="coded",
                end_date=None,
                end_date_relative=None,
                amount_off=float(idx),
                percent_off=None,
                quantity_available=200,
                quantity_sold=idx,
                start_date=None,
                start_date_relative=None,
                ticket_class_ids=[ticket_id],
                event_id=event_id,
                ticket_group_id=None,
                hold_ids=None,
            ),
        )

    for idx in range(2, 21):
        order_id = f"order_{idx}"
        attendee_id = f"attendee_{idx}"
        event_id = event_ids[(idx - 2) % len(event_ids)]
        ticket_id = f"ticket_{((idx - 2) * 2) + 3}"
        email = f"demo.user{idx}@example.com"
        _ensure(
            session,
            Order,
            order_id,
            lambda order_id=order_id, event_id=event_id, email=email, idx=idx: Order(
                id=order_id,
                organization_id="org_1",
                event_id=event_id,
                created=_now_iso(),
                changed=_now_iso(),
                status="placed",
                name=f"Demo User {idx}",
                first_name="Demo",
                last_name=f"User{idx}",
                email=email,
                costs={
                    "gross": {"currency": "USD", "value": 4500 + (idx * 100), "major_value": "65.00", "display": "$65.00"}
                },
                promo_code="PROMO10" if idx % 3 == 0 else None,
                time_remaining=None,
                questions=None,
                answers=None,
                refund_request=None,
            ),
        )
        _ensure(
            session,
            Attendee,
            attendee_id,
            lambda attendee_id=attendee_id, event_id=event_id, order_id=order_id, ticket_id=ticket_id, email=email, idx=idx: Attendee(
                id=attendee_id,
                event_id=event_id,
                order_id=order_id,
                ticket_class_id=ticket_id,
                created=_now_iso(),
                changed=_now_iso(),
                ticket_class_name=f"Standard {idx}",
                quantity=1,
                costs={"gross": {"currency": "USD", "value": 4500 + (idx * 100), "major_value": "65.00", "display": "$65.00"}},
                profile={
                    "name": f"Demo User {idx}",
                    "email": email,
                    "first_name": "Demo",
                    "last_name": f"User{idx}",
                },
                addresses=None,
                questions=None,
                answers=None,
                barcodes=[{"barcode": f"DEMO{idx:04d}", "status": "unused", "created": _now_iso(), "changed": _now_iso(), "is_printed": False}],
                team=None,
                affiliate=None,
                checked_in=False,
                cancelled=False,
                refunded=False,
                status="attending",
                guestlist_id=None,
                invited_by=None,
                delivery_method="electronic",
            ),
        )


def seed_data(session: Session, profile: str = BASELINE_PROFILE, mode: str = APPEND_MODE) -> None:
    if mode != APPEND_MODE:
        raise ValueError(f"Unsupported seed mode: {mode}")

    if profile not in {BASELINE_PROFILE, HEAVY_PROFILE}:
        raise ValueError(f"Unsupported seed profile: {profile}")

    _seed_baseline(session)
    if profile == HEAVY_PROFILE:
        _seed_heavy(session)
    session.commit()
