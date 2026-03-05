package com.eventplatform.engagement.api.controller;

import com.eventplatform.engagement.api.dto.request.ReviewSubmitRequest;
import com.eventplatform.engagement.api.dto.response.ReviewResponse;
import com.eventplatform.engagement.api.dto.response.ReviewSubmitResponse;
import com.eventplatform.engagement.api.dto.response.ReviewSummaryResponse;
import com.eventplatform.engagement.service.ReviewRatingSummaryService;
import com.eventplatform.engagement.service.ReviewService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/engagement/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewRatingSummaryService reviewRatingSummaryService;

    public ReviewController(ReviewService reviewService, ReviewRatingSummaryService reviewRatingSummaryService) {
        this.reviewService = reviewService;
        this.reviewRatingSummaryService = reviewRatingSummaryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public ReviewSubmitResponse submit(Authentication authentication, @Valid @RequestBody ReviewSubmitRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return reviewService.submitReview(user.userId(), request);
    }

    @GetMapping("/events/{eventId}")
    public Page<ReviewResponse> listPublished(
        @PathVariable Long eventId,
        @PageableDefault(size = 20, sort = "publishedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return reviewService.listPublishedReviews(eventId, pageable);
    }

    @GetMapping("/events/{eventId}/summary")
    public ReviewSummaryResponse summary(@PathVariable Long eventId) {
        return reviewRatingSummaryService.getSummary(eventId);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public Page<ReviewResponse> myReviews(
        Authentication authentication,
        @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return reviewService.listMyReviews(user.userId(), pageable);
    }
}
