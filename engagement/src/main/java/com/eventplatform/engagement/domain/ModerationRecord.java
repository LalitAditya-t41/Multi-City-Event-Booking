package com.eventplatform.engagement.domain;

import com.eventplatform.engagement.domain.enums.ModerationDecision;
import com.eventplatform.engagement.domain.enums.ModerationMethod;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "moderation_records")
public class ModerationRecord extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "review_id", nullable = false)
  private Review review;

  @Enumerated(EnumType.STRING)
  @Column(name = "method", nullable = false)
  private ModerationMethod method;

  @Column(name = "input_text", nullable = false)
  private String inputText;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision", nullable = false)
  private ModerationDecision decision;

  @Column(name = "flags", length = 500)
  private String flags;

  @Column(name = "scores_json")
  private String scoresJson;

  @Column(name = "moderator_id")
  private Long moderatorId;

  @Column(name = "reason", length = 500)
  private String reason;

  @Column(name = "auto_retry_count", nullable = false)
  private int autoRetryCount;

  @Column(name = "retry_after")
  private Instant retryAfter;

  @Column(name = "decided_at")
  private Instant decidedAt;

  protected ModerationRecord() {}

  public ModerationRecord(Review review, ModerationMethod method, String inputText) {
    this.review = review;
    this.method = method;
    this.inputText = inputText;
    this.decision = ModerationDecision.PENDING;
    this.autoRetryCount = 0;
  }

  public void markApproved(String flags, String scoresJson) {
    this.decision = ModerationDecision.APPROVED;
    this.flags = flags;
    this.scoresJson = scoresJson;
    this.decidedAt = Instant.now();
    this.retryAfter = null;
  }

  public void markRejected(String flags, String scoresJson, String reason) {
    this.decision = ModerationDecision.REJECTED;
    this.flags = flags;
    this.scoresJson = scoresJson;
    this.reason = reason;
    this.decidedAt = Instant.now();
    this.retryAfter = null;
  }

  public void markManualDecision(Long moderatorId, ModerationDecision decision, String reason) {
    this.moderatorId = moderatorId;
    this.decision = decision;
    this.reason = reason;
    this.decidedAt = Instant.now();
    this.retryAfter = null;
  }

  public void markRetryScheduled(Instant retryAfter) {
    this.autoRetryCount++;
    this.retryAfter = retryAfter;
  }

  public void markRetryExhausted() {
    this.autoRetryCount++;
    this.retryAfter = null;
  }

  public Review getReview() {
    return review;
  }

  public ModerationMethod getMethod() {
    return method;
  }

  public String getInputText() {
    return inputText;
  }

  public ModerationDecision getDecision() {
    return decision;
  }

  public String getFlags() {
    return flags;
  }

  public String getScoresJson() {
    return scoresJson;
  }

  public Long getModeratorId() {
    return moderatorId;
  }

  public String getReason() {
    return reason;
  }

  public int getAutoRetryCount() {
    return autoRetryCount;
  }

  public Instant getRetryAfter() {
    return retryAfter;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }
}
