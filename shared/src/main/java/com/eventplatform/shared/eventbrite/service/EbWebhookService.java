package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbWebhookRegistrationResult;
import java.util.List;

public interface EbWebhookService {
  EbWebhookRegistrationResult registerWebhook(
      String organizationId, String endpointUrl, List<String> actions, String eventId);
}
