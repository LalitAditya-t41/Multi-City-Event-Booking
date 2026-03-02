from __future__ import annotations

from pydantic import BaseModel
from typing import Optional, List


class CapacityHold(BaseModel):
    id: Optional[str] = None
    name: Optional[str] = None
    quantity_total: Optional[int] = None
    quantity_sold: Optional[int] = None
    quantity_pending: Optional[int] = None
    sort_order: Optional[int] = None
    is_deleted: Optional[bool] = None


class CapacityTierUpdate(BaseModel):
    capacity_total: Optional[int] = None
    holds: Optional[List[CapacityHold]] = None


class CapacityTierResponse(BaseModel):
    id: str
    capacity_total: Optional[int] = None
    holds: Optional[List[CapacityHold]] = None
