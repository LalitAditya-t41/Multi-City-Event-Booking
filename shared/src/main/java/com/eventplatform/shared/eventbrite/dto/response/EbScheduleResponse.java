package com.eventplatform.shared.eventbrite.dto.response;

import java.util.List;

public record EbScheduleResponse(
    String seriesId,
    List<EbScheduleOccurrence> occurrences
) {
}
