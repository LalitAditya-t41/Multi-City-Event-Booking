package com.eventplatform.shared.stripe.dto;

/**
 * Response returned after creating or reading a Stripe Refund.
 *
 * @param refundId Stripe Refund ID (re_…).
 * @param status Refund status: "pending", "succeeded", "failed", "canceled".
 * @param amount Amount refunded in smallest currency unit.
 * @param currency Three-letter ISO currency code (lowercase).
 */
public record StripeRefundResponse(String refundId, String status, long amount, String currency) {}
