package com.eventplatform.engagement.repository;

import com.eventplatform.engagement.domain.ModerationRecord;
import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.ModerationDecision;
import com.eventplatform.engagement.domain.enums.ModerationMethod;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationRecordRepository extends JpaRepository<ModerationRecord, Long> {

    Optional<ModerationRecord> findTopByReviewAndMethodOrderByCreatedAtDesc(Review review, ModerationMethod method);

    List<ModerationRecord> findByDecisionAndRetryAfterLessThanEqualAndAutoRetryCountLessThan(
        ModerationDecision decision,
        Instant retryAfter,
        int maxRetries
    );

    int countByReviewAndMethod(Review review, ModerationMethod method);
}
