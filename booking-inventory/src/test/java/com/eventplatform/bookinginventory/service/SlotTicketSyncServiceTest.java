package com.eventplatform.bookinginventory.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.service.client.SchedulingPricingTierResponse;
import com.eventplatform.bookinginventory.service.client.SchedulingSlotClient;
import com.eventplatform.bookinginventory.service.client.SchedulingSlotResponse;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncFailedEvent;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbTicketService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class SlotTicketSyncServiceTest {

    @Mock
    private EbTicketService ebTicketService;
    @Mock
    private SchedulingSlotClient schedulingSlotClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SlotTicketSyncService slotTicketSyncService;

    @Test
    void should_call_createTicketClasses_and_inventoryTiers_when_syncing() {
        when(schedulingSlotClient.getSlot(1L)).thenReturn(baseSlot(SeatingMode.GA, null));

        slotTicketSyncService.syncTickets(new SlotDraftCreatedEvent(1L, "eb-1", List.of(1L), SeatingMode.GA, 1L, null));

        verify(ebTicketService).createTicketClasses(eq("eb-1"), any());
        verify(ebTicketService).createInventoryTiers(eq("eb-1"), any());
        verify(eventPublisher).publishEvent(any(TicketSyncCompletedEvent.class));
    }

    @Test
    void should_call_copySeatMap_when_seatingMode_is_RESERVED() {
        when(schedulingSlotClient.getSlot(1L)).thenReturn(baseSlot(SeatingMode.RESERVED, "seatmap-1"));

        slotTicketSyncService.syncTickets(new SlotDraftCreatedEvent(1L, "eb-1", List.of(1L), SeatingMode.RESERVED, 1L, "seatmap-1"));

        verify(ebTicketService).copySeatMap("eb-1", "seatmap-1");
    }

    @Test
    void should_not_call_copySeatMap_when_seatingMode_is_GA() {
        when(schedulingSlotClient.getSlot(1L)).thenReturn(baseSlot(SeatingMode.GA, null));

        slotTicketSyncService.syncTickets(new SlotDraftCreatedEvent(1L, "eb-1", List.of(1L), SeatingMode.GA, 1L, null));

        verify(ebTicketService, never()).copySeatMap(any(), any());
    }

    @Test
    void should_publish_TicketSyncFailedEvent_when_ticket_class_creation_fails() {
        when(schedulingSlotClient.getSlot(1L)).thenReturn(baseSlot(SeatingMode.GA, null));
        when(ebTicketService.createTicketClasses(eq("eb-1"), any()))
            .thenThrow(new EbIntegrationException("boom"));

        assertThatThrownBy(() -> slotTicketSyncService.syncTickets(
            new SlotDraftCreatedEvent(1L, "eb-1", List.of(1L), SeatingMode.GA, 1L, null)))
            .isInstanceOf(EbIntegrationException.class);

        verify(eventPublisher).publishEvent(any(TicketSyncFailedEvent.class));
    }

    private SchedulingSlotResponse baseSlot(SeatingMode mode, String seatMapId) {
        return new SchedulingSlotResponse(
            1L,
            "eb-1",
            mode,
            seatMapId,
            List.of(new SchedulingPricingTierResponse(1L, "Free", BigDecimal.ZERO, "INR", 10, "FREE", null))
        );
    }
}
