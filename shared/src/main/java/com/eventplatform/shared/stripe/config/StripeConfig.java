package com.eventplatform.shared.stripe.config;

import com.stripe.Stripe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Initialises the Stripe Java SDK with the server-side secret key.
 * All other Stripe beans read publishable-key and webhook-secret via @Value.
 */
@Configuration
public class StripeConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.publishable-key}")
    private String publishableKey;

    @Value("${stripe.currency:inr}")
    private String currency;

    @Value("${stripe.payment-intent.capture-method:automatic}")
    private String captureMethod;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String getPublishableKey() {
        return publishableKey;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCaptureMethod() {
        return captureMethod;
    }
}
