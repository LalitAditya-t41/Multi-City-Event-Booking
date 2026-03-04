package com.eventplatform.engagement.api.dto.response;

import com.eventplatform.engagement.domain.enums.ReviewStatus;
import java.time.Instant;

public record ModerationResponse(Long reviewId, ReviewStatus newStatus, Instant decidedAt) {
}
