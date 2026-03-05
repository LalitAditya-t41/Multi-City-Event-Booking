package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbVenueCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbVenueResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * HTTP implementation for the Eventbrite Venue API.
 *
 * <p><b>Mock limitation</b>: The mock server ({@code localhost:8888}) does not expose a {@code POST
 * /v3/venues/} creation endpoint — venues are only pre-seeded via {@code POST /mock/reset}. For
 * local testing, create the venue through the mock admin before calling {@link #createVenue}; the
 * seeded venue id ({@code "venue_1"}) can be used directly as the EB venue ID in the local DB.
 *
 * <p>The real Eventbrite v3 API exposes {@code POST /v3/venues/} for venue creation; the
 * implementation below targets that endpoint and will work against production EB.
 */
@Service
public class DefaultEbVenueService implements EbVenueService {

  private static final Logger log = LoggerFactory.getLogger(DefaultEbVenueService.class);

  private final RestClient client;

  public DefaultEbVenueService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  @Override
  public EbVenueResponse createVenue(Long organizationId, EbVenueCreateRequest request) {
    try {
      log.debug("Creating EB venue for org={} name={}", organizationId, request.name());
      // Real EB API: POST /v3/venues/  (no org path segment for venues in v3)
      Map<String, Object> body = Map.of("venue", toVenueMap(organizationId, request));
      return client
          .post()
          .uri("/v3/venues/")
          .body(body)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB createVenue failed: " + resp.getStatusCode());
              })
          .body(EbVenueResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB createVenue error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbVenueResponse updateVenue(
      Long organizationId, String ebVenueId, EbVenueCreateRequest request) {
    try {
      log.debug("Updating EB venue ebVenueId={}", ebVenueId);
      Map<String, Object> body = Map.of("venue", toVenueMap(organizationId, request));
      return client
          .post()
          .uri("/v3/venues/{venueId}/", ebVenueId)
          .body(body)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB updateVenue failed: " + resp.getStatusCode());
              })
          .body(EbVenueResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB updateVenue error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbVenueResponse getVenue(Long organizationId, String ebVenueId) {
    try {
      return client
          .get()
          .uri("/v3/venues/{venueId}/", ebVenueId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB getVenue failed: " + resp.getStatusCode());
              })
          .body(EbVenueResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB getVenue error: " + ex.getMessage(), ex);
    }
  }

  private Map<String, Object> toVenueMap(Long organizationId, EbVenueCreateRequest req) {
    var address = new java.util.LinkedHashMap<String, Object>();
    address.put("address_1", req.addressLine1());
    address.put("address_2", req.addressLine2());
    address.put("city", req.city());
    address.put("country", req.country());
    address.put("postal_code", req.zipCode());
    address.put("latitude", req.latitude());
    address.put("longitude", req.longitude());
    return Map.of(
        "name", req.name(),
        "organization_id", organizationId.toString(),
        "address", address);
  }
}
