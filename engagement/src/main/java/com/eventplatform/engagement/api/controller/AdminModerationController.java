package com.eventplatform.engagement.api.controller;

import com.eventplatform.engagement.api.dto.request.ModerationDecisionRequest;
import com.eventplatform.engagement.api.dto.response.AdminReviewResponse;
import com.eventplatform.engagement.api.dto.response.ModerationResponse;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.service.ModerationService;
import com.eventplatform.engagement.service.model.ManualModerationDecision;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/engagement/reviews")
public class AdminModerationController {

    private final ModerationService moderationService;

    public AdminModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public Page<AdminReviewResponse> listPending(
        @RequestParam(name = "status", defaultValue = "PENDING_MODERATION") ReviewStatus status,
        @RequestParam(name = "eventId", required = false) Long eventId,
        @RequestParam(name = "submittedAfter", required = false) Instant submittedAfter,
        @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return moderationService.listAdminQueue(status, eventId, submittedAfter, pageable);
    }

    @PutMapping("/{reviewId}/moderate")
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public ModerationResponse moderate(
        Authentication authentication,
        @PathVariable Long reviewId,
        @Valid @RequestBody ModerationDecisionRequest request
    ) {
        if (request.decision() == ManualModerationDecision.REJECT
            && (request.reason() == null || request.reason().isBlank())) {
            throw new ValidationException("reason is required for reject decision", "VALIDATION_ERROR");
        }
        AuthenticatedUser admin = (AuthenticatedUser) authentication.getPrincipal();
        return moderationService.applyManualDecision(admin.userId(), reviewId, request.decision(), request.reason());
    }
}
