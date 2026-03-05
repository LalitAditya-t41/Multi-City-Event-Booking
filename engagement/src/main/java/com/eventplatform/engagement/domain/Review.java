package com.eventplatform.engagement.domain;

import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "reviews")
public class Review extends BaseEntity {

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "event_id", nullable = false)
  private Long eventId;

  @Column(name = "rating", nullable = false)
  private short rating;

  @Column(name = "title", nullable = false, length = 100)
  private String title;

  @Column(name = "body", nullable = false, length = 2000)
  private String body;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ReviewStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "attendance_verification_status", nullable = false)
  private AttendanceVerificationStatus attendanceVerificationStatus;

  @Column(name = "rejection_reason", length = 255)
  private String rejectionReason;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "submitted_at", nullable = false, updatable = false)
  private Instant submittedAt;

  protected Review() {}

  public Review(
      Long userId,
      Long eventId,
      int rating,
      String title,
      String body,
      AttendanceVerificationStatus attendanceVerificationStatus) {
    if (rating < 1 || rating > 5) {
      throw new IllegalArgumentException("rating must be between 1 and 5");
    }
    this.userId = userId;
    this.eventId = eventId;
    this.rating = (short) rating;
    this.title = title;
    this.body = body;
    this.attendanceVerificationStatus = attendanceVerificationStatus;
    this.status = ReviewStatus.SUBMITTED;
    this.submittedAt = Instant.now();
  }

  public void markPendingModeration() {
    if (status != ReviewStatus.SUBMITTED) {
      throw new BusinessRuleException(
          "Review cannot enter pending moderation from state " + status, "INVALID_REVIEW_STATE");
    }
    this.status = ReviewStatus.PENDING_MODERATION;
  }

  public void approve() {
    if (status != ReviewStatus.PENDING_MODERATION) {
      throw new BusinessRuleException(
          "Review cannot be approved from state " + status, "INVALID_REVIEW_STATE");
    }
    this.status = ReviewStatus.APPROVED;
  }

  public void publish() {
    if (status != ReviewStatus.APPROVED && status != ReviewStatus.PENDING_MODERATION) {
      throw new BusinessRuleException(
          "Review cannot be published from state " + status, "INVALID_REVIEW_STATE");
    }
    this.status = ReviewStatus.PUBLISHED;
    this.publishedAt = Instant.now();
  }

  public void reject(String rejectionReason) {
    if (status == ReviewStatus.REJECTED) {
      return;
    }
    if (status != ReviewStatus.PENDING_MODERATION) {
      throw new BusinessRuleException(
          "Review cannot be rejected from state " + status, "INVALID_REVIEW_STATE");
    }
    this.status = ReviewStatus.REJECTED;
    this.rejectionReason = rejectionReason;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getEventId() {
    return eventId;
  }

  public int getRating() {
    return rating;
  }

  public String getTitle() {
    return title;
  }

  public String getBody() {
    return body;
  }

  public ReviewStatus getStatus() {
    return status;
  }

  public AttendanceVerificationStatus getAttendanceVerificationStatus() {
    return attendanceVerificationStatus;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }
}
