from __future__ import annotations

from sqlalchemy import String, Boolean, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import JSON

from app.database import Base


class Event(Base):
    __tablename__ = "events"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    organization_id: Mapped[str] = mapped_column(String, ForeignKey("organizations.id"), nullable=False)
    venue_id: Mapped[str | None] = mapped_column(String, ForeignKey("venues.id"), nullable=True)

    name: Mapped[dict] = mapped_column(JSON, nullable=False)
    summary: Mapped[str | None] = mapped_column(String, nullable=True)
    description: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    start: Mapped[dict] = mapped_column(JSON, nullable=False)
    end: Mapped[dict] = mapped_column(JSON, nullable=False)

    created: Mapped[str] = mapped_column(String, nullable=False)
    changed: Mapped[str] = mapped_column(String, nullable=False)
    published: Mapped[str | None] = mapped_column(String, nullable=True)

    status: Mapped[str] = mapped_column(String, nullable=False)
    currency: Mapped[str] = mapped_column(String, nullable=False)
    online_event: Mapped[bool] = mapped_column(Boolean, default=False)
    listed: Mapped[bool] = mapped_column(Boolean, default=True)
    shareable: Mapped[bool] = mapped_column(Boolean, default=True)
    invite_only: Mapped[bool] = mapped_column(Boolean, default=False)
    show_remaining: Mapped[bool] = mapped_column(Boolean, default=False)
    password: Mapped[str | None] = mapped_column(String, nullable=True)
    capacity: Mapped[int | None] = mapped_column(Integer, nullable=True)
    capacity_is_custom: Mapped[bool] = mapped_column(Boolean, default=False)

    is_series: Mapped[bool] = mapped_column(Boolean, default=False)
    series_parent_id: Mapped[str | None] = mapped_column(String, nullable=True)

    is_reserved_seating: Mapped[bool] = mapped_column(Boolean, default=False)
    show_pick_a_seat: Mapped[bool] = mapped_column(Boolean, default=False)
    show_seatmap_thumbnail: Mapped[bool] = mapped_column(Boolean, default=False)
    show_colors_in_seatmap_thumbnail: Mapped[bool] = mapped_column(Boolean, default=False)

    organizer_id: Mapped[str | None] = mapped_column(String, nullable=True)
    logo_id: Mapped[str | None] = mapped_column(String, nullable=True)
    format_id: Mapped[str | None] = mapped_column(String, nullable=True)
    category_id: Mapped[str | None] = mapped_column(String, nullable=True)
    subcategory_id: Mapped[str | None] = mapped_column(String, nullable=True)
    locale: Mapped[str | None] = mapped_column(String, nullable=True)
    source: Mapped[str | None] = mapped_column(String, nullable=True)
