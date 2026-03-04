package com.eventplatform.shared.stripe.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import com.eventplatform.shared.stripe.dto.StripeRefundRequest;
import com.eventplatform.shared.stripe.dto.StripeRefundResponse;
import com.eventplatform.shared.stripe.exception.StripeIntegrationException;
import com.stripe.exception.ApiException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeRefundServiceTest {

    private StripeRefundService service;

    @BeforeEach
    void setUp() {
        service = new StripeRefundService();
    }

    @Test
    void should_return_refund_response_when_createRefund_succeeds() throws Exception {
        Refund mockRefund = mock(Refund.class);
        when(mockRefund.getId()).thenReturn("re_test_abc");
        when(mockRefund.getStatus()).thenReturn("succeeded");
        when(mockRefund.getAmount()).thenReturn(15000L);
        when(mockRefund.getCurrency()).thenReturn("inr");

        try (MockedStatic<Refund> staticMock = mockStatic(Refund.class)) {
            staticMock.when(() -> Refund.create(
                any(RefundCreateParams.class),
                any(RequestOptions.class)
            )).thenReturn(mockRefund);

            StripeRefundRequest request = new StripeRefundRequest(
                "pi_original_123", null, null, "idem-refund-1"
            );

            StripeRefundResponse response = service.createRefund(request);

            assertThat(response.refundId()).isEqualTo("re_test_abc");
            assertThat(response.status()).isEqualTo("succeeded");
            assertThat(response.amount()).isEqualTo(15000L);
            assertThat(response.currency()).isEqualTo("inr");
        }
    }

    @Test
    void should_support_partial_refund_with_amount() throws Exception {
        Refund mockRefund = mock(Refund.class);
        when(mockRefund.getId()).thenReturn("re_partial_xyz");
        when(mockRefund.getStatus()).thenReturn("succeeded");
        when(mockRefund.getAmount()).thenReturn(5000L);
        when(mockRefund.getCurrency()).thenReturn("inr");

        try (MockedStatic<Refund> staticMock = mockStatic(Refund.class)) {
            staticMock.when(() -> Refund.create(
                any(RefundCreateParams.class),
                any(RequestOptions.class)
            )).thenReturn(mockRefund);

            StripeRefundRequest request = new StripeRefundRequest(
                "pi_original_456", 5000L, null, "idem-partial-1"
            );

            StripeRefundResponse response = service.createRefund(request);

            assertThat(response.amount()).isEqualTo(5000L);
        }
    }

    @Test
    void should_throw_StripeIntegrationException_when_createRefund_fails() throws Exception {
        try (MockedStatic<Refund> staticMock = mockStatic(Refund.class)) {
            staticMock.when(() -> Refund.create(
                any(RefundCreateParams.class),
                any(RequestOptions.class)
            )).thenThrow(new ApiException("Card declined", "req_r1", null, 400, null));

            StripeRefundRequest request = new StripeRefundRequest(
                "pi_fail_789", null, null, "idem-fail-1"
            );

            assertThatThrownBy(() -> service.createRefund(request))
                .isInstanceOf(StripeIntegrationException.class)
                .hasMessageContaining("pi_fail_789");
        }
    }

    @Test
    void should_return_refund_response_when_getRefund_succeeds() throws Exception {
        Refund mockRefund = mock(Refund.class);
        when(mockRefund.getId()).thenReturn("re_get_111");
        when(mockRefund.getStatus()).thenReturn("pending");
        when(mockRefund.getAmount()).thenReturn(9000L);
        when(mockRefund.getCurrency()).thenReturn("inr");

        try (MockedStatic<Refund> staticMock = mockStatic(Refund.class)) {
            staticMock.when(() -> Refund.retrieve("re_get_111"))
                .thenReturn(mockRefund);

            StripeRefundResponse response = service.getRefund("re_get_111");

            assertThat(response.refundId()).isEqualTo("re_get_111");
            assertThat(response.status()).isEqualTo("pending");
        }
    }

    @Test
    void should_throw_StripeIntegrationException_when_getRefund_fails() throws Exception {
        try (MockedStatic<Refund> staticMock = mockStatic(Refund.class)) {
            staticMock.when(() -> Refund.retrieve("re_missing"))
                .thenThrow(new ApiException("Refund not found", "req_r2", null, 404, null));

            assertThatThrownBy(() -> service.getRefund("re_missing"))
                .isInstanceOf(StripeIntegrationException.class)
                .hasMessageContaining("re_missing");
        }
    }
}
