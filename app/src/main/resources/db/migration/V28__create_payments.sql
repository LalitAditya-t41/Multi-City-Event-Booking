-- FR5: payments table — Stripe payment record
CREATE TABLE payments (
    id                       BIGSERIAL PRIMARY KEY,
    booking_id               BIGINT       NOT NULL REFERENCES bookings(id),
    stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
    stripe_charge_id         VARCHAR(255),
    amount                   BIGINT       NOT NULL,
    currency                 VARCHAR(10)  NOT NULL DEFAULT 'inr',
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    failure_code             VARCHAR(100),
    failure_message          TEXT,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_payments_booking_id               ON payments(booking_id);
CREATE INDEX idx_payments_stripe_payment_intent_id ON payments(stripe_payment_intent_id);
