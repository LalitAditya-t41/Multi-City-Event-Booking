package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.CancellationPolicyScope;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cancellation_policies")
public class CancellationPolicy extends BaseEntity {

  @Column(name = "org_id")
  private Long orgId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false)
  private CancellationPolicyScope scope;

  @Column(name = "created_by_admin_id")
  private Long createdByAdminId;

  @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sortOrder ASC")
  private List<CancellationPolicyTier> tiers = new ArrayList<>();

  protected CancellationPolicy() {}

  public CancellationPolicy(Long orgId, CancellationPolicyScope scope, Long createdByAdminId) {
    this.orgId = orgId;
    this.scope = scope;
    this.createdByAdminId = createdByAdminId;
  }

  public void replaceTiers(List<CancellationPolicyTier> nextTiers) {
    this.tiers.clear();
    for (CancellationPolicyTier tier : nextTiers) {
      tier.attachPolicy(this);
      this.tiers.add(tier);
    }
  }

  public Long getOrgId() {
    return orgId;
  }

  public CancellationPolicyScope getScope() {
    return scope;
  }

  public Long getCreatedByAdminId() {
    return createdByAdminId;
  }

  public List<CancellationPolicyTier> getTiers() {
    return List.copyOf(tiers);
  }
}
