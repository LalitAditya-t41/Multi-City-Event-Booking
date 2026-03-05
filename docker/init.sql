-- =============================================================================
-- docker/init.sql — PostgreSQL initialization script
--
-- This file is mounted into the postgres container at
-- /docker-entrypoint-initdb.d/init.sql
-- It runs ONCE when the container's data volume is empty (first startup).
-- Flyway in the Spring Boot app creates ALL schema tables on startup.
-- This file only:
--   1. Ensures the database and user exist (pg entrypoint already
--      creates them via POSTGRES_DB / POSTGRES_USER env vars, so these
--      are safety guards only)
--   2. Seeds the minimum required data (organization + city + venue)
--      so FR1–FR10 tests can run immediately without manual SQL steps.
-- =============================================================================

-- Idempotent: re-running this file must never error.

-- ── Seed: Organization (id=1 is hardcoded as app.default-org-id) ─────────────
INSERT INTO organizations (id, name, eb_org_id)
VALUES (1, 'TestOrg', 'org_1')
ON CONFLICT (id) DO NOTHING;

-- ── Seed: City ────────────────────────────────────────────────────────────────
INSERT INTO cities (name, country, timezone)
VALUES ('Mumbai', 'India', 'Asia/Kolkata')
ON CONFLICT DO NOTHING;

-- ── Seed: Venue ───────────────────────────────────────────────────────────────
-- eb_venue_id='venue_1' matches the mock-eventbrite pre-seeded venue ID.
INSERT INTO venues (name, address, city_id, capacity, eb_venue_id, org_id)
VALUES ('Grand Hall', '1 Main Street, Mumbai', 1, 500, 'venue_1', 1)
ON CONFLICT DO NOTHING;

-- ── Seed: Admin User ──────────────────────────────────────────────────────────
-- BCrypt hash of "Admin@1234" (cost 10). Change password on first real use.
INSERT INTO users (email, password_hash, first_name, last_name, role, org_id, status)
VALUES (
    'admin@eventplatform.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LnOER1iz02M',
    'Admin',
    'User',
    'ROLE_ADMIN',
    1,
    'ACTIVE'
)
ON CONFLICT (email) DO NOTHING;

-- Wallet for admin (required by schema NOT NULL constraint on user_wallets)
INSERT INTO user_wallets (user_id, balance, currency)
SELECT id, 0, 'INR' FROM users WHERE email = 'admin@eventplatform.com'
ON CONFLICT DO NOTHING;

-- ── Seed: Cancellation Policy (default) ───────────────────────────────────────
INSERT INTO cancellation_policies (name, full_refund_window_hours, partial_refund_window_hours, partial_refund_percent, no_refund_window_hours, is_default)
VALUES ('Standard Policy', 48, 24, 50, 0, true)
ON CONFLICT DO NOTHING;
