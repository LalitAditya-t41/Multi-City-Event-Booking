
  # Mock Eventbrite API (FastAPI) — Updated Plan (Per Changes)

  Summary
  Build a standalone FastAPI mock service under mock-eventbrite-api/ that implements APIB-only Eventbrite v3
  endpoints with a /v3/* prefix, full APIB request/response schemas for those endpoints, Bearer or ?token= auth, APIB
  error envelopes, rate limiting, webhook delivery simulation (config-driven only), and SQLite in-memory persistence
  with full seed data on startup and /mock/reset.

  ———

  ## Changes Applied (From Your Update)

  - Event creation path fixed: POST /v3/organizations/{org_id}/events/
  - EbVenueService read-only: no create/update endpoints
  - EbScheduleService: GET /v3/series/{event_series_id}/ only (not /events/{id}/series/)
  - EbCapacityService: Seatmaps are org-level only, no event seatmap create
  - EbOrderService read-only: no order creation
  - EbAttendeeService read-only: no attendee creation
  - EbDiscountSyncService org-level discounts only
  - EbRefundService read-only: refund status read via order.refund_request, no refunds API
  - EbWebhookService: no webhook registration API (dashboard-only). Mock still supports outbound webhook delivery
    based on config, but no /v3/webhooks endpoints.

  ———

  ## Decisions Locked In

  - APIB-only endpoints for v3 paths (minus the removed capabilities above).
  - Base path: /v3/* at http://localhost:8888.
  - Auth: Bearer header or ?token=.
  - DB: SQLite in-memory + auto-seed; /mock/reset re-seeds.
  - Seed IDs: fixed, stable IDs documented in README.
  - /mock endpoints: keep /mock/config, /mock/reset, /mock/dashboard.
  - Rate limiting + webhook delivery simulation: included (webhook target configured via /mock/config).

  ———

  ## Scope of Endpoints (APIB-only, with removals)

  Events

  1. POST /v3/organizations/{organization_id}/events/
  2. GET /v3/events/{event_id}/
  3. POST /v3/events/{event_id}/
  4. DELETE /v3/events/{event_id}/
  5. GET /v3/venues/{venue_id}/events/
  6. GET /v3/organizations/{organization_id}/events/
  7. GET /v3/series/{event_series_id}/events/
  8. GET /v3/series/{event_series_id}/
  9. GET /v3/events/search/ (deprecated but in APIB)
  10. POST /v3/events/{event_id}/publish/
  11. POST /v3/events/{event_id}/unpublish/
  12. POST /v3/events/{event_id}/cancel/
  13. POST /v3/events/{event_id}/copy/

  Venues (Read-only)

  1. GET /v3/organizations/{organization_id}/venues/
  2. GET /v3/venues/{venue_id}/

  - Removed: venue create/update

  Schedules

  1. POST /v3/events/{event_id}/schedules/

  Ticket Classes

  1. POST /v3/events/{event_id}/ticket_classes/
  2. GET /v3/events/{event_id}/ticket_classes/
  3. GET /v3/events/{event_id}/ticket_classes/{ticket_class_id}/
  4. POST /v3/events/{event_id}/ticket_classes/{ticket_class_id}/
  5. GET /v3/events/{event_id}/ticket_classes/for_sale/

  Capacity

  1. GET /v3/events/{event_id}/capacity_tier/
  2. POST /v3/events/{event_id}/capacity_tier/

  Seat Maps (Org-level only)

  1. GET /v3/organizations/{organization_id}/seatmaps/

  - Removed: POST /v3/events/{event_id}/seatmaps/

  Orders (Read-only)

  1. GET /v3/orders/{order_id}/
  2. GET /v3/organizations/{organization_id}/orders/
  3. GET /v3/events/{event_id}/orders/
  4. GET /v3/users/{user_id}/orders/

  - Removed: any order creation

  Attendees (Read-only)

  1. GET /v3/events/{event_id}/attendees/
  2. GET /v3/events/{event_id}/attendees/{attendee_id}/
  3. GET /v3/organizations/{organization_id}/attendees/

  - Removed: any attendee creation

  Discounts (Org-level)

  1. POST /v3/organizations/{organization_id}/discounts/
  2. GET /v3/organizations/{organization_id}/discounts/
  3. GET /v3/discounts/{discount_id}/
  4. POST /v3/discounts/{discount_id}/
  5. DELETE /v3/discounts/{discount_id}/

  Refunds

  - Removed: /refunds/* endpoints
  - Source of truth: order.refund_request field in GET order responses

  Webhooks

  - Removed: all /v3/webhooks/* endpoints
  - Webhook delivery simulation only via /mock/config and internal transition triggers

  Mock Admin

  1. POST /mock/config
  2. POST /mock/reset
  3. GET /mock/dashboard

  ———

  ## Implementation Steps (Adjusted)

  1. Project skeleton under mock-eventbrite-api/ (same as prior plan).
  2. DB + Seed
      - SQLite in-memory with shared connection.
      - Seed full dataset including orgs, venues, events, ticket classes, capacity tiers, seatmaps, discounts,
        orders, attendees, refund_request data in orders.
      - /mock/reset re-seeds.
  3. Schemas
      - Keep APIB-aligned Pydantic models for Events, Venues, Ticket Classes, Orders, Attendees, Discounts, Capacity
        Tier, Seat Maps, Pagination, Error.
      - Orders include refund_request structure per APIB “Refund Request Fields”.
  4. Auth
      - Bearer header or ?token=.
      - Missing token → NO_AUTH (401). Invalid header → INVALID_AUTH_HEADER (400).
  5. Error Handling
      - Standard APIB error envelope.
      - Remove refund and webhook error handling (endpoints gone).
      - Venue create/update errors removed.
  6. Rate Limiting
      - Keep hourly/daily/event-action limits.
      - Event-actions are POSTs for: create event, publish, unpublish, cancel, copy, schedules.
  7. Business Logic
      - Events: CRUD + publish/unpublish/cancel/copy with status transitions.
      - Venues: read-only list/get.
      - Schedules: create for series parent events.
      - Ticket Classes: CRUD + for_sale availability.
      - Capacity Tier: read/update.
      - Seatmaps: org-level list only.
      - Orders/Attendees: read-only from seed.
      - Discounts: org-level CRUD.
      - Refunds: read via order refund_request only.
  8. Webhook Simulation (Config-only)
      - No registration API.
      - If webhook_target_url set via /mock/config, then status transitions trigger delivery attempts.
      - Delivery logs available in /mock/dashboard.
  9. Docs
      - README updated to call out read-only services and removed endpoints.
      - .env.example + /mock/config usage.

  ———

  ## Public APIs / Interface Changes

  - Removed endpoints: venue create/update, event seatmap create, refunds, webhooks registration, order create,
    attendee create.
  - Refund status now only via order.refund_request field on order GETs/lists.

  ———

  ## Test Plan (Adjusted)

  - Unit: event state transitions, discount CRUD, ticket class availability, rate limiter.
  - Integration:
      - Auth: header and query token accepted; missing token returns NO_AUTH.
      - Venue read-only; create/update returns METHOD_NOT_ALLOWED.
      - Refund/webhook endpoints return NOT_FOUND or METHOD_NOT_ALLOWED.
      - Orders/attendees only list/retrieve.
      - /series/{event_series_id}/ path verified.
      - Seatmaps list at org level only.
  - Edge:
      - Publish/unpublish/cancel transitions.
      - Discounts cannot be deleted if used.
      - Refund_request presence in order response.
      - Rate limit headers and 429.

  ———

  ## Assumptions

  - APIB-only paths are authoritative, except for explicit removals above.
  - Webhook delivery simulation is allowed without registration API.
  - Seed-only data is the source for orders/attendees/refunds.