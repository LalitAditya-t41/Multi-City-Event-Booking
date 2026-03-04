-- V17__add_group_discount_to_pricing_tier.sql
ALTER TABLE show_slot_pricing_tier
    ADD COLUMN group_discount_threshold  INTEGER      DEFAULT NULL,
    ADD COLUMN group_discount_percent    NUMERIC(5,2) DEFAULT NULL;

COMMENT ON COLUMN show_slot_pricing_tier.group_discount_threshold
    IS 'Min seat count to trigger group discount. NULL = no group discount for this tier.';
COMMENT ON COLUMN show_slot_pricing_tier.group_discount_percent
    IS 'Discount percentage applied when threshold is met (e.g. 10.00 = 10%). NULL = no discount.';
