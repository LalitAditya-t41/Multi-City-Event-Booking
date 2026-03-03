package com.eventplatform.shared.common.exception;

import org.springframework.http.HttpStatus;

public class IntegrationException extends BaseException {
    public IntegrationException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.BAD_GATEWAY);
    }

    public IntegrationException(String message, String errorCode, Object details) {
        super(message, errorCode, HttpStatus.BAD_GATEWAY, details);
    }
}
