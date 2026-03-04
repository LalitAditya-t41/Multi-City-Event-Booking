package com.eventplatform.paymentsticketing.domain.enums;

/**
 * Lifecycle states of a Payment record.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
