package com.eventplatform.shared.eventbrite.webhook;

import com.eventplatform.shared.eventbrite.exception.EbWebhookSignatureException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EventbriteWebhookSignatureValidator {

    private final String secret;

    public EventbriteWebhookSignatureValidator(@Value("${eventbrite.webhook.secret}") String secret) {
        this.secret = secret;
    }

    public void validate(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new EbWebhookSignatureException("Missing Eventbrite signature");
        }
        String computed = computeSignature(payload);
        if (!constantTimeEquals(computed, signatureHeader)) {
            throw new EbWebhookSignatureException("Invalid Eventbrite signature");
        }
    }

    private String computeSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new EbWebhookSignatureException("Signature validation failed");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
