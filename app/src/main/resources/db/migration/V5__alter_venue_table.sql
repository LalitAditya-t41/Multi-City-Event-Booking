-- owner: discovery-catalog module
ALTER TABLE venue
    ADD COLUMN capacity      INTEGER,
    ADD COLUMN seating_mode  VARCHAR(20) DEFAULT 'GA'
                             CONSTRAINT chk_venue_seating_mode CHECK (seating_mode IN ('GA', 'RESERVED')),
    ADD COLUMN sync_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING_SYNC'
                             CONSTRAINT chk_venue_sync_status CHECK (sync_status IN ('PENDING_SYNC', 'SYNCED', 'DRIFT_FLAGGED')),
    ADD COLUMN last_sync_error     TEXT,
    ADD COLUMN last_attempted_at   TIMESTAMP;

UPDATE venue
    SET sync_status = 'SYNCED'
    WHERE eventbrite_venue_id IS NOT NULL;

CREATE INDEX idx_venue_sync_status ON venue(sync_status);
