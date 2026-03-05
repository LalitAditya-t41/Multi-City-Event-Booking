# Multi-City Event Booking Platform
### High-Level Design & Functional Flows

**Owner:** Lalit | **Domain:** EntertainmentTech | **Date:** March 2026  
**Architecture:** Modular Monolith — Spring Boot 3.5.11 · Java 21 · PostgreSQL · Redis · Elasticsearch

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Tech Stack](#2-tech-stack)
3. [High-Level Architecture (HLD)](#3-high-level-architecture-hld)
4. [Module Map](#4-module-map)
5. [Module Dependency Graph](#5-module-dependency-graph)
6. [Inter-Module Communication](#6-inter-module-communication)
7. [External Integrations](#7-external-integrations)
8. [Functional Flow: FR1 — City Selection → Event Discovery](#8-fr1--city-selection--event-discovery)
9. [Functional Flow: FR2 — Show Scheduling & Conflict Validation](#9-fr2--show-scheduling--conflict-validation)
10. [Functional Flow: FR3 — User Registration & Preference Onboarding](#10-fr3--user-registration--preference-onboarding)
11. [Functional Flow: FR4 — Seat Selection & Cart Assembly](#11-fr4--seat-selection--cart-assembly)
12. [Functional Flow: FR5 — Payment via Stripe & Booking Confirmation](#12-fr5--payment-via-stripe--booking-confirmation)
13. [Functional Flow: FR6 — Cancellation, Refund & Wallet Credit](#13-fr6--cancellation-refund--wallet-credit)
14. [Functional Flow: FR7 — Coupon & Offer Engine](#14-fr7--coupon--offer-engine)
15. [Functional Flow: FR8 — Reviews, Moderation & Publish](#15-fr8--reviews-moderation--publish)
16. [Functional Flow: FR9 — Admin Dashboard & Event CRUD](#16-fr9--admin-dashboard--event-crud)
17. [Functional Flow: FR10 — Seat Lock State Machine](#17-fr10--seat-lock-state-machine)
18. [Functional Flow: FR11 — AI-Powered Chatbot (RAG)](#18-fr11--ai-powered-chatbot-rag)
19. [Spring Events Map (Full)](#19-spring-events-map-full)
20. [Shared Infrastructure](#20-shared-infrastructure)
21. [Database & Migrations](#21-database--migrations)
22. [Hard Rules Summary](#22-hard-rules-summary)

---

## 1. System Overview

The Multi-City Event Booking Platform lets users discover live events across cities, book seats, pay via Stripe, receive e-tickets, and leave verified reviews — all within a single **modular monolith** Spring Boot application.

| Principle | Implementation |
|---|---|
| **Internal DB is source of truth** | All mutations hit the internal DB first, then push to external systems |
| **Stripe owns payments & refunds** | All money movement is via Stripe PaymentIntents and Refunds |
| **Eventbrite is a passive listing sync layer** | EB hosts public event listings; your DB drives everything else |
| **No module crosses boundaries** | Modules communicate via REST + Spring Events only |
| **All external calls go through ACL facades** | `shared/eventbrite/service/` and `shared/stripe/service/` are the only gateways |

**Implementation Status (March 2026):**

| FR | Feature Area | Status |
|---|---|---|
| FR1 | City → Venue → Event Discovery | ✅ Designed |
| FR2 | Show Scheduling & EB Sync | ✅ Designed |
| FR3 | User Registration & Onboarding | ✅ Implemented |
| FR4 | Seat Selection & Cart | ✅ Implemented |
| FR5 | Stripe Payments & E-Tickets | ✅ Implemented |
| FR6 | Cancellation, Refunds, Wallet | ✅ Implemented |
| FR7 | Coupons & Promotions | ✅ Scaffolded (V37) |
| FR8 | Reviews & Moderation | ✅ Designed |
| FR9 | Admin Dashboard CRUD | ✅ Designed |
| FR10 | Seat Lock State Machine | ✅ Implemented |
| FR11 | AI Chatbot (RAG + GPT-4o) | ✅ Designed |

---

## 2. Tech Stack

```
Backend          Spring Boot 3.5.11 · Java 21 · Spring Security · Spring State Machine
Persistence      PostgreSQL 15 · Spring Data JPA · Flyway (migrations V1–V37 in app/)
Cache / Lock     Redis (Redisson) — distributed seat locking, cart TTL
Search / RAG     Elasticsearch — vector store for AI event catalog indexing
Payments         Stripe (sandbox sk_test_...) — PaymentIntents, Refunds, Webhooks
Event Listing    Eventbrite API v3 — passive event sync (YOUR DB is source of truth)
AI               OpenAI GPT-4o — AI chatbot + moderation + embeddings
Build            Maven multi-module (root pom.xml)
Containerization Docker Compose — postgres, redis, elasticsearch, mock-eventbrite, app
Mock APIs        Python FastAPI (mock-eventbrite-api/) — local dev without real EB calls
```

---

## 3. High-Level Architecture (HLD)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT / BROWSER                             │
│  (Stripe Payment Element · Event Browser · Seat Picker · Chatbot)   │
└───────────────────────────────┬─────────────────────────────────────┘
                                │  HTTPS / REST
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application (app/)                    │
│                     Zero Business Logic — Entry Point Only           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │discovery │ │scheduling│ │ identity │ │ booking  │ │payments  │ │
│  │-catalog  │ │          │ │          │ │-inventory│ │-ticketing│ │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘ │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                            │
│  │promotions│ │engagement│ │  admin/  │                            │
│  └──────────┘ └──────────┘ └──────────┘                            │
│  ─────────────────── shared/ (ACL Facades) ─────────────────────── │
│  ┌───────────────┐  ┌───────────────┐  ┌─────────────────────────┐ │
│  │  Eventbrite   │  │    Stripe     │  │      OpenAI GPT-4o      │ │
│  │  ACL Facades  │  │  ACL Facades  │  │      ACL Facades        │ │
│  └───────────────┘  └───────────────┘  └─────────────────────────┘ │
└───────┬───────────────────┬──────────────────────┬──────────────────┘
        │                   │                      │
        ▼                   ▼                      ▼
┌───────────────┐  ┌────────────────┐   ┌─────────────────────────┐
│  Eventbrite   │  │   Stripe API   │   │     OpenAI API           │
│  API v3       │  │  (sk_test_...) │   │  (GPT-4o + Embeddings)  │
│  (listing     │  │  Payments +    │   │  (RAG + Moderation)     │
│   sync only)  │  │  Refunds +     │   └─────────────────────────┘
└───────────────┘  │  Webhooks      │
                   └────────────────┘
        │                   │
        ▼                   ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Data Layer                                   │
│  PostgreSQL (primary)  Redis (locks/cache)  Elasticsearch (RAG)  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Module Map

| Module | Package | Owns | Key DB Tables |
|---|---|---|---|
| `discovery-catalog` | `com.*.discoverycatalog` | Events, Venues, Cities, catalog sync | `cities`, `venues`, `event_catalog` |
| `scheduling` | `com.*.scheduling` | Show slots, time slot config, conflict validation | `show_slots`, `turnaround_policies` |
| `identity` | `com.*.identity` | Users, JWT auth, profiles, preferences, wallet | `users`, `user_preferences`, `user_profiles` |
| `booking-inventory` | `com.*.bookinginventory` | Seats, SeatMap, Cart, Pricing, Seat Lock State Machine | `seats`, `seat_maps`, `carts`, `cart_items`, `pricing_tiers` |
| `payments-ticketing` | `com.*.paymentsticketing` | Bookings, Payments (Stripe), E-Tickets, Cancellations, Refunds, Wallet | `bookings`, `booking_items`, `payments`, `refunds`, `e_tickets`, `wallet_transactions` |
| `promotions` | `com.*.promotions` | Coupons, Promotions, Eligibility rules, EB discount sync | `promotions`, `coupons`, `coupon_redemptions` |
| `engagement` | `com.*.engagement` | Reviews, Moderation, AI Chatbot, RAG pipeline | `reviews`, `moderation_records`, `chat_sessions`, `chat_messages` |
| `admin/` | `com.*.admin` | **No domain ownership.** Orchestration and aggregation only | _(no tables)_ |
| `shared/` | `com.*.shared` | All external ACL facades, JWT, base classes, common DTOs | _(no tables)_ |
| `app/` | `com.*.app` | Entry point only — `@SpringBootApplication` | _(no tables)_ |

---

## 5. Module Dependency Graph

```
shared/              ← foundational — no dependency on any module
    │
    ├─────────────────────────────────┐
    ▼                                 ▼
discovery-catalog                 identity
    │                                 │
    ▼                                 │
scheduling                            │
    │ (SlotSummaryReader,              │
    │  SlotPricingReader)              │
    ▼                                 ▼
booking-inventory ←──────────────────┘
    │ (CartSnapshotReader)
    ▼
payments-ticketing
    │ (PaymentConfirmationReader ─► booking-inventory watchdog)
    ▼
promotions
    │
    ▼
engagement
    │
    ▼
admin/       ← thin orchestration; reads from all, writes via REST APIs
    │
    ▼
app/         ← entry point only
```

**Shared Reader Interfaces (in-process, zero HTTP overhead):**

| Interface | Defined In | Implemented By | Consumed By |
|---|---|---|---|
| `SlotSummaryReader` | `shared/common/service/` | `scheduling` module | `booking-inventory` |
| `SlotPricingReader` | `shared/common/service/` | `scheduling` module | `booking-inventory` |
| `CartSnapshotReader` | `shared/common/service/` | `booking-inventory` | `payments-ticketing`, `promotions` |
| `PaymentConfirmationReader` | `shared/common/service/` | `payments-ticketing` | `booking-inventory` (watchdog) |

---

## 6. Inter-Module Communication

Three mechanisms — never break these rules:

### 6.1 REST Calls (Synchronous Cross-Module Reads)
Used when Module B needs current state from Module A:
```
booking-inventory ──GET──► scheduling (slot/pricing data)
payments-ticketing ──GET──► booking-inventory (cart snapshot)
promotions ──GET──► booking-inventory (cart summary for coupon eligibility)
engagement ──GET──► payments-ticketing (booking confirmation status)
admin/ ──GET──► all modules (aggregate views — READ ONLY)
```

### 6.2 Spring Application Events (Async Reactions)

```
CartAssembledEvent         (booking-inventory → payments-ticketing)
  → Trigger: cart TTL started, seats soft-locked
  → Action: create Stripe PaymentIntent, return client_secret to frontend

PaymentFailedEvent         (payments-ticketing → booking-inventory)
  → Trigger: stripe.confirmPayment() error or payment_intent.payment_failed webhook
  → Action: release Redis seat locks, clear cart

BookingConfirmedEvent      (payments-ticketing → engagement, identity, promotions)
  → Trigger: PaymentIntent.status = succeeded
  → Action: unlock review eligibility | update order history | record CouponRedemption

BookingCancelledEvent      (payments-ticketing → booking-inventory)
  → Trigger: user or organizer cancels booking
  → Action: release all seat locks for that booking

ShowSlotCreatedEvent       (scheduling → booking-inventory)
  → Action: provision seat map for new slot

ShowSlotActivatedEvent     (scheduling → booking-inventory)
  → Action: seat provisioning for RESERVED seating mode

ShowSlotCancelledEvent     (scheduling → booking-inventory + payments-ticketing)
  → Action: release all seats | bulk Stripe refunds for all CONFIRMED bookings

CouponValidatedEvent       (promotions → analytics/audit)
  → Trigger: eligibility checks passed — cart not yet modified

CouponAppliedEvent         (promotions → booking-inventory)
  → Action: CouponAppliedListener sets cart.couponDiscountAmount

EventCatalogUpdatedEvent   (discovery-catalog → engagement)
  → Action: RAGIndexService refreshes Elasticsearch vector store
```

### 6.3 Inbound Eventbrite Webhooks
```
Eventbrite fires webhook
  → EbWebhookController (POST /api/webhooks/eventbrite)
  → EbWebhookDispatcher (routes by action type)
  → Internal Spring Event published
  → Modules listen — NEVER consume raw webhook directly

event.updated (status→cancelled) / event.unpublished
  → ShowSlotCancelledEvent
  → booking-inventory releases all seats
  → payments-ticketing: bulk Stripe refunds for all CONFIRMED bookings
```

---

## 7. External Integrations

### 7.1 Eventbrite ACL Facades (`shared/eventbrite/service/`)

| Facade | Role | Used By |
|---|---|---|
| `EbEventSyncService` | Create/update/publish/cancel/delete events; pull org catalog | discovery-catalog, scheduling |
| `EbVenueService` | Create/update venues; list org venues | discovery-catalog, scheduling |
| `EbScheduleService` | Create recurring event schedules | scheduling |
| `EbTicketService` | Create/update ticket classes; check availability | booking-inventory, scheduling |
| `EbCapacityService` | Read/update event capacity tiers | scheduling, admin/ |
| `EbOrderService` | Org-level order reporting **only** (NOT used in payments) | admin/ |
| `EbAttendeeService` | Attendance verification for reviews | engagement (FR8 only) |
| `EbDiscountSyncService` | Create/update/delete discount codes | promotions |
| `EbRefundService` | **INACTIVE** — Stripe handles all refunds | _(legacy stub only)_ |
| `EbWebhookService` | **Test/mock only** — not used in production | _(testing only)_ |

### 7.2 Stripe ACL Facades (`shared/stripe/service/`)

| Facade | Key Operations |
|---|---|
| `StripePaymentService` | `POST /v1/payment_intents` · `GET /v1/payment_intents/{id}` |
| `StripeRefundService` | `POST /v1/refunds` · `GET /v1/refunds/{id}` |
| `StripeWebhookHandler` | Signature verification · routes `payment_intent.succeeded/failed` · `refund.updated/failed` |

**Test Cards:**

| Scenario | Card | Result |
|---|---|---|
| Success | `4242 4242 4242 4242` | `succeeded` |
| India Success | `4000 0035 6000 0008` | `succeeded` |
| Decline | `4000 0000 0000 0002` | `card_declined` |
| 3DS → success | `4000 0025 0000 3155` | `requires_action → succeeded` |
| Async refund | `4000 0000 0000 7726` | `pending → succeeded` |

### 7.3 OpenAI (`shared/openai/service/`)

| Service | Purpose |
|---|---|
| `OpenAiChatService` | GPT-4o chat completions with tool calling (Chatbot FR11) |
| `OpenAiEmbeddingService` | Generate embeddings for Elasticsearch RAG indexing |

---

## 8. FR1 — City Selection → Event Discovery

**Module:** `discovery-catalog`

```
User selects city
    │
    ▼
Query internal cities table → city_id + coordinates
    │
    ▼
Query internal venues table (filtered by city_id)
    │  +  EbVenueService → GET /organizations/{org_id}/venues/ (cross-reference)
    ▼
Query internal event_catalog (filtered by city + venue)
    │  +  EbEventSyncService → GET /organizations/{org_id}/events/ (catalog sync)
    │  +  EbEventSyncService → GET /events/{event_id}/ (record refresh)
    ▼
Merge internal records + Eventbrite metadata
    │
    ▼
Return unified event list to UI
    │
    ▼
EventCatalogUpdatedEvent published → engagement refreshes RAG index
```

**Sync Strategy:** Scheduled catalog sync every 15 min via `EbEventSyncService`.  
**Constraint:** Public city-wide Eventbrite search (`GET /events/search/`) is **deprecated** — discovery is limited to your org's events.

---

## 9. FR2 — Show Scheduling & Conflict Validation

**Module:** `scheduling`

```
Phase 1 — Venue Onboarding (one-time)
─────────────────────────────────────
Admin registers venue in app
    │
    ├─► Internal venues table updated
    └─► EbVenueService → POST /organizations/{org_id}/venues/
                       → Save eb_venue_id in venues table

Phase 2 — Show Slot Creation (core sync)
────────────────────────────────────────
Admin fills slot: venue + date + time + show name
    │
    ├─► Internal conflict check (show_slots table — turnaround gap enforced)
    │   [Eventbrite has NO conflict API — gap logic is 100% internal]
    │
    ├─► EbEventSyncService → POST /organizations/{org_id}/events/
    │                       → Save eventbrite_event_id in show_slots ← SPINE OF SYSTEM
    │
    ├─► EbTicketService → POST /events/{id}/ticket_classes/  (pricing tiers)
    ├─► EbCapacityService → POST /events/{id}/inventory_tiers/
    ├─► EbCapacityService → POST /events/{id}/capacity_tier/
    └─► EbEventSyncService → POST /events/{id}/publish/
    
    Publishes: ShowSlotCreatedEvent → booking-inventory provisions seat map

Phase 3 — Recurring Shows
─────────────────────────
Admin creates recurring series (e.g., every Saturday)
    │
    ├─► Internal DB generates individual show_slot records
    └─► EbScheduleService → POST /events/{id}/schedules/

Phase 4 — Slot Status Changes
──────────────────────────────
DRAFT    → EbEventSyncService.unpublish()
ACTIVE   → EbEventSyncService.publish()
CANCELLED→ EbEventSyncService.cancel()
            ↓
          ShowSlotCancelledEvent → booking-inventory releases seats
                                 → payments-ticketing: bulk Stripe refunds

Phase 5 — Conflict Validation (Background Job, hourly)
────────────────────────────────────────────────────────
Scan show_slots table → for each eventbrite_event_id:
    └─► EbEventSyncService → GET /events/{id}/ — orphan detection
    └─► Flag mismatches for admin review
```

**ACL Facades:** `EbEventSyncService`, `EbVenueService`, `EbScheduleService`, `EbTicketService`, `EbCapacityService`

---

## 10. FR3 — User Registration & Preference Onboarding

**Module:** `identity`

```
Step 1: Registration
─────────────────────
User submits email + password + name
    │
    ├─► Validate uniqueness in users table
    ├─► BCrypt hash password
    ├─► Create user record
    ├─► Issue JWT (access + refresh tokens)
    └─► [NO Eventbrite call — no user creation API on EB]

Step 2: Profile Setup
──────────────────────
User fills: display name, phone, city, avatar
    └─► Update users table (no EB sync at this stage)

Step 3: Preference Onboarding
──────────────────────────────
User selects: genres, cities, price ranges, notification prefs
    └─► Store in user_preferences table
        (drives personalised event recommendations from internal catalog)

Post-Purchase Link (later flows)
─────────────────────────────────
When booking confirmed → user linked to Eventbrite via order email
BookingConfirmedEvent → identity.BookingConfirmedListener → update order history
```

**Key Constraint:** Eventbrite has **no user creation API**. Identity is 100% internal.

---

## 11. FR4 — Seat Selection & Cart Assembly

**Module:** `booking-inventory`

```
Step 1: Seat Selection
───────────────────────
User picks seats from internal SeatMap
    │
    ├─► AvailabilityGuard: check internal seat state (AVAILABLE required)
    ├─► EbTicketService → GET /events/{id}/ticket_classes/for_sale/ (count > 0?)
    └─► EbTicketService → GET /events/{id}/inventory_tiers/{id}/ (tier capacity)

    AcquireRedisLockAction: atomic SET NX with TTL
    seat state: AVAILABLE → SOFT_LOCKED

    If Redis lock fails → ConflictResolution: user notified, seat stays AVAILABLE

Step 2: Pricing Validation
───────────────────────────
Internal PricingTier rules applied (Standard / Premium / VIP)
    ├─► Group discount: if seat count > threshold → groupDiscountAmount set on cart
    ├─► EbTicketService → GET /events/{id}/ticket_classes/ (price cross-check)
    └─► Subtotal computed internally

Step 3: Cart Assembly
──────────────────────
    ├─► Cart record created in carts table
    ├─► CartTtlGuard + HardLockAction: extend Redis lock for checkout
    │   seat state: SOFT_LOCKED → HARD_LOCKED
    ├─► Coupon eligibility checked via promotions module REST call
    │   → CartSummaryDto (via CartSnapshotReader) passed to promotions.CouponEligibilityService
    └─► CartAssembledEvent published → payments-ticketing listens
```

**ACL Facades:** `EbTicketService`  
**Redis Keys:** `seat:lock:{seatId}`

---

## 12. FR5 — Payment via Stripe & Booking Confirmation

**Module:** `payments-ticketing`

> **Eventbrite is NOT involved in any payment, order creation, or e-ticket step.**  
> Stripe owns the entire checkout and confirmation flow.

```
Step 1: PaymentIntent Creation
────────────────────────────────
CartAssembledEvent received
    │
    ├─► Calculate total from CartSnapshotReader (includes group + coupon discounts)
    ├─► StripePaymentService.createPaymentIntent() → POST /v1/payment_intents
    │   metadata: booking_ref, event_id, user_id | currency: inr | amount: paise
    │
    ├─► client_secret → frontend ONLY (NEVER stored server-side)
    └─► stripe_payment_intent_id saved to payments table (status = PENDING)

Step 2: Frontend Payment
─────────────────────────
Stripe Payment Element rendered with client_secret
    │
    └─► User enters card → stripe.confirmPayment()
        ─► Success: Stripe redirects to return_url
                    Frontend: POST /api/payments/confirm-order (with payment_intent_id)
        ─► Failure: stripe.confirmPayment() returns error
                    Frontend: POST /api/payments/failed
                    Backend: PaymentFailedEvent → seat locks released

Step 3: Backend Confirms Payment
──────────────────────────────────
StripePaymentService.retrievePaymentIntent(id) → GET /v1/payment_intents/{id}
    │
    ├─► Assert: status = succeeded AND amount_received == amount
    ├─► Idempotency check: skip if booking already CONFIRMED for this payment_intent
    ├─► Booking record created (bookings table, status = CONFIRMED)
    ├─► booking_items created from cart snapshot
    ├─► Seat locks: HARD_LOCKED → CONFIRMED
    ├─► stripe_payment_intent_id + stripe_charge_id (latest_charge) saved
    └─► BookingConfirmedEvent published
            → engagement: unlock review eligibility
            → identity: update order history
            → promotions: record CouponRedemption (by bookingId)

Step 4: E-Ticket Generation
─────────────────────────────
ETicket record created in e_tickets table
    ├─► QR code generated internally (booking_reference + booking_item_id)
    │   [NO Eventbrite barcode]
    ├─► PDF e-ticket stored in object storage
    └─► Download link + confirmation email sent to user

Step 5: Stripe Webhooks (Safety Net)
──────────────────────────────────────
payment_intent.succeeded   → idempotent booking confirm (skip if already CONFIRMED)
payment_intent.failed      → PaymentFailedEvent → release seat locks + clear cart
payment_intent.canceled    → same as .failed
refund.updated             → update refunds.status (pending → succeeded)
refund.failed              → alert ops + notify user; refunds.status = FAILED
```

**Payment Success Summary:**
```
POST /v1/payment_intents → client_secret to frontend
  → stripe.confirmPayment() → redirect to return_url
  → POST /api/payments/confirm-order
  → GET /v1/payment_intents/{id} → succeeded
  → Booking CONFIRMED → E-ticket generated → email sent
  → payment_intent.succeeded webhook (idempotent safety net)
```

**Payment Failure Summary:**
```
stripe.confirmPayment() error
  → POST /api/payments/failed
  → PaymentFailedEvent published
  → Redis locks released → Cart cleared → User can retry
  → payment_intent.payment_failed webhook (backend safety net)
```

---

## 13. FR6 — Cancellation, Refund & Wallet Credit

**Module:** `payments-ticketing`

> **All refunds go through Stripe (`StripeRefundService`). No Eventbrite refund API involved for buyer cancellations.**

```
Buyer-Initiated Cancellation
──────────────────────────────
POST /api/bookings/{booking_ref}/cancel
    │
    ├─► CancellationRequest created in cancellation_requests table
    ├─► Refund policy evaluated (time-based):
    │   > 48h before show  → full refund
    │   24–48h before show → partial refund
    │   < 24h before show  → no refund (policy denies)
    │
    ├─► Booking status → CANCELLATION_PENDING
    │
    ├─► StripeRefundService.createRefund(paymentIntentId, amount?, reason)
    │   → POST /v1/refunds
    │   → stripe_refund_id (re_...) saved to refunds table
    │   → status: succeeded (immediate) OR pending (async)
    │
    ├─► Booking → CANCELLED | booking_items → CANCELLED | e_tickets → VOIDED
    ├─► BookingCancelledEvent published → booking-inventory releases seat locks
    └─► If partial: wallet credit recorded in wallet_transactions table

Async Refund (Stripe Webhook)
──────────────────────────────
refund.updated webhook → refundService.updateStatus(refundId, status)
  → pending → succeeded: user notified of card refund
refund.failed webhook → ops alerted + user notified + refunds.status = FAILED

Organizer-Initiated Bulk Cancellation (EB Webhook)
────────────────────────────────────────────────────
Eventbrite fires event.updated (status→cancelled) or event.unpublished
    │
    ├─► EbWebhookDispatcher → ShowSlotCancelledEvent published
    ├─► booking-inventory: release all seat locks for that slot
    ├─► payments-ticketing: iterate all CONFIRMED bookings for event_id
    │     └─► StripeRefundService.createRefund() for each booking
    ├─► All bookings → CANCELLED | e_tickets → VOIDED | refunds records created
    └─► EbEventSyncService → POST /events/{id}/cancel/ (confirm EB listing cancelled)
```

---

## 14. FR7 — Coupon & Offer Engine

**Module:** `promotions`

```
Step 1: Admin Creates Promotion
────────────────────────────────
Admin creates promotion (discount type, value, validity, usage limits)
    ├─► Internal promotions table updated
    └─► EbDiscountSyncService → POST /organizations/{org_id}/discounts/
                              → Save discount_id in coupons table

Step 2: User Applies Coupon
──────────────────────────────
User enters coupon code
    ├─► Internal eligibility checks:
    │   - coupon exists and not expired
    │   - usage count < usage_limit
    │   - user eligibility rules pass
    │   - CartSummaryDto loaded via CartSnapshotReader (orgId, slotId, currency, couponCode)
    │
    ├─► EbDiscountSyncService → GET /discounts/{discount_id}/ (read rules + usage count)
    ├─► CouponValidatedEvent published (audit signal — cart NOT yet modified)
    │
    ├─► Discount amount computed internally:
    │   netTotal = groupDiscountTotal − couponDiscountAmount (floor at 0)
    │
    └─► CouponAppliedEvent published → booking-inventory.CouponAppliedListener
            → sets cart.couponDiscountAmount

Step 3: Discounted Stripe PaymentIntent
─────────────────────────────────────────
The netTotal (post group-discount, post coupon) becomes the `amount` in:
    └─► StripePaymentService.createPaymentIntent(netTotal) → POST /v1/payment_intents
    [NO EB Checkout Widget promoCode — discount is pre-applied to Stripe amount]

Step 4: Post-Redemption Sync
──────────────────────────────
BookingConfirmedEvent received
    ├─► CouponRedemption row created (bookingId, couponCode, discountAmount)
    ├─► Usage count incremented internally
    └─► If usage limit reached:
        └─► EbDiscountSyncService → DELETE /discounts/{id}/ (deactivate on EB)
```

---

## 15. FR8 — Reviews, Moderation & Publish

**Module:** `engagement`

```
Step 1: Verify Attendance (Gate)
──────────────────────────────────
User submits review for event_id
    ├─► Check internal: user has CONFIRMED booking for this event_id?
    │   (REST call to payments-ticketing)
    └─► EbAttendeeService → GET /events/{event_id}/attendees/ (find by email)
                          → GET /events/{event_id}/attendees/{id}/ (cancelled=false, refunded=false)

Step 2: Review Submission
──────────────────────────
If attendance verified:
    ├─► Review record created in reviews table (status = SUBMITTED)
    └─► Review transitions: SUBMITTED → PENDING_MODERATION

Step 3: Moderation Engine
──────────────────────────
Automated checks:
    ├─► Profanity filter
    ├─► Spam detection (OpenAI moderation API via OpenAiChatService)
    └─► ModerationRecord created with decision

    APPROVED → PUBLISHED  (public-facing)
    REJECTED → REJECTED   (terminal, user notified)

Review State Machine:
SUBMITTED → PENDING_MODERATION → APPROVED → PUBLISHED
                                          → REJECTED (terminal)
```

**Trigger:** `BookingConfirmedEvent` → `engagement.BookingConfirmedListener` → unlocks review eligibility for that booking.

---

## 16. FR9 — Admin Dashboard & Event CRUD

**Module:** `admin/` (thin orchestrator — delegates to module REST APIs)

```
Event CRUD
───────────
Create:  Internal event_catalog updated → EbEventSyncService.createEvent()
Update:  Internal updated → EbEventSyncService.updateEvent()
Publish: EbEventSyncService.publishEvent()
Cancel:  EbEventSyncService.cancelEvent() → triggers FR6 bulk refund
Delete:  EbEventSyncService.deleteEvent() (drafts only)

Seat Inventory Management
──────────────────────────
Admin updates ticket tiers / capacities / seat maps
    ├─► Internal pricing_tiers + seat_maps updated first
    ├─► EbTicketService → POST /events/{id}/ticket_classes/{id}/
    └─► EbCapacityService → POST /events/{id}/capacity_tier/

Venue & Media
──────────────
Admin uploads images → POST /media/upload/ (Eventbrite)
Admin updates venue → EbVenueService → POST /organizations/{org_id}/venues/

Reporting & Analytics
──────────────────────
Sales reports: GET /reports/sales/ + merge with internal analytics
Attendee list: EbAttendeeService.listAttendees() → GET /events/{id}/attendees/
Order list:    EbOrderService.listOrders() → GET /organizations/{org_id}/orders/
```

**Rule:** `admin/` defines **no `@Entity`** and owns **no database tables**. All writes go through module REST APIs. All reads can go directly to module `@Service` beans (read-only exception).

---

## 17. FR10 — Seat Lock State Machine

**Module:** `booking-inventory/statemachine/`

```
       ┌─────────────────────────────────────────────────────────────┐
       │                   SEAT STATE MACHINE                        │
       └─────────────────────────────────────────────────────────────┘

AVAILABLE
    │
    │ [SELECT]
    │ Guard: AvailabilityGuard (internal DB: seat.status == AVAILABLE)
    │ Eventbrite: GET /events/{id}/ticket_classes/for_sale/ (count > 0?)
    │ Action: AcquireRedisLockAction → atomic SET NX with TTL
    │
    ├─── (lock acquired) ──────────────────────────────────────────────┐
    │                                                                  ▼
    │                                                          SOFT_LOCKED
    │                                                          (Redis TTL active)
    │                                                                  │
    │                                                     [CONFIRM] + CartTtlGuard
    │                                                     Action: HardLockAction
    │ (lock fails → ConflictResolution)                               │
    │   user notified → seat stays AVAILABLE                          ▼
    │                                                          HARD_LOCKED
    │                                                     (checkout Redis lock)
    │                                                                  │
    │                                                          [PAY] frontend
    │                                                          stripe.confirmPayment()
    │                                                                  │
    │                                                                  ▼
    │                                                       PAYMENT_PENDING
    │                                                                  │
    │                                              [CONFIRM_PAYMENT]   │   [RELEASE]
    │                                    GET /v1/payment_intents/{id}  │   PaymentFailedEvent
    │                                    status == succeeded            │   StripeWebhook
    │                                                │                  │
    │                                                ▼                  ▼
    │◄───────────────────────────────────────── AVAILABLE ◄──── RELEASED
    │
    └─► CONFIRMED (stripe_payment_intent_id + stripe_charge_id stored)
```

**Guards:** `AvailabilityGuard` · `CartTtlGuard`  
**Actions:** `AcquireRedisLockAction` · `HardLockAction` · `RollbackAction`  
**Redis Keys:** `seat:lock:{seatId}`

**Concurrent Conflict:**  
Two users select same seat simultaneously → first `SET NX` wins → second user gets `ConflictResolution` → redirected to next available seat in same tier.

**PaymentTimeoutWatchdog:** Queries `PaymentConfirmationReader.isPaymentConfirmed(cartId)` periodically — if cart TTL expires without confirmation → triggers rollback → seats return to `AVAILABLE`.

---

## 18. FR11 — AI-Powered Chatbot (RAG)

**Module:** `engagement/service/chatbot/`

```
User message received
    │
    ▼
ChatbotService (manages conversation context, session history)
    │
    ├─► RagPipelineService
    │       └─► Elasticsearch (vector store)
    │           → retrieve relevant chunks: event catalog + refund policies
    │           → indexed from internal event_catalog via EventCatalogUpdatedEvent
    │
    ├─► OpenAiChatService (GPT-4o with tool calling)
    │
    │   Tools available to GPT-4o:
    │   ├── EventSearchTool (internal)
    │   │       └─► REST call to EventCatalogService
    │   │           (city + genre + date filters — internal catalog)
    │   │
    │   └── EbLiveSearchTool (Eventbrite live query)
    │           └─► EbEventSyncService → GET /events/{id}/ (live status)
    │               EbEventSyncService → GET /organizations/{org_id}/events/
    │               EbTicketService   → GET /events/{id}/ticket_classes/for_sale/
    │
    └─► Response returned to user

RAG Index Refresh:
EventCatalogUpdatedEvent (published by discovery-catalog)
    └─► engagement.RAGIndexService
        → OpenAiEmbeddingService.generateEmbeddings()
        → Elasticsearch.upsert(chunks)
```

**Constraint:** Public Eventbrite search (`GET /events/search/`) is **deprecated** — chatbot scopes live queries to your org's events only.

---

## 19. Spring Events Map (Full)

```
┌─────────────────────┬───────────────────────────┬──────────────────────────┬────────────────────────────┐
│ Publisher           │ Event                     │ Consumer                 │ Action                     │
├─────────────────────┼───────────────────────────┼──────────────────────────┼────────────────────────────┤
│ booking-inventory   │ CartAssembledEvent        │ payments-ticketing       │ Create Stripe PaymentIntent │
│ payments-ticketing  │ PaymentFailedEvent        │ booking-inventory        │ Release Redis seat locks   │
│ payments-ticketing  │ BookingConfirmedEvent     │ engagement               │ Unlock review eligibility  │
│ payments-ticketing  │ BookingConfirmedEvent     │ identity                 │ Update order history       │
│ payments-ticketing  │ BookingConfirmedEvent     │ promotions               │ Record CouponRedemption    │
│ payments-ticketing  │ BookingCancelledEvent     │ booking-inventory        │ Release seat locks         │
│ scheduling          │ ShowSlotCreatedEvent      │ booking-inventory        │ Provision seat map         │
│ scheduling          │ ShowSlotActivatedEvent    │ booking-inventory        │ Seat provisioning (RESERVED)│
│ scheduling          │ ShowSlotCancelledEvent    │ booking-inventory        │ Release all seats          │
│ scheduling          │ ShowSlotCancelledEvent    │ payments-ticketing       │ Bulk Stripe refunds        │
│ promotions          │ CouponValidatedEvent      │ (audit/analytics)        │ Audit signal               │
│ promotions          │ CouponAppliedEvent        │ booking-inventory        │ Set couponDiscountAmount   │
│ discovery-catalog   │ EventCatalogUpdatedEvent  │ engagement               │ Refresh RAG Elasticsearch  │
└─────────────────────┴───────────────────────────┴──────────────────────────┴────────────────────────────┘
```

**Event payload rule:** Spring Events carry **only** primitives, IDs, enums, and value objects — never `@Entity` objects.

---

## 20. Shared Infrastructure

### Common Base Classes (`shared/common/`)

| Class | Purpose |
|---|---|
| `BaseEntity` | `id`, `createdAt`, `updatedAt` — all `@Entity` classes extend this |
| `Money` | Value object for all monetary amounts — never use raw primitives |
| `ApiResponse<T>` | Standard success response wrapper |
| `PageResponse<T>` | Paginated list wrapper |
| `ErrorResponse` | Standard error shape — all API errors use this |
| `GlobalExceptionHandler` | **Single** `@ControllerAdvice` for the entire app — in `shared/` |
| `ResourceNotFoundException` | 404 — extend for module-specific not-found |
| `BusinessRuleException` | 422 — thrown when a domain rule is violated |
| `SystemIntegrationException` | 502 — external system failure |

### Security (`shared/security/`)

| Class | Purpose |
|---|---|
| `JwtTokenProvider` | JWT generation and validation |
| `JwtAuthenticationFilter` | Spring Security filter — registered once globally |

### Config (`shared/config/`)

| Config | Purpose |
|---|---|
| `RedisConfig` | Redisson bean — distributed seat locking |
| `ElasticsearchConfig` | ES client — RAG vector store |

### Shared Reader Interfaces (`shared/common/service/`)

| Interface | DTOs | Implemented By | Consumed By |
|---|---|---|---|
| `SlotSummaryReader` | `SlotSummaryDto` | `scheduling` | `booking-inventory` |
| `SlotPricingReader` | `PricingTierDto` | `scheduling` | `booking-inventory` |
| `CartSnapshotReader` | `CartItemSnapshotDto`, `CartSummaryDto` | `booking-inventory` | `payments-ticketing`, `promotions` |
| `PaymentConfirmationReader` | `boolean` | `payments-ticketing` | `booking-inventory` watchdog |

---

## 21. Database & Migrations

**All Flyway scripts:** `app/src/main/resources/db/migration/`  
**Naming:** `V[n]__[verb]_[table].sql` (e.g. `V5__create_bookings.sql`)  
**Latest migration:** `V37__create_promotions_tables.sql` (March 2026)

| Module | Key Tables |
|---|---|
| discovery-catalog | `cities`, `venues`, `event_catalog` |
| scheduling | `show_slots`, `turnaround_policies` |
| identity | `users`, `user_preferences`, `user_profiles` |
| booking-inventory | `seats`, `seat_maps`, `carts`, `cart_items`, `pricing_tiers`, `group_discount_rules` |
| payments-ticketing | `bookings`, `booking_items`, `payments`, `refunds`, `cancellation_requests`, `e_tickets`, `wallet_transactions` |
| promotions | `promotions`, `coupons`, `coupon_redemptions` |
| engagement | `reviews`, `moderation_records`, `chat_sessions`, `chat_messages` |

**Key field:** `eventbrite_event_id` — stored in `show_slots`. If null, FR7/FR8/FR10 EB availability check is blocked (FR5 Stripe payments still work without it).

---

## 22. Hard Rules Summary

| # | Rule |
|---|---|
| 1 | No module imports another module's `@Service`, `@Entity`, or `@Repository`. Exception: `admin/` may READ from module services only. |
| 2 | No module calls Eventbrite, Stripe, or OpenAI HTTP directly — only through `shared/` ACL facades. |
| 3 | ACL facade names are exactly as listed — never invent new ones. |
| 4 | Spring Events carry only primitives, IDs, enums, and value objects — **never** `@Entity` objects. |
| 5 | One `GlobalExceptionHandler` in `shared/common/exception/` — never `@ControllerAdvice` in a module. |
| 6 | All field mapping in `mapper/` using MapStruct — never inside a service or controller. |
| 7 | `admin/` owns no `@Entity` and no database table. |
| 8 | `shared/` has no dependency on any module. |
| 9 | `app/` has zero business logic. |
| 10 | Dependency direction is strictly downward — no circular imports. |
| 11 | Internal DB is **always updated FIRST** before any external API call. |
| 12 | All payments via `StripePaymentService`. EB Checkout Widget is **removed** — never reference it. |
| 13 | All refunds via `StripeRefundService.createRefund()`. `EbRefundService` is inactive. |
| 14 | Stripe `client_secret` goes to the frontend **only** — never log, store, or return from backend. |
| 15 | Stripe webhook (`POST /api/webhooks/stripe`) must verify `Stripe-Signature` via `Webhook.constructEvent()` before any processing. |

---

*Document auto-generated from `PRODUCT.md` and `docs/EVENTBRITE_INTEGRATION.md` — March 2026*
