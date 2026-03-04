package com.eventplatform.bookinginventory.service.client;

import com.eventplatform.shared.common.exception.IntegrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class SchedulingSlotClient {

    private final RestClient restClient;

    public SchedulingSlotClient(@Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public SchedulingSlotResponse getSlot(Long slotId) {
        try {
            return restClient.get()
                .uri("/api/v1/scheduling/slots/{id}", slotId)
                .retrieve()
                .body(SchedulingSlotResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new IntegrationException("Slot not found via scheduling", "SCHEDULING_NOT_FOUND");
            }
            throw new IntegrationException("Failed to fetch slot from scheduling", "SCHEDULING_INTEGRATION_ERROR");
        }
    }
}
