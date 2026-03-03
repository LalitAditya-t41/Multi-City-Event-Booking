package com.eventplatform.admin.service.client;

import com.eventplatform.shared.common.dto.PageResponse;
import com.eventplatform.shared.common.exception.IntegrationException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class SchedulingAdminClient {

    private final RestClient restClient;

    public SchedulingAdminClient(@Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public List<SchedulingSlotResponse> getPendingSyncSlots(Long orgId) {
        try {
            PageResponse<SchedulingSlotResponse> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v1/scheduling/slots")
                    .queryParam("orgId", orgId)
                    .queryParam("status", "PENDING_SYNC")
                    .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response.items();
        } catch (RestClientResponseException ex) {
            throw new IntegrationException("Failed to fetch pending sync slots", "SCHEDULING_INTEGRATION_ERROR");
        }
    }

    public List<SchedulingSlotResponse> getMismatchSlots(Long orgId) {
        try {
            PageResponse<SchedulingSlotResponse> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v1/scheduling/slots/mismatches")
                    .queryParam("orgId", orgId)
                    .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response.items();
        } catch (RestClientResponseException ex) {
            throw new IntegrationException("Failed to fetch mismatch slots", "SCHEDULING_INTEGRATION_ERROR");
        }
    }
}
