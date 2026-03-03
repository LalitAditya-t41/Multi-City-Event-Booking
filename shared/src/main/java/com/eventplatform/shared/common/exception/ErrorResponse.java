package com.eventplatform.shared.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String errorCode,
    String message,
    int status,
    Instant timestamp,
    Object alternatives
) {
}
