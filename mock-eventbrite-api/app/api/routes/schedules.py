from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.event import Event
from app.schemas.schedule import ScheduleBase, ScheduleResponse

router = APIRouter(prefix="/v3", tags=["schedules"])


@router.post("/events/{event_id}/schedules/", response_model=ScheduleResponse, dependencies=[Depends(require_auth)])
def create_schedule(event_id: str, body: dict, db: Session = Depends(get_db)):
    if "schedule" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing schedule object.", 400)
    payload = ScheduleBase(**body["schedule"])
    event = db.get(Event, event_id)
    if not event or not event.is_series:
        raise ApiError("INVALID", "event_id must be a valid series parent event.", 400)
    schedule_id = f"schedule_{event_id}"
    return ScheduleResponse(
        id=schedule_id,
        event_id=event_id,
        occurrence_duration=payload.occurrence_duration,
        recurrence_rule=payload.recurrence_rule,
    )
