from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.attendee import Attendee
from app.schemas.attendee import AttendeeResponse
from app.services.pagination import paginate

router = APIRouter(prefix="/v3", tags=["attendees"])


def _to_response(att: Attendee) -> AttendeeResponse:
    return AttendeeResponse(
        id=att.id,
        created=att.created,
        changed=att.changed,
        ticket_class_id=att.ticket_class_id,
        variant_id=None,
        ticket_class_name=att.ticket_class_name,
        quantity=att.quantity,
        costs=att.costs,
        profile=att.profile,
        addresses=att.addresses,
        questions=att.questions,
        answers=att.answers,
        barcodes=att.barcodes,
        team=att.team,
        affiliate=att.affiliate,
        checked_in=att.checked_in,
        cancelled=att.cancelled,
        refunded=att.refunded,
        status=att.status,
        event_id=att.event_id,
        order_id=att.order_id,
        guestlist_id=att.guestlist_id,
        invited_by=att.invited_by,
        delivery_method=att.delivery_method,
    )


@router.get("/events/{event_id}/attendees/{attendee_id}/", response_model=AttendeeResponse, dependencies=[Depends(require_auth)])
def get_attendee(event_id: str, attendee_id: str, db: Session = Depends(get_db)):
    attendee = db.get(Attendee, attendee_id)
    if not attendee or attendee.event_id != event_id:
        raise ApiError("NOT_FOUND", "Invalid URL.", 404)
    return _to_response(attendee)


@router.get("/events/{event_id}/attendees/", dependencies=[Depends(require_auth)])
def list_attendees_by_event(
    event_id: str,
    status: str | None = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    attendees = db.query(Attendee).filter(Attendee.event_id == event_id).all()
    if status == "attending":
        attendees = [a for a in attendees if a.status in ("attending", "checked_in")]
    if status == "not_attending":
        attendees = [a for a in attendees if a.status in ("not_attending", "deleted")]
    items, pagination = paginate(attendees, page_size, page)
    return {"pagination": pagination.model_dump(), "attendees": [_to_response(a).model_dump() for a in items]}


@router.get("/organizations/{organization_id}/attendees/", dependencies=[Depends(require_auth)])
def list_attendees_by_org(
    organization_id: str,
    status: str | None = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    attendees = db.query(Attendee).all()
    if status == "attending":
        attendees = [a for a in attendees if a.status in ("attending", "checked_in")]
    if status == "not_attending":
        attendees = [a for a in attendees if a.status in ("not_attending", "deleted")]
    items, pagination = paginate(attendees, page_size, page)
    return {"pagination": pagination.model_dump(), "attendees": [_to_response(a).model_dump() for a in items]}
