package com.eventplatform.scheduling.event.listener;

import com.eventplatform.scheduling.service.ShowSlotService;
import com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TicketSyncCompletedListener {

    private final ShowSlotService showSlotService;

    public TicketSyncCompletedListener(ShowSlotService showSlotService) {
        this.showSlotService = showSlotService;
    }

    @EventListener
    public void onTicketSyncCompleted(TicketSyncCompletedEvent event) {
        showSlotService.onTicketSyncComplete(event);
    }
}
