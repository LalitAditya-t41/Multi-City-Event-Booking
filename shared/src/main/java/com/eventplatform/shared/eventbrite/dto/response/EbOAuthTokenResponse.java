package com.eventplatform.shared.eventbrite.dto.response;

import java.time.Instant;

public record EbOAuthTokenResponse(
    String accessToken,
    String refreshToken,
    Instant expiresAt,
    String ebOrganizationId
) {
}
