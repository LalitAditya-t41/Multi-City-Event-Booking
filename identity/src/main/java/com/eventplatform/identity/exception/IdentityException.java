package com.eventplatform.identity.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class IdentityException extends BaseException {

    public IdentityException(String message, String errorCode, HttpStatus status) {
        super(message, errorCode, status);
    }
}
