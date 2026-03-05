package com.eventplatform.bookinginventory.domain.enums;

public enum LockReleaseReason {
  TTL_EXPIRED,
  USER_REMOVED,
  CART_ABANDONED,
  PAYMENT_FAILED,
  ORDER_ABANDONED,
  PAYMENT_TIMEOUT,
  TIER_SOLD_OUT
}
