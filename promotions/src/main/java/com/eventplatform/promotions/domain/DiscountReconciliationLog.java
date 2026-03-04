package com.eventplatform.promotions.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "discount_reconciliation_logs")
public class DiscountReconciliationLog extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "discounts_checked", nullable = false)
    private int discountsChecked;

    @Column(name = "drifts_found", nullable = false)
    private int driftsFound;

    @Column(name = "orphans_found", nullable = false)
    private int orphansFound;

    @Column(name = "externally_deleted_found", nullable = false)
    private int externallyDeletedFound;

    @Column(name = "actions_taken_summary")
    private String actionsTakenSummary;

    @Column(name = "error_summary")
    private String errorSummary;

    protected DiscountReconciliationLog() {
    }

    public DiscountReconciliationLog(Long orgId, int discountsChecked, int driftsFound, int orphansFound,
                                     int externallyDeletedFound, String actionsTakenSummary, String errorSummary) {
        this.orgId = orgId;
        this.runAt = Instant.now();
        this.discountsChecked = discountsChecked;
        this.driftsFound = driftsFound;
        this.orphansFound = orphansFound;
        this.externallyDeletedFound = externallyDeletedFound;
        this.actionsTakenSummary = actionsTakenSummary;
        this.errorSummary = errorSummary;
    }

    public Long getOrgId() { return orgId; }
    public Instant getRunAt() { return runAt; }
    public int getDiscountsChecked() { return discountsChecked; }
    public int getDriftsFound() { return driftsFound; }
    public int getOrphansFound() { return orphansFound; }
    public int getExternallyDeletedFound() { return externallyDeletedFound; }
    public String getActionsTakenSummary() { return actionsTakenSummary; }
    public String getErrorSummary() { return errorSummary; }
}
