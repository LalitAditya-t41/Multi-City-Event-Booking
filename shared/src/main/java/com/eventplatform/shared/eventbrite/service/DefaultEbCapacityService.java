package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbCapacityResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DefaultEbCapacityService implements EbCapacityService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbCapacityService.class);

    private final RestClient client;

    public DefaultEbCapacityService(@Qualifier("ebRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public EbCapacityResponse updateCapacityTier(String eventId, int capacity) {
        try {
            log.debug("Updating EB capacity for eventId={} capacity={}", eventId, capacity);
            return client.post()
                .uri("/v3/events/{eventId}/capacity_tier/", eventId)
                .body(Map.of("capacity_tier", Map.of("capacity_total", capacity)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    throw new EbIntegrationException("EB updateCapacityTier failed: " + resp.getStatusCode());
                })
                .body(EbCapacityResponse.class);
        } catch (EbIntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EbIntegrationException("EB updateCapacityTier error: " + ex.getMessage(), ex);
        }
    }
}
