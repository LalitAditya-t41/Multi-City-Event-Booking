from __future__ import annotations

from pydantic import BaseModel
from typing import Optional


class AddressResponse(BaseModel):
    address_1: Optional[str] = None
    address_2: Optional[str] = None
    city: Optional[str] = None
    region: Optional[str] = None
    postal_code: Optional[str] = None
    country: Optional[str] = None
    latitude: Optional[str] = None
    longitude: Optional[str] = None


class VenueResponse(BaseModel):
    id: str
    name: str
    capacity: Optional[int] = None
    age_restriction: Optional[str] = None
    latitude: Optional[str] = None
    longitude: Optional[str] = None
    address: Optional[AddressResponse] = None
