package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbScheduleResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbScheduleService implements EbScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbScheduleService.class);

    @Override
    public EbScheduleResponse createSchedule(Long organizationId, String eventId, String recurrenceRule) {
        log.warn("EbScheduleService not configured. organizationId={} eventId={}", organizationId, eventId);
        throw new EbIntegrationException("Eventbrite integration not configured");
    }
}
