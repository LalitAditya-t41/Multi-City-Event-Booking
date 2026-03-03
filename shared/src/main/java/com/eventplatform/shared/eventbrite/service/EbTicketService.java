package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbInventoryTierRequest;
import com.eventplatform.shared.eventbrite.dto.EbInventoryTierResponse;
import com.eventplatform.shared.eventbrite.dto.EbTicketClassRequest;
import com.eventplatform.shared.eventbrite.dto.EbTicketClassResponse;
import java.util.List;

public interface EbTicketService {
    List<EbTicketClassResponse> createTicketClasses(String eventId, List<EbTicketClassRequest> ticketClasses);

    List<EbInventoryTierResponse> createInventoryTiers(String eventId, List<EbInventoryTierRequest> tiers);

    String copySeatMap(String eventId, String sourceSeatMapId);
}
