package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.event.published.VenueDriftDetectedEvent;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.shared.eventbrite.dto.response.EbVenueResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbVenueService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class VenueReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(VenueReconciliationJob.class);

    private final VenueRepository venueRepository;
    private final EbVenueService ebVenueService;
    private final ApplicationEventPublisher eventPublisher;

    public VenueReconciliationJob(
        VenueRepository venueRepository,
        EbVenueService ebVenueService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.venueRepository = venueRepository;
        this.ebVenueService = ebVenueService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${eventbrite.venue.reconcile.delay-ms:3600000}")
    @Transactional
    public void reconcile() {
        List<Venue> venues = venueRepository.findAll();
        for (Venue venue : venues) {
            if (venue.getEventbriteVenueId() == null) {
                continue;
            }
            try {
                EbVenueResponse ebVenue = ebVenueService.getVenue(venue.getOrganizationId(), venue.getEventbriteVenueId());
                if (ebVenue == null) {
                    venue.markDrift("Eventbrite venue missing");
                    eventPublisher.publishEvent(new VenueDriftDetectedEvent(venue.getId(), venue.getEventbriteVenueId(), "Eventbrite venue missing"));
                    continue;
                }
                String expectedAddress = venue.getAddress();
                String actualAddress = ebVenue.address() != null ? ebVenue.address().addressLine1() : null;
                if (expectedAddress != null && actualAddress != null && !expectedAddress.equalsIgnoreCase(actualAddress)) {
                    String drift = "EB address changed from '%s' to '%s'".formatted(expectedAddress, actualAddress);
                    venue.markDrift(drift);
                    eventPublisher.publishEvent(new VenueDriftDetectedEvent(venue.getId(), venue.getEventbriteVenueId(), drift));
                }
            } catch (EbIntegrationException ex) {
                log.warn("Venue reconciliation failed. venueId={} ebVenueId={}", venue.getId(), venue.getEventbriteVenueId(), ex);
            } catch (Exception ex) {
                log.warn("Unexpected venue reconciliation error. venueId={} ebVenueId={}", venue.getId(), venue.getEventbriteVenueId(), ex);
            }
        }
    }
}
