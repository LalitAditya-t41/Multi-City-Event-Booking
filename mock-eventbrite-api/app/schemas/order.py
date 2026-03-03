from __future__ import annotations

from pydantic import BaseModel
from typing import Optional, List


class OrderCostComponent(BaseModel):
    display: Optional[str] = None
    value: Optional[int] = None
    currency: Optional[str] = None


class OrderDisplayTax(BaseModel):
    name: Optional[str] = None
    tax: Optional[dict] = None


class OrderCosts(BaseModel):
    base_price: Optional[dict] = None
    display_price: Optional[dict] = None
    display_fee: Optional[dict] = None
    gross: Optional[dict] = None
    eventbrite_fee: Optional[dict] = None
    payment_fee: Optional[dict] = None
    tax: Optional[dict] = None
    display_tax: Optional[OrderDisplayTax] = None
    price_before_discount: Optional[dict] = None
    discount_amount: Optional[dict] = None
    discount_type: Optional[str] = None
    fee_components: Optional[List[OrderCostComponent]] = None
    tax_components: Optional[List[OrderCostComponent]] = None
    shipping_components: Optional[List[OrderCostComponent]] = None
    has_gts_tax: Optional[bool] = None
    tax_name: Optional[str] = None


class RefundItem(BaseModel):
    event_id: Optional[str] = None
    order_id: Optional[str] = None
    processed_date: Optional[str] = None
    item_type: Optional[str] = None
    amount_processed: Optional[dict] = None
    amount_requested: Optional[dict] = None
    quantity_processed: Optional[int] = None
    quantity_requested: Optional[int] = None
    refund_reason_code: Optional[str] = None
    status: Optional[str] = None


class RefundRequest(BaseModel):
    from_email: Optional[str] = None
    from_name: Optional[str] = None
    status: Optional[str] = None
    message: Optional[str] = None
    reason: Optional[str] = None
    last_message: Optional[str] = None
    last_reason: Optional[str] = None
    items: Optional[List[RefundItem]] = None


class OrderResponse(BaseModel):
    id: str
    created: str
    changed: str
    name: Optional[str] = None
    first_name: Optional[str] = None
    last_name: Optional[str] = None
    email: Optional[str] = None
    costs: Optional[OrderCosts] = None
    event_id: Optional[str] = None
    time_remaining: Optional[int] = None
    questions: Optional[list] = None
    answers: Optional[list] = None
    promo_code: Optional[str] = None
    status: Optional[str] = None
    refund_request: Optional[RefundRequest] = None
