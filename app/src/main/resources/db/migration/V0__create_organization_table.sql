-- owner: identity module
CREATE TYPE organization_status AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');

CREATE TABLE organization (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255),
    phone VARCHAR(20),
    website VARCHAR(500),
    logo_url VARCHAR(500),
    description TEXT,
    country_code VARCHAR(2),
    timezone VARCHAR(50) DEFAULT 'UTC',
    status organization_status DEFAULT 'ACTIVE',
    eventbrite_org_id VARCHAR(255) UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_org_status ON organization(status);
CREATE INDEX idx_org_eventbrite_id ON organization(eventbrite_org_id);
CREATE UNIQUE INDEX uk_org_slug ON organization(slug);
