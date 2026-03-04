-- FR7 pre-fix: split the single `discount_amount` column into two separate columns
-- so that group discount (quantity-based, computed by CartPricingService) and
-- coupon discount (applied by promotions.CouponAppliedListener) can be tracked
-- independently without either clobbering the other.
--
-- Background
-- ----------
-- V21 stored a single `discount_amount` column which CartPricingService used for
-- the GROUP discount.  When FR7 introduces coupon discounts, the promotions module
-- will write the coupon discount amount to the cart via CouponAppliedEvent.  If
-- both discounts share one column, every CartPricingService.recompute() call would
-- overwrite the coupon discount, producing an incorrect Stripe PaymentIntent amount.
--
-- Changes
-- -------
-- 1. Rename the existing column to make its purpose explicit.
-- 2. Add a new zero-defaulted column for coupon discounts.

ALTER TABLE carts
    RENAME COLUMN discount_amount TO group_discount_amount;

ALTER TABLE carts
    ADD COLUMN coupon_discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00;

COMMENT ON COLUMN carts.group_discount_amount  IS 'Group/bulk discount computed by CartPricingService (quantity threshold based)';
COMMENT ON COLUMN carts.coupon_discount_amount IS 'Coupon discount applied by promotions module via CouponAppliedEvent';
