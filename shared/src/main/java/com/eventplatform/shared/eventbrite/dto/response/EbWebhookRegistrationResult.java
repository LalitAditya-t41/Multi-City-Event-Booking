package com.eventplatform.shared.eventbrite.dto.response;
@JsonIgnoreProperties(ignoreUnknown = true)
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

public record EbWebhookRegistrationResult(
    String webhookId, String endpointUrl, List<String> actions, Instant registeredAt) {}
