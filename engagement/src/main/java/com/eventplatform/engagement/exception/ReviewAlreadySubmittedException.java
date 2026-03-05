package com.eventplatform.engagement.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class ReviewAlreadySubmittedException extends BaseException {

  public ReviewAlreadySubmittedException() {
    super(
        "Review already submitted for this event", "REVIEW_ALREADY_SUBMITTED", HttpStatus.CONFLICT);
  }
}
