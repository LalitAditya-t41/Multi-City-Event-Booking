from __future__ import annotations

from pydantic import BaseModel
from typing import Optional


class SeatMapResponse(BaseModel):
    id: str
    name: Optional[str] = None
    venue_id: Optional[str] = None
    metadata: Optional[dict] = None
