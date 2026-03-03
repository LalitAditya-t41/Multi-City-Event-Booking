package com.eventplatform.admin.service;

import com.eventplatform.shared.eventbrite.domain.OrganizationAuth;
import com.eventplatform.shared.eventbrite.service.EbTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrgOAuthService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final EbTokenStore tokenStore;
    private final String clientId;
    private final String redirectUri;
    private final Map<String, StateEntry> stateStore = new ConcurrentHashMap<>();

    public OrgOAuthService(
        EbTokenStore tokenStore,
        @Value("${eventbrite.oauth.client-id:}") String clientId,
        @Value("${eventbrite.oauth.redirect-uri:}") String redirectUri
    ) {
        this.tokenStore = tokenStore;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    public String buildAuthorizationUrl(Long orgId) {
        String state = UUID.randomUUID().toString();
        stateStore.put(state, new StateEntry(orgId, Instant.now()));
        return "https://www.eventbrite.com/oauth/authorize?response_type=code&client_id=%s&redirect_uri=%s&state=%s"
            .formatted(clientId, redirectUri, state);
    }

    public OrganizationAuth handleCallback(Long orgId, String code, String state) {
        validateState(orgId, state);
        return tokenStore.connectOrganization(orgId, code, redirectUri);
    }

    public OrganizationAuth getStatus(Long orgId) {
        return tokenStore.getAuth(orgId);
    }

    public void disconnect(Long orgId) {
        tokenStore.disconnect(orgId);
    }

    private void validateState(Long orgId, String state) {
        StateEntry entry = stateStore.remove(state);
        if (entry == null || !entry.orgId().equals(orgId)) {
            throw new IllegalArgumentException("Invalid OAuth state");
        }
        if (entry.createdAt().isBefore(Instant.now().minus(STATE_TTL))) {
            throw new IllegalArgumentException("OAuth state expired");
        }
    }

    private record StateEntry(Long orgId, Instant createdAt) {
    }
}
