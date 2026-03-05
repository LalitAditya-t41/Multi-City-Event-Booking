package com.eventplatform.engagement.service;

import com.eventplatform.engagement.exception.EventNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class EventMetadataLookupService {

  private final RestClient restClient;

  public EventMetadataLookupService(
      @Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public EventMetadata getRequiredEventMetadata(Long eventId) {
    try {
      EventMetadata metadata =
          restClient
              .get()
              .uri("/internal/catalog/events/{eventId}/eb-metadata", eventId)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, res) -> {
                    throw new EventNotFoundException(eventId);
                  })
              .body(EventMetadata.class);
      if (metadata == null) {
        throw new EventNotFoundException(eventId);
      }
      return metadata;
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new EventNotFoundException(eventId);
      }
      throw ex;
    }
  }

  public EventMetadata getEventMetadataOrNull(Long eventId) {
    try {
      return getRequiredEventMetadata(eventId);
    } catch (Exception ex) {
      return null;
    }
  }

  public record EventMetadata(Long eventId, Long orgId, String ebEventId) {}
}
