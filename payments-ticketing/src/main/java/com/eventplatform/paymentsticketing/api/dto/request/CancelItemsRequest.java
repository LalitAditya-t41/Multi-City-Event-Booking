package com.eventplatform.paymentsticketing.api.dto.request;

import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CancelItemsRequest(
    @NotEmpty List<Long> bookingItemIds, @NotNull RefundReason reason) {}
