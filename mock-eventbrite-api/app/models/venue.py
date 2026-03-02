from __future__ import annotations

from sqlalchemy import String, Integer, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import JSON

from app.database import Base


class Venue(Base):
    __tablename__ = "venues"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    organization_id: Mapped[str] = mapped_column(String, ForeignKey("organizations.id"), nullable=False)
    name: Mapped[str] = mapped_column(String, nullable=False)
    capacity: Mapped[int | None] = mapped_column(Integer, nullable=True)
    age_restriction: Mapped[str | None] = mapped_column(String, nullable=True)
    latitude: Mapped[str | None] = mapped_column(String, nullable=True)
    longitude: Mapped[str | None] = mapped_column(String, nullable=True)
    address: Mapped[dict | None] = mapped_column(JSON, nullable=True)
