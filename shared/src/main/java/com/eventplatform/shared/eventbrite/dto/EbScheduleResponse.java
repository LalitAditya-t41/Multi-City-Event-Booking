package com.eventplatform.shared.eventbrite.dto;

import java.util.List;

public record EbScheduleResponse(
    String seriesId,
    List<EbScheduleOccurrence> occurrences
) {
}
