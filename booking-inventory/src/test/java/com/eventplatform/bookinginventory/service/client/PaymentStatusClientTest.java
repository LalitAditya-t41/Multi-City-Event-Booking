package com.eventplatform.bookinginventory.service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@SuppressWarnings({"unchecked", "rawtypes"})
class PaymentStatusClientTest {

    private PaymentStatusClient client;
    private RestClient mockRestClient;
    private RestClient.RequestHeadersUriSpec uriSpec;
    private RestClient.RequestHeadersSpec headerSpec;
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        client = new PaymentStatusClient("http://localhost:8080");

        mockRestClient = mock(RestClient.class);
        uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        headerSpec = mock(RestClient.RequestHeadersSpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(mockRestClient.get()).thenReturn(uriSpec);
        doReturn(headerSpec).when(uriSpec).uri(anyString(), any(Object.class));
        when(headerSpec.retrieve()).thenReturn(responseSpec);

        ReflectionTestUtils.setField(client, "restClient", mockRestClient);
    }

    @Test
    void should_return_true_when_payment_status_is_SUCCEEDED() {
        doReturn(new PaymentStatusClient.PaymentStatusResponse("SUCCEEDED"))
            .when(responseSpec).body(PaymentStatusClient.PaymentStatusResponse.class);

        assertThat(client.isPaymentConfirmed(123L)).isTrue();
    }

    @Test
    void should_return_false_when_payment_status_is_PENDING() {
        doReturn(new PaymentStatusClient.PaymentStatusResponse("PENDING"))
            .when(responseSpec).body(PaymentStatusClient.PaymentStatusResponse.class);

        assertThat(client.isPaymentConfirmed(456L)).isFalse();
    }

    @Test
    void should_return_false_when_payment_status_is_FAILED() {
        doReturn(new PaymentStatusClient.PaymentStatusResponse("FAILED"))
            .when(responseSpec).body(PaymentStatusClient.PaymentStatusResponse.class);

        assertThat(client.isPaymentConfirmed(789L)).isFalse();
    }

    @Test
    void should_return_false_when_response_body_is_null() {
        doReturn(null)
            .when(responseSpec).body(PaymentStatusClient.PaymentStatusResponse.class);

        assertThat(client.isPaymentConfirmed(100L)).isFalse();
    }

    @Test
    void should_return_false_when_RestClientException_thrown() {
        doThrow(new ResourceAccessException("Connection refused"))
            .when(responseSpec).body(PaymentStatusClient.PaymentStatusResponse.class);

        assertThat(client.isPaymentConfirmed(200L)).isFalse();
    }

    @Test
    void should_return_false_when_cartId_is_null() {
        assertThat(client.isPaymentConfirmed(null)).isFalse();
    }

    @Test
    void should_return_true_for_SUCCEEDED_case_insensitive() {
        doReturn(new PaymentStatusClient.PaymentStatusResponse("succeeded"))
            .when(responseSpec).body(PaymentStatusClient.PaymentStatusResponse.class);

        assertThat(client.isPaymentConfirmed(300L)).isTrue();
    }
}
