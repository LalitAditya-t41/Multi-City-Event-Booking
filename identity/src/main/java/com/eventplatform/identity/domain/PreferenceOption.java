package com.eventplatform.identity.domain;

import com.eventplatform.identity.domain.enums.PreferenceOptionType;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "preference_options")
public class PreferenceOption extends BaseEntity {

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private PreferenceOptionType type;

  @Column(name = "value", nullable = false)
  private String value;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder;

  protected PreferenceOption() {}

  public PreferenceOption(
      PreferenceOptionType type, String value, boolean active, Integer sortOrder) {
    this.type = type;
    this.value = value;
    this.active = active;
    this.sortOrder = sortOrder;
  }

  public PreferenceOptionType getType() {
    return type;
  }

  public String getValue() {
    return value;
  }

  public boolean isActive() {
    return active;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }
}
