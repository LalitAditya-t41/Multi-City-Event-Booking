package com.eventplatform.discoverycatalog.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "venue")
public class Venue extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "city_id", nullable = false)
    private Long cityId;

    @Column(name = "eventbrite_venue_id")
    private String eventbriteVenueId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "zip_code")
    private String zipCode;

    @Column(name = "latitude")
    private String latitude;

    @Column(name = "longitude")
    private String longitude;

    protected Venue() {
    }

    public Venue(Long organizationId, Long cityId, String name) {
        this.organizationId = organizationId;
        this.cityId = cityId;
        this.name = name;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getCityId() {
        return cityId;
    }

    public String getEventbriteVenueId() {
        return eventbriteVenueId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getZipCode() {
        return zipCode;
    }

    public String getLatitude() {
        return latitude;
    }

    public String getLongitude() {
        return longitude;
    }
}
