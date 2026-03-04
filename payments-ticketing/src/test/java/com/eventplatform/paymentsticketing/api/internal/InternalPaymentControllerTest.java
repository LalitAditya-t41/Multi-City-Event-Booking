package com.eventplatform.paymentsticketing.api.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.Payment;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.PaymentRepository;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.JwtTokenProvider;
import com.eventplatform.shared.security.SecurityConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalPaymentController.class)
@ContextConfiguration(classes = com.eventplatform.paymentsticketing.PaymentsTicketingTestApplication.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, InternalPaymentControllerTest.MockBeans.class})
class InternalPaymentControllerTest {

    @TestConfiguration
    static class MockBeans {
        @Bean
        BookingRepository bookingRepository() {
            return mock(BookingRepository.class);
        }

        @Bean
        PaymentRepository paymentRepository() {
            return mock(PaymentRepository.class);
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            return new JwtAuthenticationFilter(jwtTokenProvider);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void should_return_payment_status_SUCCEEDED_when_payment_confirmed() throws Exception {
        Booking booking = new Booking("B-001", 100L, 1L, 10L, 20L, 50000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 50L);

        Payment payment = new Payment(50L, "pi_test_abc", 50000L, "inr");
        payment.markSucceeded("ch_test_abc");

        when(bookingRepository.findByCartId(100L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(50L)).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/internal/payments/by-cart/100/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void should_return_404_when_no_booking_found_for_cart() throws Exception {
        when(bookingRepository.findByCartId(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/payments/by-cart/999/status"))
            .andExpect(status().isNotFound());
    }

    @Test
    void should_return_404_when_booking_exists_but_no_payment() throws Exception {
        Booking booking = new Booking("B-002", 200L, 2L, 11L, 21L, 30000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 60L);

        when(bookingRepository.findByCartId(200L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(60L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/payments/by-cart/200/status"))
            .andExpect(status().isNotFound());
    }

    @Test
    void should_return_PENDING_status_when_payment_not_yet_confirmed() throws Exception {
        Booking booking = new Booking("B-003", 300L, 3L, 12L, 22L, 40000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 70L);

        Payment payment = new Payment(70L, "pi_pending_xyz", 40000L, "inr");

        when(bookingRepository.findByCartId(300L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(70L)).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/internal/payments/by-cart/300/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
