package com.eventplatform.shared.security;

public final class SecurityConstants {

    public static final String AUTH_HEADER         = "Authorization";
    public static final String BEARER_PREFIX       = "Bearer ";

    public static final long   ACCESS_TOKEN_TTL_S  = 3600L;    // 1 hour
    public static final long   REFRESH_TOKEN_TTL_S = 86400L;   // 1 day
    public static final long   RESET_TOKEN_TTL_S   = 900L;     // 15 minutes

    public static final String TOKEN_TYPE_ACCESS   = "access";
    public static final String TOKEN_CLAIM_ROLE    = "role";
    public static final String TOKEN_CLAIM_TYPE    = "typ";

    private SecurityConstants() {}
}
