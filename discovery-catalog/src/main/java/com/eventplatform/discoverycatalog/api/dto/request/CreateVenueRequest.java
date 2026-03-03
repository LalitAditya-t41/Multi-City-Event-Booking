package com.eventplatform.discoverycatalog.api.dto.request;

import com.eventplatform.shared.common.enums.SeatingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateVenueRequest(
    @NotBlank String name,
    @NotBlank String addressLine1,
    String addressLine2,
    @NotNull Long cityId,
    @NotBlank String country,
    String zipCode,
    String latitude,
    String longitude,
    @NotNull @Positive Integer capacity,
    @NotNull SeatingMode seatingMode
) {
}
