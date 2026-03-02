from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.order import Order
from app.schemas.order import OrderResponse
from app.services.pagination import paginate

router = APIRouter(prefix="/v3", tags=["orders"])


def _to_response(order: Order) -> OrderResponse:
    return OrderResponse(
        id=order.id,
        created=order.created,
        changed=order.changed,
        name=order.name,
        first_name=order.first_name,
        last_name=order.last_name,
        email=order.email,
        costs=order.costs,
        event_id=order.event_id,
        time_remaining=int(order.time_remaining) if order.time_remaining else None,
        questions=order.questions,
        answers=order.answers,
        promo_code=order.promo_code,
        status=order.status,
        refund_request=order.refund_request,
    )


@router.get("/orders/{order_id}/", response_model=OrderResponse, dependencies=[Depends(require_auth)])
def get_order(order_id: str, db: Session = Depends(get_db)):
    order = db.get(Order, order_id)
    if not order:
        raise ApiError("NOT_FOUND", "Invalid URL.", 404)
    return _to_response(order)


@router.get("/organizations/{organization_id}/orders/", dependencies=[Depends(require_auth)])
def list_orders_by_org(
    organization_id: str,
    status: str | None = Query(default=None),
    changed_since: str | None = Query(default=None),
    only_emails: list[str] | None = Query(default=None),
    exclude_emails: list[str] | None = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    orders = db.query(Order).filter(Order.organization_id == organization_id).all()
    if only_emails:
        orders = [o for o in orders if o.email in only_emails]
    if exclude_emails:
        orders = [o for o in orders if o.email not in exclude_emails]
    if status and status != "both":
        orders = [o for o in orders if o.status == status]
    items, pagination = paginate(orders, page_size, page)
    return {"pagination": pagination.model_dump(), "orders": [_to_response(o).model_dump() for o in items]}


@router.get("/events/{event_id}/orders/", dependencies=[Depends(require_auth)])
def list_orders_by_event(
    event_id: str,
    status: str | None = Query(default=None),
    refund_request_statuses: list[str] | None = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    orders = db.query(Order).filter(Order.event_id == event_id).all()
    if status and status != "both":
        orders = [o for o in orders if o.status == status]
    if refund_request_statuses:
        orders = [o for o in orders if o.refund_request and o.refund_request.get("status") in refund_request_statuses]
    items, pagination = paginate(orders, page_size, page)
    return {"pagination": pagination.model_dump(), "orders": [_to_response(o).model_dump() for o in items]}


@router.get("/users/{user_id}/orders/", dependencies=[Depends(require_auth)])
def list_orders_by_user(
    user_id: str,
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    # No user model; return all orders as mock
    orders = db.query(Order).all()
    items, pagination = paginate(orders, page_size, page)
    return {"pagination": pagination.model_dump(), "orders": [_to_response(o).model_dump() for o in items]}
