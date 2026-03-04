package com.eventplatform.promotions.api.controller;

import com.eventplatform.promotions.api.dto.request.PromotionCreateRequest;
import com.eventplatform.promotions.api.dto.request.PromotionUpdateRequest;
import com.eventplatform.promotions.api.dto.response.PromotionResponse;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import com.eventplatform.promotions.service.PromotionService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionAdminController {

    private final PromotionService promotionService;

    public PromotionAdminController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public PromotionResponse create(Authentication authentication, @Valid @RequestBody PromotionCreateRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return promotionService.create(user.orgId(), request);
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public List<PromotionResponse> list(Authentication authentication, @RequestParam(required = false) PromotionStatus status) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return promotionService.list(user.orgId(), status);
    }

    @GetMapping("/{promotionId}")
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public PromotionResponse get(Authentication authentication, @PathVariable Long promotionId) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return promotionService.get(user.orgId(), promotionId);
    }

    @PatchMapping("/{promotionId}")
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public PromotionResponse update(Authentication authentication,
                                    @PathVariable Long promotionId,
                                    @RequestBody PromotionUpdateRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return promotionService.update(user.orgId(), promotionId, request);
    }

    @DeleteMapping("/{promotionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public void deactivate(Authentication authentication, @PathVariable Long promotionId) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        promotionService.deactivate(user.orgId(), promotionId);
    }
}
