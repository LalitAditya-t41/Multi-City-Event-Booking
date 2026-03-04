-- I8: Rename eb_order_id → booking_ref across all booking-inventory tables.
--     Stripe flow uses bookingRef (e.g. BK-20240101-000123), not an Eventbrite orderId.

-- seats
ALTER TABLE seats RENAME COLUMN eb_order_id TO booking_ref;
DROP INDEX IF EXISTS idx_seats_eb_order;
CREATE INDEX idx_seats_booking_ref ON seats(booking_ref);

-- ga_inventory_claims
ALTER TABLE ga_inventory_claims RENAME COLUMN eb_order_id TO booking_ref;

-- seat_lock_audit_log (audit trail uses same field for reference ID)
ALTER TABLE seat_lock_audit_log RENAME COLUMN eb_order_id TO booking_ref;
