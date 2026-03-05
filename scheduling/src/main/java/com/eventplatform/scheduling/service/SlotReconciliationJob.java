package com.eventplatform.scheduling.service;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.shared.eventbrite.dto.response.EbEventDto;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbEventSyncService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SlotReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(SlotReconciliationJob.class);

  private final ShowSlotRepository showSlotRepository;
  private final EbEventSyncService ebEventSyncService;

  public SlotReconciliationJob(
      ShowSlotRepository showSlotRepository, EbEventSyncService ebEventSyncService) {
    this.showSlotRepository = showSlotRepository;
    this.ebEventSyncService = ebEventSyncService;
  }

  @Scheduled(fixedDelayString = "${eventbrite.slot.reconcile.delay-ms:3600000}")
  @Transactional
  public void reconcile() {
    List<ShowSlot> activeSlots =
        showSlotRepository.findAll().stream()
            .filter(
                slot -> slot.getStatus() == ShowSlotStatus.ACTIVE && slot.getEbEventId() != null)
            .toList();

    for (ShowSlot slot : activeSlots) {
      try {
        EbEventDto ebEvent = ebEventSyncService.getEventById(slot.getEbEventId());
        if (ebEvent == null
            || ebEvent.state() == null
            || !"LIVE".equalsIgnoreCase(ebEvent.state())) {
          slot.recordSyncFailure("EB event not live or missing on reconciliation");
          showSlotRepository.save(slot);
        }
      } catch (EbIntegrationException ex) {
        log.warn(
            "Slot reconciliation failed. slotId={} ebEventId={}",
            slot.getId(),
            slot.getEbEventId(),
            ex);
      } catch (Exception ex) {
        log.warn(
            "Unexpected slot reconciliation error. slotId={} ebEventId={}",
            slot.getId(),
            slot.getEbEventId(),
            ex);
      }
    }
  }
}
