package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbScheduleResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DefaultEbScheduleService implements EbScheduleService {

  private static final Logger log = LoggerFactory.getLogger(DefaultEbScheduleService.class);

  private final RestClient client;

  public DefaultEbScheduleService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  /**
   * Raw response from the mock schedule endpoint. Mock returns: {@code {id, event_id,
   * occurrence_duration, recurrence_rule}} No occurrences list is returned by the mock — {@code
   * occurrences} will be null.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ScheduleRawResponse(
      @JsonProperty("id") String id,
      @JsonProperty("event_id") String eventId,
      @JsonProperty("recurrence_rule") String recurrenceRule) {}

  @Override
  public EbScheduleResponse createSchedule(
      Long organizationId, String eventId, String recurrenceRule) {
    try {
      log.debug("Creating EB schedule for eventId={} rule={}", eventId, recurrenceRule);
      ScheduleRawResponse raw =
          client
              .post()
              .uri("/v3/events/{eventId}/schedules/", eventId)
              .body(
                  Map.of(
                      "schedule",
                      Map.of(
                          "event_id", eventId,
                          "recurrence_rule", recurrenceRule)))
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp) -> {
                    throw new EbIntegrationException(
                        "EB createSchedule failed: " + resp.getStatusCode());
                  })
              .body(ScheduleRawResponse.class);
      String seriesId = raw != null ? raw.id() : null;
      return new EbScheduleResponse(seriesId, List.of());
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB createSchedule error: " + ex.getMessage(), ex);
    }
  }
}
