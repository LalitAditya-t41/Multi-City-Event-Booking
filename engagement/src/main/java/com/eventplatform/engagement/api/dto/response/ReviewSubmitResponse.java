package com.eventplatform.engagement.api.dto.response;

import com.eventplatform.engagement.domain.enums.ReviewStatus;

public record ReviewSubmitResponse(Long reviewId, ReviewStatus status, String message) {
}
