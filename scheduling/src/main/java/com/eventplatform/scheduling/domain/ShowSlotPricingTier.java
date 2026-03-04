package com.eventplatform.scheduling.domain;

import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "show_slot_pricing_tier")
public class ShowSlotPricingTier extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id", nullable = false)
    private ShowSlot slot;

    @Column(name = "name", nullable = false)
    private String name;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "price_amount", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false))
    })
    private Money price;

    @Column(name = "quota", nullable = false)
    private Integer quota;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_type", nullable = false)
    private TierType tierType;

    @Column(name = "eb_ticket_class_id")
    private String ebTicketClassId;

    @Column(name = "eb_inventory_tier_id")
    private String ebInventoryTierId;

    @Column(name = "group_discount_threshold")
    private Integer groupDiscountThreshold;   // nullable — null means no group discount

    @Column(name = "group_discount_percent", precision = 5, scale = 2)
    private BigDecimal groupDiscountPercent;  // nullable

    protected ShowSlotPricingTier() {
    }

    public ShowSlotPricingTier(String name, Money price, Integer quota, TierType tierType) {
        if (quota == null || quota <= 0) {
            throw new BusinessRuleException("Pricing tier quota must be greater than 0", "INVALID_TIER_QUOTA");
        }
        if (tierType == TierType.FREE && price != null && price.amount().signum() > 0) {
            throw new BusinessRuleException("FREE tier must have priceAmount = 0", "INVALID_TIER_PRICE");
        }
        this.name = name;
        this.price = price;
        this.quota = quota;
        this.tierType = tierType;
    }

    public ShowSlot getSlot() {
        return slot;
    }

    public String getName() {
        return name;
    }

    public Money getPrice() {
        return price;
    }

    public Integer getQuota() {
        return quota;
    }

    public TierType getTierType() {
        return tierType;
    }

    public String getEbTicketClassId() {
        return ebTicketClassId;
    }

    public String getEbInventoryTierId() {
        return ebInventoryTierId;
    }

    public Integer getGroupDiscountThreshold() {
        return groupDiscountThreshold;
    }

    public BigDecimal getGroupDiscountPercent() {
        return groupDiscountPercent;
    }

    public void setGroupDiscount(Integer threshold, BigDecimal percent) {
        if (threshold != null && threshold <= 0) {
            throw new BusinessRuleException("Group discount threshold must be > 0", "INVALID_GROUP_THRESHOLD");
        }
        if (percent != null && (percent.compareTo(BigDecimal.ZERO) <= 0 || percent.compareTo(new BigDecimal("100")) > 0)) {
            throw new BusinessRuleException("Group discount percent must be between 0 and 100", "INVALID_GROUP_PERCENT");
        }
        this.groupDiscountThreshold = threshold;
        this.groupDiscountPercent   = percent;
    }

    public void attachTo(ShowSlot slot) {
        this.slot = slot;
    }

    public void setEbTicketClassId(String ebTicketClassId) {
        this.ebTicketClassId = ebTicketClassId;
    }

    public void setEbInventoryTierId(String ebInventoryTierId) {
        this.ebInventoryTierId = ebInventoryTierId;
    }
}
