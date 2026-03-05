package com.eventplatform.discoverycatalog.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class EventCatalogMetrics {

  private final MeterRegistry meterRegistry;

  public EventCatalogMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void incrementCacheHit(Long orgId) {
    counter("catalog.cache.hit", orgId).increment();
  }

  public void incrementCacheMiss(Long orgId) {
    counter("catalog.cache.miss", orgId).increment();
  }

  public void incrementReadPathRefresh(Long orgId) {
    counter("catalog.refresh.read_path", orgId).increment();
  }

  public void incrementScheduledRefresh(Long orgId) {
    counter("catalog.refresh.scheduled", orgId).increment();
  }

  public void incrementAuthFailure(Long orgId) {
    counter("catalog.refresh.auth_failure", orgId).increment();
  }

  public void incrementSchedulerError() {
    Counter.builder("catalog.scheduler.error").register(meterRegistry).increment();
  }

  private Counter counter(String name, Long orgId) {
    return Counter.builder(name).tag("orgId", String.valueOf(orgId)).register(meterRegistry);
  }
}
