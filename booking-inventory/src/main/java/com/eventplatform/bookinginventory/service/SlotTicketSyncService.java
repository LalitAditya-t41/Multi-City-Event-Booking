package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.service.client.SchedulingSlotClient;
import com.eventplatform.bookinginventory.service.client.SchedulingSlotResponse;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncFailedEvent;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.eventbrite.dto.EbInventoryTierRequest;
import com.eventplatform.shared.eventbrite.dto.EbTicketClassRequest;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbTicketService;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class SlotTicketSyncService {

    private final EbTicketService ebTicketService;
    private final SchedulingSlotClient schedulingSlotClient;
    private final ApplicationEventPublisher eventPublisher;

    public SlotTicketSyncService(
        EbTicketService ebTicketService,
        SchedulingSlotClient schedulingSlotClient,
        ApplicationEventPublisher eventPublisher
    ) {
        this.ebTicketService = ebTicketService;
        this.schedulingSlotClient = schedulingSlotClient;
        this.eventPublisher = eventPublisher;
    }

    public void syncTickets(SlotDraftCreatedEvent event) {
        try {
            SchedulingSlotResponse slot = schedulingSlotClient.getSlot(event.slotId());
            List<EbTicketClassRequest> ticketClasses = slot.pricingTiers().stream()
                .map(tier -> new EbTicketClassRequest(
                    tier.name(),
                    new Money(tier.priceAmount(), tier.currency()),
                    tier.quota(),
                    tier.tierType()
                ))
                .toList();

            ebTicketService.createTicketClasses(event.ebEventId(), ticketClasses);

            List<EbInventoryTierRequest> inventoryTiers = slot.pricingTiers().stream()
                .map(tier -> new EbInventoryTierRequest(tier.name(), tier.quota()))
                .toList();
            ebTicketService.createInventoryTiers(event.ebEventId(), inventoryTiers);

            if (slot.seatingMode() == SeatingMode.RESERVED && slot.sourceSeatMapId() != null) {
                ebTicketService.copySeatMap(event.ebEventId(), slot.sourceSeatMapId());
            }

            eventPublisher.publishEvent(new TicketSyncCompletedEvent(event.slotId(), event.ebEventId()));
        } catch (EbIntegrationException ex) {
            eventPublisher.publishEvent(new TicketSyncFailedEvent(event.slotId(), event.ebEventId(), ex.getMessage()));
            throw ex;
        } catch (Exception ex) {
            eventPublisher.publishEvent(new TicketSyncFailedEvent(event.slotId(), event.ebEventId(), ex.getMessage()));
        }
    }
}
