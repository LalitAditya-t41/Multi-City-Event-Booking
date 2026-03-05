package com.eventplatform.promotions.api.controller;

import com.eventplatform.promotions.api.dto.request.CouponCreateRequest;
import com.eventplatform.promotions.api.dto.response.CouponResponse;
import com.eventplatform.promotions.api.dto.response.CouponUsageStatsResponse;
import com.eventplatform.promotions.api.dto.response.DiscountReconciliationLogResponse;
import com.eventplatform.promotions.service.CouponAdminService;
import com.eventplatform.promotions.service.DiscountReconciliationService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/promotions")
public class CouponAdminController {

  private final CouponAdminService couponAdminService;
  private final DiscountReconciliationService reconciliationService;

  public CouponAdminController(
      CouponAdminService couponAdminService, DiscountReconciliationService reconciliationService) {
    this.couponAdminService = couponAdminService;
    this.reconciliationService = reconciliationService;
  }

  @PostMapping("/{promotionId}/coupons")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CouponResponse create(
      Authentication authentication,
      @PathVariable Long promotionId,
      @Valid @RequestBody CouponCreateRequest request) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    return couponAdminService.createCoupon(user.orgId(), promotionId, request);
  }

  @GetMapping("/{promotionId}/coupons")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public List<CouponResponse> list(Authentication authentication, @PathVariable Long promotionId) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    return couponAdminService.listCoupons(user.orgId(), promotionId);
  }

  @GetMapping("/coupons/{couponCode}/usage")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CouponUsageStatsResponse usage(
      Authentication authentication, @PathVariable String couponCode) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    return couponAdminService.usage(user.orgId(), couponCode);
  }

  @DeleteMapping("/coupons/{couponCode}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public void deactivate(Authentication authentication, @PathVariable String couponCode) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    couponAdminService.deactivateCoupon(user.orgId(), couponCode);
  }

  @PostMapping("/coupons/{couponCode}/sync")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CouponResponse manualSync(Authentication authentication, @PathVariable String couponCode) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    return couponAdminService.manualSync(user.orgId(), couponCode);
  }

  @GetMapping("/reconciliation/latest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public DiscountReconciliationLogResponse latest(Authentication authentication) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    return reconciliationService.latestLog(user.orgId());
  }

  @PostMapping("/reconciliation/trigger")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public Map<String, String> trigger(Authentication authentication) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    reconciliationService.reconcileOrg(user.orgId());
    return Map.of("message", "Reconciliation triggered");
  }
}
