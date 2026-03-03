package com.eventplatform.shared.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends BaseException {
    public BusinessRuleException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public BusinessRuleException(String message, String errorCode, Object details) {
        super(message, errorCode, HttpStatus.UNPROCESSABLE_ENTITY, details);
    }
}
