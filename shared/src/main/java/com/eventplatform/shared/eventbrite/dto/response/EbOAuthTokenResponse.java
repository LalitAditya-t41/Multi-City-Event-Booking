package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EbOAuthTokenResponse(
    String accessToken, String refreshToken, Instant expiresAt, String ebOrganizationId) {}
