package com.eventplatform.scheduling.event.listener;

import com.eventplatform.scheduling.service.ShowSlotService;
import com.eventplatform.shared.common.event.published.TicketSyncFailedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TicketSyncFailedListener {

    private final ShowSlotService showSlotService;

    public TicketSyncFailedListener(ShowSlotService showSlotService) {
        this.showSlotService = showSlotService;
    }

    @EventListener
    public void onTicketSyncFailed(TicketSyncFailedEvent event) {
        showSlotService.onTicketSyncFailed(event);
    }
}
