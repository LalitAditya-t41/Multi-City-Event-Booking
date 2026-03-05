package com.eventplatform.engagement.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class ReviewAlreadyModeratedException extends BaseException {

    public ReviewAlreadyModeratedException() {
        super("Review already moderated", "REVIEW_ALREADY_MODERATED", HttpStatus.CONFLICT);
    }
}
