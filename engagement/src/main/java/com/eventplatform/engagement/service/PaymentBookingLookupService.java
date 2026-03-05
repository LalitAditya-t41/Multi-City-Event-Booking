package com.eventplatform.engagement.service;

import com.eventplatform.engagement.exception.ReviewNotEligibleException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class PaymentBookingLookupService {

  private final RestClient restClient;

  public PaymentBookingLookupService(
      @Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public BookingEligibilitySnapshot getConfirmedBookingByUserEvent(Long userId, Long eventId) {
    try {
      BookingEligibilitySnapshot response =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/internal/payments/bookings/by-user-event")
                          .queryParam("userId", userId)
                          .queryParam("eventId", eventId)
                          .build())
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, res) -> {
                    throw new ReviewNotEligibleException(
                        "You are not eligible to review this event", "REVIEW_NOT_ELIGIBLE");
                  })
              .body(BookingEligibilitySnapshot.class);
      if (response == null || !"CONFIRMED".equalsIgnoreCase(response.status())) {
        throw new ReviewNotEligibleException(
            "You are not eligible to review this event", "REVIEW_NOT_ELIGIBLE");
      }
      return response;
    } catch (RestClientException ex) {
      throw new ReviewNotEligibleException(
          "You are not eligible to review this event", "REVIEW_NOT_ELIGIBLE");
    }
  }

  public BookingSnapshot getBookingById(Long bookingId) {
    try {
      return restClient
          .get()
          .uri("/internal/payments/bookings/{bookingId}", bookingId)
          .retrieve()
          .onStatus(
              HttpStatusCode::isError,
              (req, res) -> {
                throw new IllegalStateException("Booking lookup failed");
              })
          .body(BookingSnapshot.class);
    } catch (Exception ex) {
      return null;
    }
  }

  public record BookingEligibilitySnapshot(
      Long bookingId, Long userId, Long eventId, Long slotId, Long orgId, String status) {}

  public record BookingSnapshot(
      Long bookingId, Long userId, Long eventId, Long slotId, Long orgId, String status) {}
}
