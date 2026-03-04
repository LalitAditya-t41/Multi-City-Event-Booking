-- FR5: e_tickets table — QR code and PDF url per booking item
CREATE TABLE e_tickets (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT NOT NULL REFERENCES bookings(id),
    booking_item_id BIGINT NOT NULL REFERENCES booking_items(id),
    qr_code_data    TEXT   NOT NULL,
    pdf_url         TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'VALID',
    issued_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_e_tickets_booking_id ON e_tickets(booking_id);
