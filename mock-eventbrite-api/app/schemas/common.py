from __future__ import annotations

from pydantic import BaseModel, Field
from typing import Optional, List, Any


class DateTimeTzUtc(BaseModel):
    timezone: str
    utc: str


class DateTimeTz(BaseModel):
    timezone: str
    utc: str
    local: str


class HtmlText(BaseModel):
    html: str


class MultipartText(BaseModel):
    text: str
    html: str


class Pagination(BaseModel):
    object_count: int
    page_count: int
    page_size: int
    page_number: int
    has_more_items: bool
    continuation: Optional[str] = None


class ErrorResponse(BaseModel):
    error: str
    error_description: str
    status_code: int


class Currency(BaseModel):
    currency: str
    value: int
    major_value: str | None = None
    display: str | None = None
