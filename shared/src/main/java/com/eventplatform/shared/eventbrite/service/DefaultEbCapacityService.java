package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbCapacityResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbCapacityService implements EbCapacityService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbCapacityService.class);

    @Override
    public EbCapacityResponse updateCapacityTier(String eventId, int capacity) {
        log.warn("EbCapacityService not configured. eventId={}", eventId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }
}
