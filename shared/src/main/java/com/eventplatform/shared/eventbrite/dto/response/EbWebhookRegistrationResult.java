package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbWebhookRegistrationResult(
    String webhookId, String endpointUrl, List<String> actions, Instant registeredAt) {}
