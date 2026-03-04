package com.eventplatform.paymentsticketing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.paymentsticketing.api.controller.BookingController;
import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.BookingSummaryResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CancelItemsResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationQuoteResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationResponse;
import com.eventplatform.paymentsticketing.api.dto.response.RefundResponse;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.exception.CancellationNotAllowedException;
import com.eventplatform.paymentsticketing.exception.DuplicateItemCancellationException;
import com.eventplatform.paymentsticketing.exception.InvalidCancelItemsRequestException;
import com.eventplatform.paymentsticketing.service.BookingService;
import com.eventplatform.paymentsticketing.service.CancellationService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = BookingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;
    @MockBean
    private CancellationService cancellationService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUpFilterPassThrough() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void should_return_200_with_paginated_bookings_for_authenticated_user() throws Exception {
        when(bookingService.listBookings(any(), any())).thenReturn(new PageImpl<>(
            List.of(new BookingSummaryResponse("BK-20260304-001", 21L, BookingStatus.CONFIRMED, 150000L, "inr", Instant.parse("2026-03-04T10:00:00Z"))),
            PageRequest.of(0, 10),
            1
        ));

        mockMvc.perform(get("/api/v1/bookings").with(authentication(userAuthentication())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].bookingRef").value("BK-20260304-001"));
    }

    @Test
    void should_return_200_with_booking_detail_for_owner() throws Exception {
        when(bookingService.getBooking(1L, "BK-20260304-001")).thenReturn(new BookingResponse(
            "BK-20260304-001",
            BookingStatus.CONFIRMED,
            21L,
            150000L,
            "inr",
            "pi_123",
            List.of(),
            Instant.parse("2026-03-04T10:00:00Z")
        ));

        mockMvc.perform(get("/api/v1/bookings/BK-20260304-001").with(authentication(userAuthentication())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bookingRef").value("BK-20260304-001"));
    }

    @Test
    void should_return_200_when_booking_cancel_is_successful() throws Exception {
        when(cancellationService.cancel(1L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER))
            .thenReturn(new CancellationResponse(
                "BK-20260304-001",
                BookingStatus.CANCELLED,
                new RefundResponse("re_123", 150000L, "inr", RefundStatus.SUCCEEDED)
            ));

        mockMvc.perform(post("/api/v1/bookings/BK-20260304-001/cancel")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void should_return_200_with_quote_when_cancellation_quote_requested_for_owned_booking() throws Exception {
        when(cancellationService.computeQuote(1L, "BK-20260304-001", List.of(101L, 102L)))
            .thenReturn(new CancellationQuoteResponse("BK-20260304-001", "GE_24_HOURS", 50, 150000L, 75000L, "inr"));

        mockMvc.perform(get("/api/v1/bookings/BK-20260304-001/cancellation-quote")
                .with(authentication(userAuthentication()))
                .queryParam("bookingItemIds", "101", "102"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refundPercent").value(50))
            .andExpect(jsonPath("$.refundAmount").value(75000));
    }

    @Test
    void should_return_404_when_cancellation_quote_requested_for_non_owned_booking() throws Exception {
        when(cancellationService.computeQuote(1L, "BK-UNKNOWN", List.of(101L)))
            .thenThrow(new BookingNotFoundException("BK-UNKNOWN"));

        mockMvc.perform(get("/api/v1/bookings/BK-UNKNOWN/cancellation-quote")
                .with(authentication(userAuthentication()))
                .queryParam("bookingItemIds", "101"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void should_return_200_when_cancel_items_is_successful() throws Exception {
        when(cancellationService.cancelItems(1L, "BK-20260304-001", List.of(101L, 102L), RefundReason.REQUESTED_BY_CUSTOMER))
            .thenReturn(new CancelItemsResponse(
                "BK-20260304-001",
                BookingStatus.CONFIRMED,
                List.of(101L, 102L),
                new CancelItemsResponse.ItemCancellationRefundResponse("BUYER_PARTIAL", 50, 75000L, "inr", "PENDING")
            ));

        mockMvc.perform(post("/api/v1/bookings/BK-20260304-001/cancel-items")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"bookingItemIds\":[101,102],\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.refund.amount").value(75000));
    }

    @Test
    void should_return_400_when_cancel_items_payload_is_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/BK-20260304-001/cancel-items")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"bookingItemIds\":[],\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_409_when_cancel_items_not_allowed() throws Exception {
        when(cancellationService.cancelItems(1L, "BK-20260304-001", List.of(101L), RefundReason.REQUESTED_BY_CUSTOMER))
            .thenThrow(new CancellationNotAllowedException("Booking is not in CONFIRMED state", Map.of("currentStatus", "PENDING")));

        mockMvc.perform(post("/api/v1/bookings/BK-20260304-001/cancel-items")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"bookingItemIds\":[101],\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("CANCELLATION_NOT_ALLOWED"));
    }

    @Test
    void should_return_409_when_cancel_items_request_is_duplicate() throws Exception {
        when(cancellationService.cancelItems(1L, "BK-20260304-001", List.of(101L), RefundReason.REQUESTED_BY_CUSTOMER))
            .thenThrow(new DuplicateItemCancellationException(101L));

        mockMvc.perform(post("/api/v1/bookings/BK-20260304-001/cancel-items")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"bookingItemIds\":[101],\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ITEM_CANCELLATION"));
    }

    @Test
    void should_return_400_when_cancel_items_request_has_invalid_item_ids() throws Exception {
        when(cancellationService.cancelItems(1L, "BK-20260304-001", List.of(999L), RefundReason.REQUESTED_BY_CUSTOMER))
            .thenThrow(new InvalidCancelItemsRequestException("All bookingItemIds must belong to the booking and be ACTIVE"));

        mockMvc.perform(post("/api/v1/bookings/BK-20260304-001/cancel-items")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"bookingItemIds\":[999],\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_CANCEL_ITEMS_REQUEST"));
    }

    @Test
    void should_return_401_for_bookings_list_without_jwt() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
            .andExpect(status().isUnauthorized());
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(1L, "USER", null, null),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
