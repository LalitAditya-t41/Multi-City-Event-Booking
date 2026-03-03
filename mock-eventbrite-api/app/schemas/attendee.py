from __future__ import annotations

from pydantic import BaseModel
from typing import Optional, List


class AttendeeCost(BaseModel):
    base_price: Optional[dict] = None
    eventbrite_fee: Optional[dict] = None
    tax: Optional[dict] = None
    payment_fee: Optional[dict] = None
    gross: Optional[dict] = None


class AttendeeProfile(BaseModel):
    name: Optional[str] = None
    email: Optional[str] = None
    first_name: Optional[str] = None
    last_name: Optional[str] = None
    prefix: Optional[str] = None
    suffix: Optional[str] = None
    age: Optional[int] = None
    job_title: Optional[str] = None
    company: Optional[str] = None
    website: Optional[str] = None
    blog: Optional[str] = None
    gender: Optional[str] = None
    birth_date: Optional[str] = None
    cell_phone: Optional[str] = None


class AttendeeResponse(BaseModel):
    id: str
    created: str
    changed: str
    ticket_class_id: Optional[str] = None
    variant_id: Optional[str] = None
    ticket_class_name: Optional[str] = None
    quantity: int
    costs: Optional[AttendeeCost] = None
    profile: Optional[AttendeeProfile] = None
    addresses: Optional[dict] = None
    questions: Optional[list] = None
    answers: Optional[list] = None
    barcodes: Optional[list] = None
    team: Optional[dict] = None
    affiliate: Optional[dict] = None
    checked_in: Optional[bool] = None
    cancelled: Optional[bool] = None
    refunded: Optional[bool] = None
    status: Optional[str] = None
    event_id: Optional[str] = None
    order_id: Optional[str] = None
    guestlist_id: Optional[str] = None
    invited_by: Optional[str] = None
    delivery_method: Optional[str] = None
