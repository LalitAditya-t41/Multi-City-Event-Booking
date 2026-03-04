-- FR6: refunds table — Stripe refund record
CREATE TABLE refunds (
    id               BIGSERIAL PRIMARY KEY,
    booking_id       BIGINT      NOT NULL REFERENCES bookings(id),
    stripe_refund_id VARCHAR(255) UNIQUE,
    amount           BIGINT      NOT NULL,
    currency         VARCHAR(10) NOT NULL DEFAULT 'inr',
    reason           VARCHAR(100),
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_refunds_booking_id ON refunds(booking_id);
