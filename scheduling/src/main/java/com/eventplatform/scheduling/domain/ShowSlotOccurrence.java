package com.eventplatform.scheduling.domain;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "show_slot_occurrence")
@EntityListeners(AuditingEntityListener.class)
public class ShowSlotOccurrence {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_slot_id", nullable = false)
  private ShowSlot parentSlot;

  @Column(name = "occurrence_index", nullable = false)
  private Integer occurrenceIndex;

  @Column(name = "start_time", nullable = false)
  private ZonedDateTime startTime;

  @Column(name = "end_time", nullable = false)
  private ZonedDateTime endTime;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ShowSlotStatus status = ShowSlotStatus.ACTIVE;

  @Column(name = "eb_event_id")
  private String ebEventId;

  @Column(name = "sync_attempt_count", nullable = false)
  private int syncAttemptCount;

  @Column(name = "last_sync_error")
  private String lastSyncError;

  @Column(name = "last_attempted_at")
  private Instant lastAttemptedAt;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ShowSlotOccurrence() {}

  public ShowSlotOccurrence(
      Integer occurrenceIndex, ZonedDateTime startTime, ZonedDateTime endTime) {
    this.occurrenceIndex = occurrenceIndex;
    this.startTime = startTime;
    this.endTime = endTime;
  }

  public Long getId() {
    return id;
  }

  public ShowSlot getParentSlot() {
    return parentSlot;
  }

  public Integer getOccurrenceIndex() {
    return occurrenceIndex;
  }

  public ZonedDateTime getStartTime() {
    return startTime;
  }

  public ZonedDateTime getEndTime() {
    return endTime;
  }

  public ShowSlotStatus getStatus() {
    return status;
  }

  public String getEbEventId() {
    return ebEventId;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void attachTo(ShowSlot parentSlot) {
    this.parentSlot = parentSlot;
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

  public void recordSyncFailure(String error) {
    this.syncAttemptCount++;
    this.lastSyncError = error;
    this.lastAttemptedAt = Instant.now();
  }

  public void setEbEventId(String ebEventId) {
    this.ebEventId = ebEventId;
  }
}
