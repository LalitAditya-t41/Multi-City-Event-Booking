package com.eventplatform.engagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.engagement.domain.ModerationRecord;
import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ModerationDecision;
import com.eventplatform.engagement.domain.enums.ModerationMethod;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.exception.ReviewAlreadyModeratedException;
import com.eventplatform.engagement.mapper.ReviewMapper;
import com.eventplatform.engagement.repository.ModerationRecordRepository;
import com.eventplatform.engagement.repository.ReviewRepository;
import com.eventplatform.engagement.service.model.ManualModerationDecision;
import com.eventplatform.shared.common.event.published.ReviewPublishedEvent;
import com.eventplatform.shared.openai.service.OpenAiModerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

  @Mock private ReviewRepository reviewRepository;
  @Mock private ModerationRecordRepository moderationRecordRepository;
  @Mock private OpenAiModerationService openAiModerationService;
  @Mock private ReviewMapper reviewMapper;
  @Mock private ApplicationEventPublisher eventPublisher;

  private ModerationService moderationService;

  @BeforeEach
  void init() {
    moderationService =
        new ModerationService(
            reviewRepository,
            moderationRecordRepository,
            openAiModerationService,
            reviewMapper,
            eventPublisher,
            new ObjectMapper(),
            new SimpleMeterRegistry());
  }

  @Test
  void should_approve_and_publish_when_auto_moderation_not_flagged() throws Exception {
    Review review = pendingReview(1L);
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
    when(openAiModerationService.moderate(any()))
        .thenReturn(
            new OpenAiModerationService.ModerationResult(false, List.of(), Map.of("hate", 0.01)));

    moderationService.triggerAutoModeration(1L);

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
    ArgumentCaptor<ModerationRecord> recordCaptor = ArgumentCaptor.forClass(ModerationRecord.class);
    verify(moderationRecordRepository, times(2)).save(recordCaptor.capture());
    ModerationRecord finalRecord = recordCaptor.getAllValues().get(1);
    assertThat(finalRecord.getDecision()).isEqualTo(ModerationDecision.APPROVED);
    verify(eventPublisher).publishEvent(any(ReviewPublishedEvent.class));
  }

  @Test
  void should_reject_review_when_auto_moderation_flagged() {
    Review review = pendingReview(1L);
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
    when(openAiModerationService.moderate(any()))
        .thenReturn(
            new OpenAiModerationService.ModerationResult(
                true, List.of("hate"), Map.of("hate", 0.99)));

    moderationService.triggerAutoModeration(1L);

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(review.getRejectionReason()).isEqualTo("hate");
  }

  @Test
  void should_schedule_retry_when_auto_moderation_api_failure_on_first_attempt() {
    Review review = pendingReview(1L);
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
    when(openAiModerationService.moderate(any())).thenThrow(new RuntimeException("openai down"));

    moderationService.triggerAutoModeration(1L);

    ArgumentCaptor<ModerationRecord> recordCaptor = ArgumentCaptor.forClass(ModerationRecord.class);
    verify(moderationRecordRepository, times(2)).save(recordCaptor.capture());
    ModerationRecord finalRecord = recordCaptor.getAllValues().get(1);
    assertThat(finalRecord.getDecision()).isEqualTo(ModerationDecision.PENDING);
    assertThat(finalRecord.getAutoRetryCount()).isEqualTo(1);
    assertThat(finalRecord.getRetryAfter()).isNotNull();
    assertThat(review.getStatus()).isEqualTo(ReviewStatus.PENDING_MODERATION);
  }

  @Test
  void should_exhaust_retry_when_auto_moderation_api_failure_on_third_attempt() {
    Review review = pendingReview(1L);
    ModerationRecord record = new ModerationRecord(review, ModerationMethod.AUTO, "text");
    record.markRetryScheduled(Instant.now().minusSeconds(1));
    record.markRetryScheduled(Instant.now().minusSeconds(1));

    when(moderationRecordRepository
            .findByDecisionAndRetryAfterLessThanEqualAndAutoRetryCountLessThan(
                eq(ModerationDecision.PENDING), any(Instant.class), eq(3)))
        .thenReturn(List.of(record));
    when(openAiModerationService.moderate(any())).thenThrow(new RuntimeException("openai down"));

    moderationService.retryPendingModerations();

    assertThat(record.getAutoRetryCount()).isEqualTo(3);
    assertThat(record.getRetryAfter()).isNull();
  }

  @Test
  void should_noop_when_auto_moderation_invoked_for_already_published_review() {
    Review review =
        new Review(11L, 1001L, 4, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);
    review.markPendingModeration();
    review.approve();
    review.publish();
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

    moderationService.triggerAutoModeration(1L);

    verify(openAiModerationService, never()).moderate(any());
    verify(moderationRecordRepository, never()).save(any());
  }

  @Test
  void should_process_only_eligible_records_when_retry_pending_moderations_runs() {
    Review r1 = pendingReview(1L);
    Review r2 = pendingReview(2L);
    ModerationRecord m1 = new ModerationRecord(r1, ModerationMethod.AUTO, "a");
    ModerationRecord m2 = new ModerationRecord(r2, ModerationMethod.AUTO, "b");

    when(moderationRecordRepository
            .findByDecisionAndRetryAfterLessThanEqualAndAutoRetryCountLessThan(
                eq(ModerationDecision.PENDING), any(Instant.class), eq(3)))
        .thenReturn(List.of(m1, m2));
    when(openAiModerationService.moderate(any()))
        .thenReturn(new OpenAiModerationService.ModerationResult(false, List.of(), Map.of()));

    moderationService.retryPendingModerations();

    verify(openAiModerationService).moderate("a");
    verify(openAiModerationService).moderate("b");
  }

  @Test
  void should_continue_processing_when_first_retry_record_fails() {
    Review r1 = pendingReview(1L);
    Review r2 = pendingReview(2L);
    ModerationRecord m1 = new ModerationRecord(r1, ModerationMethod.AUTO, "a");
    ModerationRecord m2 = new ModerationRecord(r2, ModerationMethod.AUTO, "b");

    when(moderationRecordRepository
            .findByDecisionAndRetryAfterLessThanEqualAndAutoRetryCountLessThan(
                eq(ModerationDecision.PENDING), any(Instant.class), eq(3)))
        .thenReturn(List.of(m1, m2));
    when(openAiModerationService.moderate("a")).thenThrow(new RuntimeException("fail first"));
    when(openAiModerationService.moderate("b"))
        .thenReturn(new OpenAiModerationService.ModerationResult(false, List.of(), Map.of()));

    moderationService.retryPendingModerations();

    assertThat(r2.getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
  }

  @Test
  void should_publish_review_when_manual_approve_decision_applied() throws Exception {
    Review review = pendingReview(1L);
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

    var response =
        moderationService.applyManualDecision(99L, 1L, ManualModerationDecision.APPROVE, null);

    assertThat(response.newStatus()).isEqualTo(ReviewStatus.PUBLISHED);
    verify(eventPublisher).publishEvent(any(ReviewPublishedEvent.class));
  }

  @Test
  void should_reject_review_when_manual_reject_decision_applied() {
    Review review = pendingReview(1L);
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

    var response =
        moderationService.applyManualDecision(99L, 1L, ManualModerationDecision.REJECT, "policy");

    assertThat(response.newStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(review.getRejectionReason()).isEqualTo("policy");
  }

  @Test
  void should_throw_review_already_moderated_when_manual_decision_attempted_on_published_review() {
    Review review =
        new Review(11L, 1001L, 4, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);
    review.markPendingModeration();
    review.approve();
    review.publish();
    when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));

    assertThatThrownBy(
            () ->
                moderationService.applyManualDecision(
                    99L, 1L, ManualModerationDecision.REJECT, "x"))
        .isInstanceOf(ReviewAlreadyModeratedException.class);
  }

  private Review pendingReview(Long id) {
    Review review =
        new Review(11L, 1001L, 4, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);
    review.markPendingModeration();
    try {
      java.lang.reflect.Field idField =
          com.eventplatform.shared.common.domain.BaseEntity.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(review, id);
    } catch (Exception ignored) {
    }
    return review;
  }
}
