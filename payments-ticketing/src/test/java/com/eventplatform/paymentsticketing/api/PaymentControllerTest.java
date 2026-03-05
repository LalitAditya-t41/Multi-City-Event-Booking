package com.eventplatform.paymentsticketing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.paymentsticketing.api.controller.PaymentController;
import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CheckoutInitResponse;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.exception.DuplicatePaymentException;
import com.eventplatform.paymentsticketing.exception.PaymentIntentNotFoundException;
import com.eventplatform.paymentsticketing.exception.PaymentNotConfirmedException;
import com.eventplatform.paymentsticketing.service.PaymentService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PaymentControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PaymentService paymentService;

  @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

  @BeforeEach
  void setUpFilterPassThrough() throws Exception {
    doAnswer(
            invocation -> {
              jakarta.servlet.FilterChain chain = invocation.getArgument(2);
              chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(jwtAuthenticationFilter)
        .doFilter(any(), any(), any());
  }

  @Test
  void should_return_200_with_client_secret_when_checkout_exists() throws Exception {
    when(paymentService.getCheckout(42L, 1L))
        .thenReturn(
            new CheckoutInitResponse(
                42L, "BK-20260304-001", "pi_123", "secret_123", 150000L, "inr"));

    mockMvc
        .perform(get("/api/v1/payments/checkout/42").with(authentication(userAuthentication())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntentId").value("pi_123"))
        .andExpect(jsonPath("$.clientSecret").value("secret_123"));
  }

  @Test
  void should_return_404_when_checkout_cart_unknown() throws Exception {
    when(paymentService.getCheckout(42L, 1L)).thenThrow(new BookingNotFoundException("cart-42"));

    mockMvc
        .perform(get("/api/v1/payments/checkout/42").with(authentication(userAuthentication())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
  }

  @Test
  void should_return_409_when_payment_already_exists() throws Exception {
    when(paymentService.getCheckout(42L, 1L))
        .thenThrow(new DuplicatePaymentException("BK-20260304-001"));

    mockMvc
        .perform(get("/api/v1/payments/checkout/42").with(authentication(userAuthentication())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("PAYMENT_ALREADY_EXISTS"));
  }

  @Test
  void should_return_200_when_confirm_succeeds() throws Exception {
    when(paymentService.confirmPayment(1L, "pi_123"))
        .thenReturn(
            new BookingResponse(
                "BK-20260304-001",
                BookingStatus.CONFIRMED,
                21L,
                150000L,
                "inr",
                "pi_123",
                List.of(),
                Instant.parse("2026-03-04T10:00:00Z")));

    mockMvc
        .perform(
            post("/api/v1/payments/confirm")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"paymentIntentId\":\"pi_123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void should_return_402_when_confirm_requires_action() throws Exception {
    when(paymentService.confirmPayment(1L, "pi_123"))
        .thenThrow(new PaymentNotConfirmedException("requires_action"));

    mockMvc
        .perform(
            post("/api/v1/payments/confirm")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"paymentIntentId\":\"pi_123\"}"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_CONFIRMED"));
  }

  @Test
  void should_return_409_when_confirm_called_for_already_confirmed_booking() throws Exception {
    when(paymentService.confirmPayment(1L, "pi_123"))
        .thenThrow(new DuplicatePaymentException("BK-20260304-001"));

    mockMvc
        .perform(
            post("/api/v1/payments/confirm")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"paymentIntentId\":\"pi_123\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("PAYMENT_ALREADY_EXISTS"));
  }

  @Test
  void should_return_200_when_failure_reported_by_frontend() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/failed")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"paymentIntentId\":\"pi_123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Seat locks released. Cart cleared."));

    verify(paymentService).handleFailure("pi_123", null, "frontend_reported_failure");
  }

  @Test
  void should_return_404_when_failed_called_with_unknown_payment_intent() throws Exception {
    doThrow(new PaymentIntentNotFoundException("pi_missing"))
        .when(paymentService)
        .handleFailure("pi_missing", null, "frontend_reported_failure");

    mockMvc
        .perform(
            post("/api/v1/payments/failed")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"paymentIntentId\":\"pi_missing\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("PAYMENT_INTENT_NOT_FOUND"));
  }

  private UsernamePasswordAuthenticationToken userAuthentication() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(1L, "USER", null, null),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
