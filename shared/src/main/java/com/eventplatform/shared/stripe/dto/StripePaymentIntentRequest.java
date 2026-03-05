package com.eventplatform.shared.stripe.dto;

/**
 * Request to create a Stripe PaymentIntent.
 *
 * @param amount Amount in smallest currency unit (e.g. paise for INR).
 * @param currency Three-letter ISO currency code (lowercase), e.g. "inr".
 * @param receiptEmail Stripe will send a receipt to this address on capture.
 * @param description Human-readable description shown in Stripe dashboard.
 * @param idempotencyKey Caller-supplied idempotency key; prevents duplicate charges.
 */
public record StripePaymentIntentRequest(
    long amount,
    String currency,
    String receiptEmail,
    String description,
    String idempotencyKey,
    java.util.Map<String, String> metadata) {}
