package com.eventplatform.discoverycatalog.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus;
import com.eventplatform.shared.common.enums.SeatingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

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

    @Column(name = "capacity")
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "seating_mode", nullable = false)
    private SeatingMode seatingMode = SeatingMode.GA;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private VenueSyncStatus syncStatus = VenueSyncStatus.PENDING_SYNC;

    @Column(name = "last_sync_error")
    private String lastSyncError;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    protected Venue() {
    }

    public Venue(Long organizationId, Long cityId, String name) {
        this.organizationId = organizationId;
        this.cityId = cityId;
        this.name = name;
    }

    public Venue(
        Long organizationId,
        Long cityId,
        String name,
        String address,
        String zipCode,
        String latitude,
        String longitude,
        Integer capacity,
        SeatingMode seatingMode
    ) {
        this.organizationId = organizationId;
        this.cityId = cityId;
        this.name = name;
        this.address = address;
        this.zipCode = zipCode;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capacity = capacity;
        this.seatingMode = seatingMode == null ? SeatingMode.GA : seatingMode;
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

    public Integer getCapacity() {
        return capacity;
    }

    public SeatingMode getSeatingMode() {
        return seatingMode;
    }

    public VenueSyncStatus getSyncStatus() {
        return syncStatus;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }

    public Instant getLastAttemptedAt() {
        return lastAttemptedAt;
    }

    public void updateDetails(String name, String address, String zipCode, String latitude, String longitude, Integer capacity, SeatingMode seatingMode) {
        if (name != null) {
            this.name = name;
        }
        if (address != null) {
            this.address = address;
        }
        if (zipCode != null) {
            this.zipCode = zipCode;
        }
        if (latitude != null) {
            this.latitude = latitude;
        }
        if (longitude != null) {
            this.longitude = longitude;
        }
        if (capacity != null) {
            this.capacity = capacity;
        }
        if (seatingMode != null) {
            this.seatingMode = seatingMode;
        }
    }

    public void markSynced(String ebVenueId) {
        this.eventbriteVenueId = ebVenueId;
        this.syncStatus = VenueSyncStatus.SYNCED;
        this.lastSyncError = null;
        this.lastAttemptedAt = Instant.now();
    }

    public void markSyncFailed(String error) {
        this.syncStatus = VenueSyncStatus.PENDING_SYNC;
        this.lastSyncError = error;
        this.lastAttemptedAt = Instant.now();
    }

    public void markDrift(String error) {
        this.syncStatus = VenueSyncStatus.DRIFT_FLAGGED;
        this.lastSyncError = error;
        this.lastAttemptedAt = Instant.now();
    }
}
