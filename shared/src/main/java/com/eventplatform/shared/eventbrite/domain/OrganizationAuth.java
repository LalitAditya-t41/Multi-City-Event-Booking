package com.eventplatform.shared.eventbrite.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "organization_auth")
public class OrganizationAuth extends BaseEntity {

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    @Column(name = "eb_organization_id", nullable = false)
    private String ebOrganizationId;

    @Column(name = "access_token_encrypted", nullable = false)
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted")
    private String refreshTokenEncrypted;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrgAuthStatus status;

    protected OrganizationAuth() {
    }

    public OrganizationAuth(Long organizationId, String ebOrganizationId, String accessTokenEncrypted) {
        this.organizationId = organizationId;
        this.ebOrganizationId = ebOrganizationId;
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.status = OrgAuthStatus.PENDING;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getEbOrganizationId() {
        return ebOrganizationId;
    }

    public String getAccessTokenEncrypted() {
        return accessTokenEncrypted;
    }

    public String getRefreshTokenEncrypted() {
        return refreshTokenEncrypted;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public OrgAuthStatus getStatus() {
        return status;
    }

    public void markConnected(String accessTokenEncrypted, String refreshTokenEncrypted, Instant expiresAt) {
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.refreshTokenEncrypted = refreshTokenEncrypted;
        this.expiresAt = expiresAt;
        this.status = OrgAuthStatus.CONNECTED;
    }

    public void markTokenExpired() {
        this.status = OrgAuthStatus.TOKEN_EXPIRED;
    }

    public void markRevoked() {
        this.status = OrgAuthStatus.REVOKED;
    }

    public void updateTokens(String accessTokenEncrypted, String refreshTokenEncrypted, Instant expiresAt) {
        this.accessTokenEncrypted = accessTokenEncrypted;
        if (refreshTokenEncrypted != null) {
            this.refreshTokenEncrypted = refreshTokenEncrypted;
        }
        this.expiresAt = expiresAt;
        this.status = OrgAuthStatus.CONNECTED;
    }
}
