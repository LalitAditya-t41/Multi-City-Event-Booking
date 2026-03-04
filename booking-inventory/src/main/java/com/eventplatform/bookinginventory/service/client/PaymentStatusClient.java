package com.eventplatform.bookinginventory.service.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Internal REST client to check payment status in the payments-ticketing module.
 *
 * <p>Called by the PaymentTimeoutWatchdog to verify whether a cart has a confirmed
 * Stripe payment before releasing a timed-out seat lock.
 *
 * <p>Per PRODUCT.md Hard Rule #1: booking-inventory cannot import payments-ticketing
 * @Service / @Repository beans directly; inter-module calls must go via REST.
 */
@Service
public class PaymentStatusClient {

    private final RestClient restClient;

    public PaymentStatusClient(
        @Value("${app.internal-base-url:http://localhost:8080}") String baseUrl
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Returns {@code true} if the cart has a confirmed payment in payments-ticketing.
     *
     * <p>Returns {@code false} on any error (connection refused, 404, etc.) so that
     * the watchdog defaults to releasing the lock — correct because a confirmed payment
     * would have already transitioned the seat to CONFIRMED state.
     *
     * @param cartId the booking-inventory cart ID.
     * @return {@code true} if payment is confirmed; {@code false} otherwise.
     */
    public boolean isPaymentConfirmed(Long cartId) {
        if (cartId == null) {
            return false;
        }
        try {
            PaymentStatusResponse response = restClient.get()
                .uri("/internal/payments/by-cart/{cartId}/status", cartId)
                .retrieve()
                .body(PaymentStatusResponse.class);
            return response != null && "SUCCEEDED".equalsIgnoreCase(response.status());
        } catch (RestClientException ex) {
            // payments-ticketing unavailable or cart has no payment record → safe to release
            return false;
        }
    }

    // DTO for the internal endpoint response
    public record PaymentStatusResponse(String status) {}
}
