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
    void isPaymentConfirmed_returnsTrue_whenBookingIsConfirmed() {
        when(bookingRepository.existsByCartIdAndStatus(10L, BookingStatus.CONFIRMED)).thenReturn(true);

        assertThat(reader.isPaymentConfirmed(10L)).isTrue();
    }

    @Test
    void isPaymentConfirmed_returnsFalse_whenCartIdNullOrNotConfirmed() {
        when(bookingRepository.existsByCartIdAndStatus(11L, BookingStatus.CONFIRMED)).thenReturn(false);

        assertThat(reader.isPaymentConfirmed(null)).isFalse();
        assertThat(reader.isPaymentConfirmed(11L)).isFalse();
    }
}
