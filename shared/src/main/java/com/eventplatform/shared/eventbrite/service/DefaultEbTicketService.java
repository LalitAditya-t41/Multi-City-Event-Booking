package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbInventoryTierRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbInventoryTierResponse;
import com.eventplatform.shared.eventbrite.dto.request.EbTicketClassRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbTicketClassResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbTicketService implements EbTicketService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbTicketService.class);

    @Override
    public List<EbTicketClassResponse> createTicketClasses(String eventId, List<EbTicketClassRequest> ticketClasses) {
        log.warn("EbTicketService not configured. eventId={}", eventId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }

    @Override
    public List<EbInventoryTierResponse> createInventoryTiers(String eventId, List<EbInventoryTierRequest> tiers) {
        log.warn("EbTicketService not configured. eventId={}", eventId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }

    @Override
    public String copySeatMap(String eventId, String sourceSeatMapId) {
        log.warn("EbTicketService not configured. eventId={} seatMapId={}", eventId, sourceSeatMapId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }
}
