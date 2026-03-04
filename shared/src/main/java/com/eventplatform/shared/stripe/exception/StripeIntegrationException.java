package com.eventplatform.shared.stripe.exception;

/**
 * Thrown when a Stripe API call fails with a non-retriable error.
 */
public class StripeIntegrationException extends RuntimeException {

    public StripeIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public StripeIntegrationException(String message) {
        super(message);
    }
}
