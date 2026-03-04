package com.eventplatform.shared.security;

/**
 * Custom principal stored in the Spring SecurityContext.
 * Replaces the raw userId Long as principal so orgId is accessible without extra DB lookups.
 */
public record AuthenticatedUser(Long userId, String role, Long orgId) {
}
