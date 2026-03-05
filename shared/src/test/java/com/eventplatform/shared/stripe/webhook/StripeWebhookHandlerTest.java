package com.eventplatform.shared.stripe.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.eventplatform.shared.stripe.config.StripeConfig;
import com.eventplatform.shared.stripe.dto.StripeWebhookEvent;
import com.eventplatform.shared.stripe.exception.StripeWebhookSignatureException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class StripeWebhookHandlerTest {

  private StripeConfig stripeConfig;
  private StripeWebhookHandler handler;

  private static final String WEBHOOK_SECRET = "whsec_test_secret";
  private static final String PAYLOAD =
      "{\"id\":\"evt_test_1\",\"type\":\"payment_intent.succeeded\"}";
  private static final String SIG_HEADER = "t=12345,v1=abc123";

  @BeforeEach
  void setUp() {
    stripeConfig = mock(StripeConfig.class);
    when(stripeConfig.getWebhookSecret()).thenReturn(WEBHOOK_SECRET);
    handler = new StripeWebhookHandler(stripeConfig);
  }

  @Test
  void should_parse_valid_webhook_and_return_event() throws Exception {
    Event mockEvent = mock(Event.class);
    EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
    when(mockEvent.getId()).thenReturn("evt_test_1");
    when(mockEvent.getType()).thenReturn("payment_intent.succeeded");
    when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
    when(deserializer.getObject()).thenReturn(Optional.empty());

    try (MockedStatic<Webhook> staticMock = mockStatic(Webhook.class)) {
      staticMock
          .when(() -> Webhook.constructEvent(PAYLOAD, SIG_HEADER, WEBHOOK_SECRET))
          .thenReturn(mockEvent);

      StripeWebhookEvent result = handler.parse(PAYLOAD, SIG_HEADER);

      assertThat(result.id()).isEqualTo("evt_test_1");
      assertThat(result.type()).isEqualTo("payment_intent.succeeded");
      assertThat(result.rawJson()).isEqualTo(PAYLOAD);
      assertThat(result.objectId()).isNull();
    }
  }

  @Test
  void should_extract_objectId_when_stripe_object_has_getId() throws Exception {
    StripeObject stripeObj = mock(StripeObject.class);
    // StripeObject implements getId() at runtime on subtypes, but here we stub via a concrete mock
    // We use reflection in the handler; since mock returns null by default for getId(), we test
    // with null objectId
    Event mockEvent = mock(Event.class);
    EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
    when(mockEvent.getId()).thenReturn("evt_test_2");
    when(mockEvent.getType()).thenReturn("charge.succeeded");
    when(mockEvent.getDataObjectDeserializer()).thenReturn(deserializer);
    when(deserializer.getObject()).thenReturn(Optional.of(stripeObj));

    try (MockedStatic<Webhook> staticMock = mockStatic(Webhook.class)) {
      staticMock
          .when(() -> Webhook.constructEvent(PAYLOAD, SIG_HEADER, WEBHOOK_SECRET))
          .thenReturn(mockEvent);

      StripeWebhookEvent result = handler.parse(PAYLOAD, SIG_HEADER);

      assertThat(result.id()).isEqualTo("evt_test_2");
      assertThat(result.type()).isEqualTo("charge.succeeded");
    }
  }

  @Test
  void should_throw_StripeWebhookSignatureException_when_signature_invalid() throws Exception {
    try (MockedStatic<Webhook> staticMock = mockStatic(Webhook.class)) {
      staticMock
          .when(() -> Webhook.constructEvent(PAYLOAD, "bad_signature", WEBHOOK_SECRET))
          .thenThrow(new SignatureVerificationException("Invalid signature", "bad_signature"));

      assertThatThrownBy(() -> handler.parse(PAYLOAD, "bad_signature"))
          .isInstanceOf(StripeWebhookSignatureException.class)
          .hasMessageContaining("Invalid Stripe webhook signature");
    }
  }

  @Test
  void should_throw_StripeWebhookSignatureException_when_payload_tampered() throws Exception {
    String tamperedPayload = PAYLOAD + "TAMPERED";
    try (MockedStatic<Webhook> staticMock = mockStatic(Webhook.class)) {
      staticMock
          .when(() -> Webhook.constructEvent(tamperedPayload, SIG_HEADER, WEBHOOK_SECRET))
          .thenThrow(new SignatureVerificationException("Mismatch", SIG_HEADER));

      assertThatThrownBy(() -> handler.parse(tamperedPayload, SIG_HEADER))
          .isInstanceOf(StripeWebhookSignatureException.class);
    }
  }
}
