package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from the Eventbrite capacity tier endpoint.
 * Mock returns: {@code {id, capacity_total, holds: [...]}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EbCapacityResponse(
    @JsonProperty("id")             String id,
    @JsonProperty("capacity_total") Integer capacityTotal
) {
    /** Alias used by callers that reference {@code capacity}. */
    public Integer capacity() { return capacityTotal; }
}
