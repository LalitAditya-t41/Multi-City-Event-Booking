package com.eventplatform.scheduling.api.controller;

import com.eventplatform.scheduling.api.dto.request.CreateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.request.UpdateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.response.ShowSlotCancelResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotOccurrenceListResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotOccurrenceResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotPricingTierResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotRetryResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotSubmitResponse;
import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.ShowSlotOccurrence;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.mapper.ShowSlotMapper;
import com.eventplatform.scheduling.service.ShowSlotService;
import com.eventplatform.scheduling.service.ShowSlotUpdateResult;
import com.eventplatform.shared.common.dto.PageResponse;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scheduling/slots")
public class ShowSlotController {

  private final ShowSlotService showSlotService;
  private final ShowSlotMapper showSlotMapper;

  public ShowSlotController(ShowSlotService showSlotService, ShowSlotMapper showSlotMapper) {
    this.showSlotService = showSlotService;
    this.showSlotMapper = showSlotMapper;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShowSlotResponse createSlot(
      @RequestParam("orgId") Long organizationId,
      @Valid @RequestBody CreateShowSlotRequest request) {
    ShowSlot slot = showSlotService.createSlot(organizationId, request);
    return showSlotMapper.toResponse(slot);
  }

  @PostMapping("/{id}/submit")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShowSlotSubmitResponse submitSlot(
      @RequestParam("orgId") Long organizationId, @PathVariable("id") Long slotId) {
    ShowSlot slot = showSlotService.submitSlot(organizationId, slotId);
    return new ShowSlotSubmitResponse(
        slot.getId(),
        slot.getStatus(),
        slot.getEbEventId(),
        slot.getSyncAttemptCount(),
        "EB sync initiated");
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShowSlotResponse getSlot(@PathVariable("id") Long slotId) {
    return showSlotMapper.toResponse(showSlotService.getSlot(slotId));
  }

  @GetMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public PageResponse<ShowSlotResponse> listSlots(
      @RequestParam("orgId") Long organizationId,
      @RequestParam(name = "status", required = false) ShowSlotStatus status,
      @RequestParam(name = "venueId", required = false) Long venueId,
      @RequestParam(name = "cityId", required = false) Long cityId,
      @RequestParam(name = "startAfter", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          ZonedDateTime startAfter,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    Page<ShowSlot> slots =
        showSlotService.listSlots(organizationId, status, venueId, cityId, startAfter, page, size);
    List<ShowSlotResponse> responses = slots.map(showSlotMapper::toResponse).getContent();
    return new PageResponse<>(responses, slots.getTotalElements(), page, size);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<ShowSlotResponse> updateSlot(
      @RequestParam("orgId") Long organizationId,
      @PathVariable("id") Long slotId,
      @Valid @RequestBody UpdateShowSlotRequest request) {
    ShowSlotUpdateResult result = showSlotService.updateSlot(organizationId, slotId, request);
    ShowSlotResponse response = showSlotMapper.toResponse(result.slot());
    if (result.ebSyncFailed()) {
      return ResponseEntity.status(207).body(response);
    }
    return ResponseEntity.ok(response);
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShowSlotCancelResponse cancelSlot(
      @RequestParam("orgId") Long organizationId, @PathVariable("id") Long slotId) {
    boolean ebCancelled = showSlotService.cancelSlot(organizationId, slotId);
    ShowSlot slot = showSlotService.getSlot(slotId);
    return new ShowSlotCancelResponse(
        slot.getId(), slot.getStatus(), ebCancelled, "Slot cancelled");
  }

  @PostMapping("/{id}/retry-sync")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShowSlotRetryResponse retrySync(
      @RequestParam("orgId") Long organizationId, @PathVariable("id") Long slotId) {
    showSlotService.retrySync(organizationId, slotId);
    return new ShowSlotRetryResponse(ShowSlotStatus.PENDING_SYNC, "Retry initiated");
  }

  @GetMapping("/{id}/pricing-tiers")
  @PreAuthorize("isAuthenticated()")
  public List<ShowSlotPricingTierResponse> getPricingTiers(@PathVariable("id") Long slotId) {
    return showSlotService.getPricingTiers(slotId).stream()
        .map(showSlotMapper::toPricingTierResponse)
        .toList();
  }

  @GetMapping("/{id}/occurrences")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ShowSlotOccurrenceListResponse listOccurrences(@PathVariable("id") Long slotId) {
    List<ShowSlotOccurrence> occurrences = showSlotService.listOccurrences(slotId);
    List<ShowSlotOccurrenceResponse> responseList =
        occurrences.stream().map(showSlotMapper::toOccurrenceResponse).toList();
    return new ShowSlotOccurrenceListResponse(slotId, responseList);
  }

  @GetMapping("/mismatches")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public PageResponse<ShowSlotResponse> listMismatches(
      @RequestParam("orgId") Long organizationId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    Page<ShowSlot> slots = showSlotService.listMismatches(organizationId, page, size);
    List<ShowSlotResponse> responses = slots.map(showSlotMapper::toResponse).getContent();
    return new PageResponse<>(responses, slots.getTotalElements(), page, size);
  }
}
