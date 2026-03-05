package com.eventplatform.discoverycatalog.api.controller;

import com.eventplatform.discoverycatalog.api.dto.request.CreateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.request.UpdateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.response.PaginationInfo;
import com.eventplatform.discoverycatalog.api.dto.response.VenueListResponse;
import com.eventplatform.discoverycatalog.api.dto.response.VenueResponse;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.service.VenueServiceI;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalog")
public class VenueAdminController {

  private final VenueServiceI venueService;
  private final VenueMapper venueMapper;

  public VenueAdminController(VenueServiceI venueService, VenueMapper venueMapper) {
    this.venueService = venueService;
    this.venueMapper = venueMapper;
  }

  @PostMapping("/venues")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<VenueResponse> createVenue(
      @RequestParam("orgId") Long organizationId, @Valid @RequestBody CreateVenueRequest request) {
    Venue venue = venueService.createVenue(organizationId, request);
    VenueResponse response = venueMapper.toResponse(venue);
    HttpStatus status =
        venue.getSyncStatus()
                == com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus.PENDING_SYNC
            ? HttpStatus.ACCEPTED
            : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(response);
  }

  @PutMapping("/venues/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public VenueResponse updateVenue(
      @RequestParam("orgId") Long organizationId,
      @PathVariable("id") Long venueId,
      @Valid @RequestBody UpdateVenueRequest request) {
    Venue venue = venueService.updateVenue(organizationId, venueId, request);
    return venueMapper.toResponse(venue);
  }

  @PostMapping("/venues/{id}/sync")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public VenueResponse syncVenue(
      @RequestParam("orgId") Long organizationId, @PathVariable("id") Long venueId) {
    Venue venue = venueService.syncVenue(organizationId, venueId);
    return venueMapper.toResponse(venue);
  }

  @GetMapping("/venues/flagged")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public VenueListResponse listFlaggedVenues(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    Page<Venue> venues = venueService.listFlaggedVenues(page, size);
    PaginationInfo pagination =
        new PaginationInfo(page, size, venues.getTotalElements(), venues.getTotalPages());
    return new VenueListResponse(venues.map(venueMapper::toResponse).getContent(), pagination);
  }
}
