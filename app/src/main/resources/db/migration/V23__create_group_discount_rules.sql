-- owner: booking-inventory module
CREATE TABLE group_discount_rules (
    id                        BIGSERIAL     PRIMARY KEY,
    show_slot_id              BIGINT        NOT NULL,
    pricing_tier_id           BIGINT        NOT NULL,
    group_discount_threshold  INTEGER       NOT NULL,
    group_discount_percent    DECIMAL(5,2)  NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_discount_rule_tier UNIQUE (show_slot_id, pricing_tier_id)
);

CREATE INDEX idx_group_discount_slot_tier ON group_discount_rules(show_slot_id, pricing_tier_id);
