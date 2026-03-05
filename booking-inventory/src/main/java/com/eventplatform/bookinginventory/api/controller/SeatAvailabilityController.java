package com.eventplatform.bookinginventory.api.controller;

import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatsEnvelopeResponse;
import com.eventplatform.bookinginventory.service.SeatAvailabilityService;
import com.eventplatform.shared.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/booking/shows")
public class SeatAvailabilityController {

  private final SeatAvailabilityService seatAvailabilityService;

  public SeatAvailabilityController(SeatAvailabilityService seatAvailabilityService) {
    this.seatAvailabilityService = seatAvailabilityService;
  }

  @GetMapping("/{slotId}/available-seats")
  @PreAuthorize("hasRole('" + Roles.USER + "')")
  public AvailableSeatsEnvelopeResponse getAvailableSeats(@PathVariable Long slotId) {
    return seatAvailabilityService.getAvailability(slotId);
  }
}
