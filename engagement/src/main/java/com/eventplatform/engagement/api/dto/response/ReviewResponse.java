package com.eventplatform.engagement.api.dto.response;

import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import java.time.Instant;

public record ReviewResponse(
    Long reviewId,
    Long eventId,
    Integer rating,
    String title,
    String body,
    ReviewStatus status,
    AttendanceVerificationStatus attendanceVerificationStatus,
    String rejectionReason,
    Instant submittedAt,
    Instant publishedAt,
    String reviewerDisplayName) {}
