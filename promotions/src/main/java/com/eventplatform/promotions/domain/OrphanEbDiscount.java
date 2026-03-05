package com.eventplatform.promotions.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "orphan_eb_discounts")
public class OrphanEbDiscount extends BaseEntity {

  @Column(name = "eb_discount_id", nullable = false)
  private String ebDiscountId;

  @Column(name = "org_id", nullable = false)
  private Long orgId;

  @Column(name = "code", nullable = false)
  private String code;

  @Column(name = "detected_at", nullable = false)
  private Instant detectedAt;

  @Column(name = "reviewed", nullable = false)
  private boolean reviewed;

  @Column(name = "notes")
  private String notes;

  protected OrphanEbDiscount() {}

  public OrphanEbDiscount(String ebDiscountId, Long orgId, String code) {
    this.ebDiscountId = ebDiscountId;
    this.orgId = orgId;
    this.code = code;
    this.detectedAt = Instant.now();
    this.reviewed = false;
  }
}
