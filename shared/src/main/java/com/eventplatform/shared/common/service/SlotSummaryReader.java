package com.eventplatform.shared.common.service;

import com.eventplatform.shared.common.dto.SlotSummaryDto;

public interface SlotSummaryReader {
    SlotSummaryDto getSlotSummary(Long slotId);
}
