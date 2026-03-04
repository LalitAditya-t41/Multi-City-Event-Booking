package com.eventplatform.engagement.service;

import com.eventplatform.engagement.api.dto.request.ReviewSubmitRequest;
import com.eventplatform.engagement.api.dto.response.ReviewResponse;
import com.eventplatform.engagement.api.dto.response.ReviewSubmitResponse;
import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.event.published.ModerationRequiredEvent;
import com.eventplatform.engagement.exception.InvalidRatingException;
import com.eventplatform.engagement.exception.ReviewAlreadySubmittedException;
import com.eventplatform.engagement.mapper.ReviewMapper;
import com.eventplatform.engagement.repository.ReviewRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewEligibilityService reviewEligibilityService;
    private final AttendanceVerificationService attendanceVerificationService;
    private final ReviewMapper reviewMapper;
    private final IdentityLookupService identityLookupService;
    private final EventMetadataLookupService eventMetadataLookupService;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewService(
        ReviewRepository reviewRepository,
        ReviewEligibilityService reviewEligibilityService,
        AttendanceVerificationService attendanceVerificationService,
        ReviewMapper reviewMapper,
        IdentityLookupService identityLookupService,
        EventMetadataLookupService eventMetadataLookupService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.reviewRepository = reviewRepository;
        this.reviewEligibilityService = reviewEligibilityService;
        this.attendanceVerificationService = attendanceVerificationService;
        this.reviewMapper = reviewMapper;
        this.identityLookupService = identityLookupService;
        this.eventMetadataLookupService = eventMetadataLookupService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ReviewSubmitResponse submitReview(Long userId, ReviewSubmitRequest request) {
        if (request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new InvalidRatingException();
        }

        reviewEligibilityService.validateEligibility(userId, request.eventId());

        if (reviewRepository.existsByUserIdAndEventId(userId, request.eventId())) {
            throw new ReviewAlreadySubmittedException();
        }

        AttendanceVerificationStatus attendanceStatus = attendanceVerificationService.verify(userId, request.eventId());

        Review review = new Review(
            userId,
            request.eventId(),
            request.rating(),
            request.title(),
            request.body(),
            attendanceStatus
        );
        review.markPendingModeration();
        Review saved = reviewRepository.save(review);

        eventPublisher.publishEvent(new ModerationRequiredEvent(saved.getId()));
        return new ReviewSubmitResponse(saved.getId(), saved.getStatus(), "Your review has been submitted and is under moderation.");
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> listPublishedReviews(Long eventId, Pageable pageable) {
        eventMetadataLookupService.getRequiredEventMetadata(eventId);
        Page<Review> page = reviewRepository.findByEventIdAndStatusOrderBySubmittedAtDesc(eventId, ReviewStatus.PUBLISHED, pageable);
        return page.map(review -> reviewMapper.toReviewResponse(review, identityLookupService.getDisplayName(review.getUserId())));
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> listMyReviews(Long userId, Pageable pageable) {
        return reviewRepository.findByUserIdOrderBySubmittedAtDesc(userId, pageable)
            .map(review -> reviewMapper.toReviewResponse(review, identityLookupService.getDisplayName(review.getUserId())));
    }
}
