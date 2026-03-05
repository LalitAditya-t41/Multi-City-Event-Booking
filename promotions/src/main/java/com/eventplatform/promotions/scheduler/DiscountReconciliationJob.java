package com.eventplatform.promotions.scheduler;

import com.eventplatform.promotions.service.DiscountReconciliationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DiscountReconciliationJob {

  private final DiscountReconciliationService reconciliationService;

  public DiscountReconciliationJob(DiscountReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @Scheduled(cron = "${promotions.jobs.reconciliation-cron:0 0 */4 * * *}")
  public void run() {
    reconciliationService.reconcileAllOrgs();
  }
}
