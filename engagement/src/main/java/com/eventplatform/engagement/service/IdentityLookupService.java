package com.eventplatform.engagement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class IdentityLookupService {

    private static final String ANONYMOUS = "Anonymous";

    private final RestClient restClient;

    public IdentityLookupService(@Value("${app.internal-base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public String getDisplayName(Long userId) {
        try {
            DisplayNameResponse response = restClient.get()
                .uri("/internal/identity/users/{userId}/display-name", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("Display name lookup failed");
                })
                .body(DisplayNameResponse.class);
            if (response == null || response.displayName() == null || response.displayName().isBlank()) {
                return ANONYMOUS;
            }
            return response.displayName();
        } catch (Exception ex) {
            return ANONYMOUS;
        }
    }

    public String getEmail(Long userId) {
        try {
            UserEmailResponse response = restClient.get()
                .uri("/internal/identity/users/{userId}/email", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("Email lookup failed");
                })
                .body(UserEmailResponse.class);
            return response == null ? null : response.email();
        } catch (Exception ex) {
            return null;
        }
    }

    public record DisplayNameResponse(Long userId, String displayName) {
    }

    public record UserEmailResponse(Long userId, String email) {
    }
}
