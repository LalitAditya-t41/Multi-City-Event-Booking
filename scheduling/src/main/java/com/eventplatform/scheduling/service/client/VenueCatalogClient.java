package com.eventplatform.scheduling.service.client;

import com.eventplatform.scheduling.exception.SchedulingNotFoundException;
import com.eventplatform.shared.common.exception.IntegrationException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class VenueCatalogClient {

    private final RestClient restClient;

    public VenueCatalogClient(@Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public CatalogVenueResponse getVenue(Long venueId) {
        try {
            return restClient.get()
                .uri("/api/v1/catalog/venues/{id}", venueId)
                .retrieve()
                .body(CatalogVenueResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new SchedulingNotFoundException("Venue not found via catalog: " + venueId, "VENUE_NOT_FOUND");
            }
            throw new IntegrationException("Failed to fetch venue from catalog", "CATALOG_INTEGRATION_ERROR");
        }
    }

    public List<CatalogVenueResponse> listVenuesByCity(Long cityId, Long organizationId) {
        try {
            CatalogVenueListResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v1/catalog/venues")
                    .queryParam("cityId", cityId)
                    .queryParam("orgId", organizationId)
                    .build())
                .retrieve()
                .body(CatalogVenueListResponse.class);
            return response == null || response.venues() == null ? List.of() : response.venues();
        } catch (RestClientResponseException ex) {
            throw new IntegrationException("Failed to fetch venues from catalog", "CATALOG_INTEGRATION_ERROR");
        }
    }
}
