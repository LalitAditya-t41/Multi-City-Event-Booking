package com.eventplatform.shared.stripe.service;

import com.eventplatform.shared.stripe.dto.StripeRefundRequest;
import com.eventplatform.shared.stripe.dto.StripeRefundResponse;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.springframework.stereotype.Service;

/**
 * ACL facade for Stripe Refund operations.
 *
 * <p>Note: Stripe's internal refund status is read-only via their API.
 * There is no way to "submit" a refund via a webhook; all refund creation
 * goes through this facade.
 */
@Service
public class StripeRefundService {

    /**
     * Creates a refund for a PaymentIntent.
     *
     * @param request refund parameters (paymentIntentId, optional partial amount, reason).
     * @return response with Stripe Refund ID and status.
     */
    public StripeRefundResponse createRefund(StripeRefundRequest request) {
        try {
            RefundCreateParams.Builder builder = RefundCreateParams.builder()
                .setPaymentIntent(request.paymentIntentId());

            if (request.amount() != null) {
                builder.setAmount(request.amount());
            }
            if (request.reason() != null) {
                builder.setReason(RefundCreateParams.Reason.valueOf(
                    request.reason().toUpperCase()));
            }

            RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(request.idempotencyKey())
                .build();

            Refund refund = Refund.create(builder.build(), options);
            return toResponse(refund);
        } catch (StripeException ex) {
            throw new com.eventplatform.shared.stripe.exception.StripeIntegrationException(
                "Failed to create refund for paymentIntentId=" + request.paymentIntentId(), ex);
        }
    }

    /**
     * Reads the current status of a Stripe refund.
     *
     * @param refundId Stripe Refund ID (re_…).
     * @return current refund state.
     */
    public StripeRefundResponse getRefund(String refundId) {
        try {
            Refund refund = Refund.retrieve(refundId);
            return toResponse(refund);
        } catch (StripeException ex) {
            throw new com.eventplatform.shared.stripe.exception.StripeIntegrationException(
                "Failed to retrieve refund id=" + refundId, ex);
        }
    }

    // -------------------------------------------------------------------------

    private StripeRefundResponse toResponse(Refund refund) {
        return new StripeRefundResponse(
            refund.getId(),
            refund.getStatus(),
            refund.getAmount(),
            refund.getCurrency()
        );
    }
}
