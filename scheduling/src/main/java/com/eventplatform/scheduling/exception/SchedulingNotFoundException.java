package com.eventplatform.scheduling.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class SchedulingNotFoundException extends ResourceNotFoundException {
    public SchedulingNotFoundException(String message, String errorCode) {
        super(message, errorCode);
    }
}
