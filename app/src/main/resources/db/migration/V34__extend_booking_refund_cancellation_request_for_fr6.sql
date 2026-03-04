ALTER TABLE bookings
    ADD COLUMN org_id BIGINT,
    ADD COLUMN slot_start_time TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_bookings_org_id ON bookings(org_id);
CREATE INDEX idx_bookings_slot_start_time ON bookings(slot_start_time);

ALTER TABLE refunds
    ADD COLUMN cancellation_type VARCHAR(30);

ALTER TABLE cancellation_requests
    ADD COLUMN booking_item_id BIGINT REFERENCES booking_items(id);

CREATE UNIQUE INDEX uq_cancellation_requests_item_pending
    ON cancellation_requests(booking_item_id)
    WHERE booking_item_id IS NOT NULL AND status = 'PENDING';
