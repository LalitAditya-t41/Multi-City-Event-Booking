package com.eventplatform.identity.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetConfirmRequest(
    @NotBlank String token,
    @NotBlank String newPassword,
    @NotBlank String confirmPassword
) {
}
