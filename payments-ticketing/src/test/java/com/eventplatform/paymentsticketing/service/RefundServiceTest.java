package com.eventplatform.paymentsticketing.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.paymentsticketing.repository.RefundRepository;
import com.eventplatform.shared.stripe.dto.StripeRefundResponse;
import com.eventplatform.shared.stripe.service.StripeRefundService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    private RefundRepository refundRepository;
    @Mock
    private CancellationService cancellationService;
    @Mock
    private StripeRefundService stripeRefundService;

    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundService(refundRepository, cancellationService, stripeRefundService);
    }

    @Test
    void should_call_finalise_after_refund_when_webhook_status_is_succeeded() {
        Refund refund = new Refund(91L, "re_123", 1000L, "inr", RefundReason.REQUESTED_BY_CUSTOMER, RefundStatus.PENDING);
        when(refundRepository.findByStripeRefundId("re_123")).thenReturn(Optional.of(refund));
        when(stripeRefundService.getRefund("re_123")).thenReturn(new StripeRefundResponse("re_123", "succeeded", 1000L, "inr"));

        refundService.updateRefundStatus("re_123");

        verify(cancellationService).finaliseAfterRefund(91L);
    }

    @Test
    void should_handle_failed_refund_without_publishing_cancel_event() {
        Refund refund = new Refund(91L, "re_123", 1000L, "inr", RefundReason.REQUESTED_BY_CUSTOMER, RefundStatus.PENDING);
        when(refundRepository.findByStripeRefundId("re_123")).thenReturn(Optional.of(refund));
        when(stripeRefundService.getRefund("re_123")).thenReturn(new StripeRefundResponse("re_123", "failed", 1000L, "inr"));

        refundService.updateRefundStatus("re_123");

        verify(cancellationService).handleRefundFailed("re_123");
        verify(cancellationService, never()).finaliseAfterRefund(91L);
    }

    @Test
    void should_return_without_stripe_call_when_refund_id_not_found() {
        when(refundRepository.findByStripeRefundId("re_missing")).thenReturn(Optional.empty());

        refundService.updateRefundStatus("re_missing");

        verify(stripeRefundService, never()).getRefund("re_missing");
        verify(cancellationService, never()).finaliseAfterRefund(91L);
        verify(cancellationService, never()).handleRefundFailed("re_missing");
    }
}
