# PRODUCT.md — Module Registry & Product Map

**Owner:** Lalit  
**Domain:** EntertainmentTech  
**Last Updated:** March 2026 — FR5 updated: Stripe replaces Eventbrite Checkout Widget for payments and refunds

**Architecture:** Modular Monolith — Spring Boot 3.5.11, Java 21

**External Integrations:** Eventbrite (event listing sync + organizer webhooks only) | Stripe (payments + refunds) | OpenAI GPT-4o (AI assistant)

⚠ **Important:** This is the single source of truth for module ownership, boundaries, inter-module communication, and the Eventbrite ACL. Every agent and developer MUST read this before touching any module.

---

## 1. The 7 Modules — What Each One Owns

### Module Ownership

| Module | Owns | Must Not Own |
|---|---|---|
| discovery-catalog | Events, Venues, Cities, Eventbrite catalog sync | Bookings, Users, Payments |
| scheduling | Show slots, time slot config, conflict validation, Eventbrite event sync | Bookings, Users |
| identity | Users, JWT auth, profiles, preferences | Bookings, Payments, Events |
| booking-inventory | Seats, SeatMap, Cart, Seat Lock State Machine, Pricing | Payments, Reviews, Users |
| payments-ticketing | Bookings, Payments (Stripe), E-Tickets, Cancellations, Refunds (Stripe), Wallet | Seats (reads cart only) |
| promotions | Coupons, Promotions, Eligibility rules, EB discount sync | Bookings, Payments, Users |
| engagement | Reviews, Moderation, AI Chatbot, RAG pipeline | Bookings (reads attended status only) |

- `admin/` — owns no domain. Orchestrates and aggregates across modules. No `@Entity` classes.
- `shared/` — owns all external integrations (Eventbrite ACL), JWT, base classes, common DTOs.
- `app/` — entry point only. `@SpringBootApplication`. Zero business logic.

## 2. Module Dependency Order

```text
shared/               ← foundational — no dependency on any module
    ↓
discovery-catalog     ← depends on shared only
    ↓
scheduling            ← depends on shared + discovery-catalog (reads venue/event via REST)
    ↓
identity              ← depends on shared only
    ↓
booking-inventory     ← depends on shared + discovery-catalog (reads seat layout via REST);
                          scheduling slot/pricing data via SlotSummaryReader + SlotPricingReader;
                          payment confirmation status via PaymentConfirmationReader
                          (all three: shared interfaces, implemented in owning module, injected at runtime — no HTTP)
    ↓
payments-ticketing    ← depends on shared + booking-inventory + identity;
                          cart snapshot data via CartSnapshotReader
                          (shared interface, implemented in booking-inventory, injected at runtime — no HTTP)
    ↓
promotions            ← depends on shared + booking-inventory + identity (reads via REST)
    ↓
engagement            ← depends on shared + payments-ticketing + identity (reads via REST)
    ↓
admin/                ← thin orchestration — reads from all modules, writes via REST APIs
    ↓
app/                  ← entry point only
```

**Rule:** A module NEVER imports from a module at the same level or below it. Dependency direction is strictly downward — no circular imports.

## 3. How Modules Communicate — 3 Rules

### Rule 1 — Never import another module's `@Service` or `@Repository` directly
If Module B needs data from Module A, it calls Module A's REST endpoint.

**Approved exception — Shared Reader Interfaces (in-process reads only):**
For hot-path, high-frequency reads in a modular monolith, a module may define a read-only interface in `shared/common/service/` and implement it in the owning module. The consuming module injects the `shared/` interface — never the owning module's concrete `@Service`. This avoids HTTP overhead on the critical path while preserving module boundaries.

Current implementations:
- `SlotSummaryReader` (`shared/common/service/`) → implemented by `SlotSummaryReaderImpl` in `scheduling` → consumed by `booking-inventory` (slot validation path)
- `SlotPricingReader` (`shared/common/service/`) → implemented by `SlotPricingReaderImpl` in `scheduling` → consumed by `booking-inventory` (pricing/provisioning path)
- `CartSnapshotReader` (`shared/common/service/`) → implemented by `CartSnapshotReaderImpl` in `booking-inventory` → consumed by `payments-ticketing` (payment confirmation hot path — replaces internal HTTP `GET /internal/booking/carts/{cartId}/items`)
- `PaymentConfirmationReader` (`shared/common/service/`) → implemented by `PaymentConfirmationReaderImpl` in `payments-ticketing` → consumed by `booking-inventory` `PaymentTimeoutWatchdog` (replaces internal HTTP `GET /api/v1/internal/payments/by-cart/{cartId}`)

Rules for this pattern:
- Interface and DTOs MUST live in `shared/common/service/` and `shared/common/dto/` respectively — never in the owning module
- Implementation is `@Transactional(readOnly = true)` — no writes
- Consuming module caches results where appropriate (`CartPricingService.getSlotPricingCached()`) to avoid repeated reads
- REST endpoints for the same data are preserved for admin/external consumers

### Rule 2 — Use Spring Events for async reactions
When something important happens in Module A, it publishes a Spring `ApplicationEvent`. Other modules react by registering `@EventListener` methods. No direct calls.

- booking-inventory → publishes `CartAssembledEvent`
- payments-ticketing → listens `CartAssembledListener` → initiates payment

- payments-ticketing → publishes `PaymentFailedEvent`
- booking-inventory → listens `PaymentFailedListener` → triggers seat lock rollback

- payments-ticketing → publishes `BookingConfirmedEvent`
- engagement → listens `BookingConfirmedListener` → unlocks review eligibility
- identity → listens `BookingConfirmedListener` → updates order history

### Rule 3 — All Eventbrite API calls go through `shared/eventbrite/service/` only
No module ever calls Eventbrite directly. The ACL facade services are the only entry point.
Inbound Eventbrite webhooks: `EbWebhookController → EbWebhookDispatcher → dispatched as Spring Events → modules listen`.
Modules never consume the raw webhook directly.

## 4. Eventbrite ACL — Facades in `shared/`

### ACL Facade Services

- **EbEventSyncService** (`shared/eventbrite/service/`)
  - Create (org-scoped), update, publish, unpublish, cancel, delete events; pull org event catalog; verify sync integrity
  
- **EbVenueService** (`shared/eventbrite/service/`)
  - Create venues under an org (`POST /organizations/{org_id}/venues/`); update venues (`POST /venues/{venue_id}/`); list org venues (`GET /organizations/{org_id}/venues/`); list events at a venue (`GET /venues/{venue_id}/events/`).
  
- **EbScheduleService** (`shared/eventbrite/service/`)
  - Create recurring event schedules and occurrences; retrieve series info
  
- **EbTicketService** (`shared/eventbrite/service/`)
  - Create/update ticket classes; manage inventory; check availability
  
- **EbCapacityService** (`shared/eventbrite/service/`)
  - Retrieve and update event capacity tiers; list org seat maps; attach seat map to event
  
- **EbOrderService** (`shared/eventbrite/service/`)
  - Retrieve order details for org-level reporting only. **NOT used in FR5 or FR6 payment flows** — Stripe owns all payment and order management. Retained only for organizer-side admin reporting and orphan detection.
  
- **EbAttendeeService** (`shared/eventbrite/service/`)
  - Retrieve attendee records; list attendees by event. Used in FR8 (attendance verification for reviews) only. **No longer used in FR5 payment flows** — Stripe and internal DB own all booking records.
  
- **EbDiscountSyncService** (`shared/eventbrite/service/`)
  - Create/update/delete discount codes at org level; sync usage with internal promotions. (Discounts scoped to org, applied to events at checkout.)
  
- **EbRefundService** (`shared/eventbrite/service/`)
  - **Inactive for FR5/FR6.** All refunds are now processed via `StripeRefundService`. Retained in code for legacy compatibility only. Do NOT call for any new refund flow.
  
- **EbWebhookService** (`shared/eventbrite/service/`)
  - **NOT USED in production.** Webhook registration not available via API; only via Dashboard. Mock service simulates webhook delivery for testing.

> **Note:** `EbOrderService` is **not used in FR5 (payment) or FR6 (refund) flows**. Stripe owns all payment, order, and refund processing. `EbOrderService` is retained only for organizer-side admin reporting.

> **Note:** `EbAttendeeService` is used **only in FR8** (attendance verification before review submission). It reads attendee records to confirm the user attended the event.

> **Note:** `EbRefundService` is **inactive**. All refunds are processed via Stripe (`StripeRefundService`). The EB facade is retained in code for legacy compatibility only.

> **Note:** `EbVenueService` supports venue creation (`POST /organizations/{org_id}/venues/`) and updates (`POST /venues/{venue_id}/`). The Eventbrite API v3 exposes both endpoints. Venue creation in your app always pushes to Eventbrite and stores the returned `eb_venue_id`.

### Stripe ACL Facade Services (Payments & Refunds)

All Stripe-related calls go through these three facades in `shared/stripe/service/`. No module calls Stripe HTTP directly.

- **StripePaymentService** (`shared/stripe/service/`)
  - `POST /v1/payment_intents` — create PaymentIntent; returns `client_secret` for frontend Stripe Element
  - `GET /v1/payment_intents/{id}` — retrieve and verify status (`succeeded`) and `amount_received` before confirming booking
  - Stores `stripe_payment_intent_id` and `stripe_charge_id` (`latest_charge`) in `payments` table

- **StripeRefundService** (`shared/stripe/service/`)
  - `POST /v1/refunds` — create full or partial refund by `payment_intent` ID; supports `reason` field
  - `GET /v1/refunds/{id}` — retrieve async refund status (`pending`, `succeeded`, `failed`)
  - Saves `stripe_refund_id` to `refunds` table

- **StripeWebhookHandler** (`shared/stripe/service/`)
  - Verifies `Stripe-Signature` header via `Webhook.constructEvent(payload, sigHeader, whsecret)`
  - Routes events as Spring Events: `payment_intent.succeeded` → idempotent booking confirm; `payment_intent.payment_failed` / `payment_intent.canceled` → `PaymentFailedEvent`; `refund.updated` → update refund status; `refund.failed` → alert ops + notify user
  - Registered via Stripe Dashboard (Test mode) at `POST /api/webhooks/stripe`

> **Stripe Sandbox:** Use `sk_test_...` / `pk_test_...` keys. Test card `4242 4242 4242 4242` for success, `4000 0000 0000 0002` for decline, `4000 0000 0000 7726` for async refund simulation. See `docs/FR5-reworked-v2.md` and `docs/stripe-api-reference.md` for full test card matrix.

### Mock Eventbrite Service — Development & Testing

**Location:** `mock-eventbrite-api/` (Python FastAPI, in-memory)

**Purpose:** Enable local development and integration testing without making real API calls to production Eventbrite. All 10 ACL façades route through this mock service when `spring.profiles.active=mock`.

**Architecture:** Python FastAPI server (async, non-blocking) provides full mock implementations of Eventbrite API v3 endpoints. Data is stored in-memory (no database persistence by default). For integration tests, add SQLite or PostgreSQL backend to the mock service.

**Endpoints Mocked:**
- `POST/GET /organizations/{org_id}/events/` 
- `POST/GET /venues/{venue_id}/events/`
- `GET /events/{event_id}/`
- `POST/GET /ticket_classes/`
- `POST/GET /inventory_tiers/`
- `POST/GET /capacity_tiers/`
- `POST/GET /orders/`
- `POST/GET /attendees/`
- `POST/GET /discounts/`
- `POST/GET /webhooks/` (webhook registration)
- ... and 100+ supporting endpoints

**Configuration:**
- **Spring Profile:** `mock` (Spring Boot automatically routes all ACL façade calls to mock service)
- **Environment Variable:** `EVENTBRITE_API_URL=http://localhost:9000/v3/` (when using mock profile)
- **Startup Command:** 
  ```bash
  cd mock-eventbrite-api
  python -m uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
  ```

**Trade-Offs:**
| Aspect | Behavior |
|---|---|
| **Data Persistence** | In-memory; lost on shutdown. Acceptable for rapid iteration. |
| **Latency** | Minimal (<5ms); no real network calls. |
| **API Fidelity** | Matches Eventbrite API v3 schema exactly; test against real contract. |
| **Concurrency** | Handles parallel requests; respects single-flight locks for testing. |
| **Webhooks** | Simulates webhook delivery for testing event-driven flows. |

**Usage Pattern:**
- **Local Dev:** Run mock service; set `spring.profiles.active=mock` in `application-mock.properties`
- **Integration Tests:** Use Spring `@ActiveProfiles("mock")` to inject mock implementations
- **Production:** Set `spring.profiles.active=production`; real Eventbrite API calls occur

**See** `mock-eventbrite-api/README.md` **for detailed mock service documentation, sample payloads, and configuration options.**

## 5. Eventbrite Integration Principles

**Core Architecture:** Your internal DB is the primary source of truth. **Stripe is the payment and refund backbone** (FR5, FR6). **Eventbrite is a passive event listing sync layer** — it hosts the public event listing, tracks ticket class capacity, and fires organizer-side webhooks (`event.updated`, `event.unpublished`). Every entity is created internally first — then pushed to Eventbrite for listing purposes. The `eventbrite_event_id` returned from each push is stored in your DB and is the bridge for listing sync.

### 5.1 Internal-First, Then Eventbrite

The pattern for every flow that touches Eventbrite is always:

1. User/Admin performs action in YOUR app
2. Internal DB is updated / validated first
3. Eventbrite API is called via `shared/` ACL facade
4. Eventbrite returns ID or confirmation
5. Your DB stores the Eventbrite ID (`eventbrite_event_id`, `eventbrite_venue_id`, etc.)
6. Both systems are now in sync — your DB is source of truth
7. For payments and refunds: Stripe is called instead of Eventbrite — `stripe_payment_intent_id` and `stripe_charge_id` are stored in your `payments` table

### 5.2 The `eventbrite_event_id` is the Spine of the System

Every downstream flow depends on `eventbrite_event_id` being present and valid in your DB:
- FR5 (Payment) — event listing on Eventbrite uses the `event_id`; Stripe drives the actual payment, so EB checkout widget is **not required** for payment to succeed, but EB passive sync still depends on `eventbrite_event_id` being valid
- FR6 (Cancellation — organizer-initiated) — `event.updated` / `event.unpublished` webhooks from EB identify the event to bulk-cancel via `eventbrite_event_id`
- FR7 (Discounts) — Eventbrite discount codes are scoped to `event_id`
- FR8 (Reviews) — Verifying attended booking cross-references Eventbrite attendee list by `event_id`
- FR10 (Seat Lock) — Availability check hits Eventbrite `ticket_classes` for that `event_id`

Without FR2 sync: If `eventbrite_event_id` is null or orphaned, EB listing sync and capacity tracking break. However, payments (FR5) and refunds (FR6 buyer-initiated) are fully functional through Stripe regardless.

### 5.3 What Eventbrite Cannot Do — Internal Responsibilities

| Gaps | Handled Internally |
|---|---|
| User creation/sync | No user write API on Eventbrite. Identity is 100% internal. Users are linked via order email post-purchase. |
| Conflict validation | No conflict API on Eventbrite. Time slot conflicts and turnaround gaps are enforced in your internal DB. |
| "Venue creation — POST /organizations/{org_id}/venues/ is available via EbVenueService.createVenue(). Admin creates venues in your app; they are pushed to Eventbrite and eb_venue_id is stored. Venue updates via POST /venues/{venue_id}/. The Eventbrite Dashboard can also manage venues independently — VenueReconciliationJob detects and flags drift."|
| Seat locking | No seat lock API on Eventbrite. The entire state machine (Redis + Spring State Machine) is internal. |
| Cart management | No cart API on Eventbrite. Cart assembly and group discount rules are internal. |
| Order creation | Orders are created **internally** in your `bookings` table when Stripe payment is confirmed. No order creation via Eventbrite API at any point. |
| Attendee creation | Attendee records for FR8 attendance verification come from the Eventbrite attendee list (organizer hosted the event). No attendee creation API called by this app. |
| Payment processing | **Stripe** handles all payments. Backend creates a `PaymentIntent` (`POST /v1/payment_intents`), frontend renders Stripe Payment Element with `client_secret`, user pays, backend verifies via `GET /v1/payment_intents/{id}`. Eventbrite has no role in payment. |
| Single order cancel (buyer) | Buyer cancellations call `POST /v1/refunds` via `StripeRefundService`. Full or partial refund by `payment_intent` ID. No Eventbrite API call for buyer-initiated cancellations. |
| Refund submission | All refunds use `StripeRefundService.createRefund()` — programmatic, synchronous or async. Refund status tracked via `refund.updated` webhook. Eventbrite has no refund role. |
| Webhook registration | No webhook API. Webhooks are registered via Eventbrite Dashboard only. This app simulates webhooks for testing/development. |
| Reviews | No reviews API on Eventbrite. Entire review system is internal. Attendance verification uses attendee read API. |
| Public event search | `GET /events/search/` is deprecated. Event discovery is limited to your org's events. |

## 6. Functional Requirement Flows — Internal + Eventbrite Sync

### FR1 — City Selection → Venue Discovery → Event Listing
**Module:** discovery-catalog

This flow provides the entry point for users browsing events. The internal catalog is the primary source. Eventbrite contributes only events your org has created — public city-wide search is not available (deprecated API).

**Step 1: City Selection**
- Internal App (Your DB / Spring Boot)
  - User selects a city in the app UI
  - App queries internal `cities` table → returns `city_id` and coordinates
  - No Eventbrite call — city data is fully internal
- Eventbrite API Call (via `shared/` ACL)
  - (none — city data is internal only)

**Step 2: Venue Discovery**
- Internal App (Your DB / Spring Boot)
  - App queries internal `venues` table filtered by `city_id`
  - Returns list of venues with address, capacity, seating info
- Eventbrite API Call (via `shared/` ACL)
  - `GET /organizations/{org_id}/venues/` — list all org venues for cross-reference
  - `GET /venues/{venue_id}/` — verify a specific venue exists on Eventbrite side

**Step 3: Event Listing**
- Internal App (Your DB / Spring Boot)
  - App queries internal `event_catalog` table filtered by city + venue
  - Merges internal event records with Eventbrite metadata (pulled on last sync)
  - Returns merged event list to UI
- Eventbrite API Call (via `shared/` ACL)
  - `GET /organizations/{org_id}/events/` — pull org's published events for catalog sync
  - `GET /venues/{venue_id}/events/` — pull events at a specific venue
  - `GET /events/{event_id}/` — fetch individual event details for catalog refresh

**ACL Facades:** `EbEventSyncService`, `EbVenueService`  
**DB Tables:** `cities`, `venues`, `event_catalog`  
**Publishes:** `EventCatalogUpdatedEvent` → engagement (RAG index refresh)

**Sync Strategy:** Catalog sync runs on a schedule (e.g., every 15 min) via `EbEventSyncService`. On sync, pull `GET /organizations/{org_id}/events/` → compare with internal `event_catalog` → upsert changed records → store `eventbrite_event_id`.

### FR2 — Show Scheduling → Time Slot Configuration → Conflict Validation
**Module:** scheduling

This is the most Eventbrite-heavy flow in the system. Every admin-created show slot must produce a corresponding live Eventbrite event. The `eventbrite_event_id` returned from creation is the spine that all downstream flows depend on.

#### Phase 1 — Pre-Creation Setup (One-Time Onboarding)

**Venue Setup (Dashboard-Only)**
- Internal App (Your DB / Spring Boot)
  - Admin registers a venue in the app
  - Internal `venues` table is updated with address, capacity, seating config
- Eventbrite API Call (via `shared/` ACL)
  - **NO API CALLS** — Venues must be created/managed via Eventbrite Dashboard
  - `GET /venues/{venue_id}/events/` — list events at a pre-existing Eventbrite venue (read-only)
  - Store `eventbrite_venue_id` from Dashboard in your `venues` table for reference

#### Phase 2 — Show Slot Creation (Core Sync)

**Admin Creates Show Slot**
- Internal App (Your DB / Spring Boot)
  - Admin fills slot details: venue, date, start time, end time, show name
  - Internal DB validates: no conflicts in `show_slots` table for this venue + time window
  - Internal turnaround gap enforced: compare proposed start against last event end time at venue
  - Only after internal validation passes — Eventbrite API is called
- Eventbrite API Call (via `shared/` ACL)
  - `GET /venues/{venue_id}/events/` — check overlapping events at this venue on Eventbrite
  - `GET /organizations/{org_id}/events/` — check duplicate events in org
  - `POST /organizations/{org_id}/events/` — CREATE event → returns `eventbrite_event_id`
  - Store `eventbrite_event_id` in `show_slots` table in your DB

**Ticket & Capacity Setup (Immediately After Event Creation)**
- Internal App (Your DB / Spring Boot)
  - Internal `pricing_tiers` table defines ticket tiers (VIP, Premium, General)
  - Internal `seat_maps` table defines venue layout and capacity per section
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/ticket_classes/` — create ticket tiers on Eventbrite
  - `POST /events/{event_id}/ticket_classes/{id}/` — update ticket class if edited
  - `GET /events/{event_id}/ticket_classes/` — verify ticket classes are synced
  - `POST /events/{event_id}/inventory_tiers/` — set capacity per tier
  - `POST /events/{event_id}/inventory_tiers/{id}/` — update tier if capacity changes
  - `GET /events/{event_id}/inventory_tiers/` — verify inventory is synced
  - `GET /events/{event_id}/capacity_tier/` — read overall capacity ceiling
  - `POST /events/{event_id}/capacity_tier/` — update if admin changes max capacity
  - `POST /events/{event_id}/seatmaps/` — attach seat map to event (reserved seating)
  - `GET /organizations/{org_id}/seatmaps/` — list available seat maps to pick from

**Publish the Event**
- Internal App (Your DB / Spring Boot)
  - Internal slot status set to ACTIVE in `show_slots` table
  - Ticket buyers can now see the event
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/publish/` — make event live on Eventbrite

#### Phase 3 — Recurring Shows

**Recurring Show Scheduling**
- Internal App (Your DB / Spring Boot)
  - Admin creates a recurring series (e.g., every Saturday)
  - Internal DB generates individual `show_slot` records for each occurrence
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/schedules/` — create recurring occurrences on Eventbrite
  - `GET /events/{event_id}/series/` — verify series is correctly linked

#### Phase 4 — Slot Status Changes

**Status Sync (Update / Unpublish / Cancel / Delete)**
- Internal App (Your DB / Spring Boot)
  - Admin updates slot details → internal `show_slots` record updated first
  - Admin puts slot on hold → internal status set to DRAFT
  - Admin cancels slot → internal status set to CANCELLED → triggers `ShowSlotCancelledEvent`
  - Admin deletes draft slot → internal record deleted
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/` — sync time/venue/name updates to Eventbrite
  - `POST /events/{event_id}/unpublish/` — when slot goes on hold
  - `POST /events/{event_id}/cancel/` — when slot is cancelled
  - `DELETE /events/{event_id}/` — delete draft event (only works before publish)
  - `POST /events/{event_id}/copy/` — copy event template for similar recurring shows

#### Phase 5 — Conflict Validation (Read APIs)

**Orphan Detection & Consistency Check**
- Internal App (Your DB / Spring Boot)
  - Periodic background job (e.g., every hour)
  - Compares internal `show_slots` table (`eventbrite_event_id` list) vs Eventbrite
  - Detects orphaned events: exist on EB but not in internal DB (or vice versa)
  - Flags mismatches for admin review
- Eventbrite API Call (via `shared/` ACL)
  - `GET /events/{event_id}/` — check if `eventbrite_event_id` still exists on EB
  - `GET /organizations/{org_id}/events/` — full org event list for orphan detection
  - `GET /events/{event_id}/ticket_classes/` — verify ticket classes still match internal config
  - `GET /events/{event_id}/capacity_tier/` — verify capacity still matches

**Webhook Setup for FR2**
- Webhook Registration (One-Time — Dashboard-Configured)
  - Internal App (Your DB / Spring Boot)
    - Register webhooks once via Eventbrite Dashboard (not via API)
    - Configure endpoint URL: `https://your-app.com/api/webhooks/eventbrite`
    - Subscribe to: `event.updated`, `event.published`, `event.unpublished`, `order.placed`, `order.updated`, `attendee.updated`
  - Eventbrite API Call (via `shared/` ACL)
    - **NO API for webhook registration** — done via Dashboard only
    - Once registered, Eventbrite POSTs events to your configured endpoint

**ACL Facades:** `EbEventSyncService`, `EbVenueService`, `EbScheduleService`, `EbTicketService`, `EbCapacityService`  
**DB Tables:** `show_slots`, `turnaround_policies`  
**Publishes:** `ShowSlotCreatedEvent` → booking-inventory | `ShowSlotCancelledEvent` → booking-inventory + payments-ticketing

### FR3 — User Registration → Profile Setup → Preference Onboarding
**Module:** identity

Key Point: Eventbrite has NO user creation or update API. Identity is 100% internal. Users are never pushed to Eventbrite during registration. The link between your app user and Eventbrite is established post-purchase via the order email.

**Step 1: User Registration**
- Internal App (Your DB / Spring Boot)
  - User submits email + password + name in your app
  - Backend validates uniqueness in `users` table
  - Password is hashed (BCrypt) and stored
  - JWT token is issued — user is now authenticated
  - No publish Required we will be using the atendee publish that is built later on.

**Step 2: Profile Setup**
- Internal App (Your DB / Spring Boot)
  - User fills in profile details: display name, phone, city, avatar
  - Internal `users` table is updated
  - No sync to Eventbrite at this stage

**Step 3: Preference Onboarding**
- Internal App (Your DB / Spring Boot)
  - User selects preferred genres, cities, notification settings
  - Store the preferences
  - Used for personalized event recommendations from internal catalog

- This is the basic flow of the user registration and onboarding
- The JWT and authentication and API key like 

### FR4 — Seat Selection → Pricing Tier Validation → Cart Assembly
**Module:** booking-inventory

Cart assembly is entirely internal. Eventbrite is consulted only for a real-time availability count before a seat is soft-locked. No cart or seat lock API exists on Eventbrite.

**Step 1: User Selects Seats**
- Internal App (Your DB / Spring Boot)
  - User picks seats from internal SeatMap for the show slot
  - Before locking: `AvailabilityGuard` checks internal seat state
  - Additionally checks real-time count from Eventbrite as secondary validation
- Eventbrite API Call (via `shared/` ACL)
  - `GET /events/{event_id}/ticket_classes/for_sale/` — is ticket count still > 0 on Eventbrite?
  - `GET /events/{event_id}/inventory_tiers/{id}/` — verify inventory tier capacity

**Step 2: Pricing Tier Validation**
- Internal App (Your DB / Spring Boot)
  - Internal PricingTier rules applied: Standard / Premium / VIP pricing
  - Group discount rules evaluated if seat count exceeds threshold
  - Subtotal computed internally
- Eventbrite API Call (via `shared/` ACL)
  - `POST /pricing/calculate_price_for_item/` — validate price calculation against Eventbrite fee structure
  - `GET /events/{event_id}/ticket_classes/` — confirm ticket class prices match internal tiers
  - `GET /organizations/{org_id}/ticket_groups/` — check ticket group bundles

**Step 3: Cart Assembly**
- Internal App (Your DB / Spring Boot)
  - Soft lock acquired via Redis (`AcquireRedisLockAction`) — TTL starts
  - Cart record created in internal `carts` table
  - `CartAssembledEvent` published → payments-ticketing listens
  - Coupon eligibility checked via promotions module REST call
- Eventbrite API Call (via `shared/` ACL)
  - (none — cart is 100% internal)

**Seat Lock State Machine — `booking-inventory/statemachine/`**

```text
AVAILABLE
    │  [SELECT]  AvailabilityGuard (internal DB + Eventbrite ticket_classes count check)
    │            AcquireRedisLockAction
    ▼
SOFT_LOCKED  (held for cart session TTL via Redis)
    │  [CONFIRM]  CartTtlGuard + HardLockAction
    ▼
HARD_LOCKED  (held during Stripe checkout)
    │  [PAY] — user goes through Stripe Payment Element (frontend)
    ▼
PAYMENT_PENDING
    │  [CONFIRM_PAYMENT] backend verifies PaymentIntent.status=succeeded    [RELEASE] on failure
    ▼                                                                              ▼
CONFIRMED (stripe_payment_intent_id stored in booking)                  RELEASED → AVAILABLE
```

**ACL Facades:** `EbTicketService`  
**DB Tables:** `seats`, `seat_maps`, `carts`, `cart_items`, `pricing_tiers`, `group_discount_rules`  
**Redis Keys:** `seat:lock:{seatId}`  
**Publishes:** `CartAssembledEvent` → payments-ticketing

### FR5 — Payment via Stripe → Booking Confirmation → E-Ticket Generation
**Module:** payments-ticketing

**Key Point:** Eventbrite is removed from the payment and order flow entirely. **Stripe (sandbox `sk_test_...`)** owns checkout, payment processing, and refunds. Your backend creates and controls all orders internally. Eventbrite is retained **only** as a passive sync layer — event listing sync and `event.updated` / `event.unpublished` webhooks for organizer-initiated event cancellations.

**Architecture Overview:**
```
Your App (Spring Boot)
├── Stripe API           → PaymentIntent, confirm, refund (full programmatic control)
├── Your DB              → orders, bookings, payments, refunds, e-tickets
└── Eventbrite API       → event listing sync + event.updated / event.unpublished webhooks only
```

**Step 1: Stripe PaymentIntent Creation**
- Internal App (Your DB / Spring Boot)
  - `CartAssembledEvent` received by `payments-ticketing`
  - Backend calculates total `amount` from cart snapshot (in smallest currency unit — paise for INR)
  - Backend calls `StripePaymentService.createPaymentIntent()` via `shared/stripe/service/`
  - `client_secret` from response returned to frontend only — never stored server-side
  - `stripe_payment_intent_id` stored in `payments` table with status `PENDING`
- Stripe API Call (via `shared/stripe/` ACL)
  - `POST /v1/payment_intents` — `amount`, `currency=inr`, `automatic_payment_methods[enabled]=true`, `receipt_email`, `metadata[booking_ref]`, `metadata[event_id]`, `metadata[user_id]`
  - **Save to DB:** `id` (`pi_...`) as `stripe_payment_intent_id` in `payments` table

**Step 2: User Completes Payment via Stripe Payment Element (Frontend)**
- Frontend
  - Stripe Payment Element rendered using `client_secret` received from Step 1
  - User enters card details
  - `stripe.confirmPayment()` called on form submit with a `return_url`
  - On success, Stripe redirects to `return_url` with `payment_intent` as query param
  - Frontend sends `payment_intent_id` to backend via `POST /api/payments/confirm-order`

**Stripe Sandbox Test Cards (key ones):**

| Scenario | Card Number | Result |
|---|---|---|
| Success (Visa) | `4242 4242 4242 4242` | `succeeded` |
| Success (India) | `4000 0035 6000 0008` | `succeeded` |
| Generic decline | `4000 0000 0000 0002` | `card_declined` |
| Insufficient funds | `4000 0000 0000 9995` | `card_declined` / `insufficient_funds` |
| 3DS required → succeeds | `4000 0025 0000 3155` | `requires_action` → `succeeded` |
| Async refund → pending then success | `4000 0000 0000 7726` | Refund `pending` → `succeeded` |

> For all test cards: use any future expiry (e.g. `12/34`) and any CVC. Use `pm_card_visa` for server-side test code.

**Step 3: Backend Confirms Payment**
- Internal App (Your DB / Spring Boot)
  - Backend retrieves PaymentIntent from Stripe to verify `status`
  - Idempotency check: if `booking` already exists for this `payment_intent_id`, return existing
  - Only proceed if `status = succeeded` AND `amount_received == amount`
- Stripe API Call (via `shared/stripe/` ACL)
  - `GET /v1/payment_intents/{id}` via `StripePaymentService.retrievePaymentIntent()`
  - **Save to DB:** `latest_charge` (`ch_...`) as `stripe_charge_id` in `payments` and `bookings` tables

**Step 4: Booking Confirmation (Internal)**
- Internal App (Your DB / Spring Boot)
  - Internal `booking` record created in `bookings` table with `status=CONFIRMED`
  - `booking_items` created from cart snapshot
  - Seat locks transitioned from `HARD_LOCKED` → `CONFIRMED`
  - `stripe_payment_intent_id` and `stripe_charge_id` saved to booking
  - `BookingConfirmedEvent` published
- Stripe API Call: (none at this step — booking is 100% internal)
- Eventbrite API Call: (none at this step — order is owned entirely by your system)

**DB Tables Updated:**

| Table | Fields Set |
|---|---|
| `bookings` | `status=CONFIRMED`, `stripe_payment_intent_id`, `stripe_charge_id` |
| `booking_items` | created from cart snapshot |
| `payments` | `amount`, `currency`, `stripe_payment_intent_id`, `stripe_charge_id`, `status=SUCCESS` |

**Step 5: E-Ticket Generation**
- Internal App (Your DB / Spring Boot)
  - `ETicket` record created in `e_tickets` table
  - QR code generated internally from `booking_reference` + `booking_item_id` — **no EB barcode**
  - PDF e-ticket generated and stored in object storage
  - Download link returned to user; confirmation email sent with e-ticket attached
- Eventbrite API Call: (none at this step)

**Step 6: Stripe Webhooks — Safety Net + Async Confirmation**

Register once in Stripe Dashboard (Test mode) at `POST /api/webhooks/stripe`. Handled by `StripeWebhookHandler` in `shared/stripe/service/`.

| Webhook Event | Your Action |
|---|---|
| `payment_intent.succeeded` | Idempotent booking confirmation — skip if already `CONFIRMED` |
| `payment_intent.payment_failed` | Publish `PaymentFailedEvent` → release seat locks → clear cart |
| `payment_intent.canceled` | Same as `payment_intent.payment_failed` |
| `refund.created` | Record in `refunds` table if not already present |
| `refund.updated` | Update `refunds.status` (e.g. `pending` → `succeeded`) |
| `refund.failed` | Alert ops team, mark `refunds.status=FAILED`, notify user |

> Signature verification: `Webhook.constructEvent(payload, sigHeader, whsec_...)` before any processing.

**Eventbrite — Passive Sync Only (what EB still does)**

| Action in Your App | EB API Call | Purpose |
|---|---|---|
| Organizer creates event | `POST /organizations/{org_id}/events/` | Public listing on EB |
| Organizer updates event | `POST /events/{event_id}/` | Keep listing accurate |
| Organizer creates ticket types | `POST /events/{event_id}/ticket_classes/` (free, zero cost) | Capacity tracking only |
| Organizer cancels event | `POST /events/{event_id}/cancel/` | Mark EB listing cancelled |

**Inbound EB Webhooks (passive only):**

| EB Webhook Event | Your Action |
|---|---|
| `event.updated` (status → cancelled) | Trigger internal bulk cancellation → `StripeRefundService.createRefund()` for all `CONFIRMED` bookings |
| `event.unpublished` | Treat as organizer-initiated cancellation → same bulk refund flow |

> `order.placed`, `order.refunded`, `order.updated` EB webhooks are **not registered** — all order and payment state lives in your system via Stripe.

**Payment Success Path:**
```
User clicks Pay
  → Backend: POST /v1/payment_intents → status=requires_payment_method (via StripePaymentService)
  → client_secret sent to frontend
  → Stripe Payment Element rendered
  → User enters card → stripe.confirmPayment() called
  → Stripe processes → redirects to return_url
  → Frontend sends payment_intent_id to POST /api/payments/confirm-order
  → Backend: GET /v1/payment_intents/{id} → status=succeeded → latest_charge saved
  → Internal booking CONFIRMED → E-ticket generated → confirmation email sent
  → Stripe fires payment_intent.succeeded webhook (safety net — idempotent)
```

**Payment Failure Path:**
```
User clicks Pay
  → Stripe Payment Element → payment fails (declined, 3DS fail, etc.)
  → stripe.confirmPayment() returns error → frontend shows error, does NOT call backend confirm
  → Frontend fires POST /api/payments/failed with payment_intent_id
  → Backend publishes PaymentFailedEvent
  → Seat locks released (HARD_LOCKED → AVAILABLE)
  → Cart cleared → user notified → user can retry
  → Stripe fires payment_intent.payment_failed webhook (backend safety net)
```

**ACL Facades:** `StripePaymentService`, `StripeWebhookHandler`  
**DB Tables:** `bookings`, `booking_items`, `payments`, `e_tickets`  
**External System:** Stripe (payment processing, webhooks)  
**Publishes:** `BookingConfirmedEvent` → engagement + identity | `PaymentFailedEvent` → booking-inventory  
**Listens to:** `CartAssembledEvent` (from booking-inventory)

### FR6 — Cancellation Request → Refund Policy Engine → Wallet Credit
**Module:** payments-ticketing

**Key Point:** All refunds are processed programmatically via **Stripe** (`StripeRefundService`). No Eventbrite API calls are involved in buyer-initiated cancellations. Full and partial refunds are driven by `stripe_payment_intent_id` stored in the `bookings` table. Organizer-initiated bulk cancellations are triggered by `event.updated` / `event.unpublished` EB webhooks and also use Stripe for refunds.

**Step 1: User Requests Cancellation**
- Internal App (Your DB / Spring Boot)
  - User submits `POST /api/bookings/{booking_ref}/cancel`
  - `CancellationRequest` record created in `cancellation_requests` table
  - Internal refund policy evaluated: >48h full refund | 24-48h partial | <24h no refund
  - Booking status set to `CANCELLATION_PENDING`
  - Cancellation is permitted or denied based on policy
- Stripe API Call: (none yet — policy check is internal)
- Eventbrite API Call: (none)

**Step 2: Stripe Refund Issued**
- Internal App (Your DB / Spring Boot)
  - Backend retrieves `stripe_payment_intent_id` from `bookings` table
  - Calls `StripeRefundService.createRefund()` via `shared/stripe/service/`
- Stripe API Call (via `shared/stripe/` ACL)
  - **Full refund:** `POST /v1/refunds` with `payment_intent=pi_...`, `reason=requested_by_customer`
  - **Partial refund:** `POST /v1/refunds` with `payment_intent=pi_...`, `amount=<paise>`, `reason=requested_by_customer`
  - **Save to DB:** `id` (`re_...`) as `stripe_refund_id` in `refunds` table; initial status (`succeeded` or `pending`)
  - Async case: if `status=pending`, Stripe fires `refund.updated` webhook when resolved

**Step 3: Booking Cancellation (Internal DB)**
- Internal App (Your DB / Spring Boot)
  - Booking status set to `CANCELLED` in `bookings` table
  - `booking_items` status set to `CANCELLED`
  - `e_tickets` status set to `VOIDED`
  - `refunds` record created/updated with `stripe_refund_id`, `amount`, `reason`, `status`
  - `BookingCancelledEvent` published → booking-inventory releases seat locks
- Stripe API Call: (none — DB update is internal)

**Step 4: Wallet Credit (Optional Platform Credit)**
- Internal App (Your DB / Spring Boot)
  - If refund is partial (e.g. cancellation fee deducted), platform may credit remainder to wallet
  - Wallet transaction record created in `wallet_transactions` table
  - User notified of refund amount to card + any wallet credit
  - Wallet balance available for future bookings
- Stripe API Call: (none — wallet is 100% internal)

**Async Refund Handling (Webhook Path)**
- Stripe fires `refund.updated` webhook when `pending` → `succeeded`
- `StripeWebhookHandler` routes to `refundService.updateStatus(refundId, status)`
- If `refund.failed`: ops team alerted, user notified of failure

**Bulk Cancellation (Organizer-Initiated via EB Webhook)**
- Trigger: EB fires `event.updated` (status → cancelled) or `event.unpublished` to `POST /api/webhooks/eventbrite`
- Internal App (Your DB / Spring Boot)
  - `EbWebhookDispatcher` receives event → dispatched as Spring Event
  - `ShowSlotCancelledEvent` published → booking-inventory releases all seats
  - payments-ticketing iterates all `CONFIRMED` bookings for that `event_id`
  - For each booking: `StripeRefundService.createRefund(payment_intent_id, reason=requested_by_customer)`
  - All bookings → `CANCELLED`, all `e_tickets` → `VOIDED`, all `refunds` records created
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/cancel/` — confirm EB listing is marked cancelled (already done by organizer)

**Cancellation & Refund Path Summary:**
```
User requests cancellation → Backend validates policy
  → POST /v1/refunds (payment_intent=..., reason=requested_by_customer) via StripeRefundService
  → Refund status: succeeded (immediate) or pending (async)
  → Booking → CANCELLED, E-ticket → VOIDED, Seat locks → released
  → BookingCancelledEvent published → inventory + engagement notified
  → If refund async: Stripe fires refund.updated → refundService.updateStatus()
  → If refund.failed: ops alerted, user notified of failure
```

**ACL Facades:** `StripeRefundService`, `StripeWebhookHandler`, `EbEventSyncService` (bulk cancel only)  
**DB Tables:** `bookings`, `booking_items`, `cancellation_requests`, `refunds`, `e_tickets`, `wallet_transactions`  
**Publishes:** `BookingCancelledEvent` → booking-inventory  
**Listens to:** `ShowSlotCancelledEvent` (from scheduling, via EB webhook dispatcher)

### FR7 — Offer & Coupon Engine → Eligibility Check → Discount Application
**Module:** promotions

Discounts are applied **entirely internally**. Coupon eligibility is validated and the discount is computed before the Stripe `PaymentIntent` is created (reducing the `amount` sent to Stripe). Eventbrite discount codes (`EbDiscountSyncService`) are optionally synced to EB listing for marketing purposes only — they are no longer passed at checkout since the EB Checkout Widget is removed.

**Step 1: Admin Creates Promotion**
- Internal App (Your DB / Spring Boot)
  - Admin creates promotion in your app (discount type, value, validity, usage limits)
  - Internal `promotions` table updated
  - If discount is enforced at Eventbrite checkout level, it's also pushed to Eventbrite
- Eventbrite API Call (via `shared/` ACL)
  - `POST /organizations/{org_id}/discounts/` — create discount code on Eventbrite
  - `GET /discounts/{discount_id}/` — verify discount was created
  - `POST /discounts/{discount_id}/` — update discount rules if edited
  - `DELETE /discounts/{discount_id}/` — remove expired discount from Eventbrite

**Step 2: User Applies Coupon**
- Internal App (Your DB / Spring Boot)
  - User enters coupon code in app UI
  - Internal eligibility checks: validity, usage limit, user eligibility
  - Internal group discount rules checked against cart seat count
  - Discount amount computed internally
  - If valid: `CouponAppliedEvent` published so booking-inventory can recompute cart total with discounted price
  - Discounted `amount` is then used when creating the Stripe `PaymentIntent` (Step 1 of FR5)
- Eventbrite API Call (via `shared/` ACL)
  - `GET /organizations/{org_id}/discounts/` — list org discounts for validation
  - `GET /discounts/{discount_id}/` — read discount rules and current usage count

**Step 3: Discount Applied at Checkout (Stripe)**
- Internal App (Your DB / Spring Boot)
  - `CouponAppliedEvent` published → booking-inventory recomputes cart total with discounted price
  - Discounted total used as `amount` in `POST /v1/payment_intents` during FR5 Step 1
  - No Eventbrite call — discount is entirely internal at this stage
- Stripe API Call: (via FR5 path) `POST /v1/payment_intents` with discounted `amount`
- Eventbrite API Call: (none)

**Step 4: Post-Redemption Sync**
- Internal App (Your DB / Spring Boot)
  - After successful order, coupon redemption recorded in `coupon_redemptions` table
  - Usage count incremented internally
  - If usage limit reached, discount deactivated on Eventbrite
- Eventbrite API Call (via `shared/` ACL)
  - `POST /discounts/{discount_id}/` — update usage limit or deactivate
  - `DELETE /discounts/{discount_id}/` — remove if fully exhausted

**ACL Facades:** `EbDiscountSyncService`  
**DB Tables:** `promotions`, `coupons`, `coupon_redemptions`  
**Publishes:** `CouponAppliedEvent` → booking-inventory

### FR8 — Review & Rating Submission → Moderation Queue → Public Publish
**Module:** engagement

Key Point: There is NO reviews API on Eventbrite. The entire review system is internal. Eventbrite is used only to verify that the user actually attended the event (via attendee record) before allowing review submission.

**Step 1: Verify Attendance**
- Internal App (Your DB / Spring Boot)
  - User attempts to submit a review
  - Backend checks whether user has a CONFIRMED booking for this `event_id`
  - REST call to payments-ticketing to verify booking status
  - Cross-check Eventbrite attendee record using user email
- Eventbrite API Call (via `shared/` ACL)
  - `GET /events/{event_id}/attendees/` — find attendee by email
  - `GET /events/{event_id}/attendees/{attendee_id}/` — check `cancelled=false`, `refunded=false`

**Step 2: Review Submission**
- Internal App (Your DB / Spring Boot)
  - If attendance verified: Review record created in `reviews` table with status `SUBMITTED`
  - Review transitions to `PENDING_MODERATION`
  - Moderation queue updated
- Eventbrite API Call (via `shared/` ACL)
  - (none — review submission is 100% internal)

**Step 3: Moderation**
- Internal App (Your DB / Spring Boot)
  - Moderation engine runs: profanity filter, spam detection (OpenAI moderation API)
  - `ModerationRecord` created with decision
  - If APPROVED: review transitions to `PUBLISHED`
  - If REJECTED: review is terminal — user notified
- Eventbrite API Call (via `shared/` ACL)
  - (none — moderation is 100% internal)

**Review States:** `SUBMITTED → PENDING_MODERATION → APPROVED → PUBLISHED / REJECTED (terminal)`  
**ACL Facades:** `EbAttendeeService`  
**DB Tables:** `reviews`, `moderation_records`  
**Listens to:** `BookingConfirmedEvent` → unlocks review eligibility for that booking

### FR9 — Admin Dashboard → Event CRUD → Seat Inventory Management
**Module:** `admin/` (orchestrator) — delegates to scheduling, discovery-catalog, booking-inventory

The admin dashboard is the most fully-supported Eventbrite flow. Almost every action has a corresponding Eventbrite API. Eventbrite and your internal DB are kept in sync through every CRUD operation.

**Event CRUD**
- Internal App (Your DB / Spring Boot)
  - Admin creates/edits/deletes events in app dashboard
  - Internal DB updated first (`event_catalog`, `show_slots`)
  - Then Eventbrite is called to mirror the action
- Eventbrite API Call (via `shared/` ACL)
  - `POST /organizations/{org_id}/events/` — create event
  - `POST /events/{event_id}/` — update details
  - `DELETE /events/{event_id}/` — delete draft event
  - `POST /events/{event_id}/publish/` — go live
  - `POST /events/{event_id}/unpublish/` — pull from public view
  - `POST /events/{event_id}/copy/` — duplicate template
  - `POST /events/{event_id}/cancel/` — cancel event (triggers FR6 bulk refund)

**Seat Inventory Management**
- Internal App (Your DB / Spring Boot)
  - Admin manages ticket tiers, capacities, seat maps
  - Internal `pricing_tiers`, `seat_maps`, `inventory` updated first
  - Then pushed to Eventbrite
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/ticket_classes/` — create ticket tier
  - `POST /events/{event_id}/ticket_classes/{id}/` — update ticket tier
  - `POST /events/{event_id}/inventory_tiers/` — set inventory capacity
  - `POST /events/{event_id}/inventory_tiers/{id}/` — update inventory tier
  - `DELETE /events/{event_id}/inventory_tiers/{id}/` — remove inventory tier
  - `GET /events/{event_id}/capacity_tier/` — read capacity
  - `POST /events/{event_id}/capacity_tier/` — update capacity ceiling

**Venue & Media Management**
- Internal App (Your DB / Spring Boot)
  - Admin uploads event images, banners
  - Admin updates venue details
- Eventbrite API Call (via `shared/` ACL)
  - `POST /media/upload/` — upload event banner/image
  - `GET /media/{media_id}/` — retrieve uploaded media
  - `POST /organizations/{org_id}/venues/` — create/update venue
  - `PUT /venues/{venue_id}/` — update venue address/capacity

**Event Description & Content**
- Internal App (Your DB / Spring Boot)
  - Admin writes rich HTML event description
  - Structured content (modules, widgets) configured
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/structured_content/{version}/` — set structured content
  - `GET /events/{event_id}/structured_content/` — retrieve published content
  - `GET /event-descriptions/{event_id}/` — retrieve full HTML description

**Reporting & Analytics**
- Internal App (Your DB / Spring Boot)
  - Admin views sales and attendance reports
  - Reports pulled from Eventbrite and merged with internal analytics
- Eventbrite API Call (via `shared/` ACL)
  - `GET /reports/sales/` — pull sales report
  - `GET /reports/attendees/` — pull attendee report
  - `GET /organizations/{org_id}/orders/` — list all orders
  - `GET /events/{event_id}/attendees/` — list attendees for event

**ACL Facades:** `EbEventSyncService`, `EbVenueService`, `EbTicketService`, `EbCapacityService`

### FR10 — Seat Lock State Machine with Conflict Resolution
**Module:** booking-inventory — `booking-inventory/statemachine/`

Key Point: Eventbrite has no seat lock API. The entire state machine runs on Spring State Machine + Redis. Eventbrite is consulted only once — at the `AVAILABLE → SOFT_LOCKED` transition — to check if tickets are still available. All locking, conflict resolution, and rollback is internal.

**AVAILABLE → SOFT_LOCKED (Eventbrite check here)**
- Internal App (Your DB / Spring Boot)
  - User selects seat
  - `AvailabilityGuard` checks seat state in internal DB (`AVAILABLE` required)
  - `AcquireRedisLockAction`: atomic Redis lock acquired with TTL
  - If Redis lock fails: `ConflictResolution` triggers — user informed seat is taken
- Eventbrite API Call (via `shared/` ACL)
  - `GET /events/{event_id}/ticket_classes/for_sale/` — remaining count > 0?
  - `GET /events/{event_id}/inventory_tiers/{id}/` — tier-level availability

**SOFT_LOCKED → HARD_LOCKED**
- Internal App (Your DB / Spring Boot)
  - `CartTtlGuard`: checks cart session still within TTL
  - `HardLockAction`: Redis lock extended/reinforced for checkout duration
  - User proceeds to Stripe Payment Element (frontend)
- Stripe API Call: (none at this transition — lock is internal Redis)

**HARD_LOCKED → PAYMENT_PENDING → CONFIRMED**
- Internal App (Your DB / Spring Boot)
  - User completes Stripe checkout (frontend `stripe.confirmPayment()` succeeds)
  - Frontend sends `payment_intent_id` to `POST /api/payments/confirm-order`
  - Backend verifies `PaymentIntent.status = succeeded` via `StripePaymentService.retrievePaymentIntent()`
  - Seat transitions to `CONFIRMED`
  - `stripe_payment_intent_id` and `stripe_charge_id` stored in `bookings` table
- Stripe API Call (via `shared/stripe/` ACL)
  - `GET /v1/payment_intents/{id}` — verify `status=succeeded` and `amount_received==amount`
- Eventbrite API Call: (none)

**Any State → RELEASED (Rollback)**
- Internal App (Your DB / Spring Boot)
  - `PaymentFailedEvent` received (`stripe.confirmPayment()` returns error, or `payment_intent.payment_failed` webhook fires)
  - `PaymentFailedListener` → `RollbackAction` triggered
  - Redis lock released atomically
  - Seat returns to `AVAILABLE`
  - Cart cleared from internal DB
- Stripe API Call: (none — rollback is internal Redis + DB)

**Concurrent Conflict Resolution**
- Internal App (Your DB / Spring Boot)
  - Two users try to lock same seat simultaneously
  - First Redis lock acquisition wins (atomic `SET NX` with TTL)
  - Second user gets `ConflictResolutionResult: seat taken`
  - Second user redirected to next available seat in same tier
- Eventbrite API Call (via `shared/` ACL)
  - (none — concurrency handled by Redis atomic operations)

**Guards:** `AvailabilityGuard`, `CartTtlGuard`  
**Actions:** `AcquireRedisLockAction`, `HardLockAction`, `RollbackAction`  
**Redis Keys:** `seat:lock:{seatId}`  
**Listens to:** `PaymentFailedEvent` → `RollbackAction` | `ShowSlotCancelledEvent` → release all locks for slot

### FR11 — AI-Powered Event Assistant Chatbot
**Module:** engagement — `engagement/service/chatbot/`

Key Point: The chatbot can only answer about events your org has created. Public Eventbrite search (`GET /events/search/`) is deprecated and unavailable. Live event queries are scoped to your org/venue. The primary knowledge source is your internal RAG-indexed event catalog.

**User Sends Message**
- Internal App (Your DB / Spring Boot)
  - `ChatbotService` receives message, manages conversation context
  - `RagPipelineService` retrieves relevant chunks from Elasticsearch vector store
  - Vector store contains indexed event catalog + refund policies
  - GPT-4o generates response with tool calling enabled
- Eventbrite API Call (via `shared/` ACL)
  - (none at this stage — RAG is internal)

**Tool: EventSearchTool (Internal)**
- Internal App (Your DB / Spring Boot)
  - Tool calls internal `EventCatalogService` REST endpoint
  - Returns events from internal `event_catalog` table (city + genre + date filters)
  - No Eventbrite call — internal catalog is primary source
- Eventbrite API Call (via `shared/` ACL)
  - (none — internal REST call)

**Tool: EbLiveSearchTool (Eventbrite Live Query)**
- Internal App (Your DB / Spring Boot)
  - Tool invoked when user asks about live ticket availability or event details
  - Calls Eventbrite via `EbEventSyncService` facade
- Eventbrite API Call (via `shared/` ACL)
  - `GET /events/{event_id}/` — live event details (date, venue, status)
  - `GET /organizations/{org_id}/events/` — list org live events
  - `GET /venues/{venue_id}/events/` — list events at venue
  - `GET /events/{event_id}/ticket_classes/for_sale/` — live availability count
  - `POST /pricing/calculate_price_for_item/` — live price calculation
  - `GET /categories/` — list categories for context
  - `GET /formats/` — list formats

```text
User message
    ↓
ChatbotService  (orchestrates conversation, manages context)
    ↓
RagPipelineService  (retrieves chunks from Elasticsearch vector store)
    ↓
Elasticsearch  (indexed event catalog + refund policies)
    +
Tool Calling
    ├── EventSearchTool     (calls internal EventCatalogService REST endpoint)
    └── EbLiveSearchTool    (calls shared/ → EbEventSyncService → Eventbrite API)
```

**ACL Facades:** `EbEventSyncService`  
**DB Tables:** `chat_sessions`, `chat_messages`  
**Vector Store:** Elasticsearch — refreshed on `EventCatalogUpdatedEvent`  
**Listens to:** `EventCatalogUpdatedEvent` → `RAGIndexService` → refresh index

## 7. Inter-Module Event Map

### Spring Application Events

| Publisher | Event | Consumer | Purpose |
|---|---|---|---|
| booking-inventory | `CartAssembledEvent` | payments-ticketing | Cart ready → create Stripe PaymentIntent + return `client_secret` to frontend |
| payments-ticketing | `PaymentFailedEvent` | booking-inventory | Payment failed (Stripe decline/cancel) → rollback Redis seat locks |
| payments-ticketing | `BookingConfirmedEvent` | engagement | Booking confirmed (Stripe succeeded) → unlock review eligibility |
| payments-ticketing | `BookingConfirmedEvent` | identity | Booking confirmed (Stripe succeeded) → update user order history |
| payments-ticketing | `BookingCancelledEvent` | booking-inventory | Cancellation → release Redis seat locks |
| scheduling | `ShowSlotCreatedEvent` | booking-inventory | Slot created → provision seat map |
| scheduling | `ShowSlotCancelledEvent` | booking-inventory | Slot cancelled → release all seats |
| scheduling | `ShowSlotCancelledEvent` | payments-ticketing | Slot cancelled → bulk Stripe refunds + EB event cancel (passive) |
| promotions | `CouponAppliedEvent` | booking-inventory | Coupon applied → recompute cart total (discounted amount sent to Stripe) |
| discovery-catalog | `EventCatalogUpdatedEvent` | engagement | Catalog updated → refresh RAG Elasticsearch index |

## 8. Shared Infrastructure

### External ACL Packages in `shared/`

| Package | External System | Services |
|---|---|---|
| `shared/eventbrite/` | Eventbrite API | `EbEventSyncService`, `EbVenueService`, `EbScheduleService`, `EbTicketService`, `EbCapacityService`, `EbOrderService` (reporting only), `EbAttendeeService` (FR8 only), `EbDiscountSyncService`, `EbRefundService` (inactive), `EbWebhookService` |
| `shared/stripe/` | Stripe API | `StripePaymentService`, `StripeRefundService`, `StripeWebhookHandler` |
| `shared/openai/` | OpenAI GPT-4o | `OpenAiChatService`, `OpenAiEmbeddingService` |

### Shared Common Classes

| Class | Location | Purpose |
|---|---|---|
| `BaseEntity` | `shared/common/domain/` | `id`, `createdAt`, `updatedAt` — all entities extend this |
| `Money` | `shared/common/domain/` | Value object for all monetary amounts — never use primitives |
| `ApiResponse<T>` | `shared/common/dto/` | Standard success response wrapper |
| `PageResponse<T>` | `shared/common/dto/` | Paginated list wrapper |
| `ErrorResponse` | `shared/common/dto/` | Standard error shape — all API errors use this |
| `GlobalExceptionHandler` | `shared/common/exception/` | Single `@ControllerAdvice` for the entire app |
| `ResourceNotFoundException` | `shared/common/exception/` | 404 — extend for module-specific not-found exceptions |
| `BusinessRuleException` | `shared/common/exception/` | 422 — thrown when a domain rule is violated |
| `SystemIntegrationException` | `shared/common/exception/` | 502 — external system failure |
| `JwtTokenProvider` | `shared/security/` | JWT generation and validation |
| `JwtAuthenticationFilter` | `shared/security/` | Spring Security filter — registered once |
| `RedisConfig` | `shared/config/` | Redisson bean for distributed seat locking |
| `ElasticsearchConfig` | `shared/config/` | ES client for RAG vector store |
| `SlotSummaryReader` | `shared/common/service/` | Read interface: `getSlotSummary(Long slotId) → SlotSummaryDto`; implemented by `scheduling/SlotSummaryReaderImpl` |
| `SlotPricingReader` | `shared/common/service/` | Read interface: `getSlotPricing(Long slotId) → List<PricingTierDto>`; implemented by `scheduling/SlotPricingReaderImpl` |
| `CartSnapshotReader` | `shared/common/service/` | Read interface: `getCartItems(Long cartId) → List<CartItemSnapshotDto>`; implemented by `booking-inventory/CartSnapshotReaderImpl`; consumed by `payments-ticketing` on payment confirmation hot path |
| `PaymentConfirmationReader` | `shared/common/service/` | Read interface: `isPaymentConfirmed(Long cartId) → boolean`; implemented by `payments-ticketing/PaymentConfirmationReaderImpl`; consumed by `booking-inventory` `PaymentTimeoutWatchdog` |
| `SlotSummaryDto` | `shared/common/dto/` | Record: `slotId, status, ebEventId, seatingMode, orgId, venueId, cityId, sourceSeatMapId` |
| `PricingTierDto` | `shared/common/dto/` | Record: `tierId, tierName, price(Money), quota, tierType, ebTicketClassId, ebInventoryTierId, groupDiscountThreshold, groupDiscountPercent` |
| `CartItemSnapshotDto` | `shared/common/dto/` | Record: `itemId, cartId, seatId, gaClaimId, ticketClassId, unitPrice, currency, quantity`; used by `CartSnapshotReader` |

## 9. Hard Rules — Never Violate These

1. `app/` has zero business logic. No `@Service`, no `@Entity`, no `@Repository`.
2. `shared/` has zero dependency on any module. It knows nothing about bookings, users, or events.
3. No module imports another module's `@Entity` class, `@Repository` interface, or concrete `@Service` bean. The sole approved exception is shared reader interfaces defined in `shared/common/service/` — see Rule 1 above.
4. No module calls Eventbrite HTTP directly — only through `shared/eventbrite/service/`. No module calls Stripe HTTP directly — only through `shared/stripe/service/`.
5. Spring Events carry only primitive values, IDs, enums, and value objects — never `@Entity` objects.
6. `admin/` defines no `@Entity` and owns no database table.
7. Dependency direction is strictly downward — no circular imports between modules.
8. One `GlobalExceptionHandler` in `shared/common/exception/` — never add `@ControllerAdvice` in a module.
9. Internal DB is always updated FIRST before any Eventbrite or Stripe API call is made.
10. `eventbrite_event_id` is never null in production for events that require EB listing sync — if null, block FR7, FR8, FR10 (EB availability check). FR5 (Stripe payments) and FR6 (Stripe refunds) operate independently of `eventbrite_event_id`.
11. All payments are accepted and processed via **Stripe** (`StripePaymentService`). No payment processing through Eventbrite. The Eventbrite Checkout Widget is removed — do NOT use or reference it.
12. All refunds are submitted programmatically via **Stripe** (`StripeRefundService` — `POST /v1/refunds`). Do NOT use `EbRefundService` for any refund submission. Do NOT mimic admin actions on Eventbrite for refunds.
13. Stripe `client_secret` is sent to the frontend ONLY — never log, store, or return it from the backend. `stripe_payment_intent_id` (`pi_...`) and `stripe_charge_id` (`ch_...`) are stored in the `payments` and `bookings` tables.
14. Stripe webhook endpoint (`POST /api/webhooks/stripe`) MUST verify the `Stripe-Signature` header via `Webhook.constructEvent()` before any processing. Reject any request where verification fails with HTTP 400.

## 10. Where to Put New Code

**Java package naming:** module names with hyphens removed. Example: `booking-inventory` → `com.[org].bookinginventory`

### Code Placement Guide

| Need | Location |
|---|---|
| New REST endpoint | `[module]/api/controller/` |
| New request/response shape | `[module]/api/dto/request/` or `response/` |
| New business rule or domain behavior | `[module]/domain/` (no Spring annotations) |
| New use case orchestration | `[module]/service/` |
| New database table access | `[module]/repository/` |
| Module needs to notify others | `[module]/event/published/` |
| Module reacts to another | `[module]/event/listener/` |
| Shared across 2+ modules | `shared/common/` |
| New external system integration | `shared/[system-acl]/` (never inside a module) |
| Admin read/aggregate view | `admin/service/` + `admin/api/controller/` |
| New Eventbrite API call | `shared/eventbrite/service/` — add to appropriate facade only |
| New Stripe API call | `shared/stripe/service/` — add to `StripePaymentService`, `StripeRefundService`, or `StripeWebhookHandler` only |

## 11. Flyway Migration Conventions

- All Flyway scripts live in `app/src/main/resources/db/migration/`
- Naming: `V[n]__[verb]_[table].sql` — e.g. `V5__create_bookings.sql`
- Version numbers are global across all modules — check the current highest before adding
- Never modify an existing migration file — always add a new one
- Table names use `snake_case`
