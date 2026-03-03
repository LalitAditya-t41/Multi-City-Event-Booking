-- owner: discovery-catalog module
CREATE TABLE city (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    state VARCHAR(10),
    country_code VARCHAR(2),
    latitude VARCHAR(50),
    longitude VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_city_org FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE
);

CREATE INDEX idx_city_org_id ON city(organization_id);
CREATE INDEX idx_city_name ON city(name);
CREATE UNIQUE INDEX uk_org_city_name ON city(organization_id, name);
