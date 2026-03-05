BEGIN;

-- Organization baseline
INSERT INTO organization (
    id, name, slug, email, country_code, timezone, eventbrite_org_id, status
) VALUES (
    1, 'TestOrg', 'testorg', 'admin@eventplatform.com', 'IN', 'Asia/Kolkata', 'org_1', 'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

-- Cities (3 total)
INSERT INTO city (id, organization_id, name, description, state, country_code)
VALUES
  (1, 1, 'Mumbai', 'Primary demo city', 'MH', 'IN'),
  (2, 1, 'Delhi', 'North region demo city', 'DL', 'IN'),
  (3, 1, 'Bengaluru', 'South region demo city', 'KA', 'IN')
ON CONFLICT (organization_id, name) DO NOTHING;

-- Venues (6 total, aligned with mock venue ids)
INSERT INTO venue (
    id, organization_id, city_id, eventbrite_venue_id, name, address, capacity,
    seating_mode, sync_status
) VALUES
    (1, 1, 1, 'venue_1', 'Grand Hall Mumbai', '1 Main Street, Mumbai', 500, 'RESERVED', 'SYNCED'),
    (2, 1, 1, 'venue_2', 'Harbor Arena Mumbai', '22 Marine Drive, Mumbai', 1200, 'RESERVED', 'SYNCED'),
    (3, 1, 2, 'venue_3', 'Capital Dome Delhi', '5 Central Avenue, Delhi', 900, 'GA', 'SYNCED'),
    (4, 1, 2, 'venue_4', 'Indraprastha Grounds', '88 Ring Road, Delhi', 1500, 'GA', 'SYNCED'),
    (5, 1, 3, 'venue_5', 'TechPark Theatre', '77 Residency Road, Bengaluru', 800, 'RESERVED', 'SYNCED'),
    (6, 1, 3, 'venue_6', 'Garden Convention Center', '101 MG Road, Bengaluru', 2000, 'GA', 'SYNCED')
ON CONFLICT (eventbrite_venue_id) DO NOTHING;

-- Users (admin + 5 demo users)
INSERT INTO users (id, email, password_hash, role, status)
VALUES
    (1, 'admin@eventplatform.com', '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES', 'ADMIN', 'ACTIVE'),
    (101, 'demo+user1@eventplatform.com', '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES', 'USER', 'ACTIVE'),
    (102, 'demo+user2@eventplatform.com', '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES', 'USER', 'ACTIVE'),
    (103, 'demo+user3@eventplatform.com', '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES', 'USER', 'ACTIVE'),
    (104, 'demo+user4@eventplatform.com', '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES', 'USER', 'ACTIVE'),
    (105, 'demo+user5@eventplatform.com', '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES', 'USER', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_wallets (user_id, balance_amount, currency)
SELECT u.id, 1000, 'INR'
FROM users u
WHERE u.id IN (1, 101, 102, 103, 104, 105)
ON CONFLICT (user_id) DO NOTHING;

-- Preference options for profile onboarding
INSERT INTO preference_options (type, value, active, sort_order)
VALUES
  ('CITY', 'Mumbai', TRUE, 1),
  ('CITY', 'Delhi', TRUE, 2),
  ('CITY', 'Bengaluru', TRUE, 3),
  ('GENRE', 'MUSIC', TRUE, 1),
  ('GENRE', 'COMEDY', TRUE, 2),
  ('GENRE', 'THEATRE', TRUE, 3)
ON CONFLICT (type, value) DO NOTHING;

-- Event catalog (12 events)
INSERT INTO event_catalog (
    organization_id, city_id, venue_id, eventbrite_event_id, name, description, url,
    start_time, end_time, state, source, currency
) VALUES
    (1, 1, 1, 'event_1',  'Mumbai Music Night', 'Seeded event', 'https://example.test/events/event_1',  '2026-07-10 19:00:00+05:30', '2026-07-10 22:00:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 1, 2, 'event_2',  'Mumbai Stand-up Gala', 'Seeded event', 'https://example.test/events/event_2',  '2026-07-12 18:30:00+05:30', '2026-07-12 21:00:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 2, 3, 'event_3',  'Delhi Indie Fest', 'Seeded event', 'https://example.test/events/event_3',  '2026-07-14 19:00:00+05:30', '2026-07-14 22:30:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 2, 4, 'event_4',  'Delhi Comedy League', 'Seeded event', 'https://example.test/events/event_4',  '2026-07-16 20:00:00+05:30', '2026-07-16 22:00:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 3, 5, 'event_5',  'Bengaluru Jazz Evenings', 'Seeded event', 'https://example.test/events/event_5',  '2026-07-18 19:30:00+05:30', '2026-07-18 22:30:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 3, 6, 'event_6',  'Bengaluru Theatre Showcase', 'Seeded event', 'https://example.test/events/event_6',  '2026-07-20 18:00:00+05:30', '2026-07-20 21:30:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 1, 1, 'event_7',  'Rock Marathon', 'Seeded event', 'https://example.test/events/event_7',  '2026-07-22 19:00:00+05:30', '2026-07-22 23:00:00+05:30', 'DRAFT', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 1, 2, 'event_8',  'Retro Live', 'Seeded event', 'https://example.test/events/event_8',  '2026-07-24 19:00:00+05:30', '2026-07-24 22:00:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 2, 3, 'event_9',  'Delhi Spoken Word', 'Seeded event', 'https://example.test/events/event_9',  '2026-07-26 18:00:00+05:30', '2026-07-26 20:00:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 2, 4, 'event_10', 'North India Pop Fest', 'Seeded event', 'https://example.test/events/event_10', '2026-07-28 19:00:00+05:30', '2026-07-28 23:00:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 3, 5, 'event_11', 'Bengaluru Techno Night', 'Seeded event', 'https://example.test/events/event_11', '2026-07-30 20:00:00+05:30', '2026-07-31 00:00:00+05:30', 'DRAFT', 'EVENTBRITE_EXTERNAL', 'INR'),
    (1, 3, 6, 'event_12', 'Summer Closing Concert', 'Seeded event', 'https://example.test/events/event_12', '2026-08-01 18:30:00+05:30', '2026-08-01 22:30:00+05:30', 'PUBLISHED', 'EVENTBRITE_EXTERNAL', 'INR')
ON CONFLICT (eventbrite_event_id) DO NOTHING;

-- Show slots (12 slots)
INSERT INTO show_slot (
    id, organization_id, venue_id, city_id, title, description, start_time, end_time,
    seating_mode, capacity, source_seatmap_id, is_recurring, recurrence_rule,
    status, eb_event_id
) VALUES
    (101, 1, 1, 1, 'Mumbai Music Night Slot', 'Seed slot', '2026-07-10 19:00:00+05:30', '2026-07-10 22:00:00+05:30', 'RESERVED', 300, 'seatmap_1', FALSE, NULL, 'ACTIVE', 'event_1'),
    (102, 1, 2, 1, 'Mumbai Stand-up Slot', 'Seed slot', '2026-07-12 18:30:00+05:30', '2026-07-12 21:00:00+05:30', 'GA', 800, NULL, FALSE, NULL, 'ACTIVE', 'event_2'),
    (103, 1, 3, 2, 'Delhi Indie Fest Slot', 'Seed slot', '2026-07-14 19:00:00+05:30', '2026-07-14 22:30:00+05:30', 'RESERVED', 400, 'seatmap_3', FALSE, NULL, 'ACTIVE', 'event_3'),
    (104, 1, 4, 2, 'Delhi Comedy Slot', 'Seed slot', '2026-07-16 20:00:00+05:30', '2026-07-16 22:00:00+05:30', 'GA', 1000, NULL, FALSE, NULL, 'ACTIVE', 'event_4'),
    (105, 1, 5, 3, 'Bengaluru Jazz Slot', 'Seed slot', '2026-07-18 19:30:00+05:30', '2026-07-18 22:30:00+05:30', 'RESERVED', 350, 'seatmap_5', FALSE, NULL, 'ACTIVE', 'event_5'),
    (106, 1, 6, 3, 'Theatre Showcase Slot', 'Seed slot', '2026-07-20 18:00:00+05:30', '2026-07-20 21:30:00+05:30', 'GA', 1200, NULL, FALSE, NULL, 'ACTIVE', 'event_6'),
    (107, 1, 1, 1, 'Rock Marathon Slot', 'Seed slot', '2026-07-22 19:00:00+05:30', '2026-07-22 23:00:00+05:30', 'RESERVED', 320, 'seatmap_1', FALSE, NULL, 'DRAFT', 'event_7'),
    (108, 1, 2, 1, 'Retro Live Slot', 'Seed slot', '2026-07-24 19:00:00+05:30', '2026-07-24 22:00:00+05:30', 'GA', 900, NULL, FALSE, NULL, 'ACTIVE', 'event_8'),
    (109, 1, 3, 2, 'Spoken Word Slot', 'Seed slot', '2026-07-26 18:00:00+05:30', '2026-07-26 20:00:00+05:30', 'RESERVED', 280, 'seatmap_3', FALSE, NULL, 'ACTIVE', 'event_9'),
    (110, 1, 4, 2, 'Pop Fest Slot', 'Seed slot', '2026-07-28 19:00:00+05:30', '2026-07-28 23:00:00+05:30', 'GA', 1400, NULL, FALSE, NULL, 'ACTIVE', 'event_10'),
    (111, 1, 5, 3, 'Techno Night Slot', 'Seed slot', '2026-07-30 20:00:00+05:30', '2026-07-31 00:00:00+05:30', 'RESERVED', 300, 'seatmap_5', FALSE, NULL, 'DRAFT', 'event_11'),
    (112, 1, 6, 3, 'Closing Concert Slot', 'Seed slot', '2026-08-01 18:30:00+05:30', '2026-08-01 22:30:00+05:30', 'GA', 1600, NULL, FALSE, NULL, 'CANCELLED', 'event_12')
ON CONFLICT (id) DO NOTHING;

-- Pricing tiers (2 per slot)
INSERT INTO show_slot_pricing_tier (
    id, slot_id, name, price_amount, currency, quota, tier_type,
    eb_ticket_class_id, group_discount_threshold, group_discount_percent
)
SELECT
    (s.id * 10) + t.tier_no AS id,
    s.id,
    CASE WHEN t.tier_no = 1 THEN 'GENERAL' ELSE 'VIP' END AS name,
    CASE WHEN t.tier_no = 1 THEN 799.00 ELSE 1499.00 END AS price_amount,
    'INR',
    CASE WHEN t.tier_no = 1 THEN 240 ELSE 60 END AS quota,
    'PAID',
    'demo_tc_' || s.id || '_' || t.tier_no,
    4,
    10.00
FROM show_slot s
CROSS JOIN (VALUES (1), (2)) AS t(tier_no)
WHERE s.id BETWEEN 101 AND 112
ON CONFLICT (id) DO NOTHING;

-- Seat templates per venue for reserved seat provisioning
INSERT INTO venue_seat (venue_id, section, row_label, seat_number, tier_name, is_accessible)
SELECT
    v.venue_id,
    'MAIN',
    'R' || r::text,
    s::text,
    CASE WHEN s <= 10 THEN 'VIP' ELSE 'GENERAL' END,
    (s % 15 = 0)
FROM (VALUES (1), (2), (5)) AS v(venue_id)
CROSS JOIN generate_series(1, 3) AS r
CROSS JOIN generate_series(1, 20) AS s
ON CONFLICT (venue_id, section, row_label, seat_number) DO NOTHING;

-- Seats for reserved slots
WITH reserved_slots AS (
    SELECT 101 AS slot_id UNION ALL
    SELECT 103 UNION ALL
    SELECT 105 UNION ALL
    SELECT 107 UNION ALL
    SELECT 109 UNION ALL
    SELECT 111
)
INSERT INTO seats (
    show_slot_id, pricing_tier_id, eb_ticket_class_id, seat_number,
    row_label, section, lock_state
)
SELECT
    rs.slot_id,
    CASE WHEN n <= 10 THEN (rs.slot_id * 10) + 2 ELSE (rs.slot_id * 10) + 1 END AS pricing_tier_id,
    'demo_tc_' || rs.slot_id || '_' || CASE WHEN n <= 10 THEN '2' ELSE '1' END,
    'A-' || n::text,
    'A',
    'MAIN',
    'AVAILABLE'
FROM reserved_slots rs
CROSS JOIN generate_series(1, 20) AS n
ON CONFLICT (show_slot_id, seat_number) DO NOTHING;

-- Promotions and coupons
INSERT INTO promotions (
    id, org_id, name, discount_type, discount_value, scope, eb_event_id,
    max_usage_limit, per_user_cap, valid_from, valid_until, status
) VALUES
    (201, 1, 'Demo Monsoon Offer', 'PERCENT', 10.0000, 'EVENT', 'event_3', 500, 2, '2026-07-01 00:00:00+05:30', '2026-08-31 23:59:59+05:30', 'ACTIVE'),
    (202, 1, 'Demo Welcome Flat', 'FIXED', 150.0000, 'ORG', NULL, 1000, 1, '2026-07-01 00:00:00+05:30', '2026-12-31 23:59:59+05:30', 'ACTIVE'),
    (203, 1, 'Demo Expired', 'PERCENT', 20.0000, 'EVENT', 'event_7', 100, 1, '2026-05-01 00:00:00+05:30', '2026-06-01 23:59:59+05:30', 'INACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO coupons (
    id, promotion_id, org_id, code, status, redemption_count, eb_discount_id, eb_sync_status
) VALUES
    (301, 201, 1, 'WELCOME10', 'ACTIVE', 12, 'discount_1', 'SYNCED'),
    (302, 201, 1, 'DEMO_EVENT10', 'ACTIVE', 4, 'discount_2', 'SYNCED'),
    (303, 202, 1, 'DEMOFLAT150', 'ACTIVE', 9, 'discount_3', 'SYNCED'),
    (304, 202, 1, 'DEMOUSERONLY', 'ACTIVE', 2, 'discount_4', 'SYNCED'),
    (305, 202, 1, 'DEMOLIMITED', 'ACTIVE', 49, 'discount_5', 'SYNCED'),
    (306, 203, 1, 'EXPIRED_CODE', 'INACTIVE', 99, 'discount_6', 'SYNCED'),
    (307, 201, 1, 'DEMO20EVENT', 'ACTIVE', 0, 'discount_7', 'SYNCED'),
    (308, 202, 1, 'DEMO100', 'ACTIVE', 1, 'discount_8', 'SYNCED')
ON CONFLICT (id) DO NOTHING;

-- Demo confirmed bookings + payments + tickets
INSERT INTO bookings (
    id, booking_ref, cart_id, user_id, event_id, slot_id, status,
    stripe_payment_intent_id, stripe_charge_id, total_amount, currency, org_id, slot_start_time
) VALUES
    (9001, 'BK-DEMO-0001', 50001, 101, 1, 101, 'CONFIRMED', 'pi_demo_0001', 'ch_demo_0001', 159800, 'INR', 1, '2026-07-10 19:00:00+05:30'),
    (9002, 'BK-DEMO-0002', 50002, 102, 3, 103, 'CONFIRMED', 'pi_demo_0002', 'ch_demo_0002', 79900, 'INR', 1, '2026-07-14 19:00:00+05:30'),
    (9003, 'BK-DEMO-0003', 50003, 103, 5, 105, 'CONFIRMED', 'pi_demo_0003', 'ch_demo_0003', 149900, 'INR', 1, '2026-07-18 19:30:00+05:30')
ON CONFLICT (id) DO NOTHING;

INSERT INTO booking_items (
    id, booking_id, seat_id, ga_claim_id, ticket_class_id, unit_price, currency, status
) VALUES
    (9101, 9001, NULL, NULL, 'demo_tc_101_1', 79900, 'INR', 'ACTIVE'),
    (9102, 9001, NULL, NULL, 'demo_tc_101_2', 79900, 'INR', 'ACTIVE'),
    (9103, 9002, NULL, NULL, 'demo_tc_103_1', 79900, 'INR', 'ACTIVE'),
    (9104, 9003, NULL, NULL, 'demo_tc_105_2', 149900, 'INR', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments (
    id, booking_id, stripe_payment_intent_id, stripe_charge_id, amount, currency, status
) VALUES
    (9201, 9001, 'pi_demo_0001', 'ch_demo_0001', 159800, 'inr', 'SUCCEEDED'),
    (9202, 9002, 'pi_demo_0002', 'ch_demo_0002', 79900, 'inr', 'SUCCEEDED'),
    (9203, 9003, 'pi_demo_0003', 'ch_demo_0003', 149900, 'inr', 'SUCCEEDED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO e_tickets (
    id, booking_id, booking_item_id, qr_code_data, pdf_url, status
) VALUES
    (9301, 9001, 9101, 'QR-BK-DEMO-0001-1', '/tickets/demo/BK-DEMO-0001-1.pdf', 'VALID'),
    (9302, 9001, 9102, 'QR-BK-DEMO-0001-2', '/tickets/demo/BK-DEMO-0001-2.pdf', 'VALID'),
    (9303, 9002, 9103, 'QR-BK-DEMO-0002-1', '/tickets/demo/BK-DEMO-0002-1.pdf', 'VALID'),
    (9304, 9003, 9104, 'QR-BK-DEMO-0003-1', '/tickets/demo/BK-DEMO-0003-1.pdf', 'VALID')
ON CONFLICT (id) DO NOTHING;

COMMIT;
