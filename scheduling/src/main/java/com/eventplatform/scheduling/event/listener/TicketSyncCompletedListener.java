package com.eventplatform.scheduling.event.listener;

import com.eventplatform.scheduling.service.ShowSlotService;
import com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TicketSyncCompletedListener {

  private final ShowSlotService showSlotService;

  public TicketSyncCompletedListener(ShowSlotService showSlotService) {
    this.showSlotService = showSlotService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onTicketSyncCompleted(TicketSyncCompletedEvent event) {
    showSlotService.onTicketSyncComplete(event);
  }
}
