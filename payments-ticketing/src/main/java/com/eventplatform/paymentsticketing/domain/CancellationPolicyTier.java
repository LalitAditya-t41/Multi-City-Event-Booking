package com.eventplatform.paymentsticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "cancellation_policy_tiers")
public class CancellationPolicyTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private CancellationPolicy policy;

    @Column(name = "hours_before_event")
    private Integer hoursBeforeEvent;

    @Column(name = "refund_percent", nullable = false)
    private Integer refundPercent;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CancellationPolicyTier() {
    }

    public CancellationPolicyTier(Integer hoursBeforeEvent, Integer refundPercent, Integer sortOrder) {
        this.hoursBeforeEvent = hoursBeforeEvent;
        this.refundPercent = refundPercent;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
    }

    void attachPolicy(CancellationPolicy policy) {
        this.policy = policy;
    }

    public Long getId() {
        return id;
    }

    public Integer getHoursBeforeEvent() {
        return hoursBeforeEvent;
    }

    public Integer getRefundPercent() {
        return refundPercent;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
