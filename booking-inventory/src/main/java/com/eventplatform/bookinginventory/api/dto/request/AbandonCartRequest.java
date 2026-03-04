package com.eventplatform.bookinginventory.api.dto.request;

import jakarta.validation.constraints.NotNull;

public record AbandonCartRequest(@NotNull Long cartId) {
}
