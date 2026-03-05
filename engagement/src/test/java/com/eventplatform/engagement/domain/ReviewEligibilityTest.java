package com.eventplatform.engagement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventplatform.engagement.domain.enums.ReviewEligibilityStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewEligibilityTest {

  @Test
  void should_not_unlock_when_status_is_revoked() {
    ReviewEligibility eligibility =
        new ReviewEligibility(10L, 20L, 30L, 40L, Instant.now().plusSeconds(3600));
    eligibility.revoke();

    eligibility.unlock(50L, 60L, Instant.now().plusSeconds(7200));

    assertThat(eligibility.getStatus()).isEqualTo(ReviewEligibilityStatus.REVOKED);
    assertThat(eligibility.getSlotId()).isEqualTo(30L);
    assertThat(eligibility.getBookingId()).isEqualTo(40L);
  }

  @Test
  void should_return_false_for_eligibility_when_window_expired() {
    ReviewEligibility eligibility =
        new ReviewEligibility(10L, 20L, 30L, 40L, Instant.now().minusSeconds(86400));

    boolean eligible = eligibility.isEligible(Instant.now());

    assertThat(eligible).isFalse();
  }

  @Test
  void should_return_true_for_eligibility_when_unlocked_and_window_not_expired() {
    ReviewEligibility eligibility =
        new ReviewEligibility(10L, 20L, 30L, 40L, Instant.now().plusSeconds(86400));

    boolean eligible = eligibility.isEligible(Instant.now());

    assertThat(eligible).isTrue();
  }
}
