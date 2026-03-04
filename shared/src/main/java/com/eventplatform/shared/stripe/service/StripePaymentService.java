package com.eventplatform.shared.stripe.service;

import com.eventplatform.shared.stripe.config.StripeConfig;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentRequest;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentResponse;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Service;

/**
 * ACL facade — all Stripe PaymentIntent interactions go through this class.
 *
 * <p>Hard Rule #2: no module calls Stripe HTTP directly; only this facade.
 */
@Service
public class StripePaymentService {

    private final StripeConfig stripeConfig;

    public StripePaymentService(StripeConfig stripeConfig) {
        this.stripeConfig = stripeConfig;
    }

    /**
     * Creates a new PaymentIntent.
     *
     * @param request payment intent parameters.
     * @return response containing the PaymentIntent ID and client secret.
     */
    public StripePaymentIntentResponse createPaymentIntent(StripePaymentIntentRequest request) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(request.amount())
                .setCurrency(request.currency())
                .setReceiptEmail(request.receiptEmail())
                .setDescription(request.description())
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.valueOf(
                    stripeConfig.getCaptureMethod().toUpperCase().replace("-", "_")))
                .build();

            RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(request.idempotencyKey())
                .build();

            PaymentIntent intent = PaymentIntent.create(params, options);
            return toResponse(intent);
        } catch (StripeException ex) {
            throw new com.eventplatform.shared.stripe.exception.StripeIntegrationException(
                "Failed to create PaymentIntent", ex);
        }
    }

    /**
     * Retrieves an existing PaymentIntent by its ID.
     *
     * @param paymentIntentId Stripe PaymentIntent ID (pi_…).
     * @return current state of the PaymentIntent.
     */
    public StripePaymentIntentResponse getPaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            return toResponse(intent);
        } catch (StripeException ex) {
            throw new com.eventplatform.shared.stripe.exception.StripeIntegrationException(
                "Failed to retrieve PaymentIntent id=" + paymentIntentId, ex);
        }
    }

    /**
     * Cancels a PaymentIntent that has not yet been captured.
     *
     * @param paymentIntentId Stripe PaymentIntent ID.
     */
    public void cancelPaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
            intent.cancel(PaymentIntentCancelParams.builder().build());
        } catch (StripeException ex) {
            throw new com.eventplatform.shared.stripe.exception.StripeIntegrationException(
                "Failed to cancel PaymentIntent id=" + paymentIntentId, ex);
        }
    }

    // -------------------------------------------------------------------------

    private StripePaymentIntentResponse toResponse(PaymentIntent intent) {
        return new StripePaymentIntentResponse(
            intent.getId(),
            intent.getClientSecret(),
            intent.getStatus(),
            intent.getAmount(),
            intent.getCurrency()
        );
    }
}
