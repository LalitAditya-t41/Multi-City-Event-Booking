package com.eventplatform.engagement.service;

import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.exception.ReviewNotEligibleException;
import com.eventplatform.shared.eventbrite.dto.response.EbAttendeeResponse;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.service.EbAttendeeService;
import com.eventplatform.shared.eventbrite.service.EbTokenStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AttendanceVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceVerificationService.class);

    private final PaymentBookingLookupService paymentBookingLookupService;
    private final EventMetadataLookupService eventMetadataLookupService;
    private final IdentityLookupService identityLookupService;
    private final EbTokenStore ebTokenStore;
    private final EbAttendeeService ebAttendeeService;

    public AttendanceVerificationService(
        PaymentBookingLookupService paymentBookingLookupService,
        EventMetadataLookupService eventMetadataLookupService,
        IdentityLookupService identityLookupService,
        EbTokenStore ebTokenStore,
        EbAttendeeService ebAttendeeService
    ) {
        this.paymentBookingLookupService = paymentBookingLookupService;
        this.eventMetadataLookupService = eventMetadataLookupService;
        this.identityLookupService = identityLookupService;
        this.ebTokenStore = ebTokenStore;
        this.ebAttendeeService = ebAttendeeService;
    }

    public AttendanceVerificationStatus verify(Long userId, Long eventId) {
        PaymentBookingLookupService.BookingEligibilitySnapshot booking = paymentBookingLookupService.getConfirmedBookingByUserEvent(userId, eventId);
        if (booking == null || !"CONFIRMED".equalsIgnoreCase(booking.status())) {
            throw new ReviewNotEligibleException("You are not eligible to review this event", "REVIEW_NOT_ELIGIBLE");
        }

        EventMetadataLookupService.EventMetadata metadata = eventMetadataLookupService.getEventMetadataOrNull(eventId);
        if (metadata == null || metadata.ebEventId() == null || metadata.ebEventId().isBlank()) {
            return AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED;
        }

        String email = identityLookupService.getEmail(userId);
        if (email == null || email.isBlank()) {
            log.warn("Cannot verify Eventbrite attendance because user email missing. userId={} eventId={}", userId, eventId);
            return AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED;
        }

        try {
            String orgToken = ebTokenStore.getAccessToken(metadata.orgId());
            List<EbAttendeeResponse> attendees = ebAttendeeService.getAttendeesByEvent(orgToken, metadata.ebEventId());
            EbAttendeeResponse attendee = attendees.stream()
                .filter(a -> a.profile() != null && a.profile().email() != null)
                .filter(a -> a.profile().email().equalsIgnoreCase(email))
                .findFirst()
                .orElse(null);

            if (attendee == null) {
                log.warn("Eventbrite attendee not found. userId={} eventId={} ebEventId={}", userId, eventId, metadata.ebEventId());
                return AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED;
            }
            if (!attendee.cancelled() && !attendee.refunded()) {
                return AttendanceVerificationStatus.EB_VERIFIED;
            }
            return AttendanceVerificationStatus.ATTENDANCE_SELF_REPORTED;
        } catch (EbAuthException ex) {
            log.warn("Eventbrite attendance verification unavailable due to auth failure. userId={} eventId={}", userId, eventId);
            return AttendanceVerificationStatus.ATTENDANCE_EB_UNAVAILABLE;
        } catch (Exception ex) {
            log.warn("Eventbrite attendance verification unavailable. userId={} eventId={}", userId, eventId, ex);
            return AttendanceVerificationStatus.ATTENDANCE_EB_UNAVAILABLE;
        }
    }
}
