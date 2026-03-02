from __future__ import annotations

from pydantic import BaseModel
from typing import Optional


class ScheduleBase(BaseModel):
    event_id: Optional[str] = None
    occurrence_duration: Optional[int] = None
    recurrence_rule: Optional[str] = None


class ScheduleResponse(BaseModel):
    id: str
    event_id: str
    occurrence_duration: Optional[int] = None
    recurrence_rule: Optional[str] = None
