# Mock Eventbrite API (FastAPI)

Local mock for Eventbrite API v3, aligned to `docs/eventbriteapiv3public.apib` with project-specific constraints:
- Read-only Venues, Orders, Attendees
- Org-level Discounts only
- Seatmaps org-level only
- Refunds are read via `order.refund_request` (no refund APIs)
- No Webhook registration API (dashboard-only); outbound webhook delivery can be simulated via `/mock/config`

## Run (uv)
```bash
cd mock-eventbrite-api
uv venv
source .venv/bin/activate
uv sync
uv run uvicorn app.main:app --host 0.0.0.0 --port 8888 --reload
```

Base URL: `http://localhost:8888`

## Auth
Provide either:
- `Authorization: Bearer <token>`
- `?token=<token>` query param

Missing/empty token returns APIB error envelope.

## Seeded IDs
Baseline IDs are stable and re-seeded on startup and `/mock/reset`:
- Organization: `org_1`
- Venue: `venue_1`
- Events: `event_1`, `series_1` (series parent), `event_2` (series child)
- Ticket Class: `ticket_1`
- Capacity Tier: `capacity_1`
- Seat Map: `seatmap_1`
- Discount: `discount_1`
- Order: `order_1`
- Attendee: `attendee_1`

Heavy profile adds additional demo data:
- Venues: `venue_2` to `venue_6`
- Events: `event_3` to `event_14`
- Ticket Classes: `ticket_3+`
- Discounts: `discount_2+`
- Orders/Attendees: `order_2+`, `attendee_2+`

## Mock Admin
- `POST /mock/config` — update runtime config
- `POST /mock/reset` — wipe + re-seed
- `POST /mock/seed` — profile seeding with `append` or `reset` mode
- `GET /mock/dashboard` — state snapshot

Example seed request:
```json
{
  "profile": "heavy",
  "mode": "append"
}
```

Example config:
```json
{
  "transitionDelayMs": 2000,
  "webhookTargetUrl": "http://localhost:8080/api/webhooks/eventbrite",
  "generalRateLimit": 2000,
  "dailyRateLimit": 48000,
  "eventActionRateLimit": 200,
  "failureRates": {"order": 0.0, "refund": 0.0, "webhook": 0.0}
}
```

## Notes
- Order refunds are included in `order.refund_request` in order responses.
- Webhook delivery is fire-and-forget; delivery logs appear in `/mock/dashboard`.
