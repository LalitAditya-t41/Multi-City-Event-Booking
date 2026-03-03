# PRODUCT.md — Module Registry & Product Map

**Owner:** Lalit  
**Domain:** EntertainmentTech  
**Last Updated:** March 2026

**Architecture:** Modular Monolith — Spring Boot 3.5.11, Java 21

**External Integrations:** Eventbrite (ticketing + payments + orders) | OpenAI GPT-4o (AI assistant)

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
| payments-ticketing | Bookings, Payments, E-Tickets, Cancellations, Refunds, Wallet | Seats (reads cart only) |
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
booking-inventory     ← depends on shared + discovery-catalog (reads event/venue via REST)
    ↓
payments-ticketing    ← depends on shared + booking-inventory + identity (reads via REST)
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
  - Read-only: List events at a venue. Venue creation/updates not available via API (Dashboard only).
  
- **EbScheduleService** (`shared/eventbrite/service/`)
  - Create recurring event schedules and occurrences; retrieve series info
  
- **EbTicketService** (`shared/eventbrite/service/`)
  - Create/update ticket classes; manage inventory; check availability
  
- **EbCapacityService** (`shared/eventbrite/service/`)
  - Retrieve and update event capacity tiers; list org seat maps; attach seat map to event
  
- **EbOrderService** (`shared/eventbrite/service/`)
  - Retrieve order details post-checkout; list orders by event/org. **NO order creation via API** — orders created exclusively by Checkout Widget.
  
- **EbAttendeeService** (`shared/eventbrite/service/`)
  - Retrieve attendee records; list attendees by event. **NO attendee creation via API** — attendees created by Checkout Widget.
  
- **EbDiscountSyncService** (`shared/eventbrite/service/`)
  - Create/update/delete discount codes at org level; sync usage with internal promotions. (Discounts scoped to org, applied to events at checkout.)
  
- **EbRefundService** (`shared/eventbrite/service/`)
  - Read refund request status only from order `refund_request` field. **NO refund submission API.**
  
- **EbWebhookService** (`shared/eventbrite/service/`)
  - **NOT USED in production.** Webhook registration not available via API; only via Dashboard. Mock service simulates webhook delivery for testing.

> **Note:** `EbOrderService` does NOT create orders. Orders are created exclusively by the Eventbrite Checkout Widget (JS SDK). `EbOrderService` only reads orders after the widget fires the `onOrderComplete` callback.

> **Note:** `EbAttendeeService` does NOT create attendees. Attendees are created automatically when the Checkout Widget completes an order. `EbAttendeeService` only reads attendee records.

> **Note:** `EbRefundService` does NOT submit refunds programmatically. Refund status is read from the order's `refund_request` field only. Actual refund initiation is handled by mimicking admin actions via Eventbrite org token (backend only).

> **Note:** `EbVenueService` is read-only. Venue creation and updates are not exposed via Eventbrite's public API — they must be managed via the Eventbrite Dashboard.

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

**Core Architecture:** Your internal DB is the primary source of truth. Eventbrite is the ticketing/payment backbone. Every entity is created internally first — then pushed to Eventbrite. The `eventbrite_event_id` returned from each push is stored in your DB and is the bridge between both systems.

### 5.1 Internal-First, Then Eventbrite

The pattern for every flow that touches Eventbrite is always:

1. User/Admin performs action in YOUR app
2. Internal DB is updated / validated first
3. Eventbrite API is called via `shared/` ACL facade
4. Eventbrite returns ID or confirmation
5. Your DB stores the Eventbrite ID (`eventbrite_event_id`, `eventbrite_order_id`, etc.)
6. Both systems are now in sync — your DB is source of truth

### 5.2 The `eventbrite_event_id` is the Spine of the System

Every downstream flow depends on `eventbrite_event_id` being present and valid in your DB:
- FR5 (Payment) — Eventbrite Checkout Widget needs a valid `event_id` to render
- FR6 (Cancellation) — Eventbrite cancel API needs a valid event tied to a valid order
- FR7 (Discounts) — Eventbrite discount codes are scoped to `event_id`
- FR8 (Reviews) — Verifying attended booking requires a confirmed Eventbrite order under that `event_id`
- FR10 (Seat Lock) — Availability check hits Eventbrite `ticket_classes` for that `event_id`

Without FR2 sync: If `eventbrite_event_id` is null or orphaned, the entire chain breaks. No tickets can be sold, no orders created, no refunds processed.

### 5.3 What Eventbrite Cannot Do — Internal Responsibilities

| Gaps | Handled Internally |
|---|---|
| User creation/sync | No user write API on Eventbrite. Identity is 100% internal. Users are linked via order email post-purchase. |
| Conflict validation | No conflict API on Eventbrite. Time slot conflicts and turnaround gaps are enforced in your internal DB. |
| Venue creation/updates | No venue creation API. Venues can only be managed via Eventbrite Dashboard. Your app can read pre-existing venue IDs only. |
| Seat locking | No seat lock API on Eventbrite. The entire state machine (Redis + Spring State Machine) is internal. |
| Cart management | No cart API on Eventbrite. Cart assembly and group discount rules are internal. |
| Order creation | No order creation API. Orders are created exclusively via the Eventbrite Checkout Widget (JS SDK). Backend only reads orders post-creation. |
| Attendee creation | No attendee creation API. Attendees are created automatically by Checkout Widget when orders are placed. Backend only reads attendee records. |
| Payment processing | Eventbrite Checkout Widget handles all payments (credit card, debit, UPI, Eventbrite wallet, PayPal, etc.). Backend reads orders post-creation via EbOrderService. |
| Single order cancel | No per-order cancel API. Only full event cancel exists. Per-order cancellation is mimicked via org admin token. |
| Refund submission | No programmatic refund API. Refund status is embedded in order `refund_request` field only. Read-only access. |
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
  - `UserRegisteredEvent` is published
- Eventbrite API Call (via `shared/` ACL)
  - (none — no user creation API on Eventbrite)

**Step 2: Profile Setup**
- Internal App (Your DB / Spring Boot)
  - User fills in profile details: display name, phone, city, avatar
  - Internal `users` table is updated
  - No sync to Eventbrite at this stage
- Eventbrite API Call (via `shared/` ACL)
  - `GET /users/me/` — only called if user explicitly linked their Eventbrite account via OAuth (optional)

**Step 3: Preference Onboarding**
- Internal App (Your DB / Spring Boot)
  - User selects preferred genres, cities, price ranges, notification settings
  - Stored in `user_preferences` table
  - Used for personalized event recommendations from internal catalog
- Eventbrite API Call (via `shared/` ACL)
  - (none — preferences are internal only)

**Post-Purchase: User ↔ Eventbrite Link Established**
- Internal App (Your DB / Spring Boot)
  - User completes a purchase via Eventbrite Checkout Widget
  - Eventbrite creates order with buyer email
  - Your backend reads the order via `GET /orders/{order_id}/`
  - Match order email to `users` table → store `eventbrite_order_id` against `user_id` in `bookings` table
  - User is now linked to Eventbrite via their order history
- Eventbrite API Call (via `shared/` ACL)
  - `GET /orders/{order_id}/` — read order details post-checkout
  - `GET /events/{event_id}/attendees/` — verify user appears as attendee

**ACL Facades:** (none during registration) | `EbOrderService` + `EbAttendeeService` (post-purchase)  
**DB Tables:** `users`, `user_preferences`, `user_wallets`  
**Publishes:** `UserRegisteredEvent`

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
HARD_LOCKED  (held during Eventbrite checkout)
    │  [PAY] — user goes through Eventbrite Checkout Widget
    ▼
PAYMENT_PENDING
    │  [CONFIRM_PAYMENT] onOrderComplete callback fires    [RELEASE] on failure
    ▼                                                            ▼
CONFIRMED (eventbrite_order_id stored in DB)          RELEASED → AVAILABLE
```

**ACL Facades:** `EbTicketService`  
**DB Tables:** `seats`, `seat_maps`, `carts`, `cart_items`, `pricing_tiers`, `group_discount_rules`  
**Redis Keys:** `seat:lock:{seatId}`  
**Publishes:** `CartAssembledEvent` → payments-ticketing

### FR5 — Payment via Eventbrite Checkout Widget → Booking Confirmation → E-Ticket Generation
**Module:** payments-ticketing

Key Point: There is NO Order creation API on Eventbrite. Orders are created exclusively through the Eventbrite Checkout Widget (JS SDK: `eb_widgets.js`). Your backend reads the order AFTER the widget fires the `onOrderComplete` callback. Payment processing, attendee creation, and order placement are all handled by Eventbrite internally.

**Step 1: Checkout Widget Embed**
- Internal App (Your DB / Spring Boot)
  - `CartAssembledEvent` received by payments-ticketing
  - Backend prepares checkout context: `event_id`, `ticket_class_id`, `promo_code` (if any), `attendee_email`
  - Frontend receives `event_id` and renders the Eventbrite Checkout Widget
- Eventbrite API Call (via `shared/` ACL)
  - (JS SDK — not a REST API call)

The Checkout Widget is embedded as a popup modal triggered by the Buy Tickets button:

```html
<button id="eb-checkout-trigger">Buy Tickets</button>
<script src="https://www.eventbrite.com/static/widgets/eb_widgets.js"></script>
window.EBWidgets.createWidget({
  widgetType: 'checkout',
  eventId: '{eventbrite_event_id}',
  ticketClassId: '{ticket_class_id}',
  modal: true,
  modalTriggerElementId: 'eb-checkout-trigger',
  promoCode: '{coupon_code_if_any}',
  attendeeEmail: '{user_email}',
  onOrderComplete: function(orderId) { 
    fetch('/api/payments/confirm-order', {
      method: 'POST',
      body: JSON.stringify({ orderId: orderId })
    });
  }
});
```

**Step 2: User Completes Payment in Eventbrite Widget**
- Eventbrite Checkout Widget
  - User enters attendee details (name, email, custom questions)
  - User selects payment method (credit card, debit, UPI, Eventbrite wallet, PayPal, etc.)
  - Payment is processed by Eventbrite's payment processor (Stripe, etc.)
  - Eventbrite creates Order internally
  - Eventbrite creates Attendee record linked to the order
  - `onOrderComplete` JS callback fires in your frontend with the `order_id`
- Eventbrite API Call (via `shared/` ACL)
  - (Eventbrite handles payment internally — no external payment gateway call)

**Step 3: Frontend Notifies Backend**
- Internal App (Your DB / Spring Boot)
  - Frontend `onOrderComplete` callback sends `order_id` to backend
  - Backend receives `order_id` and validates it
  - No double-submit: check if booking already exists for this `order_id`
- Eventbrite API Call (via `shared/` ACL)
  - `GET /orders/{order_id}/` — read order to confirm it exists and status is `placed`

**Step 4: Booking Confirmation**
- Internal App (Your DB / Spring Boot)
  - Backend receives `order_id` from frontend callback
  - Internal booking record created in `bookings` table with status `CONFIRMED`
  - `booking_items` created from cart snapshot
  - Seat locks transitioned from HARD_LOCKED → CONFIRMED
  - `BookingConfirmedEvent` published
- Eventbrite API Call (via `shared/` ACL)
  - `GET /orders/{order_id}/` — read full order details (costs, buyer email, status, attendee info)
  - `GET /events/{event_id}/attendees/` — verify attendee created by Eventbrite
  - `GET /events/{event_id}/attendees/{attendee_id}/` — get attendee barcode/QR if Eventbrite native ticketing used

**Step 5: E-Ticket Generation**
- Internal App (Your DB / Spring Boot)
  - `ETicket` record created in `e_tickets` table
  - QR code generated from booking reference + attendee barcode (from Eventbrite)
  - PDF e-ticket generated internally (not from Eventbrite)
  - E-ticket stored in object storage, download link returned to user
  - Booking confirmation email sent with e-ticket attached
- Eventbrite API Call (via `shared/` ACL)
  - `GET /orders/{order_id}/?expand=attendees` — get attendee barcodes from Eventbrite ticket
  - `GET /events/{event_id}/attendees/{attendee_id}/` — get attendee check-in barcode for QR

**Also: Webhook for Order Events (Safety Net — Dashboard-Configured)**
- Webhook (Parallel Path)
  - Internal App (Your DB / Spring Boot)
    - `EbWebhookDispatcher` receives `order.placed` event from Eventbrite
    - Dispatched as Spring Event → payments-ticketing listens
    - Used as a secondary confirmation if frontend callback was missed
    - Idempotent: check if booking already created before creating duplicate
  - Eventbrite API Call (via `shared/` ACL)
    - **NO API for webhook registration** — Webhooks configured via Eventbrite Dashboard only
    - Once configured, Eventbrite POSTs `order.placed`, `order.updated`, `attendee.updated` to your endpoint
    - (Webhook is a safety net — not the primary confirmation path)

**Payment Success Path:**
```
User clicks Buy → Widget rendered → User enters payment details → Eventbrite processes payment
  → Order created on Eventbrite → Attendee created on Eventbrite → onOrderComplete fires
  → Frontend POST to backend → Backend reads order via EbOrderService
  → Internal booking created → E-ticket generated → User receives email
```

**Payment Failure Path:**
```
User clicks Buy → Widget rendered → User enters payment (fails) → Widget closes
  → onOrderComplete never fires → Eventbrite order NOT created
  → Frontend detects widget closed → triggers PaymentFailedEvent
  → PaymentFailedListener → RollbackAction (Redis locks released)
  → Cart cleared → User can retry or abandon
```

**ACL Facades:** `EbOrderService`, `EbAttendeeService`  
**DB Tables:** `bookings`, `booking_items`, `e_tickets`  
**External System:** Eventbrite (payment + order + attendee creation)  
**Publishes:** `BookingConfirmedEvent` → engagement + identity | `PaymentFailedEvent` → booking-inventory  
**Listens to:** `CartAssembledEvent` (from booking-inventory)

### FR6 — Cancellation Request → Refund Policy Engine → Wallet Credit
**Module:** payments-ticketing

Key Point: There is NO single-order cancel or programmatic refund API on Eventbrite. `POST /events/{event_id}/cancel/` cancels the ENTIRE event, not one order. Per-order cancellation is achieved by acting as the org admin via your org OAuth token to mimic the cancellation action on Eventbrite's side.

**Step 1: User Requests Cancellation**
- Internal App (Your DB / Spring Boot)
  - User submits cancellation request in your app
  - `CancellationRequest` record created in `cancellation_requests` table
  - Internal refund policy evaluated: >48h full refund | 24-48h partial | <24h no refund
  - Cancellation is permitted or denied based on policy
- Eventbrite API Call (via `shared/` ACL)
  - `GET /orders/{order_id}/` — read current order status and `refund_request` fields
  - `GET /events/{event_id}/orders?refund_request_statuses=pending` — check if refund already in flight

**Step 2: Cancel Order on Eventbrite (Mimicking Admin)**
- Internal App (Your DB / Spring Boot)
  - Backend acts as the org admin using your org-level OAuth token
  - Navigates Eventbrite's order management on behalf of admin
  - Cancellation is scoped to the specific `order_id` stored in your DB
  - This is not a dedicated cancel API — it uses admin-scoped token to trigger cancellation
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/cancel/` — only if cancelling the ENTIRE show slot (bulk cancel)
  - For single order: org-level OAuth token used to access Eventbrite order management
  - `GET /orders/{order_id}/` — poll to confirm order status changed to cancelled

**Step 3: Refund Status Tracking**
- Internal App (Your DB / Spring Boot)
  - Internal `refund_policy` calculates refund amount based on time-to-event
  - Refund record created in `wallet_transactions` table
  - Booking status set to `CANCELLED` in `bookings` table
  - `BookingCancelledEvent` published → booking-inventory releases seats
- Eventbrite API Call (via `shared/` ACL)
  - `GET /events/{event_id}/orders?refund_request_statuses=completed` — verify refund processed
  - `GET /events/{event_id}/orders?refund_request_statuses=denied` — handle denial case
  - `GET /orders/{order_id}/` — read final refund status from order object

**Step 4: Wallet Credit**
- Internal App (Your DB / Spring Boot)
  - Approved refund amount credited to user's internal wallet (`user_wallets` table)
  - Wallet transaction record created
  - User notified of refund amount and wallet credit
  - Wallet balance available for future bookings
- Eventbrite API Call (via `shared/` ACL)
  - (none — wallet is 100% internal)

**Bulk Cancellation (Entire Show Slot Cancelled)**
- Internal App (Your DB / Spring Boot)
  - `ShowSlotCancelledEvent` received from scheduling module
  - All bookings for that slot iterated
  - Each booking cancelled and refunded per policy
  - All seats released via `BookingCancelledEvent`
- Eventbrite API Call (via `shared/` ACL)
  - `POST /events/{event_id}/cancel/` — cancel the entire event on Eventbrite
  - `GET /events/{event_id}/orders/` — retrieve all orders for bulk refund processing
  - `GET /events/{event_id}/orders?refund_request_statuses=completed` — verify all refunds processed

**ACL Facades:** `EbOrderService`, `EbRefundService`  
**DB Tables:** `bookings`, `cancellation_requests`, `wallet_transactions`  
**Publishes:** `BookingCancelledEvent` → booking-inventory  
**Listens to:** `CartAssembledEvent` | `ShowSlotCancelledEvent`

### FR7 — Offer & Coupon Engine → Eligibility Check → Discount Application
**Module:** promotions

Discounts work on two levels: internal promotions (your DB) and Eventbrite discount codes (applied at checkout widget). Both must be kept in sync.

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
  - If valid: coupon passed to Eventbrite Checkout Widget as `promoCode` parameter
- Eventbrite API Call (via `shared/` ACL)
  - `GET /organizations/{org_id}/discounts/` — list org discounts for validation
  - `GET /discounts/{discount_id}/` — read discount rules and current usage count

**Step 3: Discount Applied at Checkout**
- Internal App (Your DB / Spring Boot)
  - `CouponAppliedEvent` published → booking-inventory recomputes cart total
  - Eventbrite widget receives `promoCode` → applies discount at payment step
  - Order is placed with discount already factored in by Eventbrite
- Eventbrite API Call (via `shared/` ACL)
  - `promoCode` passed to `window.EBWidgets.createWidget()` — applies at widget checkout
  - `GET /orders/{order_id}/` — verify discount was applied in order costs

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
  - User proceeds to Eventbrite checkout widget
- Eventbrite API Call (via `shared/` ACL)
  - (none — locking is internal Redis)

**HARD_LOCKED → PAYMENT_PENDING → CONFIRMED**
- Internal App (Your DB / Spring Boot)
  - User completes Eventbrite checkout
  - `onOrderComplete` fires → backend receives `order_id`
  - Seat transitions to `CONFIRMED`
  - `eventbrite_order_id` stored in DB against seat and booking
- Eventbrite API Call (via `shared/` ACL)
  - `GET /orders/{order_id}/` — confirm order is in placed status
  - `GET /events/{event_id}/attendees/` — verify attendee record created

**Any State → RELEASED (Rollback)**
- Internal App (Your DB / Spring Boot)
  - `PaymentFailedEvent` received (payment failure or widget closed)
  - `PaymentFailedListener` → `RollbackAction` triggered
  - Redis lock released atomically
  - Seat returns to `AVAILABLE`
  - Cart cleared from internal DB
- Eventbrite API Call (via `shared/` ACL)
  - (none — rollback is internal Redis + DB)

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
| booking-inventory | `CartAssembledEvent` | payments-ticketing | Cart ready → initiate Eventbrite checkout |
| payments-ticketing | `PaymentFailedEvent` | booking-inventory | Payment failed → rollback Redis seat locks |
| payments-ticketing | `BookingConfirmedEvent` | engagement | Booking confirmed → unlock review eligibility |
| payments-ticketing | `BookingConfirmedEvent` | identity | Booking confirmed → update user order history |
| payments-ticketing | `BookingCancelledEvent` | booking-inventory | Cancellation → release Redis seat locks |
| scheduling | `ShowSlotCreatedEvent` | booking-inventory | Slot created → provision seat map |
| scheduling | `ShowSlotCancelledEvent` | booking-inventory | Slot cancelled → release all seats |
| scheduling | `ShowSlotCancelledEvent` | payments-ticketing | Slot cancelled → trigger bulk refund + EB cancel |
| promotions | `CouponAppliedEvent` | booking-inventory | Coupon applied → recompute cart total |
| discovery-catalog | `EventCatalogUpdatedEvent` | engagement | Catalog updated → refresh RAG Elasticsearch index |

## 8. Shared Infrastructure

### External ACL Packages in `shared/`

| Package | External System | Services |
|---|---|---|
| `shared/eventbrite/` | Eventbrite API | `EbEventSyncService`, `EbVenueService`, `EbScheduleService`, `EbTicketService`, `EbCapacityService`, `EbOrderService`, `EbAttendeeService`, `EbDiscountSyncService`, `EbRefundService`, `EbWebhookService` |
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

## 9. Hard Rules — Never Violate These

1. `app/` has zero business logic. No `@Service`, no `@Entity`, no `@Repository`.
2. `shared/` has zero dependency on any module. It knows nothing about bookings, users, or events.
3. No module imports another module's `@Entity` class or `@Repository` interface.
4. No module calls Eventbrite HTTP directly — only through `shared/eventbrite/service/`.
5. Spring Events carry only primitive values, IDs, enums, and value objects — never `@Entity` objects.
6. `admin/` defines no `@Entity` and owns no database table.
7. Dependency direction is strictly downward — no circular imports between modules.
8. One `GlobalExceptionHandler` in `shared/common/exception/` — never add `@ControllerAdvice` in a module.
9. Internal DB is always updated FIRST before any Eventbrite API call is made.
10. `eventbrite_event_id` is never null in production — if null, block FR4, FR5, FR6, FR7, FR8, FR10.
11. The Checkout Widget (JS SDK) is the ONLY way orders are created on Eventbrite — no backend order creation.
12. Per-order cancellation and refunds use org admin token mimic — document this clearly in `EbRefundService`.
13. Eventbrite Checkout Widget is the ONLY way payments are accepted and orders are created. No backend order creation API exists on Eventbrite. Payment processing is 100% Eventbrite-handled. Backend reads orders AFTER widget fires onOrderComplete callback via `EbOrderService`.

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

## 11. Flyway Migration Conventions

- All Flyway scripts live in `app/src/main/resources/db/migration/`
- Naming: `V[n]__[verb]_[table].sql` — e.g. `V5__create_bookings.sql`
- Version numbers are global across all modules — check the current highest before adding
- Never modify an existing migration file — always add a new one
- Table names use `snake_case`
