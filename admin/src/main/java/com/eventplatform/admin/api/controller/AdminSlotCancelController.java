package com.eventplatform.admin.api.controller;

import com.eventplatform.admin.service.client.SchedulingAdminClient;
import com.eventplatform.admin.service.client.SchedulingSlotCancelResponse;
import com.eventplatform.shared.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/slots")
public class AdminSlotCancelController {

  private final SchedulingAdminClient schedulingAdminClient;

  public AdminSlotCancelController(SchedulingAdminClient schedulingAdminClient) {
    this.schedulingAdminClient = schedulingAdminClient;
  }

  @PostMapping("/{slotId}/cancel")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public SchedulingSlotCancelResponse cancelSlot(
      @PathVariable Long slotId, @RequestParam("orgId") Long orgId) {
    return schedulingAdminClient.cancelSlot(orgId, slotId);
  }
}
