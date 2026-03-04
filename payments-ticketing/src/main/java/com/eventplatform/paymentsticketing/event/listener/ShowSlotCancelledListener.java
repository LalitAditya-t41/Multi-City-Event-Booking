package com.eventplatform.paymentsticketing.event.listener;

import com.eventplatform.paymentsticketing.service.EventCancellationRefundService;
import com.eventplatform.shared.common.event.published.ShowSlotCancelledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ShowSlotCancelledListener {

    private final EventCancellationRefundService eventCancellationRefundService;

    public ShowSlotCancelledListener(EventCancellationRefundService eventCancellationRefundService) {
        this.eventCancellationRefundService = eventCancellationRefundService;
    }

    @EventListener
    public void onShowSlotCancelled(ShowSlotCancelledEvent event) {
        eventCancellationRefundService.processEventRefunds(event.slotId(), event.orgId());
    }
}
