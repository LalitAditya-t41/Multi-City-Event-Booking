package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbDiscountResponse(
    String id,
    String code,
    @JsonProperty("discount_type") String discountType,
    @JsonProperty("percent_off") Double percentOff,
    @JsonProperty("amount_off") Double amountOff,
    @JsonProperty("quantity_sold") Integer quantitySold,
    @JsonProperty("quantity_available") Integer quantityAvailable,
    @JsonProperty("event_id") String eventId
) {
}
