package com.eventplatform.identity.api.dto.response;

public record AuthTokensResponse(String accessToken, String refreshToken) {
}
