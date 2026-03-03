from __future__ import annotations

from pydantic import BaseModel
from typing import Optional, List


class DiscountCreate(BaseModel):
    code: str
    type: str
    end_date: Optional[str] = None
    end_date_relative: Optional[int] = None
    amount_off: Optional[float] = None
    percent_off: Optional[float] = None
    quantity_available: Optional[int] = None
    start_date: Optional[str] = None
    start_date_relative: Optional[int] = None
    ticket_class_ids: Optional[List[str]] = None
    event_id: Optional[str] = None
    ticket_group_id: Optional[str] = None
    hold_ids: Optional[List[str]] = None


class DiscountResponse(BaseModel):
    id: str
    code: str
    type: str
    end_date: Optional[str] = None
    end_date_relative: Optional[int] = None
    amount_off: Optional[float] = None
    percent_off: Optional[float] = None
    quantity_available: Optional[int] = None
    quantity_sold: Optional[int] = None
    start_date: Optional[str] = None
    start_date_relative: Optional[int] = None
    ticket_class_ids: Optional[List[str]] = None
    event_id: Optional[str] = None
    ticket_group_id: Optional[str] = None
    hold_ids: Optional[List[str]] = None
