package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbOrderResponse(
    String id,
    String status
) {
}
