from __future__ import annotations

from sqlalchemy import String, Integer, Boolean, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import JSON

from app.database import Base


class Attendee(Base):
    __tablename__ = "attendees"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    event_id: Mapped[str] = mapped_column(String, ForeignKey("events.id"), nullable=False)
    order_id: Mapped[str] = mapped_column(String, ForeignKey("orders.id"), nullable=False)
    ticket_class_id: Mapped[str | None] = mapped_column(String, nullable=True)

    created: Mapped[str] = mapped_column(String, nullable=False)
    changed: Mapped[str] = mapped_column(String, nullable=False)

    ticket_class_name: Mapped[str | None] = mapped_column(String, nullable=True)
    quantity: Mapped[int] = mapped_column(Integer, default=1)

    costs: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    profile: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    addresses: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    questions: Mapped[list | None] = mapped_column(JSON, nullable=True)
    answers: Mapped[list | None] = mapped_column(JSON, nullable=True)
    barcodes: Mapped[list | None] = mapped_column(JSON, nullable=True)
    team: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    affiliate: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    checked_in: Mapped[bool] = mapped_column(Boolean, default=False)
    cancelled: Mapped[bool] = mapped_column(Boolean, default=False)
    refunded: Mapped[bool] = mapped_column(Boolean, default=False)
    status: Mapped[str | None] = mapped_column(String, nullable=True)

    guestlist_id: Mapped[str | None] = mapped_column(String, nullable=True)
    invited_by: Mapped[str | None] = mapped_column(String, nullable=True)
    delivery_method: Mapped[str | None] = mapped_column(String, nullable=True)
