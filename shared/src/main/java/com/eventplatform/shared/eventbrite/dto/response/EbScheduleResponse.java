package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbScheduleResponse(String seriesId, List<EbScheduleOccurrence> occurrences) {}
