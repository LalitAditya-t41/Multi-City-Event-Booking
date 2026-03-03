-- owner: scheduling module
CREATE TABLE show_slot_pricing_tier (
    id                    BIGSERIAL    PRIMARY KEY,
    slot_id               BIGINT       NOT NULL,
    name                  VARCHAR(255) NOT NULL,
    price_amount          DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    currency              VARCHAR(3)   NOT NULL DEFAULT 'INR',
    quota                 INTEGER      NOT NULL,
    tier_type             VARCHAR(20)  NOT NULL DEFAULT 'PAID'
                           CONSTRAINT chk_tier_type CHECK (tier_type IN ('FREE', 'PAID', 'DONATION')),
    eb_ticket_class_id    VARCHAR(255),
    eb_inventory_tier_id  VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_pricing_tier_slot FOREIGN KEY (slot_id)
        REFERENCES show_slot(id) ON DELETE CASCADE,
    CONSTRAINT chk_quota_positive   CHECK (quota > 0),
    CONSTRAINT chk_price_free       CHECK (
        (tier_type = 'FREE' AND price_amount = 0.00) OR
        (tier_type != 'FREE')
    )
);

CREATE INDEX idx_pricing_tier_slot   ON show_slot_pricing_tier(slot_id);
CREATE INDEX idx_pricing_tier_eb     ON show_slot_pricing_tier(eb_ticket_class_id);
