from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional, List

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.event import Event
from app.models.venue import Venue
from app.schemas.common import Pagination
from app.schemas.event import EventCreate, EventUpdate, EventResponse, EventCopy
from app.services.pagination import paginate
from app.services.webhook_dispatcher import dispatch_webhook

router = APIRouter(prefix="/v3", tags=["events"])


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_utc(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def _to_multipart(text_obj: dict) -> dict:
    text = text_obj.get("text") or text_obj.get("html") or ""
    html = text_obj.get("html") or f"<p>{text}</p>"
    return {"text": text, "html": html}


def _ensure_event(db: Session, event_id: str) -> Event:
    event = db.get(Event, event_id)
    if not event:
        raise ApiError("NOT_FOUND", "The event you requested does not exist.", 404)
    return event


def _event_to_response(event: Event) -> EventResponse:
    name = _to_multipart(event.name)
    return EventResponse(
        id=event.id,
        name=name,
        summary=event.summary,
        description=event.description,
        url=f"https://www.eventbrite.com/e/{event.id}",
        start=event.start,
        end=event.end,
        created=event.created,
        changed=event.changed,
        published=event.published,
        status=event.status,
        currency=event.currency,
        online_event=event.online_event,
        hide_start_date=None,
        hide_end_date=None,
        listed=event.listed,
        shareable=event.shareable,
        invite_only=event.invite_only,
        show_remaining=event.show_remaining,
        password=event.password,
        capacity=int(event.capacity) if event.capacity is not None else None,
        capacity_is_custom=event.capacity_is_custom,
        venue_id=event.venue_id,
        organizer_id=event.organizer_id,
        logo_id=event.logo_id,
        format_id=event.format_id,
        category_id=event.category_id,
        subcategory_id=event.subcategory_id,
        locale=event.locale,
        source=event.source,
        is_series=event.is_series,
        series_parent_id=event.series_parent_id,
        is_reserved_seating=event.is_reserved_seating,
        show_pick_a_seat=event.show_pick_a_seat,
        show_seatmap_thumbnail=event.show_seatmap_thumbnail,
        show_colors_in_seatmap_thumbnail=event.show_colors_in_seatmap_thumbnail,
    )


def _validate_event_create(payload: EventCreate) -> None:
    if payload.venue_id and payload.online_event:
        raise ApiError("VENUE_AND_ONLINE", "You cannot both specify a venue and set online_event", 400)
    if payload.summary and payload.description:
        raise ApiError("SUMMARY_DESCRIPTION_CONFLICT", "You have set values for both summary and description", 400)
    if _parse_utc(payload.start.utc) >= _parse_utc(payload.end.utc):
        raise ApiError("DATE_CONFLICT", "Start date cannot be after end date", 400)
    if not payload.online_event and not payload.venue_id:
        raise ApiError("NO_VENUE", "You have attempted to create an event without a venue.", 400)


def _validate_event_update(payload: EventUpdate) -> None:
    if payload.summary and payload.description:
        raise ApiError("SUMMARY_DESCRIPTION_CONFLICT", "You have set values for both summary and description", 400)
    if payload.start and payload.end and _parse_utc(payload.start.utc) >= _parse_utc(payload.end.utc):
        raise ApiError("DATE_CONFLICT", "End date must be after start date.", 400)


@router.get("/events/{event_id}/", response_model=EventResponse, dependencies=[Depends(require_auth)])
def get_event(event_id: str, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_id)
    return _event_to_response(event)


@router.post("/organizations/{organization_id}/events/", response_model=EventResponse, dependencies=[Depends(require_auth)])
def create_event(organization_id: str, body: dict, db: Session = Depends(get_db)):
    if "event" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing event object.", 400)
    payload = EventCreate(**body["event"])
    _validate_event_create(payload)

    event_id = f"event_{int(datetime.now().timestamp())}"
    now = _now_iso()
    event = Event(
        id=event_id,
        organization_id=organization_id,
        venue_id=payload.venue_id,
        name={"text": payload.name.html, "html": payload.name.html},
        summary=payload.summary,
        description=payload.description.model_dump() if payload.description else None,
        start={"timezone": "UTC", "utc": payload.start.utc, "local": payload.start.utc.replace("Z", "")},
        end={"timezone": "UTC", "utc": payload.end.utc, "local": payload.end.utc.replace("Z", "")},
        created=now,
        changed=now,
        published=None,
        status="draft",
        currency=payload.currency,
        online_event=payload.online_event or False,
        listed=payload.listed if payload.listed is not None else True,
        shareable=payload.shareable if payload.shareable is not None else True,
        invite_only=payload.invite_only if payload.invite_only is not None else False,
        show_remaining=payload.show_remaining if payload.show_remaining is not None else False,
        password=payload.password,
        capacity=payload.capacity,
        capacity_is_custom=payload.capacity is not None,
        is_series=payload.is_series or False,
        series_parent_id=None,
        is_reserved_seating=payload.is_reserved_seating or False,
        show_pick_a_seat=payload.show_pick_a_seat or False,
        show_seatmap_thumbnail=payload.show_seatmap_thumbnail or False,
        show_colors_in_seatmap_thumbnail=payload.show_colors_in_seatmap_thumbnail or False,
        organizer_id=payload.organizer_id,
        logo_id=payload.logo_id,
        format_id=payload.format_id,
        category_id=payload.category_id,
        subcategory_id=payload.subcategory_id,
        locale=payload.locale,
        source=payload.source or "api",
    )
    db.add(event)
    db.commit()
    db.refresh(event)
    return _event_to_response(event)


@router.post("/events/{event_id}/", response_model=EventResponse, dependencies=[Depends(require_auth)])
def update_event(event_id: str, body: dict, db: Session = Depends(get_db)):
    if "event" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing event object.", 400)
    payload = EventUpdate(**body["event"])
    _validate_event_update(payload)

    event = _ensure_event(db, event_id)

    if payload.name:
        event.name = {"text": payload.name.html, "html": payload.name.html}
    if payload.summary is not None:
        event.summary = payload.summary
    if payload.description is not None:
        event.description = payload.description.model_dump()
    if payload.start:
        event.start = {"timezone": "UTC", "utc": payload.start.utc, "local": payload.start.utc.replace("Z", "")}
    if payload.end:
        event.end = {"timezone": "UTC", "utc": payload.end.utc, "local": payload.end.utc.replace("Z", "")}
    if payload.venue_id is not None:
        event.venue_id = payload.venue_id
    if payload.currency is not None:
        event.currency = payload.currency
    if payload.online_event is not None:
        event.online_event = payload.online_event
    if payload.listed is not None:
        event.listed = payload.listed
    if payload.shareable is not None:
        event.shareable = payload.shareable
    if payload.invite_only is not None:
        event.invite_only = payload.invite_only
    if payload.show_remaining is not None:
        event.show_remaining = payload.show_remaining
    if payload.password is not None:
        event.password = payload.password
    if payload.capacity is not None:
        event.capacity = payload.capacity
        event.capacity_is_custom = True
    if payload.is_series is not None:
        event.is_series = payload.is_series
    if payload.is_reserved_seating is not None:
        event.is_reserved_seating = payload.is_reserved_seating
    if payload.show_pick_a_seat is not None:
        event.show_pick_a_seat = payload.show_pick_a_seat
    if payload.show_seatmap_thumbnail is not None:
        event.show_seatmap_thumbnail = payload.show_seatmap_thumbnail
    if payload.show_colors_in_seatmap_thumbnail is not None:
        event.show_colors_in_seatmap_thumbnail = payload.show_colors_in_seatmap_thumbnail
    if payload.source is not None:
        event.source = payload.source

    event.changed = _now_iso()
    db.commit()
    db.refresh(event)
    return _event_to_response(event)


@router.delete("/events/{event_id}/", dependencies=[Depends(require_auth)])
def delete_event(event_id: str, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_id)
    if event.status != "draft":
        raise ApiError("CANNOT_DELETE", "You cannot delete an event that has pending or completed orders.", 400)
    db.delete(event)
    db.commit()
    return {"deleted": True}


@router.post("/events/{event_id}/publish/", dependencies=[Depends(require_auth)])
async def publish_event(event_id: str, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_id)
    if event.status == "live":
        raise ApiError("ALREADY_PUBLISHED_OR_DELETED", "This event has already been published or deleted.", 400)
    event.status = "live"
    event.published = _now_iso()
    event.changed = _now_iso()
    db.commit()
    await dispatch_webhook(db, "event.published", f"https://www.eventbriteapi.com/v3/events/{event.id}/", _event_to_response(event).model_dump())
    return {"published": True}


@router.post("/events/{event_id}/unpublish/", dependencies=[Depends(require_auth)])
async def unpublish_event(event_id: str, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_id)
    if event.status != "live":
        raise ApiError("NOT_PUBLISHED", "This event is not currently published and cannot be unpublished.", 400)
    event.status = "draft"
    event.changed = _now_iso()
    db.commit()
    await dispatch_webhook(db, "event.unpublished", f"https://www.eventbriteapi.com/v3/events/{event.id}/", _event_to_response(event).model_dump())
    return {"unpublished": True}


@router.post("/events/{event_id}/cancel/", dependencies=[Depends(require_auth)])
async def cancel_event(event_id: str, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_id)
    if event.status == "canceled":
        raise ApiError("ALREADY_CANCELED", "This event has already been canceled.", 400)
    event.status = "canceled"
    event.changed = _now_iso()
    db.commit()
    await dispatch_webhook(db, "event.canceled", f"https://www.eventbriteapi.com/v3/events/{event.id}/", _event_to_response(event).model_dump())
    return {"canceled": True}


@router.post("/events/{event_id}/copy/", response_model=EventResponse, dependencies=[Depends(require_auth)])
def copy_event(event_id: str, body: dict, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_id)
    payload = EventCopy(**body) if body else EventCopy()

    new_id = f"event_{int(datetime.now().timestamp())}_copy"
    now = _now_iso()
    start = event.start
    end = event.end
    if payload.start_date:
        start = {"timezone": payload.timezone or "UTC", "utc": payload.start_date, "local": payload.start_date.replace("Z", "")}
    if payload.end_date:
        end = {"timezone": payload.timezone or "UTC", "utc": payload.end_date, "local": payload.end_date.replace("Z", "")}

    new_event = Event(
        id=new_id,
        organization_id=event.organization_id,
        venue_id=event.venue_id,
        name={"text": payload.name or event.name.get("text", ""), "html": payload.name or event.name.get("html", "")},
        summary=payload.summary or event.summary,
        description=event.description,
        start=start,
        end=end,
        created=now,
        changed=now,
        published=None,
        status="draft",
        currency=event.currency,
        online_event=event.online_event,
        listed=event.listed,
        shareable=event.shareable,
        invite_only=event.invite_only,
        show_remaining=event.show_remaining,
        password=event.password,
        capacity=event.capacity,
        capacity_is_custom=event.capacity_is_custom,
        is_series=event.is_series,
        series_parent_id=event.series_parent_id,
        is_reserved_seating=event.is_reserved_seating,
        show_pick_a_seat=event.show_pick_a_seat,
        show_seatmap_thumbnail=event.show_seatmap_thumbnail,
        show_colors_in_seatmap_thumbnail=event.show_colors_in_seatmap_thumbnail,
        organizer_id=event.organizer_id,
        logo_id=event.logo_id,
        format_id=event.format_id,
        category_id=event.category_id,
        subcategory_id=event.subcategory_id,
        locale=event.locale,
        source=event.source,
    )
    db.add(new_event)
    db.commit()
    db.refresh(new_event)
    return _event_to_response(new_event)


@router.get("/venues/{venue_id}/events/", dependencies=[Depends(require_auth)])
def list_events_by_venue(
    venue_id: str,
    status: Optional[str] = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    events = db.query(Event).filter(Event.venue_id == venue_id).all()
    if status and status != "all":
        status_set = {s.strip() for s in status.split(",")}
        events = [e for e in events if e.status in status_set]
    items, pagination = paginate(events, page_size, page)
    return {"pagination": pagination.model_dump(), "events": [_event_to_response(e).model_dump() for e in items]}


@router.get("/organizations/{organization_id}/events/", dependencies=[Depends(require_auth)])
def list_events_by_org(
    organization_id: str,
    status: Optional[str] = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    events = db.query(Event).filter(Event.organization_id == organization_id).all()
    if status and status != "all":
        status_set = {s.strip() for s in status.split(",")}
        events = [e for e in events if e.status in status_set]
    items, pagination = paginate(events, page_size, page)
    return {"pagination": pagination.model_dump(), "events": [_event_to_response(e).model_dump() for e in items]}


@router.get("/series/{event_series_id}/events/", dependencies=[Depends(require_auth)])
def list_events_by_series(
    event_series_id: str,
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    events = db.query(Event).filter(Event.series_parent_id == event_series_id).all()
    items, pagination = paginate(events, page_size, page)
    return {"pagination": pagination.model_dump(), "events": [_event_to_response(e).model_dump() for e in items]}


@router.get("/series/{event_series_id}/", response_model=EventResponse, dependencies=[Depends(require_auth)])
def get_series_parent(event_series_id: str, db: Session = Depends(get_db)):
    event = _ensure_event(db, event_series_id)
    if not event.is_series:
        raise ApiError("NOT_FOUND", "Invalid series id.", 404)
    return _event_to_response(event)


@router.get("/events/search/", dependencies=[Depends(require_auth)])
def search_events(
    q: Optional[str] = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    events = db.query(Event).all()
    if q:
        events = [e for e in events if q.lower() in e.name.get("text", "").lower()]
    items, pagination = paginate(events, page_size, page)
    return {"pagination": pagination.model_dump(), "events": [_event_to_response(e).model_dump() for e in items]}
