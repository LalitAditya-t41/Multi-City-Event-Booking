package com.eventplatform.shared.common.event.published;

public record ReviewPublishedEvent(Long reviewId, Long eventId, int rating) {
}
