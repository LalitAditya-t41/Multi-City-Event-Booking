package com.eventplatform.shared.eventbrite.dto.response;
@JsonIgnoreProperties(ignoreUnknown = true)
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public record EbScheduleResponse(String seriesId, List<EbScheduleOccurrence> occurrences) {}
