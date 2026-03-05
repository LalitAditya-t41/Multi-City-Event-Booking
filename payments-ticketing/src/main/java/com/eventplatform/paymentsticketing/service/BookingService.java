package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.BookingSummaryResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.mapper.BookingMapper;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

  private final BookingRepository bookingRepository;
  private final PaymentService paymentService;
  private final BookingMapper bookingMapper;

  public BookingService(
      BookingRepository bookingRepository,
      PaymentService paymentService,
      BookingMapper bookingMapper) {
    this.bookingRepository = bookingRepository;
    this.paymentService = paymentService;
    this.bookingMapper = bookingMapper;
  }

  @Transactional(readOnly = true)
  public Page<BookingSummaryResponse> listBookings(Long userId, Pageable pageable) {
    return bookingRepository
        .findByUserIdOrderByCreatedAtDesc(userId, pageable)
        .map(bookingMapper::toSummaryResponse);
  }

  @Transactional(readOnly = true)
  public BookingResponse getBooking(Long userId, String bookingRef) {
    return paymentService.getBookingResponse(userId, bookingRef);
  }

  @Transactional(readOnly = true)
  public Booking getOwnedBooking(Long userId, String bookingRef) {
    Booking booking =
        bookingRepository
            .findByBookingRef(bookingRef)
            .orElseThrow(() -> new BookingNotFoundException(bookingRef));
    if (!booking.getUserId().equals(userId)) {
      throw new BookingNotFoundException(bookingRef);
    }
    return booking;
  }
}
