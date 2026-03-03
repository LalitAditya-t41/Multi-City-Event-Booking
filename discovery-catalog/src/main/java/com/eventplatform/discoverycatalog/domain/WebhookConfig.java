package com.eventplatform.discoverycatalog.domain;

import com.eventplatform.discoverycatalog.domain.enums.WebhookStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "webhook_config")
public class WebhookConfig extends BaseEntity {

    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration COOLDOWN_DURATION = Duration.ofHours(3);

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    @Column(name = "webhook_id", nullable = false, unique = true)
    private String webhookId;

    @Column(name = "endpoint_url", nullable = false)
    private String endpointUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WebhookStatus status;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_webhook_at")
    private Instant lastWebhookAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    protected WebhookConfig() {
    }

    public WebhookConfig(Long organizationId, String webhookId, String endpointUrl, Instant registeredAt) {
        this.organizationId = organizationId;
        this.webhookId = webhookId;
        this.endpointUrl = endpointUrl;
        this.registeredAt = registeredAt;
        this.status = WebhookStatus.REGISTERED;
        this.consecutiveFailures = 0;
    }

    public boolean isInCooldown(Instant now) {
        return cooldownUntil != null && cooldownUntil.isAfter(now);
    }

    public void recordFailure(Instant now, String errorMessage) {
        consecutiveFailures += 1;
        lastErrorAt = now;
        lastErrorMessage = errorMessage;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            enterCooldown(now);
        }
    }

    public void recordAuthFailure(Instant now, String errorMessage) {
        lastErrorAt = now;
        lastErrorMessage = errorMessage;
        enterCooldown(now);
    }

    public void recordWebhookSuccess(Instant now) {
        lastWebhookAt = now;
        exitCooldown();
    }

    public void recordSyncSuccess(Instant now) {
        lastSyncAt = now;
        exitCooldown();
    }

    private void enterCooldown(Instant now) {
        cooldownUntil = now.plus(COOLDOWN_DURATION);
        status = WebhookStatus.IN_COOLDOWN;
    }

    public void exitCooldown() {
        consecutiveFailures = 0;
        cooldownUntil = null;
        if (status == WebhookStatus.IN_COOLDOWN) {
            status = WebhookStatus.REGISTERED;
        }
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getWebhookId() {
        return webhookId;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public WebhookStatus getStatus() {
        return status;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getLastWebhookAt() {
        return lastWebhookAt;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }
}
