package com.eventplatform.bookinginventory.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EbInventoryValidationJob {

  private final StringRedisTemplate stringRedisTemplate;

  public EbInventoryValidationJob(StringRedisTemplate stringRedisTemplate) {
    this.stringRedisTemplate = stringRedisTemplate;
  }

  @Scheduled(fixedDelay = 120000)
  public void detectInventoryDrift() {
    // Placeholder scope: validate via slot endpoint when needed by runtime usage.
    // A full org-wide ACTIVE slot scanner will be added with payments-ticketing module integration.
  }

  void blockTier(Long slotId, Long tierId) {
    stringRedisTemplate
        .opsForValue()
        .set("tier:blocked:" + slotId + ":" + tierId, "1", java.time.Duration.ofMinutes(5));
  }
}
