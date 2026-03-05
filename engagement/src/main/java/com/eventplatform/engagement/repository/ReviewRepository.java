package com.eventplatform.engagement.repository;

import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

  boolean existsByUserIdAndEventId(Long userId, Long eventId);

  Optional<Review> findByUserIdAndEventId(Long userId, Long eventId);

  Page<Review> findByEventIdAndStatusOrderByPublishedAtDesc(
      Long eventId, ReviewStatus status, Pageable pageable);

  Page<Review> findByUserIdOrderBySubmittedAtDesc(Long userId, Pageable pageable);

  @Query(
      """
        select
          coalesce(avg(r.rating), 0),
          count(r),
          sum(case when r.rating = 1 then 1 else 0 end),
          sum(case when r.rating = 2 then 1 else 0 end),
          sum(case when r.rating = 3 then 1 else 0 end),
          sum(case when r.rating = 4 then 1 else 0 end),
          sum(case when r.rating = 5 then 1 else 0 end)
        from Review r
        where r.eventId = :eventId and r.status = 'PUBLISHED'
        """)
  Object[] summarizeByEventId(@Param("eventId") Long eventId);

  Page<Review> findByStatusAndEventIdAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
      ReviewStatus status, Long eventId, java.time.Instant submittedAfter, Pageable pageable);

  Page<Review> findByStatusAndEventIdOrderBySubmittedAtDesc(
      ReviewStatus status, Long eventId, Pageable pageable);

  Page<Review> findByStatusAndSubmittedAtGreaterThanEqualOrderBySubmittedAtDesc(
      ReviewStatus status, java.time.Instant submittedAfter, Pageable pageable);

  Page<Review> findByStatusOrderBySubmittedAtDesc(ReviewStatus status, Pageable pageable);
}
