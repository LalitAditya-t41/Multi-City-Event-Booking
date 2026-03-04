-- owner: booking-inventory module
CREATE TABLE seat_lock_audit_log (
    id              BIGSERIAL      PRIMARY KEY,
    seat_id         BIGINT,
    ga_claim_id     BIGINT,
    show_slot_id    BIGINT         NOT NULL,
    user_id         BIGINT,
    from_state      VARCHAR(20),
    to_state        VARCHAR(20)    NOT NULL,
    event_type      VARCHAR(50)    NOT NULL,
    reason          VARCHAR(500),
    eb_order_id     VARCHAR(255),
    occurred_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_audit_from_state CHECK (from_state IS NULL OR from_state IN ('AVAILABLE', 'SOFT_LOCKED', 'HARD_LOCKED', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED')),
    CONSTRAINT chk_audit_to_state   CHECK (to_state IN ('AVAILABLE', 'SOFT_LOCKED', 'HARD_LOCKED', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED'))
);

CREATE INDEX idx_audit_seat     ON seat_lock_audit_log(seat_id);
CREATE INDEX idx_audit_slot     ON seat_lock_audit_log(show_slot_id);
CREATE INDEX idx_audit_user     ON seat_lock_audit_log(user_id);
CREATE INDEX idx_audit_occurred ON seat_lock_audit_log(occurred_at);
