package com.eventplatform.shared.eventbrite.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class EbWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EbWebhookDispatcher.class);

    private final EventbriteWebhookSignatureValidator signatureValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Long defaultOrgId;

    public EbWebhookDispatcher(
        EventbriteWebhookSignatureValidator signatureValidator,
        ApplicationEventPublisher eventPublisher,
        ObjectMapper objectMapper,
        @Value("${app.default-org-id}") Long defaultOrgId
    ) {
        this.signatureValidator = signatureValidator;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.defaultOrgId = defaultOrgId;
    }

    public void dispatch(String payload, String signatureHeader) {
        signatureValidator.validate(payload, signatureHeader);
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            eventPublisher.publishEvent(new EbWebhookReceivedEvent(defaultOrgId, jsonNode));
        } catch (Exception ex) {
            log.warn("Failed to parse webhook payload. payload={}", payload, ex);
            eventPublisher.publishEvent(new EbWebhookReceivedEvent(defaultOrgId, objectMapper.createObjectNode()));
        }
    }
}
