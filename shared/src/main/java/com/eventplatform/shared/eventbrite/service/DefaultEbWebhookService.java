package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbWebhookRegistrationResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbWebhookService implements EbWebhookService {

  private static final Logger log = LoggerFactory.getLogger(DefaultEbWebhookService.class);

  @Override
  public EbWebhookRegistrationResult registerWebhook(
      String organizationId, String endpointUrl, List<String> actions, String eventId) {
    log.info(
        "Mock webhook registration. orgId={} endpointUrl={} actions={} eventId={}",
        organizationId,
        endpointUrl,
        actions,
        eventId);
    return new EbWebhookRegistrationResult(
        "wh_" + UUID.randomUUID(), endpointUrl, actions, Instant.now());
  }
}
