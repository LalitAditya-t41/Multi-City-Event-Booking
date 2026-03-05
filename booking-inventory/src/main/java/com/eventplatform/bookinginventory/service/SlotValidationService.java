package com.eventplatform.bookinginventory.service;

import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.exception.BaseException;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SlotValidationService {

  private final SlotSummaryReader slotSummaryReader;

  public SlotValidationService(SlotSummaryReader slotSummaryReader) {
    this.slotSummaryReader = slotSummaryReader;
  }

  public SlotSummaryDto requireActiveAndSynced(Long slotId) {
    SlotSummaryDto slot = slotSummaryReader.getSlotSummary(slotId);
    if (slot.ebEventId() == null || slot.ebEventId().isBlank()) {
      throw new BaseException(
          "Eventbrite event id is missing for slot",
          "EB_EVENT_NOT_SYNCED",
          HttpStatus.SERVICE_UNAVAILABLE,
          Map.of("slotId", slotId)) {};
    }
    if (!"ACTIVE".equalsIgnoreCase(slot.status())) {
      throw new BaseException(
          "Slot is not active",
          "SLOT_NOT_ACTIVE",
          HttpStatus.CONFLICT,
          Map.of("currentStatus", slot.status())) {};
    }
    return slot;
  }

  public void ensureTierSynced(Long tierId, String ebTicketClassId) {
    if (ebTicketClassId == null || ebTicketClassId.isBlank()) {
      throw new BaseException(
          "Tier not synced to Eventbrite",
          "TIER_NOT_SYNCED",
          HttpStatus.SERVICE_UNAVAILABLE,
          Map.of("tierId", tierId)) {};
    }
  }

  public void ensureUserActive(String role) {
    if (role == null || role.isBlank()) {
      throw new BusinessRuleException("User context missing", "USER_CONTEXT_MISSING");
    }
  }
}
