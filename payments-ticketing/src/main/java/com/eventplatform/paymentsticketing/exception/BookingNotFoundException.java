package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class BookingNotFoundException extends ResourceNotFoundException {

  public BookingNotFoundException(String bookingRef) {
    super("Booking not found: " + bookingRef, "BOOKING_NOT_FOUND");
  }

  public BookingNotFoundException(Long bookingId) {
    super("Booking not found: " + bookingId, "BOOKING_NOT_FOUND");
  }
}
