package com.eventplatform.shared.stripe.webhook;

import com.eventplatform.shared.stripe.config.StripeConfig;
import com.eventplatform.shared.stripe.dto.StripeWebhookEvent;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.stereotype.Component;

/**
 * Parses and verifies incoming Stripe webhook payloads.
 *
 * <p>Hard Rule #2: all Stripe webhook handling goes through this facade.
 */
@Component
public class StripeWebhookHandler {

  private final StripeConfig stripeConfig;

  public StripeWebhookHandler(StripeConfig stripeConfig) {
    this.stripeConfig = stripeConfig;
  }

  /**
   * Verifies the Stripe-Signature header and constructs a {@link StripeWebhookEvent}.
   *
   * @param payload Raw HTTP request body as a string (must not be deserialized first).
   * @param signatureHeader Value of the {@code Stripe-Signature} HTTP header.
   * @return parsed and verified webhook event.
   * @throws com.eventplatform.shared.stripe.exception.StripeWebhookSignatureException if the
   *     signature is invalid or the payload is tampered.
   */
  public StripeWebhookEvent parse(String payload, String signatureHeader) {
    try {
      Event event =
          Webhook.constructEvent(payload, signatureHeader, stripeConfig.getWebhookSecret());

      String objectId =
          event
              .getDataObjectDeserializer()
              .getObject()
              .map(
                  obj -> {
                    try {
                      java.lang.reflect.Method getId = obj.getClass().getMethod("getId");
                      return (String) getId.invoke(obj);
                    } catch (Exception e) {
                      return null;
                    }
                  })
              .orElse(null);

      return new StripeWebhookEvent(event.getId(), event.getType(), objectId, payload);
    } catch (SignatureVerificationException ex) {
      throw new com.eventplatform.shared.stripe.exception.StripeWebhookSignatureException(
          "Invalid Stripe webhook signature", ex);
    }
  }
}
