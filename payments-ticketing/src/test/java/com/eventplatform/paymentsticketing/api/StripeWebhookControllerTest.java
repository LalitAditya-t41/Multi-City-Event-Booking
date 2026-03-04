package com.eventplatform.paymentsticketing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.paymentsticketing.api.controller.StripeWebhookController;
import com.eventplatform.paymentsticketing.service.PaymentService;
import com.eventplatform.paymentsticketing.service.RefundService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import com.eventplatform.shared.stripe.dto.StripeWebhookEvent;
import com.eventplatform.shared.stripe.exception.StripeWebhookSignatureException;
import com.eventplatform.shared.stripe.webhook.StripeWebhookHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = StripeWebhookController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class StripeWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StripeWebhookHandler stripeWebhookHandler;
    @MockBean
    private PaymentService paymentService;
    @MockBean
    private RefundService refundService;
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUpFilterPassThrough() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void should_return_400_when_stripe_signature_invalid() throws Exception {
        when(stripeWebhookHandler.parse("{}", "bad"))
            .thenThrow(new StripeWebhookSignatureException("Invalid", new RuntimeException("bad sig")));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "bad")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_WEBHOOK_SIGNATURE"));
    }

    @Test
    void should_return_200_and_confirm_payment_when_payment_intent_succeeded_event_arrives() throws Exception {
        when(stripeWebhookHandler.parse("{}", "sig"))
            .thenReturn(new StripeWebhookEvent("evt_1", "payment_intent.succeeded", "pi_123", "{}"));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "sig")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        verify(paymentService).confirmPaymentFromWebhook("pi_123");
    }

    @Test
    void should_return_200_and_handle_payment_failed_event() throws Exception {
        String payload = "{\"data\":{\"object\":{\"last_payment_error\":{\"code\":\"card_declined\",\"message\":\"Declined\"}}}}";
        when(stripeWebhookHandler.parse(payload, "sig"))
            .thenReturn(new StripeWebhookEvent("evt_2", "payment_intent.payment_failed", "pi_123", payload));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "sig")
                .contentType("application/json")
                .content(payload))
            .andExpect(status().isOk());

        verify(paymentService).handleFailure("pi_123", "card_declined", "Declined");
    }

    @Test
    void should_return_200_and_process_refund_updated_event() throws Exception {
        when(stripeWebhookHandler.parse("{}", "sig"))
            .thenReturn(new StripeWebhookEvent("evt_3", "refund.updated", "re_123", "{}"));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "sig")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        verify(refundService).updateRefundStatus("re_123");
    }

    @Test
    void should_return_200_and_process_refund_failed_event() throws Exception {
        when(stripeWebhookHandler.parse("{}", "sig"))
            .thenReturn(new StripeWebhookEvent("evt_4", "refund.failed", "re_123", "{}"));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "sig")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        verify(refundService).updateRefundStatus("re_123");
    }

    @Test
    void should_return_200_for_unknown_event_type_without_side_effects() throws Exception {
        when(stripeWebhookHandler.parse("{}", "sig"))
            .thenReturn(new StripeWebhookEvent("evt_5", "customer.created", "cus_123", "{}"));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "sig")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    void should_return_200_for_unknown_payment_intent_id_noop() throws Exception {
        when(stripeWebhookHandler.parse("{}", "sig"))
            .thenReturn(new StripeWebhookEvent("evt_6", "payment_intent.succeeded", "pi_missing", "{}"));

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                .header("Stripe-Signature", "sig")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());

        verify(paymentService).confirmPaymentFromWebhook("pi_missing");
    }
}
