-- owner: scheduling module
CREATE TABLE show_slot (
    id                    BIGSERIAL    PRIMARY KEY,
    organization_id       BIGINT       NOT NULL,
    venue_id              BIGINT       NOT NULL,
    city_id               BIGINT       NOT NULL,
    title                 VARCHAR(500) NOT NULL,
    description           TEXT,
    start_time            TIMESTAMPTZ  NOT NULL,
    end_time              TIMESTAMPTZ  NOT NULL,
    seating_mode          VARCHAR(20)  NOT NULL
                           CONSTRAINT chk_slot_seating_mode CHECK (seating_mode IN ('GA', 'RESERVED')),
    capacity              INTEGER      NOT NULL,
    source_seatmap_id     VARCHAR(255),
    is_recurring          BOOLEAN      NOT NULL DEFAULT FALSE,
    recurrence_rule       TEXT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                           CONSTRAINT chk_slot_status CHECK (status IN ('DRAFT', 'PENDING_SYNC', 'ACTIVE', 'CANCELLED')),
    eb_event_id           VARCHAR(255) UNIQUE,
    eb_series_id          VARCHAR(255),
    sync_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    last_sync_error       TEXT,
    last_attempted_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_show_slot_org   FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_show_slot_venue FOREIGN KEY (venue_id)        REFERENCES venue(id)        ON DELETE RESTRICT,
    CONSTRAINT fk_show_slot_city  FOREIGN KEY (city_id)         REFERENCES city(id)         ON DELETE RESTRICT,
    CONSTRAINT chk_slot_times     CHECK (end_time > start_time),
    CONSTRAINT chk_slot_capacity  CHECK (capacity > 0)
);

CREATE INDEX idx_show_slot_org_id     ON show_slot(organization_id);
CREATE INDEX idx_show_slot_venue_id   ON show_slot(venue_id);
CREATE INDEX idx_show_slot_city_id    ON show_slot(city_id);
CREATE INDEX idx_show_slot_status     ON show_slot(status);
CREATE INDEX idx_show_slot_eb_event   ON show_slot(eb_event_id);
CREATE INDEX idx_show_slot_venue_time ON show_slot(venue_id, start_time, end_time);
CREATE INDEX idx_show_slot_active_eb  ON show_slot(status, eb_event_id);
CREATE INDEX idx_show_slot_org_city   ON show_slot(organization_id, city_id);
