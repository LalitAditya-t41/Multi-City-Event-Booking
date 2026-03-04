package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbOAuthTokenResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DefaultEbOAuthClient implements EbOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultEbOAuthClient.class);

    @Override
    public EbOAuthTokenResponse exchangeCodeForToken(String code, String redirectUri) {
        log.warn("EbOAuthClient not configured. code={} redirectUri={}", code, redirectUri);
        throw new EbIntegrationException("Eventbrite OAuth client not configured");
    }

    @Override
    public EbOAuthTokenResponse refreshToken(String refreshToken) {
        log.warn("EbOAuthClient not configured. refreshToken provided");
        throw new EbIntegrationException("Eventbrite OAuth client not configured");
    }
}
