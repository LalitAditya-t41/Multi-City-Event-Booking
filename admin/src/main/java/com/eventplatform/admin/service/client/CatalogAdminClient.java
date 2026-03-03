package com.eventplatform.admin.service.client;

import com.eventplatform.shared.common.exception.IntegrationException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class CatalogAdminClient {

    private final RestClient restClient;

    public CatalogAdminClient(@Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public List<AdminVenueResponse> getFlaggedVenues(Long orgId) {
        try {
            AdminVenueListResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v1/admin/catalog/venues/flagged")
                    .queryParam("orgId", orgId)
                    .build())
                .retrieve()
                .body(AdminVenueListResponse.class);
            return response == null || response.venues() == null ? List.of() : response.venues();
        } catch (RestClientResponseException ex) {
            throw new IntegrationException("Failed to fetch flagged venues", "CATALOG_INTEGRATION_ERROR");
        }
    }
}
