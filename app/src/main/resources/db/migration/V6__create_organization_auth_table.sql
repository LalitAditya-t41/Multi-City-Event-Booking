-- owner: shared/eventbrite/ — never accessed directly by any application module
CREATE TABLE organization_auth (
    id                        BIGSERIAL PRIMARY KEY,
    organization_id           BIGINT       NOT NULL UNIQUE,
    eb_organization_id        VARCHAR(255) NOT NULL,
    access_token_encrypted    TEXT         NOT NULL,
    refresh_token_encrypted   TEXT,
    expires_at                TIMESTAMP,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                              CONSTRAINT chk_org_auth_status CHECK (status IN ('PENDING', 'CONNECTED', 'TOKEN_EXPIRED', 'REVOKED')),
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_org_auth_org FOREIGN KEY (organization_id)
        REFERENCES organization(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_org_auth_org      ON organization_auth(organization_id);
CREATE INDEX        idx_org_auth_status  ON organization_auth(status);
CREATE INDEX        idx_org_auth_eb_org  ON organization_auth(eb_organization_id);
