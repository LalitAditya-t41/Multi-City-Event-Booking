package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.shared.common.service.PaymentConfirmationReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfirmationReaderImpl implements PaymentConfirmationReader {

  private final BookingRepository bookingRepository;

  public PaymentConfirmationReaderImpl(BookingRepository bookingRepository) {
    this.bookingRepository = bookingRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isPaymentConfirmed(Long cartId) {
    return cartId != null
        && bookingRepository.existsByCartIdAndStatus(cartId, BookingStatus.CONFIRMED);
  }
}
