package com.eventplatform.promotions.domain;

import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "code", nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CouponStatus status;

    @Column(name = "redemption_count", nullable = false)
    private Integer redemptionCount;

    @Column(name = "eb_discount_id")
    private String ebDiscountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "eb_sync_status", nullable = false)
    private EbSyncStatus ebSyncStatus;

    @Column(name = "eb_quantity_sold_at_last_sync")
    private Integer ebQuantitySoldAtLastSync;

    @Column(name = "last_eb_sync_at")
    private Instant lastEbSyncAt;

    protected Coupon() {
    }

    public Coupon(Promotion promotion, Long orgId, String code) {
        this.promotion = promotion;
        this.orgId = orgId;
        this.code = code;
        this.status = CouponStatus.ACTIVE;
        this.redemptionCount = 0;
        this.ebSyncStatus = EbSyncStatus.NOT_SYNCED;
    }

    public void deactivate() { this.status = CouponStatus.INACTIVE; }

    public void recordRedemption(Integer maxUsageLimit) {
        this.redemptionCount = this.redemptionCount + 1;
        if (maxUsageLimit != null && this.redemptionCount >= maxUsageLimit) {
            this.status = CouponStatus.EXHAUSTED;
        }
    }

    public void voidRedemption() {
        this.redemptionCount = Math.max(0, this.redemptionCount - 1);
        if (this.status == CouponStatus.EXHAUSTED) {
            this.status = CouponStatus.ACTIVE;
        }
    }

    public void markSyncPending() {
        this.ebSyncStatus = EbSyncStatus.SYNC_PENDING;
        this.lastEbSyncAt = Instant.now();
    }

    public void markSynced(String ebDiscountId, Integer quantitySold) {
        this.ebDiscountId = ebDiscountId;
        this.ebSyncStatus = EbSyncStatus.SYNCED;
        this.ebQuantitySoldAtLastSync = quantitySold;
        this.lastEbSyncAt = Instant.now();
    }

    public void markNotSynced() {
        this.ebDiscountId = null;
        this.ebSyncStatus = EbSyncStatus.NOT_SYNCED;
        this.lastEbSyncAt = Instant.now();
    }

    public void markSyncFailed() {
        this.ebSyncStatus = EbSyncStatus.SYNC_FAILED;
        this.lastEbSyncAt = Instant.now();
    }

    public void markDeleteBlocked(Integer quantitySold) {
        this.ebSyncStatus = EbSyncStatus.DELETE_BLOCKED;
        this.ebQuantitySoldAtLastSync = quantitySold;
        this.lastEbSyncAt = Instant.now();
    }

    public void markCannotResync(Integer quantitySold) {
        this.ebSyncStatus = EbSyncStatus.CANNOT_RESYNC;
        this.ebQuantitySoldAtLastSync = quantitySold;
        this.lastEbSyncAt = Instant.now();
    }

    public void markExternallyDeleted() {
        this.ebSyncStatus = EbSyncStatus.EB_DELETED_EXTERNALLY;
        this.lastEbSyncAt = Instant.now();
    }

    public void markDriftDetected(Integer quantitySold) {
        this.ebSyncStatus = EbSyncStatus.DRIFT_DETECTED;
        this.ebQuantitySoldAtLastSync = quantitySold;
        this.lastEbSyncAt = Instant.now();
    }

    public void updateRedemptionCountFromEb(int quantitySold) {
        this.redemptionCount = Math.max(0, quantitySold);
        this.ebQuantitySoldAtLastSync = quantitySold;
        this.lastEbSyncAt = Instant.now();
    }

    public Promotion getPromotion() { return promotion; }
    public Long getOrgId() { return orgId; }
    public String getCode() { return code; }
    public CouponStatus getStatus() { return status; }
    public Integer getRedemptionCount() { return redemptionCount; }
    public String getEbDiscountId() { return ebDiscountId; }
    public EbSyncStatus getEbSyncStatus() { return ebSyncStatus; }
    public Integer getEbQuantitySoldAtLastSync() { return ebQuantitySoldAtLastSync; }
    public Instant getLastEbSyncAt() { return lastEbSyncAt; }
}
