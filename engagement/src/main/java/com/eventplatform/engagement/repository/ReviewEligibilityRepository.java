package com.eventplatform.engagement.repository;

import com.eventplatform.engagement.domain.ReviewEligibility;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewEligibilityRepository extends JpaRepository<ReviewEligibility, Long> {

  Optional<ReviewEligibility> findByUserIdAndEventId(Long userId, Long eventId);

  Optional<ReviewEligibility> findByBookingId(Long bookingId);
}
