package com.eventplatform.bookinginventory.service.client;

import com.eventplatform.shared.common.exception.IntegrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class CatalogSeatLayoutClient {

    private final RestClient restClient;

    public CatalogSeatLayoutClient(@Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public CatalogSeatLayoutResponse getSeatLayout(Long venueId) {
        try {
            return restClient.get()
                .uri("/api/v1/catalog/venues/{venueId}/seat-layout", venueId)
                .retrieve()
                .body(CatalogSeatLayoutResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new IntegrationException("Venue seat layout not found", "CATALOG_NOT_FOUND");
            }
            throw new IntegrationException("Failed to fetch venue seat layout", "CATALOG_INTEGRATION_ERROR");
        }
    }
}
