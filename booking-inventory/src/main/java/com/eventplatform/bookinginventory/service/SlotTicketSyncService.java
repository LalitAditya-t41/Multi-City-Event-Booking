package com.eventplatform.bookinginventory.service;

import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncFailedEvent;
import com.eventplatform.shared.common.service.SlotPricingReader;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import com.eventplatform.shared.eventbrite.dto.request.EbInventoryTierRequest;
import com.eventplatform.shared.eventbrite.dto.request.EbTicketClassRequest;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbTicketService;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class SlotTicketSyncService {

  private final EbTicketService ebTicketService;
  private final SlotSummaryReader slotSummaryReader;
  private final SlotPricingReader slotPricingReader;
  private final ApplicationEventPublisher eventPublisher;

  public SlotTicketSyncService(
      EbTicketService ebTicketService,
      SlotSummaryReader slotSummaryReader,
      SlotPricingReader slotPricingReader,
      ApplicationEventPublisher eventPublisher) {
    this.ebTicketService = ebTicketService;
    this.slotSummaryReader = slotSummaryReader;
    this.slotPricingReader = slotPricingReader;
    this.eventPublisher = eventPublisher;
  }

  public void syncTickets(SlotDraftCreatedEvent event) {
    try {
      SlotSummaryDto slot = slotSummaryReader.getSlotSummary(event.slotId());
      List<PricingTierDto> tiers = slotPricingReader.getSlotPricing(event.slotId());

      List<EbTicketClassRequest> ticketClasses =
          tiers.stream()
              .map(
                  tier ->
                      new EbTicketClassRequest(
                          tier.tierName(), tier.price(), tier.quota(), tier.tierType()))
              .toList();

      ebTicketService.createTicketClasses(event.ebEventId(), ticketClasses);

      List<EbInventoryTierRequest> inventoryTiers =
          tiers.stream()
              .map(tier -> new EbInventoryTierRequest(tier.tierName(), tier.quota()))
              .toList();
      ebTicketService.createInventoryTiers(event.ebEventId(), inventoryTiers);

      if (slot.seatingMode() == SeatingMode.RESERVED && slot.sourceSeatMapId() != null) {
        ebTicketService.copySeatMap(event.ebEventId(), slot.sourceSeatMapId());
      }

      eventPublisher.publishEvent(new TicketSyncCompletedEvent(event.slotId(), event.ebEventId()));
    } catch (EbIntegrationException ex) {
      eventPublisher.publishEvent(
          new TicketSyncFailedEvent(event.slotId(), event.ebEventId(), ex.getMessage()));
      throw ex;
    } catch (Exception ex) {
      eventPublisher.publishEvent(
          new TicketSyncFailedEvent(event.slotId(), event.ebEventId(), ex.getMessage()));
    }
  }
}
