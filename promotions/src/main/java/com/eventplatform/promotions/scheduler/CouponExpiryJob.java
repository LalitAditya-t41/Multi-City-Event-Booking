package com.eventplatform.promotions.scheduler;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.promotions.service.DiscountSyncOrchestrator;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CouponExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(CouponExpiryJob.class);

  private final CouponRepository couponRepository;
  private final CouponUsageReservationRepository reservationRepository;
  private final DiscountSyncOrchestrator discountSyncOrchestrator;

  public CouponExpiryJob(
      CouponRepository couponRepository,
      CouponUsageReservationRepository reservationRepository,
      DiscountSyncOrchestrator discountSyncOrchestrator) {
    this.couponRepository = couponRepository;
    this.reservationRepository = reservationRepository;
    this.discountSyncOrchestrator = discountSyncOrchestrator;
  }

  @Scheduled(cron = "${promotions.jobs.coupon-expiry-cron:0 0 * * * *}")
  @Transactional
  public void run() {
    List<Coupon> expired = couponRepository.findExpiredActiveCoupons(Instant.now());
    for (Coupon coupon : expired) {
      try {
        coupon.deactivate();
        couponRepository.save(coupon);
        reservationRepository.releaseAllByCouponId(coupon.getId());
        discountSyncOrchestrator.guardedDelete(coupon.getId());
      } catch (Exception ex) {
        log.error("CouponExpiryJob failed for couponId={}", coupon.getId(), ex);
      }
    }

    List<Coupon> syncFailed =
        couponRepository.findByStatusAndEbSyncStatus(CouponStatus.ACTIVE, EbSyncStatus.SYNC_FAILED);
    for (Coupon coupon : syncFailed) {
      try {
        discountSyncOrchestrator.createSync(coupon.getId());
      } catch (Exception ex) {
        log.warn("Coupon sync retry failed for couponId={}", coupon.getId(), ex);
      }
    }
  }
}
