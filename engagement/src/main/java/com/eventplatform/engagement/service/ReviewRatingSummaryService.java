package com.eventplatform.engagement.service;

import com.eventplatform.engagement.api.dto.response.ReviewSummaryResponse;
import com.eventplatform.engagement.repository.ReviewRepository;
import com.eventplatform.engagement.service.model.ReviewRatingSummary;
import com.eventplatform.shared.common.event.published.ReviewPublishedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class ReviewRatingSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ReviewRatingSummaryService.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String CACHE_KEY_PREFIX = "engagement:review:summary:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ReviewRepository reviewRepository;

    public ReviewRatingSummaryService(RedisTemplate<String, Object> redisTemplate, ReviewRepository reviewRepository) {
        this.redisTemplate = redisTemplate;
        this.reviewRepository = reviewRepository;
    }

    public ReviewSummaryResponse getSummary(Long eventId) {
        String key = CACHE_KEY_PREFIX + eventId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof ReviewRatingSummary summary) {
                return toResponse(summary);
            }
        } catch (Exception ex) {
            log.warn("Failed to read review summary cache. eventId={}", eventId, ex);
        }

        ReviewRatingSummary computed = computeSummary(eventId);
        try {
            redisTemplate.opsForValue().set(key, computed, TTL);
        } catch (Exception ex) {
            log.warn("Failed to write review summary cache. eventId={}", eventId, ex);
        }
        return toResponse(computed);
    }

    @TransactionalEventListener
    public void onReviewPublished(ReviewPublishedEvent event) {
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + event.eventId());
        } catch (Exception ex) {
            log.warn("Failed to invalidate review summary cache. eventId={}", event.eventId(), ex);
        }
    }

    private ReviewRatingSummary computeSummary(Long eventId) {
        Object[] row = reviewRepository.summarizeByEventId(eventId);
        BigDecimal average = asBigDecimal(row[0]).setScale(1, RoundingMode.HALF_UP);
        long total = asLong(row[1]);
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        distribution.put(1, asLong(row[2]));
        distribution.put(2, asLong(row[3]));
        distribution.put(3, asLong(row[4]));
        distribution.put(4, asLong(row[5]));
        distribution.put(5, asLong(row[6]));
        return new ReviewRatingSummary(eventId, average, total, distribution, Instant.now());
    }

    private ReviewSummaryResponse toResponse(ReviewRatingSummary summary) {
        return new ReviewSummaryResponse(
            summary.eventId(),
            summary.averageRating(),
            summary.totalReviews(),
            summary.distribution(),
            summary.cachedAt()
        );
    }

    private long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }
}
