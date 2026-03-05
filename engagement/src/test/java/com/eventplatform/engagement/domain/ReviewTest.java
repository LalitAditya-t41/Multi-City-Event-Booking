package com.eventplatform.engagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import org.junit.jupiter.api.Test;

class ReviewTest {

    @Test
    void should_throw_when_publish_called_from_submitted_status() {
        Review review = new Review(1L, 101L, 4, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);

        assertThatThrownBy(review::publish)
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_publish_when_status_is_approved() {
        Review review = new Review(1L, 101L, 4, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);
        review.markPendingModeration();
        review.approve();

        review.publish();

        assertThat(review.getStatus().name()).isEqualTo("PUBLISHED");
        assertThat(review.getPublishedAt()).isNotNull();
    }

    @Test
    void should_keep_rejected_as_terminal_when_setters_called() {
        Review review = new Review(1L, 101L, 4, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);
        review.markPendingModeration();
        review.reject("bad content");

        assertThatThrownBy(review::approve).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(review::publish).isInstanceOf(RuntimeException.class);
        assertThat(review.getStatus().name()).isEqualTo("REJECTED");
    }

    @Test
    void should_construct_review_when_rating_is_on_valid_boundaries() {
        Review low = new Review(1L, 101L, 1, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);
        Review high = new Review(1L, 102L, 5, "title", "body", AttendanceVerificationStatus.EB_VERIFIED);

        assertThat(low.getRating()).isEqualTo(1);
        assertThat(high.getRating()).isEqualTo(5);
    }

    @Test
    void should_throw_illegal_argument_when_rating_outside_boundaries() {
        assertThatThrownBy(() -> new Review(1L, 101L, 0, "title", "body", AttendanceVerificationStatus.EB_VERIFIED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Review(1L, 101L, 6, "title", "body", AttendanceVerificationStatus.EB_VERIFIED))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
