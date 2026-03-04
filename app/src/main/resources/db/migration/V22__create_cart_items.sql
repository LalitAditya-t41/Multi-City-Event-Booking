-- owner: booking-inventory module
CREATE TABLE cart_items (
    id                  BIGSERIAL      PRIMARY KEY,
    cart_id             BIGINT         NOT NULL,
    seat_id             BIGINT,
    ga_claim_id         BIGINT,
    pricing_tier_id     BIGINT         NOT NULL,
    eb_ticket_class_id  VARCHAR(255)   NOT NULL,
    base_price_amount   DECIMAL(12,2)  NOT NULL,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'INR',
    quantity            INTEGER        NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_cart_item_cart     FOREIGN KEY (cart_id)     REFERENCES carts(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_seat     FOREIGN KEY (seat_id)     REFERENCES seats(id),
    CONSTRAINT fk_cart_item_ga_claim FOREIGN KEY (ga_claim_id) REFERENCES ga_inventory_claims(id),
    CONSTRAINT chk_cart_item_mode CHECK (
        (seat_id IS NOT NULL AND ga_claim_id IS NULL)
        OR (seat_id IS NULL AND ga_claim_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_cart_item_cart_seat ON cart_items(cart_id, seat_id) WHERE seat_id IS NOT NULL;
CREATE INDEX idx_cart_item_cart ON cart_items(cart_id);
CREATE INDEX idx_cart_item_seat ON cart_items(seat_id);
CREATE INDEX idx_cart_item_tier ON cart_items(pricing_tier_id);
