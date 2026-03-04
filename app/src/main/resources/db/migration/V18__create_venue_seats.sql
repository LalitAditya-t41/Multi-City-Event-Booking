-- V18__create_venue_seats.sql
CREATE TABLE venue_seat (
    id              BIGSERIAL PRIMARY KEY,
    venue_id        BIGINT NOT NULL REFERENCES venue(id) ON DELETE CASCADE,
    section         VARCHAR(100) NOT NULL,
    row_label       VARCHAR(20)  NOT NULL,
    seat_number     VARCHAR(20)  NOT NULL,
    tier_name       VARCHAR(100) NOT NULL,
    is_accessible   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (venue_id, section, row_label, seat_number)
);

CREATE INDEX idx_venue_seat_venue_id ON venue_seat(venue_id);

COMMENT ON TABLE venue_seat IS 'Physical seat inventory per venue. Source of truth for RESERVED-mode seat provisioning.';
COMMENT ON COLUMN venue_seat.tier_name IS 'Matches the name field in show_slot_pricing_tier. Used to map seats to pricing tiers.';
