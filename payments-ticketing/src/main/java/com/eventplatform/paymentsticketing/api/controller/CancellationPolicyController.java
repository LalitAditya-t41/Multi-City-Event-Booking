package com.eventplatform.paymentsticketing.api.controller;

import com.eventplatform.paymentsticketing.api.dto.request.CancellationPolicyRequest;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationPolicyResponse;
import com.eventplatform.paymentsticketing.service.CancellationPolicyService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/cancellation-policies")
public class CancellationPolicyController {

  private final CancellationPolicyService cancellationPolicyService;

  public CancellationPolicyController(CancellationPolicyService cancellationPolicyService) {
    this.cancellationPolicyService = cancellationPolicyService;
  }

  @PostMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CancellationPolicyResponse create(
      Authentication authentication, @Valid @RequestBody CancellationPolicyRequest request) {
    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
    return cancellationPolicyService.createPolicy(user.userId(), request);
  }

  @PutMapping("/{policyId}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CancellationPolicyResponse update(
      @PathVariable Long policyId, @Valid @RequestBody CancellationPolicyRequest request) {
    return cancellationPolicyService.updatePolicy(policyId, request);
  }

  @GetMapping("/{policyId}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CancellationPolicyResponse getById(@PathVariable Long policyId) {
    return cancellationPolicyService.getPolicy(policyId);
  }

  @GetMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public CancellationPolicyResponse getEffective(@RequestParam("orgId") Long orgId) {
    return cancellationPolicyService.getEffectivePolicy(orgId);
  }
}
