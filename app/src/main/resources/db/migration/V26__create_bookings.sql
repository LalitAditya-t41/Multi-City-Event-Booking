-- FR5: bookings table — one record per confirmed purchase
CREATE TABLE bookings (
    id                       BIGSERIAL PRIMARY KEY,
    booking_ref              VARCHAR(50)  NOT NULL UNIQUE,
    cart_id                  BIGINT       NOT NULL,
    user_id                  BIGINT       NOT NULL,
    event_id                 BIGINT       NOT NULL,
    slot_id                  BIGINT       NOT NULL,
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    stripe_payment_intent_id VARCHAR(255),
    stripe_charge_id         VARCHAR(255),
    total_amount             BIGINT       NOT NULL,
    currency                 VARCHAR(10)  NOT NULL DEFAULT 'INR',
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_bookings_user_id     ON bookings(user_id);
CREATE INDEX idx_bookings_cart_id     ON bookings(cart_id);
CREATE INDEX idx_bookings_booking_ref ON bookings(booking_ref);
