from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.ticket_class import TicketClass
from app.schemas.ticket_class import TicketClassCreate, TicketClassUpdate, TicketClassResponse
from app.services.pagination import paginate

router = APIRouter(prefix="/v3", tags=["ticket-classes"])


def _parse_cost(cost_str: str | None) -> dict | None:
    if not cost_str:
        return None
    if "," not in cost_str:
        return {"currency": "USD", "value": int(cost_str), "major_value": None, "display": None}
    currency, value = cost_str.split(",", 1)
    try:
        value_int = int(value)
    except ValueError:
        value_int = 0
    return {
        "currency": currency,
        "value": value_int,
        "major_value": f"{value_int/100:.2f}",
        "display": f"{currency} {value_int/100:.2f}",
    }


def _to_response(tc: TicketClass) -> TicketClassResponse:
    remaining = None
    if tc.capacity is not None:
        remaining = tc.capacity - tc.quantity_sold
    on_sale_status = "AVAILABLE" if remaining is None or remaining > 0 else "SOLD_OUT"
    return TicketClassResponse(
        id=tc.id,
        name=tc.name,
        description=tc.description,
        sorting=tc.sorting,
        cost=tc.cost,
        fee=tc.fee,
        donation=tc.donation,
        free=tc.free,
        minimum_quantity=tc.minimum_quantity,
        maximum_quantity=tc.maximum_quantity,
        delivery_methods=tc.delivery_methods,
        capacity=tc.capacity,
        quantity_sold=tc.quantity_sold,
        hidden=tc.hidden,
        sales_start=tc.sales_start,
        sales_end=tc.sales_end,
        sales_end_relative=tc.sales_end_relative,
        include_fee=tc.include_fee,
        split_fee=tc.split_fee,
        hide_description=tc.hide_description,
        auto_hide=tc.auto_hide,
        auto_hide_before=tc.auto_hide_before,
        auto_hide_after=tc.auto_hide_after,
        order_confirmation_message=tc.order_confirmation_message,
        inventory_tier_id=tc.inventory_tier_id,
        image_id=tc.image_id,
        on_sale_status=on_sale_status,
    )


@router.get("/events/{event_id}/ticket_classes/", dependencies=[Depends(require_auth)])
def list_ticket_classes(
    event_id: str,
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    classes = db.query(TicketClass).filter(TicketClass.event_id == event_id).all()
    items, pagination = paginate(classes, page_size, page)
    return {"pagination": pagination.model_dump(), "ticket_classes": [_to_response(tc).model_dump() for tc in items]}


@router.get("/events/{event_id}/ticket_classes/for_sale/", dependencies=[Depends(require_auth)])
def list_ticket_classes_for_sale(
    event_id: str,
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    classes = db.query(TicketClass).filter(TicketClass.event_id == event_id).all()
    classes = [tc for tc in classes if not tc.hidden]
    items, pagination = paginate(classes, page_size, page)
    return {"pagination": pagination.model_dump(), "ticket_classes": [_to_response(tc).model_dump() for tc in items]}


@router.get("/events/{event_id}/ticket_classes/{ticket_class_id}/", response_model=TicketClassResponse, dependencies=[Depends(require_auth)])
def get_ticket_class(event_id: str, ticket_class_id: str, db: Session = Depends(get_db)):
    tc = db.get(TicketClass, ticket_class_id)
    if not tc or tc.event_id != event_id:
        raise ApiError("NOT_FOUND", "The event you requested does not exist.", 404)
    return _to_response(tc)


@router.post("/events/{event_id}/ticket_classes/", response_model=TicketClassResponse, dependencies=[Depends(require_auth)])
def create_ticket_class(event_id: str, body: dict, db: Session = Depends(get_db)):
    if "ticket_class" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing ticket_class object.", 400)
    payload = TicketClassCreate(**body["ticket_class"])

    if payload.cost is None and not payload.free and not payload.donation:
        raise ApiError("NO_COST", "A price must be set for a charged ticket.", 400)

    new_id = f"ticket_{int(datetime.utcnow().timestamp())}"
    tc = TicketClass(
        id=new_id,
        event_id=event_id,
        name=payload.name or "Ticket",
        description=payload.description,
        sorting=payload.sorting,
        cost=_parse_cost(payload.cost),
        fee=None,
        donation=payload.donation or False,
        free=payload.free or False,
        minimum_quantity=payload.minimum_quantity,
        maximum_quantity=payload.maximum_quantity,
        capacity=payload.capacity or payload.quantity_total,
        quantity_sold=0,
        hidden=payload.hidden or False,
        sales_start=payload.sales_start,
        sales_end=payload.sales_end,
        sales_end_relative=payload.sales_end_relative,
        include_fee=payload.include_fee or False,
        split_fee=payload.split_fee or False,
        hide_description=payload.hide_description or False,
        hide_sale_dates=False,
        auto_hide=payload.auto_hide or False,
        auto_hide_before=payload.auto_hide_before,
        auto_hide_after=payload.auto_hide_after,
        order_confirmation_message=payload.order_confirmation_message,
        delivery_methods=payload.delivery_methods,
        inventory_tier_id=payload.inventory_tier_id,
        image_id=None,
    )
    db.add(tc)
    db.commit()
    db.refresh(tc)
    return _to_response(tc)


@router.post("/events/{event_id}/ticket_classes/{ticket_class_id}/", response_model=TicketClassResponse, dependencies=[Depends(require_auth)])
def update_ticket_class(event_id: str, ticket_class_id: str, body: dict, db: Session = Depends(get_db)):
    if "ticket_class" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing ticket_class object.", 400)
    payload = TicketClassUpdate(**body["ticket_class"])

    tc = db.get(TicketClass, ticket_class_id)
    if not tc or tc.event_id != event_id:
        raise ApiError("NOT_FOUND", "The event you requested does not exist.", 404)

    if payload.name is not None:
        tc.name = payload.name
    if payload.description is not None:
        tc.description = payload.description
    if payload.sorting is not None:
        tc.sorting = payload.sorting
    if payload.cost is not None:
        tc.cost = _parse_cost(payload.cost)
    if payload.capacity is not None:
        tc.capacity = payload.capacity
    if payload.quantity_total is not None:
        tc.capacity = payload.quantity_total
    if payload.donation is not None:
        tc.donation = payload.donation
    if payload.free is not None:
        tc.free = payload.free
    if payload.minimum_quantity is not None:
        tc.minimum_quantity = payload.minimum_quantity
    if payload.maximum_quantity is not None:
        tc.maximum_quantity = payload.maximum_quantity
    if payload.hidden is not None:
        tc.hidden = payload.hidden
    if payload.sales_start is not None:
        tc.sales_start = payload.sales_start
    if payload.sales_end is not None:
        tc.sales_end = payload.sales_end
    if payload.sales_end_relative is not None:
        tc.sales_end_relative = payload.sales_end_relative
    if payload.include_fee is not None:
        tc.include_fee = payload.include_fee
    if payload.split_fee is not None:
        tc.split_fee = payload.split_fee
    if payload.hide_description is not None:
        tc.hide_description = payload.hide_description
    if payload.auto_hide is not None:
        tc.auto_hide = payload.auto_hide
    if payload.auto_hide_before is not None:
        tc.auto_hide_before = payload.auto_hide_before
    if payload.auto_hide_after is not None:
        tc.auto_hide_after = payload.auto_hide_after
    if payload.order_confirmation_message is not None:
        tc.order_confirmation_message = payload.order_confirmation_message
    if payload.delivery_methods is not None:
        tc.delivery_methods = payload.delivery_methods
    if payload.inventory_tier_id is not None:
        tc.inventory_tier_id = payload.inventory_tier_id
    if payload.image_id is not None:
        tc.image_id = payload.image_id

    db.commit()
    db.refresh(tc)
    return _to_response(tc)
