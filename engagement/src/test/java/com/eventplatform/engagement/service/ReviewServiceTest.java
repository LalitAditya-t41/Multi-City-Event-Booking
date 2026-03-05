package com.eventplatform.engagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.engagement.api.dto.request.ReviewSubmitRequest;
import com.eventplatform.engagement.api.dto.response.ReviewSubmitResponse;
import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.event.published.ModerationRequiredEvent;
import com.eventplatform.engagement.exception.ReviewAlreadySubmittedException;
import com.eventplatform.engagement.exception.ReviewNotEligibleException;
import com.eventplatform.engagement.mapper.ReviewMapper;
import com.eventplatform.engagement.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

  @Mock private ReviewRepository reviewRepository;
  @Mock private ReviewEligibilityService reviewEligibilityService;
  @Mock private AttendanceVerificationService attendanceVerificationService;
  @Mock private ReviewMapper reviewMapper;
  @Mock private IdentityLookupService identityLookupService;
  @Mock private EventMetadataLookupService eventMetadataLookupService;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private ReviewService reviewService;

  private ReviewSubmitRequest request;

  @BeforeEach
  void setUp() {
    request = new ReviewSubmitRequest(1001L, 4, "Great", "Great show");
  }

  @Test
  void should_save_pending_moderation_and_publish_event_when_submit_review_happy_path() {
    when(reviewRepository.existsByUserIdAndEventId(11L, 1001L)).thenReturn(false);
    when(attendanceVerificationService.verify(11L, 1001L))
        .thenReturn(AttendanceVerificationStatus.EB_VERIFIED);
    when(reviewRepository.save(any(Review.class)))
        .thenAnswer(
            invocation -> {
              Review review = invocation.getArgument(0);
              java.lang.reflect.Field id =
                  com.eventplatform.shared.common.domain.BaseEntity.class.getDeclaredField("id");
              id.setAccessible(true);
              id.set(review, 55L);
              return review;
            });

    ReviewSubmitResponse response = reviewService.submitReview(11L, request);

    assertThat(response.reviewId()).isEqualTo(55L);
    assertThat(response.status().name()).isEqualTo("PENDING_MODERATION");

    ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).save(reviewCaptor.capture());
    assertThat(reviewCaptor.getValue().getStatus().name()).isEqualTo("PENDING_MODERATION");
    assertThat(reviewCaptor.getValue().getAttendanceVerificationStatus())
        .isEqualTo(AttendanceVerificationStatus.EB_VERIFIED);

    ArgumentCaptor<ModerationRequiredEvent> eventCaptor =
        ArgumentCaptor.forClass(ModerationRequiredEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().reviewId()).isEqualTo(55L);
  }

  @Test
  void should_throw_review_not_eligible_when_no_eligibility_found() {
    when(reviewEligibilityService.validateEligibility(11L, 1001L))
        .thenThrow(new ReviewNotEligibleException("not eligible", "REVIEW_NOT_ELIGIBLE"));

    assertThatThrownBy(() -> reviewService.submitReview(11L, request))
        .isInstanceOf(ReviewNotEligibleException.class)
        .hasMessageContaining("not eligible");
  }

  @Test
  void should_throw_review_not_eligible_when_eligibility_revoked() {
    when(reviewEligibilityService.validateEligibility(11L, 1001L))
        .thenThrow(new ReviewNotEligibleException("revoked", "REVIEW_NOT_ELIGIBLE"));

    assertThatThrownBy(() -> reviewService.submitReview(11L, request))
        .isInstanceOf(ReviewNotEligibleException.class)
        .hasMessageContaining("revoked");
  }

  @Test
  void should_throw_review_window_closed_when_eligibility_window_expired() {
    when(reviewEligibilityService.validateEligibility(11L, 1001L))
        .thenThrow(new ReviewNotEligibleException("window closed", "REVIEW_WINDOW_CLOSED"));

    assertThatThrownBy(() -> reviewService.submitReview(11L, request))
        .isInstanceOf(ReviewNotEligibleException.class)
        .hasMessageContaining("window closed");
  }

  @Test
  void should_throw_review_already_submitted_when_duplicate_review_exists() {
    when(reviewRepository.existsByUserIdAndEventId(11L, 1001L)).thenReturn(true);

    assertThatThrownBy(() -> reviewService.submitReview(11L, request))
        .isInstanceOf(ReviewAlreadySubmittedException.class);
  }

  @Test
  void should_propagate_review_not_eligible_when_booking_check_fails() {
    when(reviewRepository.existsByUserIdAndEventId(11L, 1001L)).thenReturn(false);
    when(attendanceVerificationService.verify(11L, 1001L))
        .thenThrow(new ReviewNotEligibleException("booking check failed", "REVIEW_NOT_ELIGIBLE"));

    assertThatThrownBy(() -> reviewService.submitReview(11L, request))
        .isInstanceOf(ReviewNotEligibleException.class)
        .hasMessageContaining("booking check failed");
  }

  @Test
  void should_save_self_reported_attendance_when_eb_attendee_not_found() {
    when(reviewRepository.existsByUserIdAndEventId(11L, 1001L)).thenReturn(false);
    when(attendanceVerificationService.verify(11L, 1001L))
        .thenReturn(AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED);
    when(reviewRepository.save(any(Review.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    reviewService.submitReview(11L, request);

    ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).save(reviewCaptor.capture());
    assertThat(reviewCaptor.getValue().getAttendanceVerificationStatus())
        .isEqualTo(AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED);
  }
}
