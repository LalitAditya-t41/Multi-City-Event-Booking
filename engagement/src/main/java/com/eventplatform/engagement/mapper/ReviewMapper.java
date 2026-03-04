package com.eventplatform.engagement.mapper;

import com.eventplatform.engagement.api.dto.response.AdminReviewResponse;
import com.eventplatform.engagement.api.dto.response.ReviewResponse;
import com.eventplatform.engagement.domain.ModerationRecord;
import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.ModerationDecision;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    default ReviewResponse toReviewResponse(Review review, String reviewerDisplayName) {
        return new ReviewResponse(
            review.getId(),
            review.getEventId(),
            review.getRating(),
            review.getTitle(),
            review.getBody(),
            review.getStatus(),
            review.getAttendanceVerificationStatus(),
            review.getRejectionReason(),
            review.getSubmittedAt(),
            review.getPublishedAt(),
            reviewerDisplayName
        );
    }

    default AdminReviewResponse toAdminReviewResponse(Review review, int autoModerationAttempts, ModerationDecision lastAutoDecision) {
        return new AdminReviewResponse(
            review.getId(),
            review.getEventId(),
            review.getUserId(),
            review.getRating(),
            review.getTitle(),
            review.getBody(),
            review.getStatus(),
            review.getAttendanceVerificationStatus(),
            review.getSubmittedAt(),
            autoModerationAttempts,
            lastAutoDecision
        );
    }

    default ModerationDecision resolveLastAutoDecision(ModerationRecord record) {
        return record == null ? null : record.getDecision();
    }
}
