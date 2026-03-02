from __future__ import annotations

from pydantic import BaseModel
from typing import Optional, List


class TicketClassCost(BaseModel):
    currency: Optional[str] = None
    value: Optional[int] = None
    major_value: Optional[str] = None
    display: Optional[str] = None


class TicketClassCreate(BaseModel):
    cost: Optional[str] = None
    name: Optional[str] = None
    description: Optional[str] = None
    sorting: Optional[int] = None
    capacity: Optional[int] = None
    quantity_total: Optional[int] = None
    donation: Optional[bool] = None
    free: Optional[bool] = None
    include_fee: Optional[bool] = None
    split_fee: Optional[bool] = None
    hide_description: Optional[bool] = None
    sales_channels: Optional[List[str]] = None
    sales_start: Optional[str] = None
    sales_end: Optional[str] = None
    sales_end_relative: Optional[dict] = None
    sales_start_after: Optional[str] = None
    minimum_quantity: Optional[int] = None
    maximum_quantity: Optional[int] = None
    auto_hide: Optional[bool] = None
    auto_hide_before: Optional[str] = None
    auto_hide_after: Optional[str] = None
    has_pdf_ticket: Optional[bool] = None
    hidden: Optional[bool] = None
    order_confirmation_message: Optional[str] = None
    delivery_methods: Optional[List[str]] = None
    inventory_tier_id: Optional[str] = None
    ticket_classes: Optional[List[dict]] = None


class TicketClassUpdate(TicketClassCreate):
    image_id: Optional[str] = None


class TicketClassResponse(BaseModel):
    id: str
    name: Optional[str] = None
    description: Optional[str] = None
    sorting: Optional[int] = None
    cost: Optional[TicketClassCost] = None
    fee: Optional[TicketClassCost] = None
    donation: Optional[bool] = None
    free: Optional[bool] = None
    minimum_quantity: Optional[int] = None
    maximum_quantity: Optional[int] = None
    delivery_methods: Optional[List[str]] = None
    capacity: Optional[int] = None
    quantity_sold: Optional[int] = None
    hidden: Optional[bool] = None
    sales_start: Optional[str] = None
    sales_end: Optional[str] = None
    sales_end_relative: Optional[dict] = None
    include_fee: Optional[bool] = None
    split_fee: Optional[bool] = None
    hide_description: Optional[bool] = None
    auto_hide: Optional[bool] = None
    auto_hide_before: Optional[str] = None
    auto_hide_after: Optional[str] = None
    order_confirmation_message: Optional[str] = None
    inventory_tier_id: Optional[str] = None
    image_id: Optional[str] = None
    on_sale_status: Optional[str] = None
