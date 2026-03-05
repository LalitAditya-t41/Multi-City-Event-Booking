package com.eventplatform.engagement.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class InvalidRatingException extends BaseException {

    public InvalidRatingException() {
        super("Rating must be between 1 and 5", "INVALID_RATING", HttpStatus.BAD_REQUEST);
    }
}
