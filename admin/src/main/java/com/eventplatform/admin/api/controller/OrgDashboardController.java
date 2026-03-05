package com.eventplatform.admin.api.controller;

import com.eventplatform.admin.service.OrgDashboardService;
import com.eventplatform.admin.service.OrgDashboardService.SlotFlaggedResponse;
import com.eventplatform.admin.service.client.AdminVenueResponse;
import com.eventplatform.shared.security.Roles;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class OrgDashboardController {

  private final OrgDashboardService dashboardService;

  public OrgDashboardController(OrgDashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping("/scheduling/slots/flagged")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public SlotFlaggedResponse flaggedSlots(@RequestParam("orgId") Long orgId) {
    return dashboardService.getFlaggedSlots(orgId);
  }

  @GetMapping("/dashboard/venues/flagged")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public List<AdminVenueResponse> flaggedVenues(@RequestParam("orgId") Long orgId) {
    return dashboardService.getFlaggedVenues(orgId);
  }
}
