package com.eventplatform.paymentsticketing.api.controller;

import com.eventplatform.paymentsticketing.service.PaymentService;
import com.eventplatform.paymentsticketing.service.RefundService;
import com.eventplatform.shared.stripe.dto.StripeWebhookEvent;
import com.eventplatform.shared.stripe.webhook.StripeWebhookHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeWebhookHandler stripeWebhookHandler;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    public StripeWebhookController(
        StripeWebhookHandler stripeWebhookHandler,
        PaymentService paymentService,
        RefundService refundService,
        ObjectMapper objectMapper
    ) {
        this.stripeWebhookHandler = stripeWebhookHandler;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> handleWebhook(
        @RequestBody String payload,
        @RequestHeader("Stripe-Signature") String signature
    ) {
        StripeWebhookEvent event = stripeWebhookHandler.parse(payload, signature);

        switch (event.type()) {
            case "payment_intent.succeeded" -> paymentService.confirmPaymentFromWebhook(event.objectId());
            case "payment_intent.payment_failed", "payment_intent.canceled" -> {
                JsonNode root = readJson(event.rawJson());
                JsonNode objectNode = root.path("data").path("object");
                String failureCode = objectNode.path("last_payment_error").path("code").asText(null);
                String failureMessage = objectNode.path("last_payment_error").path("message").asText(null);
                paymentService.handleFailure(event.objectId(), failureCode, failureMessage);
            }
            case "refund.updated" -> refundService.updateRefundStatus(event.objectId());
            case "refund.failed" -> refundService.updateRefundStatus(event.objectId());
            default -> log.debug("Ignoring unsupported Stripe webhook event type={} eventId={}", event.type(), event.id());
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private JsonNode readJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse Stripe webhook payload", ex);
        }
    }
}
