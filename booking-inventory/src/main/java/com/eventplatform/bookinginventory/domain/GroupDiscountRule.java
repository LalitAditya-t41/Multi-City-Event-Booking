package com.eventplatform.bookinginventory.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;

@Entity
@Table(
    name = "group_discount_rules",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_discount_rule_tier",
            columnNames = {"show_slot_id", "pricing_tier_id"}))
public class GroupDiscountRule extends BaseEntity {

  @Column(name = "show_slot_id", nullable = false)
  private Long showSlotId;

  @Column(name = "pricing_tier_id", nullable = false)
  private Long pricingTierId;

  @Column(name = "group_discount_threshold", nullable = false)
  private Integer groupDiscountThreshold;

  @Column(name = "group_discount_percent", nullable = false)
  private BigDecimal groupDiscountPercent;

  protected GroupDiscountRule() {}

  public GroupDiscountRule(
      Long showSlotId,
      Long pricingTierId,
      Integer groupDiscountThreshold,
      BigDecimal groupDiscountPercent) {
    this.showSlotId = showSlotId;
    this.pricingTierId = pricingTierId;
    this.groupDiscountThreshold = groupDiscountThreshold;
    this.groupDiscountPercent = groupDiscountPercent;
  }

  public Long getShowSlotId() {
    return showSlotId;
  }

  public Long getPricingTierId() {
    return pricingTierId;
  }

  public Integer getGroupDiscountThreshold() {
    return groupDiscountThreshold;
  }

  public BigDecimal getGroupDiscountPercent() {
    return groupDiscountPercent;
  }
}
