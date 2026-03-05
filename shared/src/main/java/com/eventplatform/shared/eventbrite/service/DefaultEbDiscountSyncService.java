package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbDiscountCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbDiscountResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DefaultEbDiscountSyncService implements EbDiscountSyncService {

  private final RestClient client;

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DiscountListResponse(
      @JsonProperty("discounts") List<EbDiscountResponse> discounts) {}

  public DefaultEbDiscountSyncService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  @Override
  public EbDiscountResponse createDiscount(String orgId, EbDiscountCreateRequest request) {
    try {
      return client
          .post()
          .uri("/v3/organizations/{orgId}/discounts/", orgId)
          .body(Map.of("discount", request))
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException(
                    "EB createDiscount failed: " + resp.getStatusCode());
              })
          .body(EbDiscountResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB createDiscount error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public List<EbDiscountResponse> listDiscounts(String orgId) {
    try {
      DiscountListResponse response =
          client
              .get()
              .uri("/v3/organizations/{orgId}/discounts/", orgId)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, resp) -> {
                    throw new EbIntegrationException(
                        "EB listDiscounts failed: " + resp.getStatusCode());
                  })
              .body(DiscountListResponse.class);
      return response == null || response.discounts() == null ? List.of() : response.discounts();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB listDiscounts error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public EbDiscountResponse getDiscount(String discountId) {
    try {
      return client
          .get()
          .uri("/v3/discounts/{discountId}/", discountId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException("EB getDiscount failed: " + resp.getStatusCode());
              })
          .body(EbDiscountResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB getDiscount error: " + ex.getMessage(), ex);
    }
  }

  @Override
  public void deleteDiscount(String discountId) {
    try {
      client
          .delete()
          .uri("/v3/discounts/{discountId}/", discountId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp) -> {
                throw new EbIntegrationException(
                    "EB deleteDiscount failed: " + resp.getStatusCode());
              })
          .toBodilessEntity();
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB deleteDiscount error: " + ex.getMessage(), ex);
    }
  }
}
