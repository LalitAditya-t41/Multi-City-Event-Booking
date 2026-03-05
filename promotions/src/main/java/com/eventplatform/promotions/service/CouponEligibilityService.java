package com.eventplatform.promotions.service;

import com.eventplatform.promotions.api.dto.request.CouponValidateRequest;
import com.eventplatform.promotions.api.dto.response.DiscountBreakdownResponse;
import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.CouponUsageReservation;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.exception.CouponEventMismatchException;
import com.eventplatform.promotions.exception.CouponExpiredException;
import com.eventplatform.promotions.exception.CouponInactiveException;
import com.eventplatform.promotions.exception.CouponNotFoundException;
import com.eventplatform.promotions.exception.CouponNotYetValidException;
import com.eventplatform.promotions.exception.CouponOrgMismatchException;
import com.eventplatform.promotions.exception.CouponPerUserCapReachedException;
import com.eventplatform.promotions.exception.CouponUsageLimitReachedException;
import com.eventplatform.promotions.repository.CouponRedemptionRepository;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.dto.CartSummaryDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.event.published.CouponAppliedEvent;
import com.eventplatform.shared.common.event.published.CouponValidatedEvent;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponEligibilityService {

  private final CouponRepository couponRepository;
  private final CouponUsageReservationRepository reservationRepository;
  private final CouponRedemptionRepository redemptionRepository;
  private final CartSnapshotReader cartSnapshotReader;
  private final SlotSummaryReader slotSummaryReader;
  private final ApplicationEventPublisher eventPublisher;

  public CouponEligibilityService(
      CouponRepository couponRepository,
      CouponUsageReservationRepository reservationRepository,
      CouponRedemptionRepository redemptionRepository,
      CartSnapshotReader cartSnapshotReader,
      SlotSummaryReader slotSummaryReader,
      ApplicationEventPublisher eventPublisher) {
    this.couponRepository = couponRepository;
    this.reservationRepository = reservationRepository;
    this.redemptionRepository = redemptionRepository;
    this.cartSnapshotReader = cartSnapshotReader;
    this.slotSummaryReader = slotSummaryReader;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public DiscountBreakdownResponse validateAndApply(Long userId, CouponValidateRequest request) {
    CartSummaryDto cartSummary = cartSnapshotReader.getCartSummary(request.cartId());
    if (!cartSummary.expiresAt().isAfter(Instant.now())) {
      throw new BusinessRuleException("Cart expired", "CART_EXPIRED");
    }

    Coupon coupon =
        couponRepository
            .findTopByCodeIgnoreCase(request.couponCode())
            .orElseThrow(() -> new CouponNotFoundException(request.couponCode()));

    if (!coupon.getOrgId().equals(cartSummary.orgId())) {
      throw new CouponOrgMismatchException();
    }

    if (coupon.getStatus() != CouponStatus.ACTIVE) {
      throw new CouponInactiveException(coupon.getCode());
    }

    Promotion promotion = coupon.getPromotion();
    Instant now = Instant.now();
    if (now.isAfter(promotion.getValidUntil())) {
      throw new CouponExpiredException(coupon.getCode());
    }
    if (now.isBefore(promotion.getValidFrom())) {
      throw new CouponNotYetValidException(coupon.getCode());
    }

    if (promotion.getScope() == PromotionScope.EVENT_SCOPED) {
      SlotSummaryDto slot = slotSummaryReader.getSlotSummary(cartSummary.slotId());
      if (slot == null
          || slot.ebEventId() == null
          || !slot.ebEventId().equals(promotion.getEbEventId())) {
        throw new CouponEventMismatchException();
      }
    }

    long activeReservations = reservationRepository.countActiveByCouponId(coupon.getId(), now);
    if (promotion.getMaxUsageLimit() != null
        && coupon.getRedemptionCount() + activeReservations >= promotion.getMaxUsageLimit()) {
      throw new CouponUsageLimitReachedException();
    }

    if (promotion.getPerUserCap() != null
        && redemptionRepository.countByCouponIdAndUserIdAndVoidedFalse(coupon.getId(), userId)
            >= promotion.getPerUserCap()) {
      throw new CouponPerUserCapReachedException();
    }

    boolean reservationCreated =
        reservationRepository
            .findActiveByCouponIdAndCartId(coupon.getId(), request.cartId(), now)
            .map(existing -> false)
            .orElseGet(
                () -> {
                  reservationRepository.save(
                      new CouponUsageReservation(
                          coupon, request.cartId(), userId, cartSummary.expiresAt()));
                  return true;
                });

    DiscountCalculationResult result =
        calculateDiscount(coupon, cartSummary.currency(), request.cartId());
    if (reservationCreated) {
      eventPublisher.publishEvent(
          new CouponValidatedEvent(
              request.cartId(),
              coupon.getCode(),
              result.discountAmountInSmallestUnit(),
              result.currency(),
              userId));
      eventPublisher.publishEvent(
          new CouponAppliedEvent(
              request.cartId(),
              coupon.getCode(),
              result.discountAmountInSmallestUnit(),
              result.currency(),
              userId));
    }

    return new DiscountBreakdownResponse(
        result.couponCode(),
        result.discountType(),
        result.discountAmountInSmallestUnit(),
        result.adjustedTotalInSmallestUnit(),
        result.currency());
  }

  @Transactional
  public void removeCouponFromCart(Long userId, Long cartId) {
    int released = reservationRepository.releaseByCartIdAndUserId(cartId, userId);
    if (released < 1) {
      throw new CouponNotFoundException("cart=" + cartId);
    }
  }

  @Transactional(readOnly = true)
  public DiscountCalculationResult calculateDiscount(Coupon coupon, String currency, Long cartId) {
    List<CartItemSnapshotDto> items = cartSnapshotReader.getCartItems(cartId);
    long subtotal =
        items.stream()
            .mapToLong(
                i -> {
                  int qty = i.quantity() == null || i.quantity() < 1 ? 1 : i.quantity();
                  return i.unitPrice() * qty;
                })
            .sum();

    long discount;
    if (coupon.getPromotion().getDiscountType()
        == com.eventplatform.promotions.domain.enums.DiscountType.PERCENT_OFF) {
      BigDecimal value =
          BigDecimal.valueOf(subtotal)
              .multiply(coupon.getPromotion().getDiscountValue())
              .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
      discount = value.longValue();
    } else {
      long amountOff =
          coupon
              .getPromotion()
              .getDiscountValue()
              .multiply(BigDecimal.valueOf(100))
              .setScale(0, RoundingMode.HALF_UP)
              .longValue();
      discount = Math.min(amountOff, subtotal);
    }

    long adjusted = Math.max(0L, subtotal - discount);
    return new DiscountCalculationResult(
        coupon.getCode(),
        coupon.getPromotion().getDiscountType(),
        discount,
        adjusted,
        currency.toLowerCase());
  }
}
