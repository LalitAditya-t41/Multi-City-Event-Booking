package com.eventplatform.discoverycatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookConfigTest {

    @Test
    void should_enter_cooldown_when_threshold_reached() {
        WebhookConfig config = new WebhookConfig(1L, "wh_1", "http://example.com", Instant.now());
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            config.recordFailure(now.plusSeconds(i), "fail");
        }
        assertThat(config.isInCooldown(now.plusSeconds(10))).isTrue();
    }

    @Test
    void should_exit_cooldown_on_success() {
        WebhookConfig config = new WebhookConfig(1L, "wh_1", "http://example.com", Instant.now());
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            config.recordFailure(now.plusSeconds(i), "fail");
        }
        assertThat(config.isInCooldown(now.plusSeconds(10))).isTrue();
        config.recordWebhookSuccess(now.plusSeconds(20));
        assertThat(config.isInCooldown(now.plusSeconds(30))).isFalse();
        assertThat(config.getConsecutiveFailures()).isEqualTo(0);
    }
}
