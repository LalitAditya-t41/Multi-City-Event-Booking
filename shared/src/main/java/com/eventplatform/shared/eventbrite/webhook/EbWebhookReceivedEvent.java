package com.eventplatform.shared.eventbrite.webhook;

import com.fasterxml.jackson.databind.JsonNode;

public record EbWebhookReceivedEvent(Long organizationId, JsonNode payload) {
}
