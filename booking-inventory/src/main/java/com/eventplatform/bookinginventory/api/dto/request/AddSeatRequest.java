package com.eventplatform.bookinginventory.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddSeatRequest(
    @NotNull Long slotId, Long seatId, @NotNull Long tierId, @NotNull @Min(1) Integer quantity) {}
