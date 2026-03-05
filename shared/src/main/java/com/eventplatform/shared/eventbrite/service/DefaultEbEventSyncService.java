package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbEventCreateRequest;
import com.eventplatform.shared.eventbrite.dto.request.EbEventUpdateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbEventDto;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DefaultEbEventSyncService implements EbEventSyncService {

  private static final Logger log = LoggerFactory.getLogger(DefaultEbEventSyncService.class);

  private final RestClient client;

  public DefaultEbEventSyncService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EventListResponse(List<EbEventDto> events) {}

  @Override
  public List<EbEventDto> listEventsByOrganization(String organizationId) {
    try {
      EventListResponse response =
          client
              .get()
              .uri("/v3/organizations/{orgId}/events/", organizationId)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp) -> {
                    throw new EbIntegrationException(
                        "EB listEventsByOrganization failed: " + resp.getStatusCode());
                  })
              .body(EventListResponse.class);
      return response != null && response.events() != null ? response.events() : List.of();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB listEventsByOrganization error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public List<EbEventDto> listEventsByVenue(String venueId) {
    try {
      EventListResponse response =
          client
              .get()
              .uri("/v3/venues/{venueId}/events/", venueId)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp) -> {
                    throw new EbIntegrationException(
                        "EB listEventsByVenue failed: " + resp.getStatusCode());
                  })
              .body(EventListResponse.class);
      return response != null && response.events() != null ? response.events() : List.of();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB listEventsByVenue error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbEventDto getEventById(String eventId) {
    try {
      return client
          .get()
          .uri("/v3/events/{eventId}/", eventId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB getEventById failed: " + resp.getStatusCode());
              })
          .body(EbEventDto.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB getEventById error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbEventDto createDraft(Long organizationId, EbEventCreateRequest request) {
    try {
      log.debug("Creating EB draft event for org={}", organizationId);
      return client
          .post()
          .uri("/v3/organizations/{orgId}/events/", organizationId)
          .body(Map.of("event", request))
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB createDraft failed: " + resp.getStatusCode());
              })
          .body(EbEventDto.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB createDraft error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbEventDto updateEvent(Long organizationId, String eventId, EbEventUpdateRequest request) {
    try {
      log.debug("Updating EB event eventId={}", eventId);
      return client
          .post()
          .uri("/v3/events/{eventId}/", eventId)
          .body(Map.of("event", request))
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB updateEvent failed: " + resp.getStatusCode());
              })
          .body(EbEventDto.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB updateEvent error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public void publishEvent(Long organizationId, String eventId) {
    try {
      log.debug("Publishing EB event eventId={}", eventId);
      client
          .post()
          .uri("/v3/events/{eventId}/publish/", eventId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB publishEvent failed: " + resp.getStatusCode());
              })
          .toBodilessEntity();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB publishEvent error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public void cancelEvent(Long organizationId, String eventId) {
    try {
      log.debug("Cancelling EB event eventId={}", eventId);
      client
          .post()
          .uri("/v3/events/{eventId}/cancel/", eventId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB cancelEvent failed: " + resp.getStatusCode());
              })
          .toBodilessEntity();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB cancelEvent error: " + ex.getMessage(), ex);
    }
  }
}
