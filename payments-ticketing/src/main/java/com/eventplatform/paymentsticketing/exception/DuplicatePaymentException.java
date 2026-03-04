package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class DuplicatePaymentException extends BaseException {

    public DuplicatePaymentException(String bookingRef) {
        super(
            "Payment already exists for this cart",
            "PAYMENT_ALREADY_EXISTS",
            HttpStatus.CONFLICT,
            Map.of("bookingRef", bookingRef)
        );
    }
}
