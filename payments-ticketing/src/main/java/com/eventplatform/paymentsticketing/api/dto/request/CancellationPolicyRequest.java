package com.eventplatform.paymentsticketing.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CancellationPolicyRequest(
    Long orgId,
    @NotEmpty List<@Valid TierRequest> tiers
) {

    public record TierRequest(
        Integer hoursBeforeEvent,
        @NotNull Integer refundPercent,
        @NotNull Integer sortOrder
    ) {
    }
}
