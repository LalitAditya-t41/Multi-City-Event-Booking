package com.eventplatform.bookinginventory.event.listener;

import com.eventplatform.bookinginventory.service.SlotTicketSyncService;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SlotDraftCreatedListener {

    private final SlotTicketSyncService slotTicketSyncService;

    public SlotDraftCreatedListener(SlotTicketSyncService slotTicketSyncService) {
        this.slotTicketSyncService = slotTicketSyncService;
    }

    @EventListener
    public void onSlotDraftCreated(SlotDraftCreatedEvent event) {
        slotTicketSyncService.syncTickets(event);
    }
}
