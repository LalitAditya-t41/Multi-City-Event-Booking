-- owner: scheduling module
CREATE TABLE show_slot_occurrence (
    id                    BIGSERIAL   PRIMARY KEY,
    parent_slot_id        BIGINT      NOT NULL,
    occurrence_index      INTEGER     NOT NULL,
    start_time            TIMESTAMPTZ NOT NULL,
    end_time              TIMESTAMPTZ NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                           CONSTRAINT chk_occurrence_status CHECK (status IN ('DRAFT', 'PENDING_SYNC', 'ACTIVE', 'CANCELLED')),
    eb_event_id           VARCHAR(255) UNIQUE,
    sync_attempt_count    INTEGER     NOT NULL DEFAULT 0,
    last_sync_error       TEXT,
    last_attempted_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_occurrence_slot_time CHECK (end_time > start_time),
    CONSTRAINT fk_slot_occurrence FOREIGN KEY (parent_slot_id)
        REFERENCES show_slot(id) ON DELETE CASCADE,
    CONSTRAINT uk_occurrence_slot_index UNIQUE (parent_slot_id, occurrence_index)
);

CREATE INDEX idx_occurrence_parent   ON show_slot_occurrence(parent_slot_id);
CREATE INDEX idx_occurrence_status   ON show_slot_occurrence(status);
CREATE INDEX idx_occurrence_eb_event ON show_slot_occurrence(eb_event_id);
