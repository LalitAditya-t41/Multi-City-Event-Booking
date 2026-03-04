package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.domain.OrganizationAuth;
import com.eventplatform.shared.eventbrite.domain.OrgAuthStatus;
import com.eventplatform.shared.eventbrite.dto.response.EbOAuthTokenResponse;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.repository.OrganizationAuthRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EbTokenStore {

    private static final Duration REFRESH_WINDOW = Duration.ofMinutes(5);

    private final OrganizationAuthRepository repository;
    private final EbOAuthClient oauthClient;
    private final TokenCipher tokenCipher;

    public EbTokenStore(
        OrganizationAuthRepository repository,
        EbOAuthClient oauthClient,
        TokenCipher tokenCipher
    ) {
        this.repository = repository;
        this.oauthClient = oauthClient;
        this.tokenCipher = tokenCipher;
    }

    @Transactional
    public OrganizationAuth connectOrganization(Long orgId, String code, String redirectUri) {
        EbOAuthTokenResponse tokenResponse = oauthClient.exchangeCodeForToken(code, redirectUri);
        if (tokenResponse == null || tokenResponse.accessToken() == null) {
            throw new EbAuthException("Eventbrite OAuth token exchange failed");
        }
        OrganizationAuth auth = repository.findByOrganizationId(orgId)
            .orElseGet(() -> new OrganizationAuth(orgId, tokenResponse.ebOrganizationId(), ""));

        auth.markConnected(
            tokenCipher.encrypt(tokenResponse.accessToken()),
            tokenCipher.encrypt(tokenResponse.refreshToken()),
            tokenResponse.expiresAt()
        );
        return repository.save(auth);
    }

    @Transactional(readOnly = true)
    public OrganizationAuth getAuth(Long orgId) {
        return repository.findByOrganizationId(orgId)
            .orElseThrow(() -> new EbAuthException("No Eventbrite auth configured for org: " + orgId));
    }

    @Transactional
    public void disconnect(Long orgId) {
        repository.findByOrganizationId(orgId).ifPresent(auth -> {
            auth.markRevoked();
            repository.save(auth);
        });
    }

    @Transactional
    public String getAccessToken(Long orgId) {
        OrganizationAuth auth = repository.findByOrganizationId(orgId)
            .orElseThrow(() -> new EbAuthException("No Eventbrite auth configured for org: " + orgId));

        if (auth.getStatus() == OrgAuthStatus.REVOKED) {
            throw new EbAuthException("Eventbrite auth revoked for org: " + orgId);
        }

        if (shouldRefresh(auth)) {
            refreshToken(auth);
        }

        return tokenCipher.decrypt(auth.getAccessTokenEncrypted());
    }

    private boolean shouldRefresh(OrganizationAuth auth) {
        Instant expiresAt = auth.getExpiresAt();
        if (expiresAt == null) {
            return false;
        }
        return expiresAt.isBefore(Instant.now().plus(REFRESH_WINDOW));
    }

    private void refreshToken(OrganizationAuth auth) {
        String refreshToken = tokenCipher.decrypt(auth.getRefreshTokenEncrypted());
        if (refreshToken == null) {
            auth.markTokenExpired();
            repository.save(auth);
            throw new EbAuthException("Eventbrite refresh token missing");
        }

        try {
            EbOAuthTokenResponse refreshed = oauthClient.refreshToken(refreshToken);
            if (refreshed == null || refreshed.accessToken() == null) {
                auth.markTokenExpired();
                repository.save(auth);
                throw new EbAuthException("Eventbrite token refresh failed");
            }
            auth.updateTokens(
                tokenCipher.encrypt(refreshed.accessToken()),
                tokenCipher.encrypt(refreshed.refreshToken()),
                refreshed.expiresAt()
            );
            repository.save(auth);
        } catch (Exception ex) {
            auth.markTokenExpired();
            repository.save(auth);
            throw new EbAuthException("Eventbrite token refresh failed");
        }
    }
}
