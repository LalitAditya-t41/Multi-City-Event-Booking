package com.eventplatform.scheduling.api.dto.response;

import java.util.List;

public record ShowSlotOccurrenceListResponse(
    Long slotId,
    List<ShowSlotOccurrenceResponse> occurrences
) {
}
