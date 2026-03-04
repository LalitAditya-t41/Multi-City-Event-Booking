package com.eventplatform.shared.stripe.dto;

/**
 * Request to create a Stripe Refund.
 *
 * @param paymentIntentId Stripe PaymentIntent ID to refund.
 * @param amount          Amount to refund in smallest currency unit; null means full refund.
 * @param reason          Stripe refund reason: "duplicate", "fraudulent", or "requested_by_customer".
 * @param idempotencyKey  Caller-supplied idempotency key.
 */
public record StripeRefundRequest(
    String paymentIntentId,
    Long amount,
    String reason,
    String idempotencyKey
) {}
