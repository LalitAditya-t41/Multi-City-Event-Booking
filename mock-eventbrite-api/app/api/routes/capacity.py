from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.capacity_tier import CapacityTier
from app.schemas.capacity import CapacityTierUpdate, CapacityTierResponse

router = APIRouter(prefix="/v3", tags=["capacity"])


def _to_response(capacity: CapacityTier) -> CapacityTierResponse:
    return CapacityTierResponse(
        id=capacity.id,
        capacity_total=capacity.capacity_total,
        holds=capacity.holds,
    )


@router.get("/events/{event_id}/capacity_tier/", response_model=CapacityTierResponse, dependencies=[Depends(require_auth)])
def get_capacity_tier(event_id: str, db: Session = Depends(get_db)):
    cap = db.query(CapacityTier).filter(CapacityTier.event_id == event_id).first()
    if not cap:
        raise ApiError("NOT_FOUND", "The event you requested does not exist.", 404)
    return _to_response(cap)


@router.post("/events/{event_id}/capacity_tier/", response_model=CapacityTierResponse, dependencies=[Depends(require_auth)])
def update_capacity_tier(event_id: str, body: dict, db: Session = Depends(get_db)):
    if "capacity_tier" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing capacity_tier object.", 400)
    payload = CapacityTierUpdate(**body["capacity_tier"])

    cap = db.query(CapacityTier).filter(CapacityTier.event_id == event_id).first()
    if not cap:
        cap = CapacityTier(id=f"capacity_{event_id}", event_id=event_id, capacity_total=None, holds=None)
        db.add(cap)

    if payload.capacity_total is not None:
        cap.capacity_total = payload.capacity_total
    if payload.holds is not None:
        cap.holds = [h.model_dump() for h in payload.holds]

    db.commit()
    db.refresh(cap)
    return _to_response(cap)
