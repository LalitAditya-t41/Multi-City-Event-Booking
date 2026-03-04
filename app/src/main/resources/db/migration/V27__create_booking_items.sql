-- FR5: booking_items table — line items per booking (one per seat / GA claim)
CREATE TABLE booking_items (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT       NOT NULL REFERENCES bookings(id),
    seat_id         BIGINT,
    ga_claim_id     BIGINT,
    ticket_class_id VARCHAR(255) NOT NULL,
    unit_price      BIGINT       NOT NULL,
    currency        VARCHAR(10)  NOT NULL DEFAULT 'INR',
    status          VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_booking_items_booking_id ON booking_items(booking_id);
