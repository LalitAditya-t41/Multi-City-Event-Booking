package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.CancellationPolicyScope;
import java.util.List;

public record CancellationPolicyResponse(
    Long id,
    Long orgId,
    CancellationPolicyScope scope,
    Long createdByAdminId,
    List<TierResponse> tiers
) {

    public record TierResponse(
        Long id,
        Integer hoursBeforeEvent,
        Integer refundPercent,
        Integer sortOrder
    ) {
    }
}
