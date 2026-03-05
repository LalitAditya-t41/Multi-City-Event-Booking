package com.eventplatform.engagement.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class ReviewNotFoundException extends ResourceNotFoundException {

  public ReviewNotFoundException(Long reviewId) {
    super("Review not found: " + reviewId, "REVIEW_NOT_FOUND");
  }
}
