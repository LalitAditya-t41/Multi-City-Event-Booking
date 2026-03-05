package com.eventplatform.bookinginventory.event.listener;

import com.eventplatform.bookinginventory.service.SlotTicketSyncService;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlotDraftCreatedListener {

  private final SlotTicketSyncService slotTicketSyncService;

  public SlotDraftCreatedListener(SlotTicketSyncService slotTicketSyncService) {
    this.slotTicketSyncService = slotTicketSyncService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSlotDraftCreated(SlotDraftCreatedEvent event) {
    slotTicketSyncService.syncTickets(event);
  }
}
