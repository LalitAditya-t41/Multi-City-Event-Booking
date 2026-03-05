package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;

public record RefundResponse(
    String stripeRefundId, Long amount, String currency, RefundStatus status) {}
