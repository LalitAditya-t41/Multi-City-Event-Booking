package com.eventplatform.engagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.exception.ReviewNotEligibleException;
import com.eventplatform.shared.eventbrite.dto.response.EbAttendeeResponse;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.service.EbAttendeeService;
import com.eventplatform.shared.eventbrite.service.EbTokenStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttendanceVerificationServiceTest {

  @Mock private PaymentBookingLookupService paymentBookingLookupService;
  @Mock private EventMetadataLookupService eventMetadataLookupService;
  @Mock private IdentityLookupService identityLookupService;
  @Mock private EbTokenStore ebTokenStore;
  @Mock private EbAttendeeService ebAttendeeService;

  @InjectMocks private AttendanceVerificationService service;

  @Test
  void should_return_eb_verified_when_booking_confirmed_and_eb_attendee_matches() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenReturn(
            new PaymentBookingLookupService.BookingEligibilitySnapshot(
                1L, 11L, 1001L, 101L, 501L, "CONFIRMED"));
    when(eventMetadataLookupService.getEventMetadataOrNull(1001L))
        .thenReturn(new EventMetadataLookupService.EventMetadata(1001L, 501L, "eb-1"));
    when(identityLookupService.getEmail(11L)).thenReturn("user@test.com");
    when(ebTokenStore.getAccessToken(501L)).thenReturn("token");
    when(ebAttendeeService.getAttendeesByEvent("token", "eb-1"))
        .thenReturn(
            List.of(
                new EbAttendeeResponse(
                    "a1", false, false, new EbAttendeeResponse.Profile("user@test.com", "User"))));

    AttendanceVerificationStatus status = service.verify(11L, 1001L);

    assertThat(status).isEqualTo(AttendanceVerificationStatus.EB_VERIFIED);
  }

  @Test
  void should_return_self_reported_when_booking_confirmed_and_eb_attendee_not_found() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenReturn(
            new PaymentBookingLookupService.BookingEligibilitySnapshot(
                1L, 11L, 1001L, 101L, 501L, "CONFIRMED"));
    when(eventMetadataLookupService.getEventMetadataOrNull(1001L))
        .thenReturn(new EventMetadataLookupService.EventMetadata(1001L, 501L, "eb-1"));
    when(identityLookupService.getEmail(11L)).thenReturn("user@test.com");
    when(ebTokenStore.getAccessToken(501L)).thenReturn("token");
    when(ebAttendeeService.getAttendeesByEvent("token", "eb-1")).thenReturn(List.of());

    AttendanceVerificationStatus status = service.verify(11L, 1001L);

    assertThat(status).isEqualTo(AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED);
  }

  @Test
  void should_throw_review_not_eligible_when_booking_not_confirmed() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenThrow(new ReviewNotEligibleException("not confirmed", "REVIEW_NOT_ELIGIBLE"));

    assertThatThrownBy(() -> service.verify(11L, 1001L))
        .isInstanceOf(ReviewNotEligibleException.class);
  }

  @Test
  void should_throw_review_not_eligible_when_booking_check_times_out() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenThrow(new ReviewNotEligibleException("timeout", "REVIEW_NOT_ELIGIBLE"));

    assertThatThrownBy(() -> service.verify(11L, 1001L))
        .isInstanceOf(ReviewNotEligibleException.class);
  }

  @Test
  void should_return_self_reported_when_eb_metadata_times_out() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenReturn(
            new PaymentBookingLookupService.BookingEligibilitySnapshot(
                1L, 11L, 1001L, 101L, 501L, "CONFIRMED"));
    when(eventMetadataLookupService.getEventMetadataOrNull(1001L)).thenReturn(null);

    AttendanceVerificationStatus status = service.verify(11L, 1001L);

    assertThat(status).isEqualTo(AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED);
  }

  @Test
  void should_return_eb_unavailable_when_eb_api_throws_exception() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenReturn(
            new PaymentBookingLookupService.BookingEligibilitySnapshot(
                1L, 11L, 1001L, 101L, 501L, "CONFIRMED"));
    when(eventMetadataLookupService.getEventMetadataOrNull(1001L))
        .thenReturn(new EventMetadataLookupService.EventMetadata(1001L, 501L, "eb-1"));
    when(identityLookupService.getEmail(11L)).thenReturn("user@test.com");
    when(ebTokenStore.getAccessToken(501L)).thenReturn("token");
    when(ebAttendeeService.getAttendeesByEvent("token", "eb-1"))
        .thenThrow(new RuntimeException("boom"));

    AttendanceVerificationStatus status = service.verify(11L, 1001L);

    assertThat(status).isEqualTo(AttendanceVerificationStatus.ATTENDANCE_EB_UNAVAILABLE);
  }

  @Test
  void should_return_eb_unavailable_when_eb_auth_exception_occurs() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenReturn(
            new PaymentBookingLookupService.BookingEligibilitySnapshot(
                1L, 11L, 1001L, 101L, 501L, "CONFIRMED"));
    when(eventMetadataLookupService.getEventMetadataOrNull(1001L))
        .thenReturn(new EventMetadataLookupService.EventMetadata(1001L, 501L, "eb-1"));
    when(identityLookupService.getEmail(11L)).thenReturn("user@test.com");
    when(ebTokenStore.getAccessToken(501L)).thenThrow(new EbAuthException("auth"));

    AttendanceVerificationStatus status = service.verify(11L, 1001L);

    assertThat(status).isEqualTo(AttendanceVerificationStatus.ATTENDANCE_EB_UNAVAILABLE);
  }

  @Test
  void should_return_self_reported_when_no_eb_event_id_present() {
    when(paymentBookingLookupService.getConfirmedBookingByUserEvent(11L, 1001L))
        .thenReturn(
            new PaymentBookingLookupService.BookingEligibilitySnapshot(
                1L, 11L, 1001L, 101L, 501L, "CONFIRMED"));
    when(eventMetadataLookupService.getEventMetadataOrNull(1001L))
        .thenReturn(new EventMetadataLookupService.EventMetadata(1001L, 501L, null));

    AttendanceVerificationStatus status = service.verify(11L, 1001L);

    assertThat(status).isEqualTo(AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED);
  }
}
