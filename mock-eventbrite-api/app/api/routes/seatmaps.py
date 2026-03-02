from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.models.seatmap import SeatMap
from app.schemas.seatmap import SeatMapResponse

router = APIRouter(prefix="/v3", tags=["seatmaps"])


def _to_response(sm: SeatMap) -> SeatMapResponse:
    return SeatMapResponse(id=sm.id, name=sm.name, venue_id=sm.venue_id, metadata=sm.metadata)


@router.get("/organizations/{organization_id}/seatmaps/", dependencies=[Depends(require_auth)])
def list_seatmaps(
    organization_id: str,
    venue_id: str | None = Query(default=None),
    venue_name_filter: str | None = Query(default=None),
    db: Session = Depends(get_db),
):
    query = db.query(SeatMap).filter(SeatMap.organization_id == organization_id)
    if venue_id:
        query = query.filter(SeatMap.venue_id == venue_id)
    seatmaps = query.all()
    return {"seatmaps": [_to_response(sm).model_dump() for sm in seatmaps]}
