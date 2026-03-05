package com.eventplatform.promotions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.promotions.api.dto.request.CouponValidateRequest;
import com.eventplatform.promotions.api.dto.response.DiscountBreakdownResponse;
import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.CouponUsageReservation;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.exception.CouponEventMismatchException;
import com.eventplatform.promotions.exception.CouponExpiredException;
import com.eventplatform.promotions.exception.CouponInactiveException;
import com.eventplatform.promotions.exception.CouponOrgMismatchException;
import com.eventplatform.promotions.exception.CouponPerUserCapReachedException;
import com.eventplatform.promotions.exception.CouponUsageLimitReachedException;
import com.eventplatform.promotions.repository.CouponRedemptionRepository;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.dto.CartSummaryDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.event.published.CouponAppliedEvent;
import com.eventplatform.shared.common.event.published.CouponValidatedEvent;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponEligibilityServiceTest {

  @Mock private CouponRepository couponRepository;
  @Mock private CouponUsageReservationRepository reservationRepository;
  @Mock private CouponRedemptionRepository redemptionRepository;
  @Mock private CartSnapshotReader cartSnapshotReader;
  @Mock private SlotSummaryReader slotSummaryReader;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private CouponEligibilityService service;

  private Promotion promotion;
  private Coupon coupon;
  private CartSummaryDto cartSummary;

  @BeforeEach
  void setUp() {
    promotion =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            10,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    coupon = new Coupon(promotion, 1L, "SAVE10");
    cartSummary = new CartSummaryDto(100L, 1L, 10L, null, Instant.now().plusSeconds(1800), "inr");

    when(cartSnapshotReader.getCartSummary(100L)).thenReturn(cartSummary);
    when(cartSnapshotReader.getCartItems(100L))
        .thenReturn(List.of(new CartItemSnapshotDto(1L, null, null, "tc1", 10_000L, "inr", 1)));
    when(couponRepository.findTopByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(coupon));
    when(reservationRepository.countActiveByCouponId(any(), any())).thenReturn(0L);
    when(redemptionRepository.countByCouponIdAndUserIdAndVoidedFalse(any(), any())).thenReturn(0L);
    when(reservationRepository.findActiveByCouponIdAndCartId(any(), any(), any()))
        .thenReturn(Optional.empty());
  }

  @Test
  void validate_should_return_discount_breakdown_for_valid_active_coupon() {
    DiscountBreakdownResponse result =
        service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L));

    assertThat(result.couponCode()).isEqualTo("SAVE10");
    assertThat(result.discountAmountInSmallestUnit()).isEqualTo(1000L);
    assertThat(result.adjustedCartTotalInSmallestUnit()).isEqualTo(9000L);
    verify(reservationRepository).save(any(CouponUsageReservation.class));
    verify(eventPublisher).publishEvent(any(CouponValidatedEvent.class));
    verify(eventPublisher).publishEvent(any(CouponAppliedEvent.class));
  }

  @Test
  void validate_should_throw_CouponExpiredException_when_outside_validity_window() {
    Promotion expiredPromotion =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            10,
            2,
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(3600));
    Coupon expiredCoupon = new Coupon(expiredPromotion, 1L, "SAVE10");
    when(couponRepository.findTopByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(expiredCoupon));

    assertThatThrownBy(
            () -> service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L)))
        .isInstanceOf(CouponExpiredException.class);
  }

  @Test
  void validate_should_throw_CouponInactiveException_when_status_is_EXHAUSTED() {
    for (int i = 0; i < 10; i++) {
      coupon.recordRedemption(10);
    }
    assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXHAUSTED);

    assertThatThrownBy(
            () -> service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L)))
        .isInstanceOf(CouponInactiveException.class);
  }

  @Test
  void validate_should_throw_CouponOrgMismatchException_when_coupon_org_differs_from_cart_org() {
    when(couponRepository.findTopByCodeIgnoreCase("SAVE10"))
        .thenReturn(Optional.of(new Coupon(promotion, 2L, "SAVE10")));

    assertThatThrownBy(
            () -> service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L)))
        .isInstanceOf(CouponOrgMismatchException.class);
  }

  @Test
  void validate_should_throw_CouponEventMismatchException_for_event_scoped_coupon_on_wrong_slot() {
    Promotion eventScoped =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.EVENT_SCOPED,
            "eb-event-1",
            10,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    Coupon scopedCoupon = new Coupon(eventScoped, 1L, "SAVE10");
    when(couponRepository.findTopByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(scopedCoupon));
    when(slotSummaryReader.getSlotSummary(10L))
        .thenReturn(
            new SlotSummaryDto(10L, "ACTIVE", "eb-event-2", SeatingMode.GA, 1L, 1L, 1L, null));

    assertThatThrownBy(
            () -> service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L)))
        .isInstanceOf(CouponEventMismatchException.class);
  }

  @Test
  void
      validate_should_throw_CouponUsageLimitReachedException_when_count_plus_reservations_equals_max() {
    for (int i = 0; i < 4; i++) {
      coupon.recordRedemption(100);
    }
    when(reservationRepository.countActiveByCouponId(any(), any())).thenReturn(1L);
    Promotion limitPromotion =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            5,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    Coupon limitCoupon = new Coupon(limitPromotion, 1L, "SAVE10");
    for (int i = 0; i < 4; i++) {
      limitCoupon.recordRedemption(100);
    }
    when(couponRepository.findTopByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(limitCoupon));

    assertThatThrownBy(
            () -> service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L)))
        .isInstanceOf(CouponUsageLimitReachedException.class);
  }

  @Test
  void validate_should_throw_CouponPerUserCapReachedException_when_user_has_reached_per_user_cap() {
    Promotion capPromotion =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            10,
            1,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    Coupon capCoupon = new Coupon(capPromotion, 1L, "SAVE10");
    when(couponRepository.findTopByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(capCoupon));
    when(redemptionRepository.countByCouponIdAndUserIdAndVoidedFalse(any(), any())).thenReturn(1L);

    assertThatThrownBy(
            () -> service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L)))
        .isInstanceOf(CouponPerUserCapReachedException.class);
  }

  @Test
  void validate_should_return_idempotent_response_when_reservation_already_exists_for_same_cart() {
    when(reservationRepository.findActiveByCouponIdAndCartId(any(), any(), any()))
        .thenReturn(
            Optional.of(
                new CouponUsageReservation(coupon, 100L, 99L, Instant.now().plusSeconds(1200))));

    DiscountBreakdownResponse result =
        service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L));

    assertThat(result.discountAmountInSmallestUnit()).isEqualTo(1000L);
    verify(reservationRepository, never()).save(any(CouponUsageReservation.class));
    verify(eventPublisher, never()).publishEvent(any(CouponValidatedEvent.class));
    verify(eventPublisher, never()).publishEvent(any(CouponAppliedEvent.class));
  }

  @Test
  void validate_should_create_CouponUsageReservation_with_expiresAt_matching_cart_expiry() {
    Instant expiry = Instant.now().plusSeconds(1800);
    when(cartSnapshotReader.getCartSummary(100L))
        .thenReturn(new CartSummaryDto(100L, 1L, 10L, null, expiry, "inr"));

    service.validateAndApply(99L, new CouponValidateRequest("SAVE10", 100L));

    ArgumentCaptor<CouponUsageReservation> captor =
        ArgumentCaptor.forClass(CouponUsageReservation.class);
    verify(reservationRepository).save(captor.capture());
    assertThat(captor.getValue().getExpiresAt()).isEqualTo(expiry);
  }
}
