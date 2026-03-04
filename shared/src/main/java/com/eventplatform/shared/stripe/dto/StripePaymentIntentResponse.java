package com.eventplatform.shared.stripe.dto;

/**
 * Response returned after creating or reading a Stripe PaymentIntent.
 *
 * @param paymentIntentId Stripe PaymentIntent ID (pi_…).
 * @param clientSecret    Client secret — must be sent to the frontend for Stripe.js confirmation.
 * @param status          PaymentIntent status string, e.g. "requires_payment_method", "succeeded".
 * @param amount          Amount in smallest currency unit.
 * @param currency        Three-letter ISO currency code (lowercase).
 */
public record StripePaymentIntentResponse(
    String paymentIntentId,
    String clientSecret,
    String status,
    long amount,
    String currency,
    String latestCharge
) {}
