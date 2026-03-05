package com.eventplatform.identity.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

  public UserNotFoundException(Long userId) {
    super("User not found", "USER_NOT_FOUND");
  }
}
