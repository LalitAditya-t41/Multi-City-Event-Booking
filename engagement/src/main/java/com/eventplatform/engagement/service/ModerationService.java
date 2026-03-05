package com.eventplatform.engagement.service;

import com.eventplatform.engagement.api.dto.response.AdminReviewResponse;
import com.eventplatform.engagement.api.dto.response.ModerationResponse;
import com.eventplatform.engagement.domain.ModerationRecord;
import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.ModerationDecision;
import com.eventplatform.engagement.domain.enums.ModerationMethod;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.exception.ReviewAlreadyModeratedException;
import com.eventplatform.engagement.exception.ReviewNotFoundException;
import com.eventplatform.engagement.mapper.ReviewMapper;
import com.eventplatform.engagement.repository.ModerationRecordRepository;
import com.eventplatform.engagement.repository.ReviewRepository;
import com.eventplatform.engagement.service.model.ManualModerationDecision;
import com.eventplatform.shared.common.event.published.ReviewPublishedEvent;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.openai.service.OpenAiModerationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final ReviewRepository reviewRepository;
    private final ModerationRecordRepository moderationRecordRepository;
    private final OpenAiModerationService openAiModerationService;
    private final ReviewMapper reviewMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Counter autoApprovedCounter;
    private final Counter autoRejectedCounter;
    private final Counter autoExhaustedCounter;

    public ModerationService(
        ReviewRepository reviewRepository,
        ModerationRecordRepository moderationRecordRepository,
        OpenAiModerationService openAiModerationService,
        ReviewMapper reviewMapper,
        ApplicationEventPublisher eventPublisher,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.reviewRepository = reviewRepository;
        this.moderationRecordRepository = moderationRecordRepository;
        this.openAiModerationService = openAiModerationService;
        this.reviewMapper = reviewMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.autoApprovedCounter = meterRegistry.counter("engagement.review.moderation.auto.approved");
        this.autoRejectedCounter = meterRegistry.counter("engagement.review.moderation.auto.rejected");
        this.autoExhaustedCounter = meterRegistry.counter("engagement.review.moderation.auto.exhausted");
    }

    @Transactional
    public void triggerAutoModeration(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null || review.getStatus() != ReviewStatus.PENDING_MODERATION) {
            return;
        }

        String inputText = review.getTitle() + " " + review.getBody();
        ModerationRecord record = new ModerationRecord(review, ModerationMethod.AUTO, inputText);
        moderationRecordRepository.save(record);
        processAutoModeration(record);
    }

    @Transactional
    public ModerationResponse applyManualDecision(Long adminId, Long reviewId, ManualModerationDecision decision, String reason) {
        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new ReviewNotFoundException(reviewId));

        if (review.getStatus() != ReviewStatus.PENDING_MODERATION) {
            throw new ReviewAlreadyModeratedException();
        }

        ModerationRecord record = new ModerationRecord(review, ModerationMethod.MANUAL, review.getTitle() + " " + review.getBody());
        if (decision == ManualModerationDecision.APPROVE) {
            record.markManualDecision(adminId, ModerationDecision.APPROVED, reason);
            review.approve();
            review.publish();
            eventPublisher.publishEvent(new ReviewPublishedEvent(review.getId(), review.getEventId(), review.getRating()));
        } else {
            if (reason == null || reason.isBlank()) {
                throw new ValidationException("reason is required for reject decision", "VALIDATION_ERROR");
            }
            record.markManualDecision(adminId, ModerationDecision.REJECTED, reason);
            review.reject(reason);
        }
        moderationRecordRepository.save(record);
        reviewRepository.save(review);

        return new ModerationResponse(review.getId(), review.getStatus(), record.getDecidedAt());
    }

    @Transactional(readOnly = true)
    public Page<AdminReviewResponse> listAdminQueue(
        ReviewStatus status,
        Long eventId,
        Instant submittedAfter,
        Pageable pageable
    ) {
        Page<Review> reviews;
        if (eventId != null && submittedAfter != null) {
            reviews = reviewRepository.findByStatusAndEventIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
                status,
                eventId,
                submittedAfter,
                pageable
            );
        } else if (eventId != null) {
            reviews = reviewRepository.findByStatusAndEventIdOrderBySubmittedAtDesc(status, eventId, pageable);
        } else if (submittedAfter != null) {
            reviews = reviewRepository.findByStatusAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(status, submittedAfter, pageable);
        } else {
            reviews = reviewRepository.findByStatusOrderBySubmittedAtDesc(status, pageable);
        }

        return reviews.map(review -> {
            int attempts = moderationRecordRepository.countByReviewAndMethod(review, ModerationMethod.AUTO);
            ModerationRecord lastAuto = moderationRecordRepository.findTopByReviewAndMethodOrderByCreatedAtDesc(review, ModerationMethod.AUTO)
                .orElse(null);
            return reviewMapper.toAdminReviewResponse(review, attempts, reviewMapper.resolveLastAutoDecision(lastAuto));
        });
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void retryPendingModerations() {
        List<ModerationRecord> pending = moderationRecordRepository.findByDecisionAndRetryAfterLessThanEqualAndAutoRetryCountLessThan(
            ModerationDecision.PENDING,
            Instant.now(),
            3
        );

        int processed = 0;
        for (ModerationRecord record : pending) {
            try {
                processAutoModeration(record);
                processed++;
            } catch (Exception ex) {
                log.error("Failed moderation retry. moderationRecordId={} reviewId={}", record.getId(), record.getReview().getId(), ex);
            }
        }
        if (processed > 0) {
            log.info("Processed {} moderation retries", processed);
        }
    }

    private void processAutoModeration(ModerationRecord record) {
        Review review = record.getReview();
        if (review.getStatus() != ReviewStatus.PENDING_MODERATION) {
            return;
        }

        try {
            OpenAiModerationService.ModerationResult result = openAiModerationService.moderate(record.getInputText());
            String flags = String.join(",", result.flaggedCategories());
            String scoresJson = toJson(result.categoryScores());
            if (!result.flagged()) {
                record.markApproved(flags, scoresJson);
                review.approve();
                review.publish();
                eventPublisher.publishEvent(new ReviewPublishedEvent(review.getId(), review.getEventId(), review.getRating()));
                autoApprovedCounter.increment();
            } else {
                String topCategory = result.flaggedCategories().isEmpty() ? "content_policy_violation" : result.flaggedCategories().get(0);
                record.markRejected(flags, scoresJson, topCategory);
                review.reject(topCategory);
                autoRejectedCounter.increment();
            }
            moderationRecordRepository.save(record);
            reviewRepository.save(review);
        } catch (Exception ex) {
            scheduleRetry(record, review.getId(), ex);
            moderationRecordRepository.save(record);
        }
    }

    private void scheduleRetry(ModerationRecord record, Long reviewId, Exception ex) {
        if (record.getAutoRetryCount() >= 2) {
            record.markRetryExhausted();
            autoExhaustedCounter.increment();
            log.error("Auto moderation retries exhausted. reviewId={}", reviewId, ex);
            return;
        }
        int nextAttempt = record.getAutoRetryCount() + 1;
        Instant retryAt;
        if (nextAttempt == 1) {
            retryAt = Instant.now().plusSeconds(30);
        } else if (nextAttempt == 2) {
            retryAt = Instant.now().plusSeconds(120);
        } else {
            retryAt = Instant.now().plusSeconds(600);
        }
        record.markRetryScheduled(retryAt);
        log.warn("Auto moderation failed. reviewId={} retryAttempt={} retryAfter={}", reviewId, nextAttempt, retryAt, ex);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
