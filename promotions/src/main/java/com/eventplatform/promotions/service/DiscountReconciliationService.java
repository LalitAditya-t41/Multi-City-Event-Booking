package com.eventplatform.promotions.service;

import com.eventplatform.promotions.api.dto.response.DiscountReconciliationLogResponse;
import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.DiscountReconciliationLog;
import com.eventplatform.promotions.domain.OrphanEbDiscount;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.DiscountReconciliationLogRepository;
import com.eventplatform.promotions.repository.OrphanEbDiscountRepository;
import com.eventplatform.shared.eventbrite.dto.response.EbDiscountResponse;
import com.eventplatform.shared.eventbrite.service.EbDiscountSyncService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DiscountReconciliationService.class);

    private final CouponRepository couponRepository;
    private final OrphanEbDiscountRepository orphanRepository;
    private final DiscountReconciliationLogRepository logRepository;
    private final EbDiscountSyncService ebDiscountSyncService;

    public DiscountReconciliationService(
        CouponRepository couponRepository,
        OrphanEbDiscountRepository orphanRepository,
        DiscountReconciliationLogRepository logRepository,
        EbDiscountSyncService ebDiscountSyncService
    ) {
        this.couponRepository = couponRepository;
        this.orphanRepository = orphanRepository;
        this.logRepository = logRepository;
        this.ebDiscountSyncService = ebDiscountSyncService;
    }

    @Transactional
    public void reconcileAllOrgs() {
        List<Long> orgIds = couponRepository.findDistinctOrgIdByEbSyncStatus(EbSyncStatus.SYNCED);
        for (Long orgId : orgIds) {
            reconcileOrg(orgId);
        }
    }

    @Transactional
    public void reconcileOrg(Long orgId) {
        int drifts = 0;
        int orphans = 0;
        int deleted = 0;
        int checked = 0;
        String summary;
        String error = null;

        try {
            List<EbDiscountResponse> ebDiscounts = ebDiscountSyncService.listDiscounts(String.valueOf(orgId));
            Map<String, EbDiscountResponse> byId = ebDiscounts.stream()
                .filter(d -> d.id() != null)
                .collect(Collectors.toMap(EbDiscountResponse::id, Function.identity(), (a, b) -> a));

            List<Coupon> syncedCoupons = couponRepository.findByOrgIdAndEbSyncStatus(orgId, EbSyncStatus.SYNCED);
            checked = syncedCoupons.size();

            for (Coupon coupon : syncedCoupons) {
                EbDiscountResponse eb = coupon.getEbDiscountId() == null ? null : byId.get(coupon.getEbDiscountId());
                if (eb == null) {
                    coupon.markExternallyDeleted();
                    couponRepository.save(coupon);
                    deleted++;
                    continue;
                }

                int ebQuantity = eb.quantitySold() == null ? 0 : eb.quantitySold();
                if (ebQuantity > coupon.getRedemptionCount()) {
                    coupon.updateRedemptionCountFromEb(ebQuantity);
                    couponRepository.save(coupon);
                    drifts++;
                }

                boolean drift = (eb.code() != null && !eb.code().equalsIgnoreCase(coupon.getCode()));
                if (drift) {
                    coupon.markDriftDetected(ebQuantity);
                    couponRepository.save(coupon);
                    drifts++;
                }
            }

            var internalByEbId = syncedCoupons.stream()
                .filter(c -> c.getEbDiscountId() != null)
                .collect(Collectors.toMap(Coupon::getEbDiscountId, Function.identity(), (a, b) -> a));

            for (EbDiscountResponse eb : ebDiscounts) {
                if (eb.id() != null && !internalByEbId.containsKey(eb.id())) {
                    orphanRepository.save(new OrphanEbDiscount(eb.id(), orgId, eb.code() == null ? "UNKNOWN" : eb.code()));
                    orphans++;
                }
            }

            summary = "reconciled";
        } catch (Exception ex) {
            summary = "failed";
            error = ex.getMessage();
            log.error("Discount reconciliation failed for orgId={}", orgId, ex);
        }

        logRepository.save(new DiscountReconciliationLog(orgId, checked, drifts, orphans, deleted, summary, error));
    }

    @Transactional(readOnly = true)
    public DiscountReconciliationLogResponse latestLog(Long orgId) {
        DiscountReconciliationLog log = logRepository.findTopByOrgIdOrderByRunAtDesc(orgId)
            .orElseThrow(() -> new com.eventplatform.shared.common.exception.ResourceNotFoundException(
                "No reconciliation run for org=" + orgId,
                "RECONCILIATION_LOG_NOT_FOUND"
            ));
        return new DiscountReconciliationLogResponse(
            log.getOrgId(),
            log.getRunAt(),
            log.getDiscountsChecked(),
            log.getDriftsFound(),
            log.getOrphansFound(),
            log.getExternallyDeletedFound(),
            log.getActionsTakenSummary(),
            log.getErrorSummary()
        );
    }
}
