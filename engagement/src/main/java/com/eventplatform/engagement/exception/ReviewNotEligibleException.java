package com.eventplatform.engagement.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class ReviewNotEligibleException extends BaseException {

    public ReviewNotEligibleException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.FORBIDDEN);
    }
}
