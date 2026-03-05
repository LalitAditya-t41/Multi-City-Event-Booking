package com.eventplatform.discoverycatalog.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "city")
public class City extends BaseEntity {

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "state")
  private String state;

  @Column(name = "country_code")
  private String countryCode;

  @Column(name = "latitude")
  private String latitude;

  @Column(name = "longitude")
  private String longitude;

  protected City() {}

  public City(Long organizationId, String name) {
    this.organizationId = organizationId;
    this.name = name;
  }

  public Long getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getState() {
    return state;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public String getLatitude() {
    return latitude;
  }

  public String getLongitude() {
    return longitude;
  }
}
