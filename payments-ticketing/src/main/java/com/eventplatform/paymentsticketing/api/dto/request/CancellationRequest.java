package com.eventplatform.paymentsticketing.api.dto.request;

import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import jakarta.validation.constraints.NotNull;

public record CancellationRequest(@NotNull RefundReason reason) {}
