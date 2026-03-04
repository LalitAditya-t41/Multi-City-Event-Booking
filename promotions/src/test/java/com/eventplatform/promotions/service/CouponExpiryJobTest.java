package com.eventplatform.promotions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.promotions.scheduler.CouponExpiryJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponExpiryJobTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponUsageReservationRepository reservationRepository;
    @Mock
    private DiscountSyncOrchestrator discountSyncOrchestrator;

    @InjectMocks
    private CouponExpiryJob job;

    private Coupon expiredCoupon1;
    private Coupon expiredCoupon2;
    private Coupon expiredCoupon3;

    @BeforeEach
    void setUp() {
        expiredCoupon1 = coupon("SAVE1");
        expiredCoupon2 = coupon("SAVE2");
        expiredCoupon3 = coupon("SAVE3");
        expiredCoupon1.markSynced("eb1", 0);
        expiredCoupon2.markSynced("eb2", 0);
        expiredCoupon3.markSynced("eb3", 0);
    }

    @Test
    void expiryJob_should_deactivate_expired_coupons_and_release_open_reservations() {
        when(couponRepository.findExpiredActiveCoupons(any())).thenReturn(List.of(expiredCoupon1));
        when(couponRepository.findByStatusAndEbSyncStatus(CouponStatus.ACTIVE, EbSyncStatus.SYNC_FAILED)).thenReturn(List.of());

        job.run();

        assertThat(expiredCoupon1.getStatus()).isEqualTo(CouponStatus.INACTIVE);
        verify(reservationRepository).releaseAllByCouponId(expiredCoupon1.getId());
    }

    @Test
    void expiryJob_should_trigger_guarded_EB_delete_for_expired_coupon_with_zero_quantity_sold() {
        when(couponRepository.findExpiredActiveCoupons(any())).thenReturn(List.of(expiredCoupon1));
        when(couponRepository.findByStatusAndEbSyncStatus(CouponStatus.ACTIVE, EbSyncStatus.SYNC_FAILED)).thenReturn(List.of());

        job.run();

        verify(discountSyncOrchestrator).guardedDelete(expiredCoupon1.getId());
    }

    @Test
    void expiryJob_should_continue_processing_remaining_coupons_when_one_fails() {
        when(couponRepository.findExpiredActiveCoupons(any())).thenReturn(List.of(expiredCoupon1, expiredCoupon2, expiredCoupon3));
        when(couponRepository.findByStatusAndEbSyncStatus(CouponStatus.ACTIVE, EbSyncStatus.SYNC_FAILED)).thenReturn(List.of());
        doThrow(new RuntimeException("fail one")).when(discountSyncOrchestrator).guardedDelete(expiredCoupon2.getId());

        job.run();

        assertThat(expiredCoupon1.getStatus()).isEqualTo(CouponStatus.INACTIVE);
        assertThat(expiredCoupon3.getStatus()).isEqualTo(CouponStatus.INACTIVE);
        verify(couponRepository, times(3)).save(any(Coupon.class));
    }

    private Coupon coupon(String code) {
        Promotion promotion = new Promotion(
            1L, "Promo", DiscountType.PERCENT_OFF, new BigDecimal("10"), PromotionScope.ORG_WIDE,
            null, 10, 2, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)
        );
        return new Coupon(promotion, 1L, code);
    }
}
