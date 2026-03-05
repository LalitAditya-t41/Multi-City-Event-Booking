package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class InvalidPolicyTierConfigException extends BaseException {

  public InvalidPolicyTierConfigException(String message) {
    super(message, "INVALID_POLICY_TIER_CONFIG", HttpStatus.BAD_REQUEST);
  }
}
