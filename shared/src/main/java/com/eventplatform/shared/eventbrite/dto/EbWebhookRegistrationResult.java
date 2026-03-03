package com.eventplatform.shared.eventbrite.dto;

import java.time.Instant;
import java.util.List;

public record EbWebhookRegistrationResult(
    String webhookId,
    String endpointUrl,
    List<String> actions,
    Instant registeredAt
) {
}
