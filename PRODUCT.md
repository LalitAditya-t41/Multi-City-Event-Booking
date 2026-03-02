# PRODUCT.md — Module Registry & Product Map

> **Owner:** Lalit
> **Domain:** EntertainmentTech
> **Architecture:** Modular Monolith — Spring Boot 3.5.11, Java 21
> **External Integration:** Eventbrite (primary), Razorpay (payments), OpenAI GPT-4o (AI assistant)
> **Last Updated:** March 2026
>
> This is the single source of truth for module ownership, boundaries, inter-module communication,
> and the Eventbrite ACL. Every agent and developer MUST read this before touching any module.

---

## The 7 Modules — What Each One Owns

| Module | Owns | Never Touches |
|---|---|---|
| `discovery-catalog` | Events, Venues, Cities, Eventbrite catalog sync | Bookings, Users, Payments |
| `scheduling` | Show slots, time slot config, conflict validation | Events (reads only), Bookings |
| `identity` | Users, JWT auth, profiles, preferences, EB profile sync | Bookings, Payments, Events |
| `booking-inventory` | Seats, SeatMap, Cart, Seat Lock State Machine, Pricing | Payments, Reviews, Users |
| `payments-ticketing` | Bookings, Payments, E-Tickets, Cancellations, Refunds, Wallet | Seats (reads cart only) |
| `promotions` | Coupons, Promotions, Eligibility rules, EB discount sync | Bookings, Payments, Users |
| `engagement` | Reviews, Moderation, AI Chatbot, RAG pipeline | Bookings (reads attended status only) |

**`admin/`** — owns no domain. Orchestrates and aggregates across modules. No `@Entity` classes.
**`shared/`** — owns all external integrations (Eventbrite ACL), JWT, base classes, common DTOs.
**`app/`** — entry point only. `@SpringBootApplication`. Zero business logic.

---

## Module Dependency Order

```
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
payments-ticketing    ← depends on shared + booking-inventory (reads cart via REST) + identity (reads user via REST)
    ↓
promotions            ← depends on shared + booking-inventory (reads cart via REST) + identity (reads user via REST)
    ↓
engagement            ← depends on shared + payments-ticketing (reads booking status via REST) + identity (reads user via REST)
    ↓
admin/                ← thin orchestration — reads from all modules, writes via their REST APIs
    ↓
app/                  ← entry point only
```

A module NEVER imports from a module at the same level or below it.
Dependency direction is strictly downward — no circular imports.

---

## How Modules Communicate — 3 Rules

**Rule 1 — Never import another module's `@Service` or `@Repository` directly.**
If Module B needs data from Module A, it calls Module A's REST endpoint.

**Rule 2 — Use Spring Events for async reactions.**
When something important happens in Module A, it publishes a Spring `ApplicationEvent`.
Other modules react by registering `@EventListener` methods. No direct calls.

```
booking-inventory  →  publishes CartAssembledEvent
payments-ticketing →  listens  CartAssembledListener  →  initiates payment

payments-ticketing →  publishes PaymentFailedEvent
booking-inventory  →  listens  PaymentFailedListener  →  triggers seat lock rollback

payments-ticketing →  publishes BookingConfirmedEvent
engagement         →  listens  BookingConfirmedListener  →  unlocks review eligibility
identity           →  listens  BookingConfirmedListener  →  updates order history
```

**Rule 3 — All Eventbrite API calls go through `shared/eventbrite/service/` only.**
No module ever calls Eventbrite directly. The ACL facade services are the only entry point.

---

## Eventbrite ACL — Facades Available in `shared/`

All Eventbrite integration routes through exactly these facade services.
When a feature needs Eventbrite, call one of these — never add new HTTP calls inside a module.

| ACL Facade Service | Location | What It Does |
|---|---|---|
| `EbEventSyncService` | `shared/eventbrite/service/` | Create, update, delete events on Eventbrite; pull catalog |
| `EbOrderService` | `shared/eventbrite/service/` | Create orders, get order details, confirm bookings |
| `EbAttendeeService` | `shared/eventbrite/service/` | Register attendees, fetch attendee records |
| `EbTicketService` | `shared/eventbrite/service/` | Manage ticket classes, update availability |
| `EbDiscountSyncService` | `shared/eventbrite/service/` | Pull discount codes, sync internal promotions |
| `EbRefundService` | `shared/eventbrite/service/` | Submit refund requests, check refund status |

Inbound Eventbrite webhooks:
`EbWebhookController` → `EbWebhookDispatcher` → dispatched as Spring Events → modules listen.
Modules never consume the raw webhook directly.

---

## Module Definitions

---

### `discovery-catalog`

**Owns:** Events, Venues, Cities, Eventbrite catalog sync
**Flows:** City Selection → Venue Discovery → Event Listing

**Domain concepts:**
- `City` — searchable city with coordinates and timezone
- `Venue` — physical venue with address, capacity, and seating map reference
- `EventCatalog` — merged internal + Eventbrite event record; source of truth for event data

**DB tables:** `cities`, `venues`, `event_catalog`

**ACL facades consumed:**
- `EbEventSyncService` — pulls and syncs events from Eventbrite by city
- `EbTicketService` — syncs ticket class availability

**Publishes:**
- `EventCatalogUpdatedEvent` — consumed by `engagement` (RAG index refresh)

**Consumed by:** `scheduling`, `booking-inventory`, `engagement`, `admin/`

---

### `scheduling`

**Owns:** Show slots, time slot configuration, conflict validation
**Flows:** Show Scheduling → Time Slot Configuration → Conflict Validation

**Domain concepts:**
- `ShowSlot` — a scheduled occurrence of an event at a venue with start/end times
- `TurnaroundPolicy` — minimum gap rule enforced between consecutive shows at the same venue

**DB tables:** `show_slots`, `turnaround_policies`

**Reads via REST:** `discovery-catalog` (venue and event data)

**ACL facades consumed:**
- `EbEventSyncService` — syncs slot creation/update to Eventbrite

**Publishes:**
- `ShowSlotCreatedEvent` — consumed by `booking-inventory` (seat map provisioning)
- `ShowSlotCancelledEvent` — consumed by `booking-inventory` (seat release) and `payments-ticketing` (bulk refund trigger)

**Consumed by:** `booking-inventory`, `payments-ticketing`, `admin/`

---

### `identity`

**Owns:** Users, JWT auth, profiles, preferences, Eventbrite attendee profile sync
**Flows:** User Registration → Profile Setup → Preference Onboarding

**Domain concepts:**
- `User` — registered user with credentials, profile, and roles
- `UserPreference` — city, genre, price range, notification preferences
- `UserWallet` — internal wallet balance used for refund credits

**DB tables:** `users`, `user_preferences`, `user_wallets`

**ACL facades consumed:**
- `EbAttendeeService` — syncs new user profile to Eventbrite on registration
- `JwtTokenProvider` (from `shared/security/`) — token generation and validation

**Publishes:**
- `UserRegisteredEvent` — consumed by `payments-ticketing` (Eventbrite attendee registration)

**Listens to:**
- `BookingConfirmedEvent` (from `payments-ticketing`) → `BookingConfirmedListener` → updates order history

**Consumed by:** `payments-ticketing`, `promotions`, `engagement`, `admin/`

---

### `booking-inventory`

**Owns:** Seats, SeatMap, Cart, Pricing tiers, Group discount rules, Seat Lock State Machine
**Flows:** Seat Selection → Pricing Tier Validation → Cart Assembly; Seat Lock State Machine

**Domain concepts:**
- `Seat` — physical seat with tier, price, and current lock state
- `SeatMap` — full venue seat layout for a show slot
- `Cart` — session container of soft-locked seats with computed subtotals
- `PricingTier` — Standard / Premium / VIP with price rules
- `GroupDiscountRule` — discount applied when seat count exceeds a threshold

**DB tables:** `seats`, `seat_maps`, `carts`, `cart_items`, `pricing_tiers`, `group_discount_rules`

**Redis keys owned:** `seat:lock:{seatId}` — distributed lock with TTL

**Reads via REST:** `discovery-catalog` (event and venue context)

**ACL facades consumed:**
- `EbTicketService` — validates real-time seat availability against Eventbrite before locking

**Publishes:**
- `CartAssembledEvent` — consumed by `payments-ticketing` (initiates payment)

**Listens to:**
- `PaymentFailedEvent` (from `payments-ticketing`) → `PaymentFailedListener` → triggers `RollbackAction`
- `ShowSlotCancelledEvent` (from `scheduling`) → releases all seat locks for that slot
- `CouponAppliedEvent` (from `promotions`) → recomputes cart total

**Consumed by:** `payments-ticketing`, `promotions`, `admin/`

#### Seat Lock State Machine — lives in `booking-inventory/statemachine/`

Do not add seat state logic anywhere else. This is an implementation detail of `booking-inventory`.

```
AVAILABLE
    │  [SELECT]  AvailabilityGuard passes + AcquireRedisLockAction
    ▼
SOFT_LOCKED  (held for cart session TTL)
    │  [CONFIRM]  CartTtlGuard passes + HardLockAction
    ▼
HARD_LOCKED  (held during checkout)
    │  [PAY]
    ▼
PAYMENT_PENDING
    │  [CONFIRM_PAYMENT]              [RELEASE] on any failure
    ▼                                      ▼
CONFIRMED                             RELEASED → AVAILABLE
                                      (RollbackAction runs)
```

Guards: `AvailabilityGuard`, `CartTtlGuard`
Actions: `AcquireRedisLockAction`, `HardLockAction`, `RollbackAction`

Atomic rollback: `PaymentFailedEvent` → `PaymentFailedListener` → `RollbackAction`
→ releases Redis lock → seat returns to `AVAILABLE`

---

### `payments-ticketing`

**Owns:** Bookings, Payments, E-Tickets, Cancellations, Refunds, Wallet transactions
**Flows:** Payment Gateway Integration → Booking Confirmation → E-Ticket Generation;
Cancellation Request → Refund Policy Engine → Wallet Credit

**Why this module owns both payment and refund:** Payment and refund are two sides of the same
financial transaction. They share the same `Booking` and `Order` entities. Splitting them would
create cross-module dependencies on the same DB tables — violating module autonomy.

**Domain concepts:**
- `Booking` — confirmed order with status, reference code, and seat snapshot
- `Payment` — Razorpay payment record with intent ID and status
- `ETicket` — QR-coded PDF ticket tied to a booking item
- `CancellationRequest` — user-initiated cancellation with reason and timestamp
- `RefundPolicy` — time-based rules (>48h full refund, 24–48h partial, <24h none)

**Booking states:** `INITIATED → PAYMENT_PENDING → CONFIRMED → CANCELLED` / `FAILED` (terminal)

**DB tables:** `bookings`, `booking_items`, `payments`, `e_tickets`, `cancellation_requests`, `wallet_transactions`

**Reads via REST:** `booking-inventory` (cart), `identity` (user profile)

**ACL facades consumed:**
- `EbOrderService` — creates and confirms orders on Eventbrite
- `EbAttendeeService` — registers user as Eventbrite attendee on booking confirmation
- `EbRefundService` — submits refund request on cancellation
- `RazorpayPaymentService` (from `shared/razorpay/`) — charges and refunds via Razorpay

**Publishes:**
- `BookingConfirmedEvent` — consumed by `engagement` (review eligibility) and `identity` (order history)
- `PaymentFailedEvent` — consumed by `booking-inventory` (seat lock rollback)
- `BookingCancelledEvent` — consumed by `booking-inventory` (seat release)

**Listens to:**
- `CartAssembledEvent` (from `booking-inventory`) → `CartAssembledListener` → initiates payment
- `ShowSlotCancelledEvent` (from `scheduling`) → triggers bulk cancellation and refund
- `UserRegisteredEvent` (from `identity`) → registers user as Eventbrite attendee

**Consumed by:** `engagement`, `admin/`

---

### `promotions`

**Owns:** Coupons, Promotions, Eligibility rules, Eventbrite discount sync
**Flows:** Offer & Coupon Engine → Eligibility Check → Discount Application

**Domain concepts:**
- `Promotion` — internal offer with discount type, value, and validity window
- `Coupon` — unique code tied to a promotion with usage limits
- `CouponRedemption` — record of a coupon applied to a cart by a user

**DB tables:** `promotions`, `coupons`, `coupon_redemptions`

**Reads via REST:** `booking-inventory` (cart total for eligibility check), `identity` (user history for eligibility)

**ACL facades consumed:**
- `EbDiscountSyncService` — pulls Eventbrite discount codes and marks them used on redemption

**Publishes:**
- `CouponAppliedEvent` — consumed by `booking-inventory` (cart total recompute)

**Consumed by:** `booking-inventory`, `admin/`

---

### `engagement`

**Owns:** Reviews, Moderation queue, AI Chatbot, RAG pipeline
**Flows:** Review Submission → Moderation Queue → Public Publish; AI Event Assistant Chatbot

**Domain concepts:**
- `Review` — user-submitted rating and text, tied to a confirmed booking
- `ModerationRecord` — moderation decision with status and moderator notes
- `ChatSession` — stateful AI conversation context per user
- `ChatMessage` — individual message within a chat session

**Review states:** `SUBMITTED → PENDING_MODERATION → APPROVED → PUBLISHED` / `REJECTED` (terminal)

**DB tables:** `reviews`, `moderation_records`, `chat_sessions`, `chat_messages`

**Vector store:** Elasticsearch (via `shared/config/ElasticsearchConfig`) — indexed event catalog + refund policies

**Reads via REST:** `payments-ticketing` (verifies attended booking before allowing review submission), `identity` (user profile)

**ACL facades consumed:**
- `EbEventSyncService` — syncs published reviews to Eventbrite event listing
- `OpenAiChatService` (from `shared/openai/`) — GPT-4o completions with tool definitions
- `OpenAiEmbeddingService` (from `shared/openai/`) — generates embeddings for RAG index

**Publishes:** None

**Listens to:**
- `BookingConfirmedEvent` (from `payments-ticketing`) → `BookingConfirmedListener` → unlocks review eligibility
- `EventCatalogUpdatedEvent` (from `discovery-catalog`) → `RAGIndexService` → refreshes Elasticsearch vector store

#### AI Chatbot — `engagement/service/chatbot/`

```
User message
    ↓
ChatbotService          (orchestrates conversation, manages context)
    ↓
RagPipelineService      (retrieves relevant chunks from Elasticsearch vector store)
    ↓
Elasticsearch           (indexed event catalog + refund policies)
    +
Tool Calling
    ├── EventSearchTool     (calls internal EventCatalogService REST endpoint)
    └── EbLiveSearchTool    (calls shared/eventbrite → EbEventSyncService)
```

RAG index refreshed by `RAGIndexService` on `EventCatalogUpdatedEvent`.

**Consumed by:** `admin/`

---

## Shared Infrastructure

Everything below lives in `shared/` and is never duplicated in any module.

### External ACL Packages

| Package | External System | Facade Services |
|---|---|---|
| `shared/eventbrite/` | Eventbrite API | `EbEventSyncService`, `EbOrderService`, `EbAttendeeService`, `EbTicketService`, `EbDiscountSyncService`, `EbRefundService` |
| `shared/razorpay/` | Razorpay | `RazorpayPaymentService`, `RazorpayWebhookDispatcher` |
| `shared/openai/` | OpenAI GPT-4o | `OpenAiChatService`, `OpenAiEmbeddingService` |

Inbound webhooks: `EbWebhookController` → `EbWebhookDispatcher` → Spring Events → modules listen

### Shared Common

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
| `[System]IntegrationException` | `shared/common/exception/` | 502 — external system failure |
| `JwtTokenProvider` | `shared/security/` | JWT generation and validation |
| `JwtAuthenticationFilter` | `shared/security/` | Spring Security filter — registered once |
| `RedisConfig` | `shared/config/` | Redisson bean for distributed seat locking |
| `ElasticsearchConfig` | `shared/config/` | ES client for RAG vector store |

---

## Inter-Module Event Map

| Publisher | Event | Listener Module | Effect |
|---|---|---|---|
| `booking-inventory` | `CartAssembledEvent` | `payments-ticketing` | Cart ready → initiate payment |
| `payments-ticketing` | `PaymentFailedEvent` | `booking-inventory` | Payment failed → rollback seat locks |
| `payments-ticketing` | `BookingConfirmedEvent` | `engagement` | Booking confirmed → unlock review eligibility |
| `payments-ticketing` | `BookingConfirmedEvent` | `identity` | Booking confirmed → update order history |
| `payments-ticketing` | `BookingCancelledEvent` | `booking-inventory` | Cancellation → release seat locks |
| `scheduling` | `ShowSlotCancelledEvent` | `booking-inventory` | Slot cancelled → release all seats |
| `scheduling` | `ShowSlotCancelledEvent` | `payments-ticketing` | Slot cancelled → trigger bulk refund |
| `identity` | `UserRegisteredEvent` | `payments-ticketing` | New user → register as Eventbrite attendee |
| `promotions` | `CouponAppliedEvent` | `booking-inventory` | Coupon applied → recompute cart total |
| `discovery-catalog` | `EventCatalogUpdatedEvent` | `engagement` | Catalog updated → refresh RAG index |

---

## Hard Rules — Never Violate These

1. `app/` has zero business logic. No `@Service`, no `@Entity`, no `@Repository`.
2. `shared/` has zero dependency on any module. It knows nothing about bookings, users, or events.
3. No module imports another module's `@Entity` class or `@Repository` interface.
4. No module calls Eventbrite HTTP directly — only through `shared/eventbrite/service/`.
5. Spring Events carry only primitive values, IDs, enums, and value objects — never `@Entity` objects.
6. `admin/` defines no `@Entity` and owns no database table.
7. Dependency direction is strictly downward — no circular imports between modules.
8. One `GlobalExceptionHandler` in `shared/common/exception/` — never add `@ControllerAdvice` in a module.

---

## Where to Put New Code

**Java package naming rule for modules:**
Module names contain hyphens (e.g., `booking-inventory`) which are not valid in Java packages.
Use the module name with hyphens removed (concatenated) when forming the package path.
Example: `booking-inventory` → `com.[org].bookinginventory`.

```
New REST endpoint?                    → [module]/api/controller/
New request/response shape?           → [module]/api/dto/request/ or response/
New business rule or domain behavior? → [module]/domain/  (no Spring annotations)
New use case orchestration?           → [module]/service/
New database table access?            → [module]/repository/
This module needs to notify others?   → [module]/event/published/
This module reacts to another?        → [module]/event/listener/
Shared across 2+ modules?             → shared/common/
New external system integration?      → shared/[system-acl]/  (never inside a module)
Admin read/aggregate view?            → admin/service/ + admin/api/controller/
```

---

## Flyway Migration Conventions

- All Flyway scripts live in `app/src/main/resources/db/migration/`
- Naming: `V[n]__[verb]_[table].sql` — e.g. `V5__create_bookings.sql`
- Version numbers are global across all modules — check the current highest before adding
- Never modify an existing migration file — always add a new one
- Table names use `snake_case`
