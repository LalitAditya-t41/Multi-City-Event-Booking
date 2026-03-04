package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class HardLockException extends BaseException {
    public HardLockException(String message) {
        super(message, "HARD_LOCK_DB_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
