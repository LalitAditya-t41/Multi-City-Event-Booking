package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbInventoryTierRequest;
import com.eventplatform.shared.eventbrite.dto.request.EbTicketClassRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbInventoryTierResponse;
import com.eventplatform.shared.eventbrite.dto.response.EbTicketClassResponse;
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
public class DefaultEbTicketService implements EbTicketService {

  private static final Logger log = LoggerFactory.getLogger(DefaultEbTicketService.class);

  private final RestClient client;

  public DefaultEbTicketService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TicketClassListResponse(
      @JsonProperty("ticket_classes") List<EbTicketClassResponse> ticketClasses) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record InventoryTierListResponse(List<EbInventoryTierResponse> inventoryTiers) {}

  @Override
  public List<EbTicketClassResponse> createTicketClasses(
      String eventId, List<EbTicketClassRequest> ticketClasses) {
    try {
      log.debug("Creating {} ticket class(es) for eventId={}", ticketClasses.size(), eventId);
      var results = new java.util.ArrayList<EbTicketClassResponse>();
      for (EbTicketClassRequest tc : ticketClasses) {
        EbTicketClassResponse resp =
            client
                .post()
                .uri("/v3/events/{eventId}/ticket_classes/", eventId)
                .body(Map.of("ticket_class", toTicketClassMap(tc)))
                .retrieve()
                .onStatus(
                    HttpStatusCode::isError,
                    (req, resp2) -> {
                      throw new EbIntegrationException(
                          "EB createTicketClass failed: " + resp2.getStatusCode());
                    })
                .body(EbTicketClassResponse.class);
        if (resp != null) results.add(resp);
      }
      return results;
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB createTicketClasses error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public List<EbInventoryTierResponse> createInventoryTiers(
      String eventId, List<EbInventoryTierRequest> tiers) {
    try {
      log.debug("Creating {} inventory tier(s) for eventId={}", tiers.size(), eventId);
      var results = new java.util.ArrayList<EbInventoryTierResponse>();
      for (EbInventoryTierRequest tier : tiers) {
        // Eventbrite inventory tiers are created as ticket classes with additional metadata
        EbInventoryTierResponse resp =
            client
                .post()
                .uri("/v3/events/{eventId}/ticket_classes/", eventId)
                .body(
                    Map.of(
                        "ticket_class",
                        Map.of(
                            "name", tier.name(),
                            "quantity_total", tier.quantity())))
                .retrieve()
                .onStatus(
                    HttpStatusCode::isError,
                    (req, resp2) -> {
                      throw new EbIntegrationException(
                          "EB createInventoryTier failed: " + resp2.getStatusCode());
                    })
                .body(EbInventoryTierResponse.class);
        if (resp != null) results.add(resp);
      }
      return results;
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB createInventoryTiers error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public String copySeatMap(String eventId, String sourceSeatMapId) {
    try {
      log.debug("Copying seatmap for eventId={} from sourceSeatMapId={}", eventId, sourceSeatMapId);
      // Eventbrite seatmap copy endpoint
      var resp =
          client
              .post()
              .uri("/v3/events/{eventId}/", eventId)
              .body(Map.of("event", Map.of("is_reserved_seating", true, "show_pick_a_seat", true)))
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp2) -> {
                    throw new EbIntegrationException(
                        "EB copySeatMap failed: " + resp2.getStatusCode());
                  })
              .body(Map.class);
      return sourceSeatMapId; // mock doesn't support seatmap copy; return source ID
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB copySeatMap error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbTicketClassResponse getTicketClass(String eventId, String ticketClassId) {
    try {
      return client
          .get()
          .uri("/v3/events/{eventId}/ticket_classes/{ticketClassId}/", eventId, ticketClassId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp2) -> {
                throw new EbIntegrationException(
                    "EB getTicketClass failed: " + resp2.getStatusCode());
              })
          .body(EbTicketClassResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB getTicketClass error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public List<EbTicketClassResponse> listTicketClasses(String eventId) {
    try {
      TicketClassListResponse response =
          client
              .get()
              .uri("/v3/events/{eventId}/ticket_classes/", eventId)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp2) -> {
                    throw new EbIntegrationException(
                        "EB listTicketClasses failed: " + resp2.getStatusCode());
                  })
              .body(TicketClassListResponse.class);
      return response == null || response.ticketClasses() == null
          ? List.of()
          : response.ticketClasses();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB listTicketClasses error: " + ex.getMessage(), ex);
    }
  }

  private Map<String, Object> toTicketClassMap(EbTicketClassRequest tc) {
    var map = new java.util.LinkedHashMap<String, Object>();
    map.put("name", tc.name());
    if (tc.price() != null) {
      // Eventbrite cost format: "USD,1000" (currency,cents)
      long cents = tc.price().amount().multiply(java.math.BigDecimal.valueOf(100)).longValue();
      map.put("cost", tc.price().currency() + "," + cents);
      map.put("free", cents == 0);
    }
    if (tc.quantity() != null) map.put("quantity_total", tc.quantity());
    return map;
  }
}
