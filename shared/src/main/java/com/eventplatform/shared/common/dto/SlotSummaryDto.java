package com.eventplatform.shared.common.dto;

import com.eventplatform.shared.common.enums.SeatingMode;

public record SlotSummaryDto(
    Long slotId,
    String status,
    String ebEventId,
    SeatingMode seatingMode,
    Long orgId,
    Long venueId,
    Long cityId,
    String sourceSeatMapId) {}
