package com.eventplatform.shared.stripe.exception;

/**
 * Thrown when a Stripe webhook signature verification fails.
 * The request should be rejected with HTTP 400.
 */
public class StripeWebhookSignatureException extends RuntimeException {

    public StripeWebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
