package com.eventplatform.shared.eventbrite.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EbDiscountCreateRequest(
    String code,
    @JsonProperty("discount_type") String discountType,
    @JsonProperty("percent_off") Double percentOff,
    @JsonProperty("amount_off") Double amountOff,
    @JsonProperty("start_date") String startDate,
    @JsonProperty("end_date") String endDate,
    @JsonProperty("quantity_available") Integer quantityAvailable,
    @JsonProperty("event_id") String eventId) {}
