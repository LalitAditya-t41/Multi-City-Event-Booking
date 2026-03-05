package com.eventplatform.promotions.service;

import com.eventplatform.promotions.api.dto.request.CouponCreateRequest;
import com.eventplatform.promotions.api.dto.response.CouponResponse;
import com.eventplatform.promotions.api.dto.response.CouponUsageStatsResponse;
import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.exception.CouponCodeConflictException;
import com.eventplatform.promotions.exception.CouponHasActiveRedemptionsException;
import com.eventplatform.promotions.exception.CouponNotFoundException;
import com.eventplatform.promotions.exception.PromotionNotFoundException;
import com.eventplatform.promotions.mapper.CouponMapper;
import com.eventplatform.promotions.repository.CouponRedemptionRepository;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.promotions.repository.PromotionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponAdminService {

  private final PromotionRepository promotionRepository;
  private final CouponRepository couponRepository;
  private final CouponRedemptionRepository couponRedemptionRepository;
  private final CouponUsageReservationRepository reservationRepository;
  private final CouponMapper couponMapper;
  private final DiscountSyncOrchestrator discountSyncOrchestrator;

  public CouponAdminService(
      PromotionRepository promotionRepository,
      CouponRepository couponRepository,
      CouponRedemptionRepository couponRedemptionRepository,
      CouponUsageReservationRepository reservationRepository,
      CouponMapper couponMapper,
      DiscountSyncOrchestrator discountSyncOrchestrator) {
    this.promotionRepository = promotionRepository;
    this.couponRepository = couponRepository;
    this.couponRedemptionRepository = couponRedemptionRepository;
    this.reservationRepository = reservationRepository;
    this.couponMapper = couponMapper;
    this.discountSyncOrchestrator = discountSyncOrchestrator;
  }

  @Transactional
  public CouponResponse createCoupon(Long orgId, Long promotionId, CouponCreateRequest request) {
    Promotion promotion =
        promotionRepository
            .findById(promotionId)
            .filter(p -> p.getOrgId().equals(orgId))
            .orElseThrow(() -> new PromotionNotFoundException(promotionId));

    if (couponRepository.existsByCodeIgnoreCaseAndOrgIdAndStatusNot(
        request.code(), orgId, com.eventplatform.promotions.domain.enums.CouponStatus.INACTIVE)) {
      throw new CouponCodeConflictException(request.code());
    }

    Coupon coupon = new Coupon(promotion, orgId, request.code().trim());
    coupon.markSyncPending();
    coupon = couponRepository.save(coupon);
    discountSyncOrchestrator.createSync(coupon.getId());
    return couponMapper.toResponse(coupon);
  }

  @Transactional(readOnly = true)
  public List<CouponResponse> listCoupons(Long orgId, Long promotionId) {
    return couponRepository.findByPromotionId(promotionId).stream()
        .filter(c -> c.getOrgId().equals(orgId))
        .map(couponMapper::toResponse)
        .toList();
  }

  @Transactional
  public void deactivateCoupon(Long orgId, String couponCode) {
    Coupon coupon =
        couponRepository
            .findByCodeIgnoreCaseAndOrgId(couponCode, orgId)
            .orElseThrow(() -> new CouponNotFoundException(couponCode));
    if (couponRedemptionRepository.existsByCouponIdAndVoidedFalse(coupon.getId())) {
      throw new CouponHasActiveRedemptionsException(couponCode);
    }
    coupon.deactivate();
    couponRepository.save(coupon);
    reservationRepository.releaseAllByCouponId(coupon.getId());
    discountSyncOrchestrator.guardedDelete(coupon.getId());
  }

  @Transactional(readOnly = true)
  public CouponUsageStatsResponse usage(Long orgId, String couponCode) {
    Coupon coupon =
        couponRepository
            .findByCodeIgnoreCaseAndOrgId(couponCode, orgId)
            .orElseThrow(() -> new CouponNotFoundException(couponCode));
    long activeReservations =
        reservationRepository.countOpenReservations(coupon.getId(), Instant.now());
    long voided = couponRedemptionRepository.countByCouponIdAndVoidedTrue(coupon.getId());
    return new CouponUsageStatsResponse(
        coupon.getCode(),
        coupon.getStatus(),
        coupon.getRedemptionCount(),
        activeReservations,
        voided,
        coupon.getEbSyncStatus(),
        coupon.getLastEbSyncAt(),
        coupon.getEbQuantitySoldAtLastSync());
  }

  @Transactional
  public CouponResponse manualSync(Long orgId, String couponCode) {
    Coupon coupon =
        couponRepository
            .findByCodeIgnoreCaseAndOrgId(couponCode, orgId)
            .orElseThrow(() -> new CouponNotFoundException(couponCode));
    discountSyncOrchestrator.resyncAfterPromotionUpdate(coupon.getId());
    Coupon refreshed = couponRepository.findById(coupon.getId()).orElse(coupon);
    return couponMapper.toResponse(refreshed);
  }
}
