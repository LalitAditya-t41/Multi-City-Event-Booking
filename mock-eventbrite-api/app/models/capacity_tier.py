from __future__ import annotations

from sqlalchemy import String, Integer, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import JSON

from app.database import Base


class CapacityTier(Base):
    __tablename__ = "capacity_tiers"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    event_id: Mapped[str] = mapped_column(String, ForeignKey("events.id"), nullable=False)
    capacity_total: Mapped[int | None] = mapped_column(Integer, nullable=True)
    holds: Mapped[list | None] = mapped_column(JSON, nullable=True)
