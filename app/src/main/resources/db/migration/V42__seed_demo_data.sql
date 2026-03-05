-- owner: shared demo seed
-- Idempotent demo data for local/testing environments.

INSERT INTO organization (
    id,
    name,
    slug,
    email,
    country_code,
    timezone,
    eventbrite_org_id,
    status
)
VALUES (
    1,
    'TestOrg',
    'testorg',
    'admin@eventplatform.com',
    'IN',
    'Asia/Kolkata',
    'org_1',
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO city (id, organization_id, name, description, state, country_code)
VALUES (1, 1, 'Mumbai', 'Primary demo city', 'MH', 'IN')
ON CONFLICT (organization_id, name) DO NOTHING;

INSERT INTO venue (
    id,
    organization_id,
    city_id,
    eventbrite_venue_id,
    name,
    address,
    capacity,
    seating_mode,
    sync_status
)
VALUES (
    1,
    1,
    1,
    'venue_1',
    'Grand Hall',
    '1 Main Street, Mumbai',
    500,
    'RESERVED',
    'SYNCED'
)
ON CONFLICT (eventbrite_venue_id) DO NOTHING;

INSERT INTO users (email, password_hash, role, status)
VALUES (
    'admin@eventplatform.com',
    '$2a$10$4pxLa2TsqBjiofqCgY4JReGp6OfTL4CjKRIK46oNLvQtNkYv55yES',
    'ADMIN',
    'ACTIVE'
)
ON CONFLICT (LOWER(email)) DO NOTHING;

INSERT INTO user_wallets (user_id, balance_amount, currency)
SELECT id, 0, 'INR'
FROM users
WHERE LOWER(email) = LOWER('admin@eventplatform.com')
ON CONFLICT (user_id) DO NOTHING;
