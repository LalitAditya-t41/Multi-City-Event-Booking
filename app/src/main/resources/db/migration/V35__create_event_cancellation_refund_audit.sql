CREATE TABLE event_cancellation_refund_audits (
    id            BIGSERIAL PRIMARY KEY,
    slot_id       BIGINT NOT NULL,
    booking_id    BIGINT NOT NULL REFERENCES bookings(id),
    refund_id     BIGINT REFERENCES refunds(id),
    status        VARCHAR(30) NOT NULL,
    error_message TEXT,
    processed_at  TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_event_cancel_audit_slot_id ON event_cancellation_refund_audits(slot_id);
CREATE INDEX idx_event_cancel_audit_booking_id ON event_cancellation_refund_audits(booking_id);

CREATE UNIQUE INDEX uq_event_cancel_slot_booking
    ON event_cancellation_refund_audits(slot_id, booking_id);
