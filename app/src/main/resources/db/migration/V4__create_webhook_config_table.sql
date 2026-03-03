-- owner: discovery-catalog module
CREATE TYPE webhook_status AS ENUM ('REGISTERED', 'IN_COOLDOWN', 'FAILED', 'INACTIVE', 'ORPHANED');

CREATE TABLE webhook_config (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT UNIQUE NOT NULL,
    webhook_id VARCHAR(255) UNIQUE NOT NULL,
    endpoint_url VARCHAR(500) NOT NULL,
    status webhook_status DEFAULT 'REGISTERED',
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_webhook_at TIMESTAMP,
    last_sync_at TIMESTAMP,
    last_error_at TIMESTAMP,
    last_error_message TEXT,
    consecutive_failures INTEGER DEFAULT 0,
    cooldown_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_webhook_org FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE
);

CREATE INDEX idx_webhook_org_id ON webhook_config(organization_id);
CREATE INDEX idx_webhook_status ON webhook_config(status);
CREATE INDEX idx_webhook_cooldown ON webhook_config(cooldown_until);
