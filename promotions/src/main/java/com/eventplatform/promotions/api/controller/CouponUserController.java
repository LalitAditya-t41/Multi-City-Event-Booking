package com.eventplatform.promotions.api.controller;

import com.eventplatform.promotions.api.dto.request.CouponValidateRequest;
import com.eventplatform.promotions.api.dto.response.DiscountBreakdownResponse;
import com.eventplatform.promotions.service.CouponEligibilityService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/promotions")
public class CouponUserController {

    private final CouponEligibilityService couponEligibilityService;

    public CouponUserController(CouponEligibilityService couponEligibilityService) {
        this.couponEligibilityService = couponEligibilityService;
    }

    @PostMapping("/validate")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public DiscountBreakdownResponse validate(Authentication authentication, @Valid @RequestBody CouponValidateRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return couponEligibilityService.validateAndApply(user.userId(), request);
    }

    @DeleteMapping("/cart/{cartId}/coupon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public void remove(Authentication authentication, @PathVariable Long cartId) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        couponEligibilityService.removeCouponFromCart(user.userId(), cartId);
    }
}
