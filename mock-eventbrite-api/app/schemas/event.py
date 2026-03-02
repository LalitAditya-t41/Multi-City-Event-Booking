from __future__ import annotations

from pydantic import BaseModel, Field
from typing import Optional, List

from app.schemas.common import DateTimeTz, DateTimeTzUtc, HtmlText, MultipartText


class EventCreate(BaseModel):
    name: HtmlText
    summary: Optional[str] = None
    description: Optional[HtmlText] = None
    start: DateTimeTzUtc
    end: DateTimeTzUtc
    hide_start_date: Optional[bool] = None
    hide_end_date: Optional[bool] = None
    currency: str
    online_event: Optional[bool] = False
    organizer_id: Optional[str] = None
    logo_id: Optional[str] = None
    venue_id: Optional[str] = None
    format_id: Optional[str] = None
    category_id: Optional[str] = None
    subcategory_id: Optional[str] = None
    listed: Optional[bool] = True
    shareable: Optional[bool] = True
    invite_only: Optional[bool] = False
    show_remaining: Optional[bool] = False
    password: Optional[str] = None
    capacity: Optional[int] = None
    is_reserved_seating: Optional[bool] = False
    is_series: Optional[bool] = False
    show_pick_a_seat: Optional[bool] = False
    show_seatmap_thumbnail: Optional[bool] = False
    show_colors_in_seatmap_thumbnail: Optional[bool] = False
    source: Optional[str] = None
    locale: Optional[str] = None


class EventUpdate(BaseModel):
    name: Optional[HtmlText] = None
    summary: Optional[str] = None
    description: Optional[HtmlText] = None
    start: Optional[DateTimeTzUtc] = None
    end: Optional[DateTimeTzUtc] = None
    hide_start_date: Optional[bool] = None
    hide_end_date: Optional[bool] = None
    currency: Optional[str] = None
    online_event: Optional[bool] = None
    organizer_id: Optional[str] = None
    logo_id: Optional[str] = None
    venue_id: Optional[str] = None
    format_id: Optional[str] = None
    category_id: Optional[str] = None
    subcategory_id: Optional[str] = None
    listed: Optional[bool] = None
    shareable: Optional[bool] = None
    invite_only: Optional[bool] = None
    show_remaining: Optional[bool] = None
    password: Optional[str] = None
    capacity: Optional[int] = None
    is_reserved_seating: Optional[bool] = None
    is_series: Optional[bool] = None
    show_pick_a_seat: Optional[bool] = None
    show_seatmap_thumbnail: Optional[bool] = None
    show_colors_in_seatmap_thumbnail: Optional[bool] = None
    source: Optional[str] = None


class EventCopy(BaseModel):
    name: Optional[str] = None
    start_date: Optional[str] = None
    end_date: Optional[str] = None
    timezone: Optional[str] = None
    summary: Optional[str] = None


class EventResponse(BaseModel):
    id: str
    name: MultipartText
    summary: Optional[str] = None
    description: Optional[HtmlText] = None
    url: Optional[str] = None
    start: DateTimeTz
    end: DateTimeTz
    created: str
    changed: str
    published: Optional[str] = None
    status: str
    currency: str
    online_event: bool
    hide_start_date: Optional[bool] = None
    hide_end_date: Optional[bool] = None

    listed: Optional[bool] = None
    shareable: Optional[bool] = None
    invite_only: Optional[bool] = None
    show_remaining: Optional[bool] = None
    password: Optional[str] = None
    capacity: Optional[int] = None
    capacity_is_custom: Optional[bool] = None

    venue_id: Optional[str] = None
    organizer_id: Optional[str] = None
    logo_id: Optional[str] = None
    format_id: Optional[str] = None
    category_id: Optional[str] = None
    subcategory_id: Optional[str] = None
    locale: Optional[str] = None
    source: Optional[str] = None

    is_series: Optional[bool] = None
    series_parent_id: Optional[str] = None
    is_reserved_seating: Optional[bool] = None
    show_pick_a_seat: Optional[bool] = None
    show_seatmap_thumbnail: Optional[bool] = None
    show_colors_in_seatmap_thumbnail: Optional[bool] = None
