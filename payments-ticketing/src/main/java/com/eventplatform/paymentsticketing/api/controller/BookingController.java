package com.eventplatform.paymentsticketing.api.controller;

import com.eventplatform.paymentsticketing.api.dto.request.CancellationRequest;
import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.BookingSummaryResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationResponse;
import com.eventplatform.paymentsticketing.service.BookingService;
import com.eventplatform.paymentsticketing.service.CancellationService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;

    public BookingController(BookingService bookingService, CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public Page<BookingSummaryResponse> listBookings(
        Authentication authentication,
        @PageableDefault(size = 10) Pageable pageable
    ) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return bookingService.listBookings(user.userId(), pageable);
    }

    @GetMapping("/{bookingRef}")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public BookingResponse getBooking(Authentication authentication, @PathVariable String bookingRef) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return bookingService.getBooking(user.userId(), bookingRef);
    }

    @PostMapping("/{bookingRef}/cancel")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public CancellationResponse cancel(
        Authentication authentication,
        @PathVariable String bookingRef,
        @Valid @RequestBody CancellationRequest request
    ) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return cancellationService.cancel(user.userId(), bookingRef, request.reason());
    }
}
