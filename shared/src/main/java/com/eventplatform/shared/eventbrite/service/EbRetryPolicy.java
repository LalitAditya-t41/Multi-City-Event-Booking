package com.eventplatform.shared.eventbrite.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EbRetryPolicy {

  private final int maxAttempts;
  private final Duration initialBackoff;
  private final Duration maxBackoff;

  public EbRetryPolicy(
      @Value("${eventbrite.retry.max-attempts:3}") int maxAttempts,
      @Value("${eventbrite.retry.initial-backoff-ms:250}") long initialBackoffMs,
      @Value("${eventbrite.retry.max-backoff-ms:5000}") long maxBackoffMs) {
    this.maxAttempts = maxAttempts;
    this.initialBackoff = Duration.ofMillis(initialBackoffMs);
    this.maxBackoff = Duration.ofMillis(maxBackoffMs);
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public Duration getInitialBackoff() {
    return initialBackoff;
  }

  public Duration getMaxBackoff() {
    return maxBackoff;
  }
}
