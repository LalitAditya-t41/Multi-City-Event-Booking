package com.eventplatform.engagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.engagement.repository.ReviewRepository;
import com.eventplatform.engagement.service.model.ReviewRatingSummary;
import com.eventplatform.shared.common.event.published.ReviewPublishedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ReviewRatingSummaryServiceTest {

  @Mock private RedisTemplate<String, Object> redisTemplate;
  @Mock private ValueOperations<String, Object> valueOperations;
  @Mock private ReviewRepository reviewRepository;

  @InjectMocks private ReviewRatingSummaryService service;

  @Test
  void should_return_summary_from_cache_when_cache_hit() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    ReviewRatingSummary cached =
        new ReviewRatingSummary(1001L, new BigDecimal("4.2"), 10L, Map.of(1, 1L), Instant.now());
    when(valueOperations.get("engagement:review:summary:1001")).thenReturn(cached);

    var response = service.getSummary(1001L);

    assertThat(response.averageRating()).isEqualTo(new BigDecimal("4.2"));
    verify(reviewRepository, never()).summarizeByEventId(any());
  }

  @Test
  void should_compute_and_cache_summary_when_cache_miss() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("engagement:review:summary:1001")).thenReturn(null);
    when(reviewRepository.summarizeByEventId(1001L))
        .thenReturn(new Object[] {new BigDecimal("4.25"), 20L, 1L, 2L, 3L, 4L, 10L});

    var response = service.getSummary(1001L);

    assertThat(response.averageRating()).isEqualTo(new BigDecimal("4.3"));
    verify(valueOperations)
        .set(
            eq("engagement:review:summary:1001"),
            any(ReviewRatingSummary.class),
            eq(Duration.ofMinutes(5)));
  }

  @Test
  void should_fallback_to_db_when_redis_get_throws() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("engagement:review:summary:1001"))
        .thenThrow(new RuntimeException("redis down"));
    when(reviewRepository.summarizeByEventId(1001L))
        .thenReturn(new Object[] {new BigDecimal("4.0"), 5L, 1L, 1L, 1L, 1L, 1L});

    var response = service.getSummary(1001L);

    assertThat(response.totalReviews()).isEqualTo(5L);
    verify(reviewRepository).summarizeByEventId(1001L);
  }

  @Test
  void should_return_result_when_redis_set_throws() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("engagement:review:summary:1001")).thenReturn(null);
    when(reviewRepository.summarizeByEventId(1001L))
        .thenReturn(new Object[] {new BigDecimal("4.0"), 5L, 1L, 1L, 1L, 1L, 1L});
    doThrow(new RuntimeException("set fail"))
        .when(valueOperations)
        .set(
            eq("engagement:review:summary:1001"),
            any(ReviewRatingSummary.class),
            eq(Duration.ofMinutes(5)));

    var response = service.getSummary(1001L);

    assertThat(response.totalReviews()).isEqualTo(5L);
  }

  @Test
  void should_invalidate_cache_when_review_published_event_received() {
    service.onReviewPublished(new ReviewPublishedEvent(1L, 1001L, 4));

    verify(redisTemplate).delete("engagement:review:summary:1001");
  }
}
