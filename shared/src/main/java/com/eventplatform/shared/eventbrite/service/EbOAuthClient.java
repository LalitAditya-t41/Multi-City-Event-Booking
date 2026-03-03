package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbOAuthTokenResponse;

public interface EbOAuthClient {
    EbOAuthTokenResponse exchangeCodeForToken(String code, String redirectUri);

    EbOAuthTokenResponse refreshToken(String refreshToken);
}
