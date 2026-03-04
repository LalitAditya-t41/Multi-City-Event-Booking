-- owner: booking-inventory module
CREATE TABLE seats (
    id                  BIGSERIAL     PRIMARY KEY,
    show_slot_id        BIGINT        NOT NULL,
    pricing_tier_id     BIGINT        NOT NULL,
    eb_ticket_class_id  VARCHAR(255)  NOT NULL,
    seat_number         VARCHAR(20)   NOT NULL,
    row_label           VARCHAR(10),
    section             VARCHAR(100),
    lock_state          VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE'
                         CONSTRAINT chk_seat_lock_state CHECK (lock_state IN ('AVAILABLE', 'SOFT_LOCKED', 'HARD_LOCKED', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED')),
    locked_by_user_id   BIGINT,
    locked_until        TIMESTAMPTZ,
    eb_order_id         VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_seat_position UNIQUE (show_slot_id, seat_number)
);

CREATE INDEX idx_seats_slot_state   ON seats(show_slot_id, lock_state);
CREATE INDEX idx_seats_locked_until ON seats(locked_until);
CREATE INDEX idx_seats_user_lock    ON seats(locked_by_user_id);
CREATE INDEX idx_seats_eb_order     ON seats(eb_order_id);
