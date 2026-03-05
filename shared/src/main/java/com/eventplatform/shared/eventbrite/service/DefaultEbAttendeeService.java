package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbAttendeeResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DefaultEbAttendeeService implements EbAttendeeService {

  private final RestClient client;

  public DefaultEbAttendeeService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record AttendeeListResponse(
      @JsonProperty("attendees") List<EbAttendeeResponse> attendees) {}

  @Override
  public List<EbAttendeeResponse> getAttendeesByEvent(String orgToken, String eventId) {
    try {
      AttendeeListResponse response =
          client
              .get()
              .uri("/v3/events/{eventId}/attendees/", eventId)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + orgToken)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp2) -> {
                    throw new EbIntegrationException(
                        "EB getAttendeesByEvent failed: " + resp2.getStatusCode());
                  })
              .body(AttendeeListResponse.class);
      return response == null || response.attendees() == null ? List.of() : response.attendees();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB getAttendeesByEvent error: " + ex.getMessage(), ex);
    }
  }
}
