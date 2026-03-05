package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalBookingQueryService {

    private final BookingRepository bookingRepository;

    public InternalBookingQueryService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public InternalBookingResponse getByUserEvent(Long userId, Long eventId) {
        Booking booking = bookingRepository.findTopByUserIdAndEventIdOrderByCreatedAtDesc(userId, eventId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Booking not found for userId=" + userId + " and eventId=" + eventId,
                "BOOKING_NOT_FOUND"
            ));
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public InternalBookingResponse getByBookingId(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));
        return toResponse(booking);
    }

    private InternalBookingResponse toResponse(Booking booking) {
        return new InternalBookingResponse(
            booking.getId(),
            booking.getUserId(),
            booking.getEventId(),
            booking.getSlotId(),
            booking.getOrgId(),
            booking.getStatus().name()
        );
    }

    public record InternalBookingResponse(
        Long bookingId,
        Long userId,
        Long eventId,
        Long slotId,
        Long orgId,
        String status
    ) {
    }
}
