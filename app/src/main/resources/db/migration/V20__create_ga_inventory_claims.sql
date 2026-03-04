-- owner: booking-inventory module
CREATE TABLE ga_inventory_claims (
    id                  BIGSERIAL     PRIMARY KEY,
    show_slot_id        BIGINT        NOT NULL,
    pricing_tier_id     BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    cart_id             BIGINT,
    quantity            INTEGER       NOT NULL CHECK (quantity > 0),
    lock_state          VARCHAR(20)   NOT NULL DEFAULT 'SOFT_LOCKED'
                         CONSTRAINT chk_ga_lock_state CHECK (lock_state IN ('AVAILABLE', 'SOFT_LOCKED', 'HARD_LOCKED', 'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED')),
    locked_until        TIMESTAMPTZ   NOT NULL,
    eb_order_id         VARCHAR(255),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_ga_claim_user_tier UNIQUE (show_slot_id, pricing_tier_id, user_id, cart_id)
);

CREATE INDEX idx_ga_claim_slot_tier ON ga_inventory_claims(show_slot_id, pricing_tier_id, lock_state);
CREATE INDEX idx_ga_claim_user      ON ga_inventory_claims(user_id, lock_state);
CREATE INDEX idx_ga_claim_expiry    ON ga_inventory_claims(locked_until, lock_state);
