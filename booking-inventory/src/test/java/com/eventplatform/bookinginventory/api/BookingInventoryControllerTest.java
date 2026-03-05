package com.eventplatform.bookinginventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.bookinginventory.api.controller.CartController;
import com.eventplatform.bookinginventory.api.controller.SeatAvailabilityController;
import com.eventplatform.bookinginventory.api.dto.request.AddSeatRequest;
import com.eventplatform.bookinginventory.api.dto.request.ConfirmCartRequest;
import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatResponse;
import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatsEnvelopeResponse;
import com.eventplatform.bookinginventory.api.dto.response.CartItemResponse;
import com.eventplatform.bookinginventory.api.dto.response.CartResponse;
import com.eventplatform.bookinginventory.api.dto.response.GaTierAvailabilityResponse;
import com.eventplatform.bookinginventory.api.dto.response.SeatAlternativesResponse;
import com.eventplatform.bookinginventory.api.dto.response.SeatSuggestionResponse;
import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.exception.SeatUnavailableException;
import com.eventplatform.bookinginventory.exception.SoftLockExpiredException;
import com.eventplatform.bookinginventory.exception.TierSoldOutException;
import com.eventplatform.bookinginventory.service.CartService;
import com.eventplatform.bookinginventory.service.SeatAvailabilityService;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.BaseException;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.JwtTokenProvider;
import com.eventplatform.shared.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CartController.class, SeatAvailabilityController.class})
@ContextConfiguration(
    classes = com.eventplatform.bookinginventory.BookingInventoryTestApplication.class)
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  BookingInventoryControllerTest.MockBeans.class
})
class BookingInventoryControllerTest {

  @TestConfiguration
  static class MockBeans {
    @Bean
    CartService cartService() {
      return mock(CartService.class);
    }

    @Bean
    SeatAvailabilityService seatAvailabilityService() {
      return mock(SeatAvailabilityService.class);
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

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private CartService cartService;

  @Autowired private SeatAvailabilityService seatAvailabilityService;

  @Test
  void should_return_200_with_seat_map_for_active_reserved_slot() throws Exception {
    when(seatAvailabilityService.getAvailability(10L))
        .thenReturn(
            new AvailableSeatsEnvelopeResponse(
                10L,
                SeatingMode.RESERVED,
                List.of(
                    new AvailableSeatResponse(
                        101L,
                        "A1",
                        "A",
                        "S1",
                        1L,
                        "VIP",
                        new Money(new BigDecimal("500.00"), "INR"),
                        SeatLockState.AVAILABLE)),
                List.of()));

    mockMvc
        .perform(get("/api/v1/booking/shows/10/available-seats").with(authentication(userAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seatingMode").value("RESERVED"))
        .andExpect(jsonPath("$.seats[0].seatId").value(101));
  }

  @Test
  void should_return_200_with_ga_tier_availability_for_active_ga_slot() throws Exception {
    when(seatAvailabilityService.getAvailability(11L))
        .thenReturn(
            new AvailableSeatsEnvelopeResponse(
                11L,
                SeatingMode.GA,
                List.of(),
                List.of(
                    new GaTierAvailabilityResponse(
                        1L, "GA", 100, 88L, new Money(new BigDecimal("300.00"), "INR"), false))));

    mockMvc
        .perform(get("/api/v1/booking/shows/11/available-seats").with(authentication(userAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seatingMode").value("GA"))
        .andExpect(jsonPath("$.tiers[0].available").value(88));
  }

  @Test
  void should_return_503_with_EB_EVENT_NOT_SYNCED_when_eb_event_id_null() throws Exception {
    when(seatAvailabilityService.getAvailability(12L))
        .thenThrow(
            new BaseException(
                "Eventbrite event id is missing for slot",
                "EB_EVENT_NOT_SYNCED",
                HttpStatus.SERVICE_UNAVAILABLE,
                Map.of("slotId", 12L)) {});

    mockMvc
        .perform(get("/api/v1/booking/shows/12/available-seats").with(authentication(userAuth())))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.errorCode").value("EB_EVENT_NOT_SYNCED"));
  }

  @Test
  void should_return_409_with_alternatives_when_seat_unavailable() throws Exception {
    when(cartService.addSeat(eq(1L), eq(44L), eq("USER"), any(AddSeatRequest.class)))
        .thenThrow(
            new SeatUnavailableException(
                200L,
                new SeatAlternativesResponse(
                    200L, List.of(new SeatSuggestionResponse(201L, "A2", "S1", 1L)), List.of())));

    mockMvc
        .perform(
            post("/api/v1/booking/cart/add-seat")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AddSeatRequest(50L, 200L, 1L, 1))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("SEAT_UNAVAILABLE"));
  }

  @Test
  void should_return_200_with_group_discount_applied_in_cart_response() throws Exception {
    when(cartService.addSeat(eq(1L), eq(44L), eq("USER"), any(AddSeatRequest.class)))
        .thenReturn(cartResponse());

    mockMvc
        .perform(
            post("/api/v1/booking/cart/add-seat")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AddSeatRequest(50L, 200L, 1L, 1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groupDiscountAmount.amount").value(100.00));
  }

  @Test
  void should_return_410_when_soft_lock_expired_at_confirm() throws Exception {
    when(cartService.confirm(eq(1L), eq(44L), any(String.class), any(ConfirmCartRequest.class)))
        .thenThrow(new SoftLockExpiredException(Set.of(201L)));

    mockMvc
        .perform(
            post("/api/v1/booking/cart/confirm")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCartRequest(500L))))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.errorCode").value("SOFT_LOCK_EXPIRED"));
  }

  @Test
  void should_return_409_when_tier_sold_out_on_eventbrite_at_confirm() throws Exception {
    when(cartService.confirm(eq(1L), eq(44L), any(String.class), any(ConfirmCartRequest.class)))
        .thenThrow(new TierSoldOutException(1L, Map.of("otherTiers", List.of())));

    mockMvc
        .perform(
            post("/api/v1/booking/cart/confirm")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCartRequest(500L))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("TIER_SOLD_OUT"));
  }

  @Test
  void should_return_503_when_eventbrite_unreachable_at_confirm() throws Exception {
    when(cartService.confirm(eq(1L), eq(44L), any(String.class), any(ConfirmCartRequest.class)))
        .thenThrow(
            new BaseException(
                "Eventbrite unavailable", "EB_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE) {});

    mockMvc
        .perform(
            post("/api/v1/booking/cart/confirm")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCartRequest(500L))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.errorCode").value("EB_UNAVAILABLE"));
  }

  @Test
  void should_return_410_when_cart_expired_at_confirm() throws Exception {
    when(cartService.confirm(eq(1L), eq(44L), any(String.class), any(ConfirmCartRequest.class)))
        .thenThrow(new BaseException("Cart expired", "CART_EXPIRED", HttpStatus.GONE) {});

    mockMvc
        .perform(
            post("/api/v1/booking/cart/confirm")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCartRequest(500L))))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.errorCode").value("CART_EXPIRED"));
  }

  @Test
  void should_return_409_with_existing_cart_id_when_duplicate_cart_attempted() throws Exception {
    when(cartService.addSeat(eq(1L), eq(44L), eq("USER"), any(AddSeatRequest.class)))
        .thenThrow(
            new BaseException(
                "Pending cart already exists",
                "CART_ALREADY_EXISTS",
                HttpStatus.CONFLICT,
                Map.of("cartId", 500L)) {});

    mockMvc
        .perform(
            post("/api/v1/booking/cart/add-seat")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AddSeatRequest(50L, 200L, 1L, 1))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("CART_ALREADY_EXISTS"));
  }

  @Test
  void should_return_401_when_no_jwt_on_any_booking_endpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/booking/cart/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCartRequest(500L))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return_422_when_invalid_state_transition_attempted() throws Exception {
    when(cartService.confirm(eq(1L), eq(44L), any(String.class), any(ConfirmCartRequest.class)))
        .thenThrow(new BusinessRuleException("Invalid seat transition", "INVALID_SEAT_TRANSITION"));

    mockMvc
        .perform(
            post("/api/v1/booking/cart/confirm")
                .with(authentication(userAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmCartRequest(500L))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errorCode").value("INVALID_SEAT_TRANSITION"));
  }

  private UsernamePasswordAuthenticationToken userAuth() {
    AuthenticatedUser user = new AuthenticatedUser(1L, "USER", 44L, "test@example.com");
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private CartResponse cartResponse() {
    return new CartResponse(
        500L,
        50L,
        CartStatus.PENDING,
        Instant.now().plusSeconds(300),
        SeatingMode.RESERVED,
        List.of(
            new CartItemResponse(
                900L,
                200L,
                "A1",
                1L,
                "VIP",
                1,
                new Money(new BigDecimal("500.00"), "INR"),
                new Money(new BigDecimal("100.00"), "INR"))),
        new Money(new BigDecimal("500.00"), "INR"),
        new Money(new BigDecimal("100.00"), "INR"),
        new Money(new BigDecimal("0.00"), "INR"),
        new Money(new BigDecimal("400.00"), "INR"));
  }
}
