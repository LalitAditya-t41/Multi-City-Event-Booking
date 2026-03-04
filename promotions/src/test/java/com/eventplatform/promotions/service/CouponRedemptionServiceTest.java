package com.eventplatform.promotions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.CouponRedemption;
import com.eventplatform.promotions.domain.CouponUsageReservation;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.repository.CouponRedemptionRepository;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.shared.common.dto.CartSummaryDto;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class CouponRedemptionServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponRedemptionRepository redemptionRepository;
    @Mock
    private CouponUsageReservationRepository reservationRepository;
    @Mock
    private CouponEligibilityService eligibilityService;
    @Mock
    private CartSnapshotReader cartSnapshotReader;
    @Mock
    private DiscountSyncOrchestrator discountSyncOrchestrator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CouponRedemptionService service;

    private Promotion promotion;
    private Coupon coupon;
    private CouponUsageReservation reservation;

    @BeforeEach
    void setUp() {
        promotion = new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            5,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600)
        );
        coupon = new Coupon(promotion, 1L, "SAVE10");
        reservation = new CouponUsageReservation(coupon, 500L, 99L, Instant.now().plusSeconds(1800));
    }

    @Test
    void onBookingConfirmed_should_create_redemption_and_increment_count() {
        when(cartSnapshotReader.getCartSummary(500L)).thenReturn(new CartSummaryDto(500L, 1L, 10L, "SAVE10", Instant.now().plusSeconds(1800), "inr"));
        when(reservationRepository.findOpenByCartId(500L)).thenReturn(Optional.of(reservation));
        when(couponRepository.findByIdForUpdate(any())).thenReturn(Optional.of(coupon));
        when(redemptionRepository.existsByCouponIdAndBookingIdAndVoidedFalse(any(), any())).thenReturn(false);
        when(eligibilityService.calculateDiscount(coupon, "inr", 500L))
            .thenReturn(new DiscountCalculationResult("SAVE10", DiscountType.PERCENT_OFF, 1000L, 9000L, "inr"));

        service.onBookingConfirmed(91L, 500L, 99L);

        ArgumentCaptor<CouponRedemption> redCaptor = ArgumentCaptor.forClass(CouponRedemption.class);
        verify(redemptionRepository).save(redCaptor.capture());
        assertThat(redCaptor.getValue().getBookingId()).isEqualTo(91L);
        assertThat(coupon.getRedemptionCount()).isEqualTo(1);
        assertThat(reservation.isReleased()).isTrue();
    }

    @Test
    void onBookingConfirmed_should_mark_coupon_EXHAUSTED_and_trigger_guarded_delete_when_limit_reached() {
        for (int i = 0; i < 4; i++) {
            coupon.recordRedemption(5);
        }
        when(cartSnapshotReader.getCartSummary(500L)).thenReturn(new CartSummaryDto(500L, 1L, 10L, "SAVE10", Instant.now().plusSeconds(1800), "inr"));
        when(reservationRepository.findOpenByCartId(500L)).thenReturn(Optional.of(reservation));
        when(couponRepository.findByIdForUpdate(any())).thenReturn(Optional.of(coupon));
        when(redemptionRepository.existsByCouponIdAndBookingIdAndVoidedFalse(any(), any())).thenReturn(false);
        when(eligibilityService.calculateDiscount(coupon, "inr", 500L))
            .thenReturn(new DiscountCalculationResult("SAVE10", DiscountType.PERCENT_OFF, 500L, 0L, "inr"));

        service.onBookingConfirmed(91L, 500L, 99L);

        assertThat(coupon.getStatus()).isEqualTo(com.eventplatform.promotions.domain.enums.CouponStatus.EXHAUSTED);
        verify(discountSyncOrchestrator).guardedDelete(coupon.getId());
    }

    @Test
    void onBookingConfirmed_should_be_noop_when_no_coupon_in_cart_snapshot() {
        when(cartSnapshotReader.getCartSummary(500L)).thenReturn(new CartSummaryDto(500L, 1L, 10L, null, Instant.now().plusSeconds(1800), "inr"));

        service.onBookingConfirmed(91L, 500L, 99L);

        verify(redemptionRepository, never()).save(any());
    }

    @Test
    void onBookingCancelled_should_void_redemption_and_decrement_count() {
        coupon.recordRedemption(5);
        CouponRedemption redemption = new CouponRedemption(coupon, 99L, 91L, 500L, new BigDecimal("10.00"), "inr");
        when(redemptionRepository.findByBookingIdAndVoidedFalse(91L)).thenReturn(Optional.of(redemption));
        when(couponRepository.findByIdForUpdate(any())).thenReturn(Optional.of(coupon));

        service.onBookingCancelled(91L);

        assertThat(redemption.isVoided()).isTrue();
        assertThat(coupon.getRedemptionCount()).isEqualTo(0);
    }

    @Test
    void onBookingCancelled_should_revert_EXHAUSTED_to_ACTIVE_and_not_recreate_on_EB() {
        for (int i = 0; i < 5; i++) {
            coupon.recordRedemption(5);
        }
        coupon.markDeleteBlocked(2);
        CouponRedemption redemption = new CouponRedemption(coupon, 99L, 91L, 500L, new BigDecimal("10.00"), "inr");
        when(redemptionRepository.findByBookingIdAndVoidedFalse(91L)).thenReturn(Optional.of(redemption));
        when(couponRepository.findByIdForUpdate(any())).thenReturn(Optional.of(coupon));

        service.onBookingCancelled(91L);

        assertThat(coupon.getStatus()).isEqualTo(com.eventplatform.promotions.domain.enums.CouponStatus.ACTIVE);
        assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.DELETE_BLOCKED);
        verify(discountSyncOrchestrator, never()).createSync(any());
    }

    @Test
    void onPaymentFailed_should_release_open_usage_reservation_for_cart() {
        service.onPaymentFailed(500L, 99L);

        verify(reservationRepository).releaseByCartIdAndUserId(500L, 99L);
    }
}
