package com.eventplatform.admin.service;

import com.eventplatform.admin.service.client.AdminVenueResponse;
import com.eventplatform.admin.service.client.CatalogAdminClient;
import com.eventplatform.admin.service.client.SchedulingAdminClient;
import com.eventplatform.admin.service.client.SchedulingSlotResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrgDashboardService {

    private final SchedulingAdminClient schedulingAdminClient;
    private final CatalogAdminClient catalogAdminClient;

    public OrgDashboardService(
        SchedulingAdminClient schedulingAdminClient,
        CatalogAdminClient catalogAdminClient
    ) {
        this.schedulingAdminClient = schedulingAdminClient;
        this.catalogAdminClient = catalogAdminClient;
    }

    public SlotFlaggedResponse getFlaggedSlots(Long orgId) {
        List<SchedulingSlotResponse> pending = schedulingAdminClient.getPendingSyncSlots(orgId).stream()
            .filter(slot -> slot.syncAttemptCount() > 0)
            .toList();
        List<SchedulingSlotResponse> mismatches = schedulingAdminClient.getMismatchSlots(orgId);
        return new SlotFlaggedResponse(pending, mismatches);
    }

    public List<AdminVenueResponse> getFlaggedVenues(Long orgId) {
        return catalogAdminClient.getFlaggedVenues(orgId);
    }

    public record SlotFlaggedResponse(
        List<SchedulingSlotResponse> pendingSyncFailed,
        List<SchedulingSlotResponse> activeWithEbMismatch
    ) {
    }
}
