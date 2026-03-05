package com.eventplatform.discoverycatalog.exception;

import com.eventplatform.shared.common.exception.ValidationException;

public class MissingWebhookPayloadException extends ValidationException {
  public MissingWebhookPayloadException(String message) {
    super(message, "MISSING_WEBHOOK_PAYLOAD");
  }
}
