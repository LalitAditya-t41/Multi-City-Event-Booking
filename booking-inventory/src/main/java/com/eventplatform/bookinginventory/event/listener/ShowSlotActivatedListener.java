package com.eventplatform.bookinginventory.event.listener;

import com.eventplatform.bookinginventory.service.SeatProvisioningService;
import com.eventplatform.shared.common.event.published.ShowSlotActivatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ShowSlotActivatedListener {

  private final SeatProvisioningService seatProvisioningService;

  public ShowSlotActivatedListener(SeatProvisioningService seatProvisioningService) {
    this.seatProvisioningService = seatProvisioningService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onShowSlotActivated(ShowSlotActivatedEvent event) {
    seatProvisioningService.provision(event.slotId(), event.venueId(), event.seatingMode());
  }
}
