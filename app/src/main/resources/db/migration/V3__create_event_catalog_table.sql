-- owner: discovery-catalog module
CREATE TYPE event_state AS ENUM ('DRAFT', 'PUBLISHED', 'CANCELLED');
CREATE TYPE event_source AS ENUM ('INTERNAL', 'EVENTBRITE_EXTERNAL');

CREATE TABLE event_catalog (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    city_id BIGINT NOT NULL,
    venue_id BIGINT,
    eventbrite_event_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(500) NOT NULL,
    description TEXT,
    url VARCHAR(500),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    state event_state DEFAULT 'PUBLISHED',
    source event_source DEFAULT 'EVENTBRITE_EXTERNAL',
    currency VARCHAR(10),
    eventbrite_changed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT fk_event_org FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_city FOREIGN KEY (city_id) REFERENCES city(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_venue FOREIGN KEY (venue_id) REFERENCES venue(id) ON DELETE SET NULL
);

CREATE INDEX idx_event_city_active ON event_catalog(city_id, deleted_at);
CREATE INDEX idx_event_venue_active ON event_catalog(venue_id, deleted_at);
CREATE INDEX idx_event_org_id ON event_catalog(organization_id);
CREATE INDEX idx_event_deleted_at ON event_catalog(deleted_at);
CREATE INDEX idx_event_eventbrite_id ON event_catalog(eventbrite_event_id);
CREATE INDEX idx_event_start_time ON event_catalog(start_time);
