from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.venue import Venue
from app.schemas.venue import VenueResponse
from app.services.pagination import paginate

router = APIRouter(prefix="/v3", tags=["venues"])


def _venue_to_response(venue: Venue) -> VenueResponse:
    return VenueResponse(
        id=venue.id,
        name=venue.name,
        capacity=venue.capacity,
        age_restriction=venue.age_restriction,
        latitude=venue.latitude,
        longitude=venue.longitude,
        address=venue.address,
    )


@router.get("/venues/{venue_id}/", response_model=VenueResponse, dependencies=[Depends(require_auth)])
def get_venue(venue_id: str, db: Session = Depends(get_db)):
    venue = db.get(Venue, venue_id)
    if not venue:
        raise ApiError("NOT_FOUND", "Invalid URL.", 404)
    return _venue_to_response(venue)


@router.get("/organizations/{organization_id}/venues/", dependencies=[Depends(require_auth)])
def list_venues_by_org(
    organization_id: str,
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    venues = db.query(Venue).filter(Venue.organization_id == organization_id).all()
    items, pagination = paginate(venues, page_size, page)
    return {"pagination": pagination.model_dump(), "venues": [_venue_to_response(v).model_dump() for v in items]}
