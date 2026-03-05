package com.eventplatform.engagement.api.dto.response;

import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ModerationDecision;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import java.time.Instant;

public record AdminReviewResponse(
    Long reviewId,
    Long eventId,
    Long userId,
    Integer rating,
    String title,
    String body,
    ReviewStatus status,
    AttendanceVerificationStatus attendanceVerificationStatus,
    Instant submittedAt,
    int autoModerationAttempts,
    ModerationDecision lastAutoDecision) {}
