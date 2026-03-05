package com.eventplatform.shared.stripe.dto;

/**
 * Parsed and verified Stripe webhook event.
 *
 * @param id Stripe event ID (evt_…).
 * @param type Event type string, e.g. "payment_intent.succeeded".
 * @param objectId ID of the top-level object inside event.data.object.
 * @param rawJson Full raw JSON payload — use for detailed field access.
 */
public record StripeWebhookEvent(String id, String type, String objectId, String rawJson) {}
