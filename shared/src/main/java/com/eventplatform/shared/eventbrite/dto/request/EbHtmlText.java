package com.eventplatform.shared.eventbrite.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Represents an Eventbrite name/description text object. Serializes to: {@code {"html": "..."}} */
public record EbHtmlText(@JsonProperty("html") String html) {}
