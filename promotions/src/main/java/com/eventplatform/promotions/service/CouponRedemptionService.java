package com.eventplatform.promotions.service;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.CouponRedemption;
import com.eventplatform.promotions.domain.CouponUsageReservation;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.repository.CouponRedemptionRepository;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.shared.common.dto.CartSummaryDto;
import com.eventplatform.shared.common.event.published.CouponAppliedEvent;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponRedemptionService {

    private static final Logger log = LoggerFactory.getLogger(CouponRedemptionService.class);

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final CouponUsageReservationRepository reservationRepository;
    private final CouponEligibilityService eligibilityService;
    private final CartSnapshotReader cartSnapshotReader;
    private final DiscountSyncOrchestrator discountSyncOrchestrator;
    private final ApplicationEventPublisher eventPublisher;

    public CouponRedemptionService(
        CouponRepository couponRepository,
        CouponRedemptionRepository redemptionRepository,
        CouponUsageReservationRepository reservationRepository,
        CouponEligibilityService eligibilityService,
        CartSnapshotReader cartSnapshotReader,
        DiscountSyncOrchestrator discountSyncOrchestrator,
        ApplicationEventPublisher eventPublisher
    ) {
        this.couponRepository = couponRepository;
        this.redemptionRepository = redemptionRepository;
        this.reservationRepository = reservationRepository;
        this.eligibilityService = eligibilityService;
        this.cartSnapshotReader = cartSnapshotReader;
        this.discountSyncOrchestrator = discountSyncOrchestrator;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void onBookingConfirmed(Long bookingId, Long cartId, Long userId) {
        CouponUsageReservation reservation = reservationRepository.findOpenByCartId(cartId).orElse(null);
        if (reservation == null) {
            return;
        }

        Coupon coupon = couponRepository.findByIdForUpdate(reservation.getCoupon().getId()).orElse(null);
        if (coupon == null) {
            reservation.release();
            reservationRepository.save(reservation);
            return;
        }

        if (redemptionRepository.existsByCouponIdAndBookingIdAndVoidedFalse(coupon.getId(), bookingId)) {
            reservation.release();
            reservationRepository.save(reservation);
            return;
        }

        CartSummaryDto summary = cartSnapshotReader.getCartSummary(cartId);
        DiscountCalculationResult calc = eligibilityService.calculateDiscount(coupon, summary.currency(), cartId);
        BigDecimal discountMajor = BigDecimal.valueOf(calc.discountAmountInSmallestUnit())
            .divide(BigDecimal.valueOf(100));

        redemptionRepository.save(new CouponRedemption(
            coupon,
            userId,
            bookingId,
            cartId,
            discountMajor,
            calc.currency()
        ));

        coupon.recordRedemption(coupon.getPromotion().getMaxUsageLimit());
        reservation.release();
        couponRepository.save(coupon);
        reservationRepository.save(reservation);

        if (coupon.getStatus() == com.eventplatform.promotions.domain.enums.CouponStatus.EXHAUSTED) {
            discountSyncOrchestrator.guardedDelete(coupon.getId());
        }
    }

    @Transactional
    public void onBookingCancelled(Long bookingId) {
        CouponRedemption redemption = redemptionRepository.findByBookingIdAndVoidedFalse(bookingId).orElse(null);
        if (redemption == null) {
            return;
        }

        Coupon coupon = couponRepository.findByIdForUpdate(redemption.getCoupon().getId()).orElse(null);
        if (coupon == null) {
            return;
        }

        redemption.voidRedemption();
        coupon.voidRedemption();
        redemptionRepository.save(redemption);
        couponRepository.save(coupon);

        if (coupon.getEbSyncStatus() == EbSyncStatus.DELETE_BLOCKED || coupon.getEbSyncStatus() == EbSyncStatus.EB_DELETED_EXTERNALLY) {
            log.info("Skipping EB recreate for couponId={} due to syncStatus={}", coupon.getId(), coupon.getEbSyncStatus());
        }
    }

    @Transactional
    public void onPaymentFailed(Long cartId, Long userId) {
        reservationRepository.releaseByCartIdAndUserId(cartId, userId);
    }

    @Transactional
    public void onCartAssembled(Long cartId, Long userId) {
        CartSummaryDto summary = cartSnapshotReader.getCartSummary(cartId);
        String couponCode = summary.couponCode();
        if (couponCode == null || couponCode.isBlank()) {
            return;
        }

        Coupon coupon = couponRepository.findByCodeIgnoreCaseAndOrgId(couponCode, summary.orgId()).orElse(null);
        if (coupon == null) {
            return;
        }

        DiscountCalculationResult result = eligibilityService.calculateDiscount(coupon, summary.currency(), cartId);
        eventPublisher.publishEvent(new CouponAppliedEvent(
            cartId,
            couponCode,
            result.discountAmountInSmallestUnit(),
            result.currency(),
            userId
        ));
        log.debug("Cart assembled coupon check done cartId={} discount={}", cartId, result.discountAmountInSmallestUnit());
    }
}
