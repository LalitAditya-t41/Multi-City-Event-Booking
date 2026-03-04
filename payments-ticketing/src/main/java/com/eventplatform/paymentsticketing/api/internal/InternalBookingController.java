package com.eventplatform.paymentsticketing.api.internal;

import com.eventplatform.paymentsticketing.service.InternalBookingQueryService;
import com.eventplatform.paymentsticketing.service.InternalBookingQueryService.InternalBookingResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments/bookings")
public class InternalBookingController {

    private final InternalBookingQueryService internalBookingQueryService;

    public InternalBookingController(InternalBookingQueryService internalBookingQueryService) {
        this.internalBookingQueryService = internalBookingQueryService;
    }

    @GetMapping("/by-user-event")
    public InternalBookingResponse byUserEvent(@RequestParam Long userId, @RequestParam Long eventId) {
        return internalBookingQueryService.getByUserEvent(userId, eventId);
    }

    @GetMapping("/{bookingId}")
    public InternalBookingResponse byId(@PathVariable Long bookingId) {
        return internalBookingQueryService.getByBookingId(bookingId);
    }
}
