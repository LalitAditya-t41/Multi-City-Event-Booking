-- owner: booking-inventory module
CREATE TABLE carts (
    id              BIGSERIAL      PRIMARY KEY,
    user_id         BIGINT         NOT NULL,
    show_slot_id    BIGINT         NOT NULL,
    org_id          BIGINT         NOT NULL,
    seating_mode    VARCHAR(20)    NOT NULL
                     CONSTRAINT chk_cart_seating_mode CHECK (seating_mode IN ('RESERVED', 'GA')),
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
                     CONSTRAINT chk_cart_status CHECK (status IN ('PENDING', 'CONFIRMED', 'ABANDONED', 'EXPIRED')),
    expires_at      TIMESTAMPTZ    NOT NULL,
    coupon_code     VARCHAR(100),
    discount_amount DECIMAL(12,2)  NOT NULL DEFAULT 0.00,
    currency        VARCHAR(3)     NOT NULL DEFAULT 'INR',
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_cart_user_slot_pending
    ON carts(user_id, show_slot_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_cart_user   ON carts(user_id);
CREATE INDEX idx_cart_slot   ON carts(show_slot_id);
CREATE INDEX idx_cart_status ON carts(status);
CREATE INDEX idx_cart_expiry ON carts(expires_at, status);
