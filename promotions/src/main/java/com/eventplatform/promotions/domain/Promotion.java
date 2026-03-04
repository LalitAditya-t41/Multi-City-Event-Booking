package com.eventplatform.promotions.domain;

import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "promotions")
public class Promotion extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private BigDecimal discountValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private PromotionScope scope;

    @Column(name = "eb_event_id")
    private String ebEventId;

    @Column(name = "max_usage_limit")
    private Integer maxUsageLimit;

    @Column(name = "per_user_cap")
    private Integer perUserCap;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PromotionStatus status;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL)
    private List<Coupon> coupons = new ArrayList<>();

    protected Promotion() {
    }

    public Promotion(Long orgId, String name, DiscountType discountType, BigDecimal discountValue, PromotionScope scope,
                     String ebEventId, Integer maxUsageLimit, Integer perUserCap, Instant validFrom, Instant validUntil) {
        this.orgId = orgId;
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.scope = scope;
        this.ebEventId = ebEventId;
        this.maxUsageLimit = maxUsageLimit;
        this.perUserCap = perUserCap;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.status = PromotionStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = PromotionStatus.INACTIVE;
    }

    public boolean isActiveAt(Instant now) {
        return status == PromotionStatus.ACTIVE && !now.isBefore(validFrom) && !now.isAfter(validUntil);
    }

    public Long getOrgId() { return orgId; }
    public String getName() { return name; }
    public DiscountType getDiscountType() { return discountType; }
    public BigDecimal getDiscountValue() { return discountValue; }
    public PromotionScope getScope() { return scope; }
    public String getEbEventId() { return ebEventId; }
    public Integer getMaxUsageLimit() { return maxUsageLimit; }
    public Integer getPerUserCap() { return perUserCap; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public PromotionStatus getStatus() { return status; }

    public void updateWindowAndCaps(Instant validFrom, Instant validUntil, Integer maxUsageLimit, Integer perUserCap) {
        if (validFrom != null) this.validFrom = validFrom;
        if (validUntil != null) this.validUntil = validUntil;
        if (maxUsageLimit != null) this.maxUsageLimit = maxUsageLimit;
        if (perUserCap != null) this.perUserCap = perUserCap;
    }
}
