package com.eventplatform.shared.common.exception;

import java.time.Instant;

public record ErrorResponse(String errorCode, String message, int status, Instant timestamp) {
}
