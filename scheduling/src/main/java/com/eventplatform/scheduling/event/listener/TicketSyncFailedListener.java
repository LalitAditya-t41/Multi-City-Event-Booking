package com.eventplatform.scheduling.event.listener;

import com.eventplatform.scheduling.service.ShowSlotService;
import com.eventplatform.shared.common.event.published.TicketSyncFailedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TicketSyncFailedListener {

  private final ShowSlotService showSlotService;

  public TicketSyncFailedListener(ShowSlotService showSlotService) {
    this.showSlotService = showSlotService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onTicketSyncFailed(TicketSyncFailedEvent event) {
    showSlotService.onTicketSyncFailed(event);
  }
}
