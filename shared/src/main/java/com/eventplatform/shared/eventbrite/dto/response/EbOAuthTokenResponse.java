package com.eventplatform.shared.eventbrite.dto.response;
@JsonIgnoreProperties(ignoreUnknown = true)
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

public record EbOAuthTokenResponse(
    String accessToken, String refreshToken, Instant expiresAt, String ebOrganizationId) {}
