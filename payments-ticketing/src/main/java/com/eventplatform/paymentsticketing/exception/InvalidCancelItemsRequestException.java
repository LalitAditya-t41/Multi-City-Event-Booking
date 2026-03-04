package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class InvalidCancelItemsRequestException extends BaseException {

    public InvalidCancelItemsRequestException(String message) {
        super(message, "INVALID_CANCEL_ITEMS_REQUEST", HttpStatus.BAD_REQUEST);
    }
}
