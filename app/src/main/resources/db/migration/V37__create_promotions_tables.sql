CREATE TABLE promotions (
    id BIGSERIAL PRIMARY KEY,
    org_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value NUMERIC(12,4) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    eb_event_id VARCHAR(255),
    max_usage_limit INTEGER,
    per_user_cap INTEGER,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_promotions_org_id ON promotions(org_id);
CREATE INDEX idx_promotions_status ON promotions(status);
CREATE INDEX idx_promotions_eb_event ON promotions(eb_event_id) WHERE eb_event_id IS NOT NULL;

CREATE TABLE coupons (
    id BIGSERIAL PRIMARY KEY,
    promotion_id BIGINT NOT NULL REFERENCES promotions(id),
    org_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    redemption_count INTEGER NOT NULL DEFAULT 0,
    eb_discount_id VARCHAR(255),
    eb_sync_status VARCHAR(30) NOT NULL DEFAULT 'NOT_SYNCED',
    eb_quantity_sold_at_last_sync INTEGER,
    last_eb_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_coupons_code_org_active ON coupons(lower(code), org_id) WHERE status != 'INACTIVE';
CREATE INDEX idx_coupons_promotion_id ON coupons(promotion_id);
CREATE INDEX idx_coupons_eb_discount_id ON coupons(eb_discount_id) WHERE eb_discount_id IS NOT NULL;
CREATE INDEX idx_coupons_eb_sync_status ON coupons(eb_sync_status);
CREATE INDEX idx_coupons_status ON coupons(status);

CREATE TABLE coupon_redemptions (
    id BIGSERIAL PRIMARY KEY,
    coupon_id BIGINT NOT NULL REFERENCES coupons(id),
    user_id BIGINT NOT NULL,
    booking_id BIGINT NOT NULL,
    cart_id BIGINT NOT NULL,
    discount_amount NUMERIC(12,4) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    voided BOOLEAN NOT NULL DEFAULT FALSE,
    voided_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_coupon_redemptions_active ON coupon_redemptions(coupon_id, booking_id) WHERE voided = FALSE;
CREATE INDEX idx_coupon_redemptions_coupon_id ON coupon_redemptions(coupon_id);
CREATE INDEX idx_coupon_redemptions_user_id ON coupon_redemptions(user_id);
CREATE INDEX idx_coupon_redemptions_booking_id ON coupon_redemptions(booking_id);
CREATE INDEX idx_coupon_redemptions_cart_id ON coupon_redemptions(cart_id);

CREATE TABLE coupon_usage_reservations (
    id BIGSERIAL PRIMARY KEY,
    coupon_id BIGINT NOT NULL REFERENCES coupons(id),
    cart_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    released BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_coupon_reservations_active ON coupon_usage_reservations(coupon_id, cart_id) WHERE released = FALSE;
CREATE INDEX idx_coupon_reservations_coupon_id ON coupon_usage_reservations(coupon_id);
CREATE INDEX idx_coupon_reservations_cart_id ON coupon_usage_reservations(cart_id);
CREATE INDEX idx_coupon_reservations_user_id ON coupon_usage_reservations(user_id);
CREATE INDEX idx_coupon_reservations_expires_at ON coupon_usage_reservations(expires_at) WHERE released = FALSE;

CREATE TABLE orphan_eb_discounts (
    id BIGSERIAL PRIMARY KEY,
    eb_discount_id VARCHAR(255) NOT NULL,
    org_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT
);

CREATE INDEX idx_orphan_eb_discounts_org_id ON orphan_eb_discounts(org_id);
CREATE INDEX idx_orphan_eb_discounts_reviewed ON orphan_eb_discounts(reviewed) WHERE reviewed = FALSE;

CREATE TABLE discount_reconciliation_logs (
    id BIGSERIAL PRIMARY KEY,
    org_id BIGINT NOT NULL,
    run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    discounts_checked INTEGER NOT NULL DEFAULT 0,
    drifts_found INTEGER NOT NULL DEFAULT 0,
    orphans_found INTEGER NOT NULL DEFAULT 0,
    externally_deleted_found INTEGER NOT NULL DEFAULT 0,
    actions_taken_summary TEXT,
    error_summary TEXT
);

CREATE INDEX idx_reconciliation_logs_org_id ON discount_reconciliation_logs(org_id);
CREATE INDEX idx_reconciliation_logs_run_at ON discount_reconciliation_logs(run_at);
