package com.eventplatform.shared.security;

/**
 * Custom principal stored in the Spring SecurityContext. Replaces the raw userId Long as principal
 * so userId, role, orgId and email are all accessible without extra DB lookups.
 */
public record AuthenticatedUser(Long userId, String role, Long orgId, String email) {}
