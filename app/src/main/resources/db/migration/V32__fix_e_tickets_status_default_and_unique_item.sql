ALTER TABLE e_tickets
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

UPDATE e_tickets
SET status = 'ACTIVE'
WHERE status = 'VALID';

ALTER TABLE e_tickets
    ADD CONSTRAINT uq_e_tickets_booking_item_id UNIQUE (booking_item_id);
