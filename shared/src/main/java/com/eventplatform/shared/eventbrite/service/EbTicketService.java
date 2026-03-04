package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbInventoryTierRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbInventoryTierResponse;
import com.eventplatform.shared.eventbrite.dto.request.EbTicketClassRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbTicketClassResponse;
import java.util.List;

public interface EbTicketService {
    List<EbTicketClassResponse> createTicketClasses(String eventId, List<EbTicketClassRequest> ticketClasses);

    List<EbInventoryTierResponse> createInventoryTiers(String eventId, List<EbInventoryTierRequest> tiers);

    String copySeatMap(String eventId, String sourceSeatMapId);

    EbTicketClassResponse getTicketClass(String eventId, String ticketClassId);

    List<EbTicketClassResponse> listTicketClasses(String eventId);
}
