-- owner: discovery-catalog module
CREATE TABLE venue (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    city_id BIGINT NOT NULL,
    eventbrite_venue_id VARCHAR(255) UNIQUE,
    name VARCHAR(255) NOT NULL,
    address TEXT,
    zip_code VARCHAR(50),
    latitude VARCHAR(255),
    longitude VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_venue_org FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_venue_city FOREIGN KEY (city_id) REFERENCES city(id) ON DELETE CASCADE
);

CREATE INDEX idx_venue_city_id ON venue(city_id);
CREATE INDEX idx_venue_org_id ON venue(organization_id);
CREATE INDEX idx_venue_eventbrite_id ON venue(eventbrite_venue_id);
