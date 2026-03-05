package com.eventplatform.bookinginventory.api.dto.request;

import jakarta.validation.constraints.NotNull;

public record ConfirmCartRequest(@NotNull Long cartId) {}
