package com.eventplatform.engagement.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class EventNotFoundException extends ResourceNotFoundException {

    public EventNotFoundException(Long eventId) {
        super("Event not found: " + eventId, "EVENT_NOT_FOUND");
    }
}
