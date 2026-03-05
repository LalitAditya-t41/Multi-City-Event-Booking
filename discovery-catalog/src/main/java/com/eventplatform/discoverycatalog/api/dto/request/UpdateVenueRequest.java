package com.eventplatform.discoverycatalog.api.dto.request;

import com.eventplatform.shared.common.enums.SeatingMode;
import jakarta.validation.constraints.Positive;

public record UpdateVenueRequest(
    String name,
    String addressLine1,
    String addressLine2,
    Long cityId,
    String country,
    String zipCode,
    String latitude,
    String longitude,
    @Positive Integer capacity,
    SeatingMode seatingMode) {}
