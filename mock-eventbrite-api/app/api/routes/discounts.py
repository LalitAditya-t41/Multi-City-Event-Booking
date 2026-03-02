from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import require_auth
from app.api.db import get_db
from app.exception.exceptions import ApiError
from app.models.discount import Discount
from app.schemas.discount import DiscountCreate, DiscountResponse
from app.services.pagination import paginate

router = APIRouter(prefix="/v3", tags=["discounts"])


def _to_response(discount: Discount) -> DiscountResponse:
    return DiscountResponse(
        id=discount.id,
        code=discount.code,
        type=discount.type,
        end_date=discount.end_date,
        end_date_relative=discount.end_date_relative,
        amount_off=discount.amount_off,
        percent_off=discount.percent_off,
        quantity_available=discount.quantity_available,
        quantity_sold=discount.quantity_sold,
        start_date=discount.start_date,
        start_date_relative=discount.start_date_relative,
        ticket_class_ids=discount.ticket_class_ids,
        event_id=discount.event_id,
        ticket_group_id=discount.ticket_group_id,
        hold_ids=discount.hold_ids,
    )


@router.get("/discounts/{discount_id}/", response_model=DiscountResponse, dependencies=[Depends(require_auth)])
def get_discount(discount_id: str, db: Session = Depends(get_db)):
    discount = db.get(Discount, discount_id)
    if not discount:
        raise ApiError("NOT_FOUND", "Invalid URL.", 404)
    return _to_response(discount)


@router.post("/organizations/{organization_id}/discounts/", response_model=DiscountResponse, dependencies=[Depends(require_auth)])
def create_discount(organization_id: str, body: dict, db: Session = Depends(get_db)):
    if "discount" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing discount object.", 400)
    payload = DiscountCreate(**body["discount"])

    new_id = f"discount_{organization_id}_{payload.code}"
    if db.get(Discount, new_id):
        raise ApiError("REQUEST_CONFLICT", "Requested operation resulted in conflict.", 409)

    discount = Discount(
        id=new_id,
        organization_id=organization_id,
        code=payload.code,
        type=payload.type,
        end_date=payload.end_date,
        end_date_relative=payload.end_date_relative,
        amount_off=payload.amount_off,
        percent_off=payload.percent_off,
        quantity_available=payload.quantity_available,
        quantity_sold=0,
        start_date=payload.start_date,
        start_date_relative=payload.start_date_relative,
        ticket_class_ids=payload.ticket_class_ids,
        event_id=payload.event_id,
        ticket_group_id=payload.ticket_group_id,
        hold_ids=payload.hold_ids,
    )
    db.add(discount)
    db.commit()
    db.refresh(discount)
    return _to_response(discount)


@router.post("/discounts/{discount_id}/", response_model=DiscountResponse, dependencies=[Depends(require_auth)])
def update_discount(discount_id: str, body: dict, db: Session = Depends(get_db)):
    if "discount" not in body:
        raise ApiError("ARGUMENTS_ERROR", "Missing discount object.", 400)
    payload = DiscountCreate(**body["discount"])
    discount = db.get(Discount, discount_id)
    if not discount:
        raise ApiError("NOT_FOUND", "Invalid URL.", 404)

    discount.code = payload.code
    discount.type = payload.type
    discount.end_date = payload.end_date
    discount.end_date_relative = payload.end_date_relative
    discount.amount_off = payload.amount_off
    discount.percent_off = payload.percent_off
    discount.quantity_available = payload.quantity_available
    discount.start_date = payload.start_date
    discount.start_date_relative = payload.start_date_relative
    discount.ticket_class_ids = payload.ticket_class_ids
    discount.event_id = payload.event_id
    discount.ticket_group_id = payload.ticket_group_id
    discount.hold_ids = payload.hold_ids

    db.commit()
    db.refresh(discount)
    return _to_response(discount)


@router.delete("/discounts/{discount_id}/", response_model=DiscountResponse, dependencies=[Depends(require_auth)])
def delete_discount(discount_id: str, db: Session = Depends(get_db)):
    discount = db.get(Discount, discount_id)
    if not discount:
        raise ApiError("NOT_FOUND", "Invalid URL.", 404)
    if discount.quantity_sold > 0:
        raise ApiError("DISCOUNT_CANNOT_BE_DELETED", "A discount that has been used cannot be deleted.", 400)
    db.delete(discount)
    db.commit()
    return _to_response(discount)


@router.get("/organizations/{organization_id}/discounts/", dependencies=[Depends(require_auth)])
def list_discounts(
    organization_id: str,
    scope: str = Query(default="event"),
    code_filter: str | None = Query(default=None),
    code: str | None = Query(default=None),
    page_size: int = Query(default=50),
    page: int = Query(default=1),
    db: Session = Depends(get_db),
):
    discounts = db.query(Discount).filter(Discount.organization_id == organization_id).all()
    if code_filter:
        discounts = [d for d in discounts if code_filter.lower() in d.code.lower()]
    if code:
        discounts = [d for d in discounts if d.code == code]
    items, pagination = paginate(discounts, page_size, page)
    return {"pagination": pagination.model_dump(), "discounts": [_to_response(d).model_dump() for d in items]}
