package com.eventplatform.scheduling.domain;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "show_slot")
public class ShowSlot extends BaseEntity {

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "venue_id", nullable = false)
  private Long venueId;

  @Column(name = "city_id", nullable = false)
  private Long cityId;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "description")
  private String description;

  @Column(name = "start_time", nullable = false)
  private ZonedDateTime startTime;

  @Column(name = "end_time", nullable = false)
  private ZonedDateTime endTime;

  @Enumerated(EnumType.STRING)
  @Column(name = "seating_mode", nullable = false)
  private SeatingMode seatingMode;

  @Column(name = "capacity", nullable = false)
  private Integer capacity;

  @Column(name = "source_seatmap_id")
  private String sourceSeatMapId;

  @Column(name = "is_recurring", nullable = false)
  private boolean recurring;

  @Column(name = "recurrence_rule")
  private String recurrenceRule;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ShowSlotStatus status = ShowSlotStatus.DRAFT;

  @Column(name = "eb_event_id")
  private String ebEventId;

  @Column(name = "eb_series_id")
  private String ebSeriesId;

  @Column(name = "sync_attempt_count", nullable = false)
  private int syncAttemptCount;

  @Column(name = "last_sync_error")
  private String lastSyncError;

  @Column(name = "last_attempted_at")
  private Instant lastAttemptedAt;

  @OneToMany(mappedBy = "slot", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ShowSlotPricingTier> pricingTiers = new ArrayList<>();

  @OneToMany(mappedBy = "parentSlot", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ShowSlotOccurrence> occurrences = new ArrayList<>();

  protected ShowSlot() {}

  public ShowSlot(
      Long organizationId,
      Long venueId,
      Long cityId,
      String title,
      String description,
      ZonedDateTime startTime,
      ZonedDateTime endTime,
      SeatingMode seatingMode,
      Integer capacity,
      boolean recurring,
      String recurrenceRule,
      String sourceSeatMapId) {
    this.organizationId = organizationId;
    this.venueId = venueId;
    this.cityId = cityId;
    this.title = title;
    this.description = description;
    this.startTime = startTime;
    this.endTime = endTime;
    this.seatingMode = seatingMode;
    this.capacity = capacity;
    this.recurring = recurring;
    this.recurrenceRule = recurrenceRule;
    this.sourceSeatMapId = sourceSeatMapId;
    this.status = ShowSlotStatus.DRAFT;
    this.syncAttemptCount = 0;
  }

  public Long getOrganizationId() {
    return organizationId;
  }

  public Long getVenueId() {
    return venueId;
  }

  public Long getCityId() {
    return cityId;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public ZonedDateTime getStartTime() {
    return startTime;
  }

  public ZonedDateTime getEndTime() {
    return endTime;
  }

  public SeatingMode getSeatingMode() {
    return seatingMode;
  }

  public Integer getCapacity() {
    return capacity;
  }

  public String getSourceSeatMapId() {
    return sourceSeatMapId;
  }

  public boolean isRecurring() {
    return recurring;
  }

  public String getRecurrenceRule() {
    return recurrenceRule;
  }

  public ShowSlotStatus getStatus() {
    return status;
  }

  public String getEbEventId() {
    return ebEventId;
  }

  public String getEbSeriesId() {
    return ebSeriesId;
  }

  public int getSyncAttemptCount() {
    return syncAttemptCount;
  }

  public String getLastSyncError() {
    return lastSyncError;
  }

  public Instant getLastAttemptedAt() {
    return lastAttemptedAt;
  }

  public List<ShowSlotPricingTier> getPricingTiers() {
    return pricingTiers;
  }

  public List<ShowSlotOccurrence> getOccurrences() {
    return occurrences;
  }

  public void addPricingTier(ShowSlotPricingTier tier) {
    pricingTiers.add(tier);
    tier.attachTo(this);
  }

  public void clearPricingTiers() {
    pricingTiers.forEach(tier -> tier.attachTo(null));
    pricingTiers.clear();
  }

  public void addOccurrence(ShowSlotOccurrence occurrence) {
    occurrences.add(occurrence);
    occurrence.attachTo(this);
  }

  public void submit() {
    if (status != ShowSlotStatus.DRAFT) {
      throw new BusinessRuleException(
          "Invalid slot transition: " + status + " -> PENDING_SYNC", "INVALID_SLOT_TRANSITION");
    }
    if (pricingTiers == null || pricingTiers.isEmpty()) {
      throw new BusinessRuleException(
          "Slot must have at least one pricing tier before submission", "MISSING_PRICING_TIERS");
    }
    markPendingSync();
  }

  public void activate() {
    if (status != ShowSlotStatus.PENDING_SYNC) {
      throw new BusinessRuleException(
          "Invalid slot transition: " + status + " -> ACTIVE", "INVALID_SLOT_TRANSITION");
    }
    markActive();
  }

  public void cancel() {
    if (status == ShowSlotStatus.CANCELLED) {
      throw new BusinessRuleException("Slot already cancelled", "SLOT_ALREADY_CANCELLED");
    }
    if (status == ShowSlotStatus.DRAFT) {
      throw new BusinessRuleException(
          "DRAFT slots must be deleted, not cancelled", "SLOT_DRAFT_CANCEL");
    }
    markCancelled();
  }

  public void markPendingSync() {
    this.status = ShowSlotStatus.PENDING_SYNC;
  }

  public void markActive() {
    this.status = ShowSlotStatus.ACTIVE;
    this.syncAttemptCount = 0;
    this.lastSyncError = null;
    this.lastAttemptedAt = null;
  }

  public void markCancelled() {
    this.status = ShowSlotStatus.CANCELLED;
  }

  public void recordSyncFailure(String error) {
    this.syncAttemptCount++;
    this.lastSyncError = error;
    this.lastAttemptedAt = Instant.now();
  }

  public void markAttempted() {
    this.lastAttemptedAt = Instant.now();
  }

  public void setEbEventId(String ebEventId) {
    this.ebEventId = ebEventId;
  }

  public void setEbSeriesId(String ebSeriesId) {
    this.ebSeriesId = ebSeriesId;
  }

  public void updateDetails(
      String title,
      String description,
      ZonedDateTime startTime,
      ZonedDateTime endTime,
      Integer capacity) {
    if (title != null) {
      this.title = title;
    }
    if (description != null) {
      this.description = description;
    }
    if (startTime != null) {
      this.startTime = startTime;
    }
    if (endTime != null) {
      this.endTime = endTime;
    }
    if (capacity != null) {
      this.capacity = capacity;
    }
  }
}
