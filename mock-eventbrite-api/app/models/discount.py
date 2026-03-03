from __future__ import annotations

from sqlalchemy import String, Integer, Float, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import JSON

from app.database import Base


class Discount(Base):
    __tablename__ = "discounts"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    organization_id: Mapped[str] = mapped_column(String, ForeignKey("organizations.id"), nullable=False)

    code: Mapped[str] = mapped_column(String, nullable=False)
    type: Mapped[str] = mapped_column(String, nullable=False)

    end_date: Mapped[str | None] = mapped_column(String, nullable=True)
    end_date_relative: Mapped[int | None] = mapped_column(Integer, nullable=True)
    amount_off: Mapped[float | None] = mapped_column(Float, nullable=True)
    percent_off: Mapped[float | None] = mapped_column(Float, nullable=True)
    quantity_available: Mapped[int | None] = mapped_column(Integer, nullable=True)
    quantity_sold: Mapped[int] = mapped_column(Integer, default=0)
    start_date: Mapped[str | None] = mapped_column(String, nullable=True)
    start_date_relative: Mapped[int | None] = mapped_column(Integer, nullable=True)

    ticket_class_ids: Mapped[list | None] = mapped_column(JSON, nullable=True)
    event_id: Mapped[str | None] = mapped_column(String, nullable=True)
    ticket_group_id: Mapped[str | None] = mapped_column(String, nullable=True)
    hold_ids: Mapped[list | None] = mapped_column(JSON, nullable=True)
