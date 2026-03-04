package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import org.junit.jupiter.api.Test;

class PaymentConfirmationReaderImplTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final PaymentConfirmationReaderImpl reader = new PaymentConfirmationReaderImpl(bookingRepository);

    @Test
    void should_return_true_when_booking_exists_with_confirmed_status() {
        when(bookingRepository.existsByCartIdAndStatus(10L, BookingStatus.CONFIRMED)).thenReturn(true);

        assertThat(reader.isPaymentConfirmed(10L)).isTrue();
    }

    @Test
    void should_return_false_when_no_confirmed_booking_exists() {
        when(bookingRepository.existsByCartIdAndStatus(11L, BookingStatus.CONFIRMED)).thenReturn(false);

        assertThat(reader.isPaymentConfirmed(null)).isFalse();
        assertThat(reader.isPaymentConfirmed(11L)).isFalse();
    }
}
