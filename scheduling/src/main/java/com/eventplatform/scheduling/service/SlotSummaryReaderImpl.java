package com.eventplatform.scheduling.service;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SlotSummaryReaderImpl implements SlotSummaryReader {

  private final ShowSlotRepository showSlotRepository;

  public SlotSummaryReaderImpl(ShowSlotRepository showSlotRepository) {
    this.showSlotRepository = showSlotRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public SlotSummaryDto getSlotSummary(Long slotId) {
    ShowSlot slot =
        showSlotRepository
            .findById(slotId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Slot not found: " + slotId, "SLOT_NOT_FOUND"));

    return new SlotSummaryDto(
        slot.getId(),
        slot.getStatus().name(),
        slot.getEbEventId(),
        slot.getSeatingMode(),
        slot.getOrganizationId(),
        slot.getVenueId(),
        slot.getCityId(),
        slot.getSourceSeatMapId());
  }
}
