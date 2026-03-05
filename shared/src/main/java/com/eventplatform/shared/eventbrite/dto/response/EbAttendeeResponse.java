package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbAttendeeResponse(
    String id,
    boolean cancelled,
    boolean refunded,
    Profile profile
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
        String email,
        @JsonProperty("name") String fullName
    ) {
    }
}
