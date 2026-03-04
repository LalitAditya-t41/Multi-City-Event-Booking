package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CartItemsFetchException extends BaseException {

    public CartItemsFetchException(String message) {
        super(message, "CART_ITEMS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
