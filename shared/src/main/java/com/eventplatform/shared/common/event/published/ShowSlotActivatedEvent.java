package com.eventplatform.shared.common.event.published;

import com.eventplatform.shared.common.enums.SeatingMode;

public record ShowSlotActivatedEvent(
    Long slotId,
    String ebEventId,
    Long orgId,
    Long venueId,
    Long cityId,
    SeatingMode seatingMode // determines RESERVED vs GA seat provisioning
    ) {}
