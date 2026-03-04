-- FR6: cancellation_requests table — buyer-initiated cancellation request
CREATE TABLE cancellation_requests (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   BIGINT NOT NULL REFERENCES bookings(id),
    user_id      BIGINT NOT NULL,
    reason       TEXT,
    status       VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resolved_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_cancellation_requests_booking_id ON cancellation_requests(booking_id);
