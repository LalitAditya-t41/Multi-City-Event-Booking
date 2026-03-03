package com.eventplatform.discoverycatalog.service.scheduler;

import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.WebhookConfigRepository;
import com.eventplatform.discoverycatalog.service.EventCatalogRefreshService;
import com.eventplatform.discoverycatalog.service.metrics.EventCatalogMetrics;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventCatalogRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventCatalogRefreshScheduler.class);
    private static final Duration STALE_THRESHOLD = Duration.ofHours(1);

    private final WebhookConfigRepository webhookConfigRepository;
    private final CityRepository cityRepository;
    private final EventCatalogRefreshService refreshService;
    private final EventCatalogMetrics metrics;

    public EventCatalogRefreshScheduler(
        WebhookConfigRepository webhookConfigRepository,
        CityRepository cityRepository,
        EventCatalogRefreshService refreshService,
        EventCatalogMetrics metrics
    ) {
        this.webhookConfigRepository = webhookConfigRepository;
        this.cityRepository = cityRepository;
        this.refreshService = refreshService;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "PT4H")
    public void run() {
        try {
            Instant now = Instant.now();
            Instant cutoff = now.minus(STALE_THRESHOLD);
            webhookConfigRepository.findByLastWebhookAtBeforeOrLastWebhookAtIsNull(cutoff).forEach(config -> {
                if (config.isInCooldown(now)) {
                    return;
                }
                Long orgId = config.getOrganizationId();
                metrics.incrementScheduledRefresh(orgId);
                log.info("Scheduled refresh triggered. orgId={}", orgId);
                cityRepository.findByOrganizationId(orgId)
                    .forEach(city -> refreshService.refreshAsync(orgId, city.getId(), null));
            });
        } catch (Throwable ex) {
            metrics.incrementSchedulerError();
            log.error("Scheduler run failed", ex);
        }
    }
}
