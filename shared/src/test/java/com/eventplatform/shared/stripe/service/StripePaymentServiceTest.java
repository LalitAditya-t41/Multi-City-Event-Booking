package com.eventplatform.shared.stripe.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.eventplatform.shared.stripe.config.StripeConfig;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentRequest;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentResponse;
import com.eventplatform.shared.stripe.exception.StripeIntegrationException;
import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class StripePaymentServiceTest {

    private StripeConfig stripeConfig;
    private StripePaymentService service;

    @BeforeEach
    void setUp() {
        stripeConfig = mock(StripeConfig.class);
        when(stripeConfig.getCaptureMethod()).thenReturn("automatic");
        service = new StripePaymentService(stripeConfig);
    }

    @Test
    void should_return_response_when_createPaymentIntent_succeeds() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_test_123");
        when(mockIntent.getClientSecret()).thenReturn("pi_test_123_secret");
        when(mockIntent.getStatus()).thenReturn("requires_payment_method");
        when(mockIntent.getAmount()).thenReturn(50000L);
        when(mockIntent.getCurrency()).thenReturn("inr");

        try (MockedStatic<PaymentIntent> staticMock = mockStatic(PaymentIntent.class)) {
            staticMock.when(() -> PaymentIntent.create(
                any(PaymentIntentCreateParams.class),
                any(RequestOptions.class)
            )).thenReturn(mockIntent);

            StripePaymentIntentRequest request = new StripePaymentIntentRequest(
                50000L, "inr", "user@test.com", "Test booking", "idem-key-1", java.util.Map.of()
            );

            StripePaymentIntentResponse response = service.createPaymentIntent(request);

            assertThat(response.paymentIntentId()).isEqualTo("pi_test_123");
            assertThat(response.clientSecret()).isEqualTo("pi_test_123_secret");
            assertThat(response.status()).isEqualTo("requires_payment_method");
            assertThat(response.amount()).isEqualTo(50000L);
            assertThat(response.currency()).isEqualTo("inr");
        }
    }

    @Test
    void should_wrap_StripeException_in_StripeIntegrationException_on_create() throws Exception {
        try (MockedStatic<PaymentIntent> staticMock = mockStatic(PaymentIntent.class)) {
            staticMock.when(() -> PaymentIntent.create(
                any(PaymentIntentCreateParams.class),
                any(RequestOptions.class)
            )).thenThrow(new ApiException("Stripe error", "req_1", null, 400, null));

            StripePaymentIntentRequest request = new StripePaymentIntentRequest(
                1000L, "inr", "user@test.com", "Test", "idem-key-2", java.util.Map.of()
            );

            assertThatThrownBy(() -> service.createPaymentIntent(request))
                .isInstanceOf(StripeIntegrationException.class)
                .hasMessageContaining("Failed to create PaymentIntent");
        }
    }

    @Test
    void should_return_response_when_getPaymentIntent_succeeds() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_retrieve_456");
        when(mockIntent.getClientSecret()).thenReturn("pi_retrieve_456_secret");
        when(mockIntent.getStatus()).thenReturn("succeeded");
        when(mockIntent.getAmount()).thenReturn(20000L);
        when(mockIntent.getCurrency()).thenReturn("inr");

        try (MockedStatic<PaymentIntent> staticMock = mockStatic(PaymentIntent.class)) {
            staticMock.when(() -> PaymentIntent.retrieve("pi_retrieve_456"))
                .thenReturn(mockIntent);

            StripePaymentIntentResponse response = service.getPaymentIntent("pi_retrieve_456");

            assertThat(response.paymentIntentId()).isEqualTo("pi_retrieve_456");
            assertThat(response.status()).isEqualTo("succeeded");
        }
    }

    @Test
    void should_throw_StripeIntegrationException_when_getPaymentIntent_fails() throws Exception {
        try (MockedStatic<PaymentIntent> staticMock = mockStatic(PaymentIntent.class)) {
            staticMock.when(() -> PaymentIntent.retrieve("pi_missing"))
                .thenThrow(new ApiException("Not found", "req_2", null, 404, null));

            assertThatThrownBy(() -> service.getPaymentIntent("pi_missing"))
                .isInstanceOf(StripeIntegrationException.class)
                .hasMessageContaining("pi_missing");
        }
    }

    @Test
    void should_throw_StripeIntegrationException_when_cancelPaymentIntent_fails() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);

        try (MockedStatic<PaymentIntent> staticMock = mockStatic(PaymentIntent.class)) {
            staticMock.when(() -> PaymentIntent.retrieve("pi_cancel_fail"))
                .thenReturn(mockIntent);
            when(mockIntent.cancel(any(PaymentIntentCancelParams.class)))
                .thenThrow(new ApiException("Cannot cancel", "req_3", null, 400, null));

            assertThatThrownBy(() -> service.cancelPaymentIntent("pi_cancel_fail"))
                .isInstanceOf(StripeIntegrationException.class)
                .hasMessageContaining("pi_cancel_fail");
        }
    }

    @Test
    void should_successfully_cancel_payment_intent() throws Exception {
        PaymentIntent mockIntent = mock(PaymentIntent.class);
        PaymentIntent cancelledIntent = mock(PaymentIntent.class);
        when(cancelledIntent.getStatus()).thenReturn("canceled");

        try (MockedStatic<PaymentIntent> staticMock = mockStatic(PaymentIntent.class)) {
            staticMock.when(() -> PaymentIntent.retrieve("pi_cancel_ok"))
                .thenReturn(mockIntent);
            when(mockIntent.cancel(any(PaymentIntentCancelParams.class)))
                .thenReturn(cancelledIntent);

            // Should not throw
            service.cancelPaymentIntent("pi_cancel_ok");
        }
    }
}
