package com.eventplatform.promotions.service;

import com.eventplatform.promotions.api.dto.request.PromotionCreateRequest;
import com.eventplatform.promotions.api.dto.request.PromotionUpdateRequest;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import com.eventplatform.promotions.exception.PromotionHasActiveRedemptionsException;
import com.eventplatform.promotions.exception.PromotionNotFoundException;
import com.eventplatform.promotions.mapper.PromotionMapper;
import com.eventplatform.promotions.repository.CouponRedemptionRepository;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.CouponUsageReservationRepository;
import com.eventplatform.promotions.repository.PromotionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService {

  private final PromotionRepository promotionRepository;
  private final CouponRepository couponRepository;
  private final CouponRedemptionRepository couponRedemptionRepository;
  private final CouponUsageReservationRepository reservationRepository;
  private final PromotionMapper promotionMapper;
  private final DiscountSyncOrchestrator discountSyncOrchestrator;

  public PromotionService(
      PromotionRepository promotionRepository,
      CouponRepository couponRepository,
      CouponRedemptionRepository couponRedemptionRepository,
      CouponUsageReservationRepository reservationRepository,
      PromotionMapper promotionMapper,
      DiscountSyncOrchestrator discountSyncOrchestrator) {
    this.promotionRepository = promotionRepository;
    this.couponRepository = couponRepository;
    this.couponRedemptionRepository = couponRedemptionRepository;
    this.reservationRepository = reservationRepository;
    this.promotionMapper = promotionMapper;
    this.discountSyncOrchestrator = discountSyncOrchestrator;
  }

  @Transactional
  public com.eventplatform.promotions.api.dto.response.PromotionResponse create(
      Long orgId, PromotionCreateRequest request) {
    Promotion promotion =
        new Promotion(
            orgId,
            request.name(),
            request.discountType(),
            request.discountValue(),
            request.scope(),
            request.ebEventId(),
            request.maxUsageLimit(),
            request.perUserCap(),
            request.validFrom(),
            request.validUntil());
    return promotionMapper.toResponse(promotionRepository.save(promotion));
  }

  @Transactional(readOnly = true)
  public List<com.eventplatform.promotions.api.dto.response.PromotionResponse> list(
      Long orgId, PromotionStatus status) {
    List<Promotion> promotions =
        status == null
            ? promotionRepository.findByOrgId(orgId)
            : promotionRepository.findByOrgIdAndStatus(orgId, status);
    return promotions.stream().map(promotionMapper::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public com.eventplatform.promotions.api.dto.response.PromotionResponse get(
      Long orgId, Long promotionId) {
    Promotion promotion =
        promotionRepository
            .findById(promotionId)
            .filter(p -> p.getOrgId().equals(orgId))
            .orElseThrow(() -> new PromotionNotFoundException(promotionId));
    return promotionMapper.toResponse(promotion);
  }

  @Transactional
  public com.eventplatform.promotions.api.dto.response.PromotionResponse update(
      Long orgId, Long promotionId, PromotionUpdateRequest request) {
    Promotion promotion =
        promotionRepository
            .findById(promotionId)
            .filter(p -> p.getOrgId().equals(orgId))
            .orElseThrow(() -> new PromotionNotFoundException(promotionId));

    promotion.updateWindowAndCaps(
        request.validFrom(), request.validUntil(), request.maxUsageLimit(), request.perUserCap());
    Promotion saved = promotionRepository.save(promotion);

    couponRepository
        .findByPromotionId(saved.getId())
        .forEach(
            coupon -> {
              if (coupon.getEbSyncStatus() == EbSyncStatus.SYNCED
                  || coupon.getEbSyncStatus() == EbSyncStatus.SYNC_FAILED) {
                discountSyncOrchestrator.resyncAfterPromotionUpdate(coupon.getId());
              }
            });

    return promotionMapper.toResponse(saved);
  }

  @Transactional
  public void deactivate(Long orgId, Long promotionId) {
    Promotion promotion =
        promotionRepository
            .findById(promotionId)
            .filter(p -> p.getOrgId().equals(orgId))
            .orElseThrow(() -> new PromotionNotFoundException(promotionId));

    var coupons = couponRepository.findByPromotionId(promotion.getId());
    List<Long> couponIds = coupons.stream().map(c -> c.getId()).toList();
    if (!couponIds.isEmpty()
        && couponRedemptionRepository.existsByCouponIdInAndVoidedFalse(couponIds)) {
      throw new PromotionHasActiveRedemptionsException(promotionId);
    }

    coupons.forEach(
        coupon -> {
          coupon.deactivate();
          couponRepository.save(coupon);
          reservationRepository.releaseAllByCouponId(coupon.getId());
          discountSyncOrchestrator.guardedDelete(coupon.getId());
        });
    promotion.deactivate();
    promotionRepository.save(promotion);
  }
}
