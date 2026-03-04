package com.eventplatform.promotions.service;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.mapper.EbDiscountSyncPayloadMapper;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.shared.eventbrite.dto.response.EbDiscountResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbDiscountSyncService;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountSyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DiscountSyncOrchestrator.class);

    private final CouponRepository couponRepository;
    private final EbDiscountSyncService ebDiscountSyncService;
    private final EbDiscountSyncPayloadMapper payloadMapper;

    public DiscountSyncOrchestrator(
        CouponRepository couponRepository,
        EbDiscountSyncService ebDiscountSyncService,
        EbDiscountSyncPayloadMapper payloadMapper
    ) {
        this.couponRepository = couponRepository;
        this.ebDiscountSyncService = ebDiscountSyncService;
        this.payloadMapper = payloadMapper;
    }

    @Async
    @Transactional
    public void createSync(Long couponId) {
        couponRepository.findByIdForUpdate(couponId).ifPresent(this::createSyncInternal);
    }

    @Transactional
    public void createSyncInternal(Coupon coupon) {
        Promotion promotion = coupon.getPromotion();
        if (promotion.getScope() == PromotionScope.EVENT_SCOPED && promotion.getEbEventId() == null) {
            coupon.markNotSynced();
            couponRepository.save(coupon);
            log.warn("Skipping EB coupon sync due to null ebEventId for event-scoped promotion. couponId={}", coupon.getId());
            return;
        }

        coupon.markSyncPending();

        try {
            String orgId = String.valueOf(coupon.getOrgId());
            var existing = ebDiscountSyncService.listDiscounts(orgId).stream()
                .filter(d -> d.code() != null && d.code().equalsIgnoreCase(coupon.getCode()))
                .findFirst();

            EbDiscountResponse adoptedOrCreated;
            if (existing.isPresent()) {
                adoptedOrCreated = existing.get();
            } else {
                adoptedOrCreated = ebDiscountSyncService.createDiscount(orgId, payloadMapper.toRequest(coupon, promotion));
            }

            if (adoptedOrCreated == null || adoptedOrCreated.id() == null) {
                coupon.markSyncFailed();
                couponRepository.save(coupon);
                return;
            }

            EbDiscountResponse verify = ebDiscountSyncService.getDiscount(adoptedOrCreated.id());
            if (verify == null || verify.id() == null) {
                coupon.markSyncFailed();
            } else {
                coupon.markSynced(verify.id(), safeInt(verify.quantitySold()));
            }
            couponRepository.save(coupon);
        } catch (Exception ex) {
            coupon.markSyncFailed();
            couponRepository.save(coupon);
            log.error("EB create sync failed for couponId={}", coupon.getId(), ex);
        }
    }

    @Transactional
    public void guardedDelete(Long couponId) {
        couponRepository.findByIdForUpdate(couponId).ifPresent(this::guardedDeleteInternal);
    }

    @Transactional
    public void guardedDeleteInternal(Coupon coupon) {
        if (coupon.getEbDiscountId() == null || coupon.getEbDiscountId().isBlank()) {
            coupon.markNotSynced();
            couponRepository.save(coupon);
            return;
        }

        try {
            EbDiscountResponse current = ebDiscountSyncService.getDiscount(coupon.getEbDiscountId());
            int quantitySold = safeInt(current == null ? null : current.quantitySold());
            if (quantitySold > 0) {
                coupon.markDeleteBlocked(quantitySold);
                couponRepository.save(coupon);
                return;
            }

            ebDiscountSyncService.deleteDiscount(coupon.getEbDiscountId());
            coupon.markNotSynced();
            couponRepository.save(coupon);
        } catch (EbIntegrationException ex) {
            if (isDeleteBlocked(ex)) {
                coupon.markDeleteBlocked(coupon.getEbQuantitySoldAtLastSync() == null ? 1 : coupon.getEbQuantitySoldAtLastSync());
            } else {
                coupon.markSyncFailed();
            }
            couponRepository.save(coupon);
            log.warn("EB guarded delete failed for couponId={} status={}", coupon.getId(), coupon.getEbSyncStatus(), ex);
        }
    }

    @Transactional
    public void resyncAfterPromotionUpdate(Long couponId) {
        couponRepository.findByIdForUpdate(couponId).ifPresent(this::resyncAfterPromotionUpdateInternal);
    }

    private void resyncAfterPromotionUpdateInternal(Coupon coupon) {
        if (coupon.getEbDiscountId() == null || coupon.getEbDiscountId().isBlank()) {
            createSyncInternal(coupon);
            return;
        }

        try {
            EbDiscountResponse current = ebDiscountSyncService.getDiscount(coupon.getEbDiscountId());
            int quantitySold = safeInt(current == null ? null : current.quantitySold());
            if (quantitySold > 0) {
                coupon.markCannotResync(quantitySold);
                couponRepository.save(coupon);
                return;
            }

            ebDiscountSyncService.deleteDiscount(coupon.getEbDiscountId());
            createSyncInternal(coupon);
        } catch (Exception ex) {
            coupon.markSyncFailed();
            couponRepository.save(coupon);
            log.error("EB resync failed for couponId={}", coupon.getId(), ex);
        }
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static boolean isDeleteBlocked(EbIntegrationException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("400");
    }
}
