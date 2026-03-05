package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbOrderResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DefaultEbOrderService implements EbOrderService {

  private final RestClient client;

  public DefaultEbOrderService(@Qualifier("ebRestClient") RestClient client) {
    this.client = client;
  }

  @Override
  public EbOrderResponse getOrder(String orderId) {
    try {
      return client
          .get()
          .uri("/v3/orders/{orderId}/", orderId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, resp2) -> {
                throw new EbIntegrationException("EB getOrder failed: " + resp2.getStatusCode());
              })
          .body(EbOrderResponse.class);
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EbIntegrationException("EB getOrder error: " + ex.getMessage(), ex);
    }
  }
}
