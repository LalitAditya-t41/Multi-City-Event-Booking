package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Eventbrite v3 event response payload.
 * The mock / real API returns nested objects for {@code name}, {@code start}, and {@code end}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EbEventDto(
    String id,
    @JsonProperty("venue_id")   String venueId,
    /**
     * Resolved from the nested {@code name.html} or {@code name.text} field by the
     * {@link EbEventDto.NameField} helper during Jackson deserialization.
     */
    @JsonProperty("name")       NameField nameField,
    @JsonProperty("description") DescriptionField descriptionField,
    String url,
    @JsonProperty("start")      TimeField start,
    @JsonProperty("end")        TimeField end,
    String status,
    String currency,
    String changed
) {
    /** Returns the event status/state (e.g. {@code "draft"}, {@code "live"}, {@code "cancelled"}). */
    public String state() {
        return status;
    }

    /** Returns the plain text name extracted from the nested name object. */
    public String name() {
        if (nameField == null) return null;
        return nameField.html() != null ? nameField.html() : nameField.text();
    }

    /** Returns the description HTML text. */
    public String description() {
        if (descriptionField == null) return null;
        return descriptionField.html();
    }

    /** Returns the UTC start time as {@link Instant}. */
    public Instant startTime() {
        return start != null && start.utc() != null
            ? Instant.parse(start.utc())
            : null;
    }

    /** Returns the UTC end time as {@link Instant}. */
    public Instant endTime() {
        return end != null && end.utc() != null
            ? Instant.parse(end.utc())
            : null;
    }

    /** Returns the ISO-8601 changed instant. */
    public Instant changedAt() {
        return changed != null ? Instant.parse(changed) : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NameField(
        @JsonProperty("html")  String html,
        @JsonProperty("text")  String text
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DescriptionField(
        @JsonProperty("html")  String html,
        @JsonProperty("text")  String text
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeField(
        @JsonProperty("utc")      String utc,
        @JsonProperty("timezone") String timezone
    ) {}
}
