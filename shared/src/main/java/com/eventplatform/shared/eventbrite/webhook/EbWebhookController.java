package com.eventplatform.shared.eventbrite.webhook;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/webhooks/eventbrite")
public class EbWebhookController {

  private final EbWebhookDispatcher dispatcher;

  public EbWebhookController(EbWebhookDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @PostMapping
  public ResponseEntity<Map<String, String>> receive(
      @RequestHeader(name = "X-Eventbrite-Signature", required = false) String signature,
      @RequestBody String payload) {
    dispatcher.dispatch(payload, signature);
    return ResponseEntity.ok(Map.of("status", "OK"));
  }
}
