from __future__ import annotations

from sqlalchemy import String, Integer, Boolean, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import JSON

from app.database import Base


class TicketClass(Base):
    __tablename__ = "ticket_classes"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    event_id: Mapped[str] = mapped_column(String, ForeignKey("events.id"), nullable=False)

    name: Mapped[str] = mapped_column(String, nullable=False)
    description: Mapped[str | None] = mapped_column(String, nullable=True)
    sorting: Mapped[int | None] = mapped_column(Integer, nullable=True)

    cost: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    fee: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    donation: Mapped[bool] = mapped_column(Boolean, default=False)
    free: Mapped[bool] = mapped_column(Boolean, default=False)

    minimum_quantity: Mapped[int | None] = mapped_column(Integer, nullable=True)
    maximum_quantity: Mapped[int | None] = mapped_column(Integer, nullable=True)

    capacity: Mapped[int | None] = mapped_column(Integer, nullable=True)
    quantity_sold: Mapped[int] = mapped_column(Integer, default=0)

    hidden: Mapped[bool] = mapped_column(Boolean, default=False)
    sales_start: Mapped[str | None] = mapped_column(String, nullable=True)
    sales_end: Mapped[str | None] = mapped_column(String, nullable=True)
    sales_end_relative: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    include_fee: Mapped[bool] = mapped_column(Boolean, default=False)
    split_fee: Mapped[bool] = mapped_column(Boolean, default=False)
    hide_description: Mapped[bool] = mapped_column(Boolean, default=False)
    hide_sale_dates: Mapped[bool] = mapped_column(Boolean, default=False)
    auto_hide: Mapped[bool] = mapped_column(Boolean, default=False)
    auto_hide_before: Mapped[str | None] = mapped_column(String, nullable=True)
    auto_hide_after: Mapped[str | None] = mapped_column(String, nullable=True)
    order_confirmation_message: Mapped[str | None] = mapped_column(String, nullable=True)
    delivery_methods: Mapped[list | None] = mapped_column(JSON, nullable=True)

    inventory_tier_id: Mapped[str | None] = mapped_column(String, nullable=True)
    image_id: Mapped[str | None] = mapped_column(String, nullable=True)
