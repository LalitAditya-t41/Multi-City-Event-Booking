User: Now lets plan the SPEC_5.md file for the functional flow 5 now lets make spec file for flow 5.

GitHub Copilot: Ran terminal command:  find /home/dell/Multi-City-Event-Booking -name "*FR5*" -o -name "*SPEC_5*" -o -name "*flow5*" -o -name "*flow-5*" 2>/dev/null | head -20

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FR5-reworked-v2.md#1-1), lines 1 to 150

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#1-1), lines 1 to 100

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FR5-reworked-v2.md#151-151), lines 151 to 462

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#100-100), lines 100 to 300

Read [](file:///home/dell/Multi-City-Event-Booking/pre5_changes_implemented.md#1-1), lines 1 to 100

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/payments-ticketing/src -type f -name "*.java" 2>/dev/null | head -40 && echo "---" && ls /home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/ 2>/dev/null || echo "payments-ticketing not structured yet"

Now I have a clear picture. Let me follow the SPEC_GENERATOR.md process — starting with Stage 1 (Requirements outline) and presenting it for your confirmation before writing the full spec.

---

## SPEC_5.md — Stage 1 Outline: Requirements

**Feature:** FR5 — Stripe Payment → Booking Confirmation → E-Ticket Generation
**Module:** payments-ticketing (primary), booking-inventory (seat lock release), identity (bookmark order history), `engagement` (review unlock), `shared/stripe/` (ACL facades)

---

### Area A — PaymentIntent Creation
- `[MUST]` On `CartAssembledEvent`, create a Stripe `PaymentIntent` via `StripePaymentService` with `amount`, `currency`, `receipt_email`, `metadata[booking_ref]`, `metadata[user_id]`
- `[MUST]` Store `stripe_payment_intent_id` + `status=PENDING` in `payments` table
- `[MUST]` Return `client_secret` to frontend via `GET /api/v1/payments/checkout/{cartId}`
- `[MUST]` Never log or store `client_secret` server-side; return it once and discard
- `[MUST]` If `CartAssembledEvent` arrives for a cart that already has a `PENDING` or `SUCCESS` payment, return idempotent response (no duplicate `PaymentIntent`)

### Area B — Payment Confirmation (Frontend → Backend)
- `[MUST]` Expose `POST /api/v1/payments/confirm` accepting `{ paymentIntentId }` (JWT `ROLE_USER`)
- `[MUST]` Call `StripePaymentService.retrievePaymentIntent(id)` — verify `status = succeeded`
- `[MUST]` Save `stripe_charge_id` (`latest_charge`) from the response to `payments` table
- `[MUST]` Idempotency: if booking already `CONFIRMED` for this `paymentIntentId`, return existing booking reference — no duplicate records
- `[MUST]` If status is not `succeeded`, return `402 PAYMENT_NOT_CONFIRMED` with Stripe status in body

### Area C — Booking Confirmation (Internal)
- `[MUST]` Create `Booking` record (`status=CONFIRMED`, `bookingRef` auto-generated as `BK-{YYYYMMDD}-{seq}`)
- `[MUST]` Create `BookingItem` rows from cart snapshot (one per `CartItem`)
- `[MUST]` Persist `stripePaymentIntentId` and `stripeChargeId` on `Booking`
- `[MUST]` Transition `Payment.status` → `SUCCESS`
- `[MUST]` Publish `BookingConfirmedEvent(cartId, seatIds, stripePaymentIntentId, userId)`
- `[MUST]` All booking creation steps execute within a single `@Transactional` boundary

### Area D — E-Ticket Generation
- `[MUST]` Create one `ETicket` per `BookingItem`
- `[MUST]` Generate QR code data from `bookingRef + bookingItemId` (internal — no EB barcode)
- `[SHOULD]` Generate a PDF e-ticket and store URL (object storage path / placeholder URL)
- `[MUST]` `ETicket.status = ACTIVE` at creation
- `[MUST]` Expose `GET /api/v1/tickets/{bookingRef}` to return e-ticket list for a booking (JWT `ROLE_USER`, owns booking only)

### Area E — Payment Failure Handling
- `[MUST]` Expose `POST /api/v1/payments/failed` accepting `{ paymentIntentId }` for frontend-reported failures
- `[MUST]` Publish `PaymentFailedEvent(cartId, userId)` → booking-inventory listener releases `HARD_LOCKED` seats
- `[MUST]` Update `payments.status = FAILED`
- `[MUST]` `payment_intent.payment_failed` and `payment_intent.canceled` Stripe webhooks must also trigger the same failure path (idempotent)

### Area F — Cancellation & Refund (Buyer-Initiated)
- `[MUST]` Expose `POST /api/v1/bookings/{bookingRef}/cancel` (JWT `ROLE_USER`, owns booking only)
- `[MUST]` Validate cancellation policy: `booking.status = CONFIRMED`, not already cancelled, within cancellation window
- `[MUST]` Call `StripeRefundService.createRefund(paymentIntentId, amount, reason)` — full or partial based on policy
- `[MUST]` Set `Booking.status = CANCELLATION_PENDING` before Stripe call; `CANCELLED` after success
- `[MUST]` Persist `Refund` record with `stripeRefundId`, `amount`, `reason`, `status`
- `[MUST]` Void all `ETicket` records for the booking (`status = VOIDED`)
- `[MUST]` Set all `BookingItem.status = CANCELLED`
- `[MUST]` Publish `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)` → booking-inventory (seat release) + `engagement`
- `[SHOULD]` Support partial refund when a cancellation fee is configured (deduct fee, refund remainder)
- `[WONT]` Organizer-initiated bulk cancel lives in admin/scheduling, not this flow

### Area G — Stripe Webhook Safety Net
- `[MUST]` Expose `POST /api/v1/webhooks/stripe` (public, no JWT, Stripe-Signature verified)
- `[MUST]` Verify signature via `StripeWebhookHandler.constructEvent(payload, sigHeader)` — reject `400` on invalid signature
- `[MUST]` Handle `payment_intent.succeeded` → idempotent booking confirmation (skip if already `CONFIRMED`)
- `[MUST]` Handle `payment_intent.payment_failed` + `payment_intent.canceled` → `PaymentFailedEvent`
- `[MUST]` Handle `refund.updated` → update `refunds.status`
- `[MUST]` Handle `refund.failed` → mark `refunds.status = FAILED`, log alert
- `[SHOULD]` Return `200 OK` for all handled events (Stripe retries on non-200)
- `[WONT]` EB `order.placed`, `order.refunded` webhook handling — Stripe is the order authority

### Area H — Internal Payment Status API (Cross-Module)
- `[MUST]` Expose `GET /api/v1/internal/payments/by-cart/{cartId}` (internal service account auth) for booking-inventory's `PaymentTimeoutWatchdog`
- `[MUST]` Return `{ confirmed: true/false }` based on whether a `CONFIRMED` booking exists for the cart

### Area I — Booking History
- `[MUST]` Expose `GET /api/v1/bookings` (JWT `ROLE_USER`) — paginated list of caller's bookings
- `[MUST]` Expose `GET /api/v1/bookings/{bookingRef}` (JWT `ROLE_USER`, owns booking) — single booking detail with items + e-tickets

### Area J — Async Refund Handling
- `[MUST]` `refund.updated` webhook updates `Refund.status` in DB
- `[SHOULD]` On `refund.failed`, send notification alert (log ops-level alert; no email delivery in this flow)
- `[WONT]` Email delivery of refund confirmation (token/status only)

---

Does this look complete? Anything to add, remove, or change before I write Stage 1 in full and move to Stage 2 (Domain Modeling)?

User: next stage

GitHub Copilot: ## Stage 2 Outline — Domain Modeling

Here are the entities and their relationships for FR5:

---

**`Booking`**
- Identity: `id`, `bookingRef` (human-readable, unique, `BK-{YYYYMMDD}-{seq}`)
- Lifecycle: `CONFIRMED → CANCELLATION_PENDING → CANCELLED`
- Relationships: belongs to one `User` (via `userId`); references one `Cart` (via `cartId`); has many `BookingItem`; has one `Payment`; has many `ETicket` (via `BookingItem`)
- Ownership: payments-ticketing

**`BookingItem`**
- Identity: `id`
- Lifecycle: `ACTIVE → CANCELLED`
- Relationships: belongs to one `Booking`; references one `CartItem` (via `cartItemId`); references seat or GA tier (via `seatId` nullable + `pricingTierId`)
- Ownership: payments-ticketing

**`Payment`**
- Identity: `id`, `stripePaymentIntentId` (unique)
- Lifecycle: `PENDING → SUCCESS | FAILED`
- Relationships: belongs to one `Booking` (1:1); references `cartId` for pre-booking lookup
- Ownership: payments-ticketing

**`Refund`**
- Identity: `id`, `stripeRefundId` (unique)
- Lifecycle: `PENDING → SUCCEEDED | FAILED`
- Relationships: belongs to one `Booking`; belongs to one `Payment` (via `paymentId`)
- Ownership: payments-ticketing

**`ETicket`**
- Identity: `id`, `ticketCode` (unique — derived from `bookingRef + bookingItemId`)
- Lifecycle: `ACTIVE → VOIDED`
- Relationships: belongs to one `BookingItem` (1:1)
- Ownership: payments-ticketing

**Non-Persisted Concepts:**
- `PaymentIntentResult` — DTO returned by `StripePaymentService`; carries `clientSecret`, `paymentIntentId`; never stored server-side
- `RefundResult` — DTO returned by `StripeRefundService`; carries `refundId`, `status`

**Cross-Module References (IDs only, no entity imports):**
- `Booking.userId` → `identity.users.id`
- `Booking.cartId` → `booking-inventory.carts.id`
- `BookingItem.seatId` → `booking-inventory.seats.id` (nullable for GA)
- `BookingItem.slotId` → `scheduling.show_slots.id`

---

Does this look complete? Anything to add, remove, or change before I write Stage 2 in full and move to Stage 3 (Architecture & File Structure)?

User: next stage 

GitHub Copilot: Ran terminal command:  find /home/dell/Multi-City-Event-Booking/payments-ticketing/src -type f | sort && echo "---" && find /home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/stripe -type f | sort 2>/dev/null

## Stage 3 Outline — Architecture & File Structure

**Owning module:** payments-ticketing
**Supporting:** `shared/stripe/` (ACL — already exists from pre-FR5 changes)
**Cross-module listeners:** booking-inventory, identity, `engagement` (existing event listeners, no new files)

---

### Already exists (pre-FR5 scaffold — don't recreate)

**payments-ticketing:**
- `domain/Booking.java`, `domain/Payment.java`
- `domain/enums/BookingStatus.java`, `domain/enums/PaymentStatus.java`
- `repository/BookingRepository.java`, `repository/PaymentRepository.java`
- `api/internal/InternalPaymentController.java`

**`shared/stripe/`:**
- `service/StripePaymentService.java`, `service/StripeRefundService.java`
- `webhook/StripeWebhookHandler.java`
- `config/StripeConfig.java`, all DTOs + exceptions

---

### New files to create for FR5

```
payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/

domain/
  BookingItem.java                      ← new entity
  Refund.java                           ← new entity
  ETicket.java                          ← new entity
  enums/BookingItemStatus.java          ← new enum (ACTIVE, CANCELLED)
  enums/RefundStatus.java               ← new enum (PENDING, SUCCEEDED, FAILED)
  enums/ETicketStatus.java              ← new enum (ACTIVE, VOIDED)
  enums/RefundReason.java               ← new enum (REQUESTED_BY_CUSTOMER, DUPLICATE, FRAUDULENT)

repository/
  BookingItemRepository.java            ← new
  RefundRepository.java                 ← new
  ETicketRepository.java                ← new

service/
  PaymentService.java                   ← PaymentIntent creation + confirmation
  BookingService.java                   ← booking record creation + confirmation logic
  ETicketService.java                   ← ticket generation + QR code
  CancellationService.java              ← buyer-initiated cancel + refund
  RefundService.java                    ← refund status updates (webhook-driven)

event/listener/
  CartAssembledListener.java            ← listens CartAssembledEvent → creates PaymentIntent
  BookingConfirmedByPaymentListener.java ← (already scaffold — confirm/extend for FR5 full flow)

api/controller/
  PaymentController.java                ← POST /confirm, POST /failed, GET /checkout/{cartId}
  BookingController.java                ← GET /bookings, GET /bookings/{bookingRef}
  ETicketController.java                ← GET /tickets/{bookingRef}
  StripeWebhookController.java          ← POST /webhooks/stripe

api/dto/request/
  PaymentConfirmRequest.java            ← { paymentIntentId }
  PaymentFailedRequest.java             ← { paymentIntentId }
  CancellationRequest.java              ← { reason }

api/dto/response/
  CheckoutInitResponse.java             ← { clientSecret, paymentIntentId, cartId }
  BookingResponse.java                  ← booking + items summary
  BookingItemResponse.java              ← item-level detail
  ETicketResponse.java                  ← { ticketCode, qrData, pdfUrl, status }
  RefundResponse.java                   ← { stripeRefundId, amount, status }

mapper/
  BookingMapper.java                    ← entity → response DTOs
  ETicketMapper.java                    ← entity → response DTOs

exception/
  BookingNotFoundException.java
  PaymentNotConfirmedException.java
  DuplicatePaymentException.java
  CancellationNotAllowedException.java
  RefundFailedException.java

arch/
  PaymentsTicketingArchTest.java        ← ArchUnit: no direct imports from booking-inventory/scheduling/identity modules
```

---

### Shared events (already exist — no new files needed)
- `CartAssembledEvent` ✅ (updated with amount/email in pre-FR5)
- `BookingConfirmedEvent` ✅ (renamed field in pre-FR5)
- `PaymentFailedEvent` ✅
- `BookingCancelledEvent` ✅ (created in pre-FR5)

---

Does this look complete? Anything to add, remove, or change before I write Stage 3 in full and move to Stage 4 (DB Schema)?

User: next stage 

GitHub Copilot: Ran terminal command:  ls /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/ | sort

Ran terminal command:  for f in V26 V27 V28 V29 V30 V31; do echo "=== $f ==="; cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/${f}__*.sql; echo; done

## Stage 4 Outline — DB Schema

All 6 tables were created by the pre-FR5 migrations (V26–V31). No new migrations needed. Below is the schema inventory with gaps flagged:

---

| Table | Migration | Status | Key Fields |
|---|---|---|---|
| `bookings` | V26 | ✅ Exists | `id`, `booking_ref`, `cart_id`, `user_id`, `event_id`, `slot_id`, `status`, `stripe_payment_intent_id`, `stripe_charge_id`, `total_amount`, `currency` |
| `booking_items` | V27 | ✅ Exists | `id`, `booking_id FK`, `seat_id` nullable, `ga_claim_id` nullable, `ticket_class_id`, `unit_price`, `currency`, `status` |
| `payments` | V28 | ✅ Exists | `id`, `booking_id FK`, `stripe_payment_intent_id` UNIQUE, `stripe_charge_id`, `amount`, `currency`, `status`, `failure_code`, `failure_message` |
| `e_tickets` | V29 | ✅ Exists | `id`, `booking_id FK`, `booking_item_id FK`, `qr_code_data`, `pdf_url`, `status` (default `VALID`) |
| `refunds` | V30 | ✅ Exists | `id`, `booking_id FK`, `stripe_refund_id` UNIQUE, `amount`, `currency`, `reason`, `status` |
| `cancellation_requests` | V31 | ✅ Exists | `id`, `booking_id FK`, `user_id`, `reason`, `status`, `requested_at`, `resolved_at` |

---

### Gaps requiring V32

**Gap 1 — `payments` table has no `cart_id`:**
`CartAssembledEvent` arrives *before* a `Booking` row can exist. To store the pending PaymentIntent we need one of:
- **Option A (recommended):** Add nullable `cart_id` column to `payments`; make `booking_id` nullable; add constraint `CHECK (booking_id IS NOT NULL OR cart_id IS NOT NULL)`. Payment lifecycle: created with only `cart_id` → `booking_id` backfilled after booking is persisted.
- **Option B:** Always create a `PENDING` `Booking` shell at `CartAssembledEvent` time, then promote to `CONFIRMED`. `payments.booking_id` remains NOT NULL throughout.

**Gap 2 — `e_tickets.status` default is `VALID`, domain says `ACTIVE`:**
Align enum — migrate `VALID → ACTIVE` or keep `VALID` and adjust the domain enum to match.

---

**Recommendation:** Option B for Gap 1 (PENDING booking shell — consistent with existing `status DEFAULT 'PENDING'` on the `bookings` table). Align `e_tickets.status` to `ACTIVE` (it's more consistent with all other status enums in the system). This requires one migration:

`V32__fix_e_tickets_status_and_add_booking_items_pricing_tier.sql`

---

Does this look complete? Confirm how to resolve the two gaps (or override the recommendations), and I'll write Stage 4 in full before moving to Stage 5 (API).

User: next stage 

GitHub Copilot: ## Stage 4 — DB Schema (Full)

Using **Option B** (PENDING booking shell at `CartAssembledEvent` time) and aligning `e_tickets.status` to `ACTIVE`. One new migration V32.

---

### All Tables

---

#### `bookings` — V26 (exists, no changes)
```sql
bookings (
  id                       BIGSERIAL PK,
  booking_ref              VARCHAR(50) NOT NULL UNIQUE,       -- BK-{YYYYMMDD}-{seq}
  cart_id                  BIGINT NOT NULL,                   -- FK → carts.id (booking-inventory)
  user_id                  BIGINT NOT NULL,                   -- FK → users.id (identity)
  event_id                 BIGINT NOT NULL,                   -- FK → event_catalog.id
  slot_id                  BIGINT NOT NULL,                   -- FK → show_slots.id
  status                   VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- PENDING | CONFIRMED | CANCELLATION_PENDING | CANCELLED
  stripe_payment_intent_id VARCHAR(255),
  stripe_charge_id         VARCHAR(255),
  total_amount             BIGINT NOT NULL,                   -- smallest currency unit (paise)
  currency                 VARCHAR(10) NOT NULL DEFAULT 'INR',
  created_at               TIMESTAMPTZ DEFAULT NOW(),
  updated_at               TIMESTAMPTZ DEFAULT NOW()
)
Indexes: idx_bookings_user_id, idx_bookings_cart_id, idx_bookings_booking_ref
```

Lifecycle: created as `PENDING` when `CartAssembledEvent` arrives → `CONFIRMED` after Stripe `succeeded` → `CANCELLATION_PENDING` → `CANCELLED`.

---

#### `booking_items` — V27 (exists, no changes)
```sql
booking_items (
  id              BIGSERIAL PK,
  booking_id      BIGINT NOT NULL REFERENCES bookings(id),
  seat_id         BIGINT,                    -- nullable — null for GA items
  ga_claim_id     BIGINT,                    -- nullable — null for reserved seats
  ticket_class_id VARCHAR(255) NOT NULL,     -- eb_ticket_class_id from pricing tier
  unit_price      BIGINT NOT NULL,           -- smallest currency unit
  currency        VARCHAR(10) NOT NULL DEFAULT 'INR',
  status          VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | CANCELLED
  created_at      TIMESTAMPTZ DEFAULT NOW()
)
Indexes: idx_booking_items_booking_id
```

---

#### `payments` — V28 (exists, no changes)
```sql
payments (
  id                       BIGSERIAL PK,
  booking_id               BIGINT NOT NULL REFERENCES bookings(id),
  stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
  stripe_charge_id         VARCHAR(255),              -- latest_charge from Stripe, filled on succeed
  amount                   BIGINT NOT NULL,
  currency                 VARCHAR(10) NOT NULL DEFAULT 'inr',
  status                   VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- PENDING | SUCCESS | FAILED
  failure_code             VARCHAR(100),
  failure_message          TEXT,
  created_at               TIMESTAMPTZ DEFAULT NOW(),
  updated_at               TIMESTAMPTZ DEFAULT NOW()
)
Indexes: idx_payments_booking_id, idx_payments_stripe_payment_intent_id
```

Note: `booking_id` is populated immediately since a PENDING booking shell is always created first.

---

#### `e_tickets` — V29 + V32 patch
```sql
e_tickets (
  id              BIGSERIAL PK,
  booking_id      BIGINT NOT NULL REFERENCES bookings(id),
  booking_item_id BIGINT NOT NULL REFERENCES booking_items(id),
  qr_code_data    TEXT NOT NULL,    -- "{bookingRef}:{bookingItemId}" Base64 encoded
  pdf_url         TEXT,             -- object storage URL, nullable until generated
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | VOIDED  ← V32 fixes 'VALID'
  issued_at       TIMESTAMPTZ DEFAULT NOW()
)
Indexes: idx_e_tickets_booking_id
Constraint: UNIQUE (booking_item_id)   -- one ticket per item  ← V32 adds this
```

---

#### `refunds` — V30 (exists, no changes)
```sql
refunds (
  id               BIGSERIAL PK,
  booking_id       BIGINT NOT NULL REFERENCES bookings(id),
  stripe_refund_id VARCHAR(255) UNIQUE,
  amount           BIGINT NOT NULL,
  currency         VARCHAR(10) NOT NULL DEFAULT 'inr',
  reason           VARCHAR(100),   -- REQUESTED_BY_CUSTOMER | DUPLICATE | FRAUDULENT
  status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- PENDING | SUCCEEDED | FAILED
  created_at       TIMESTAMPTZ DEFAULT NOW(),
  updated_at       TIMESTAMPTZ DEFAULT NOW()
)
Indexes: idx_refunds_booking_id
```

---

#### `cancellation_requests` — V31 (exists, no changes)
```sql
cancellation_requests (
  id           BIGSERIAL PK,
  booking_id   BIGINT NOT NULL REFERENCES bookings(id),
  user_id      BIGINT NOT NULL,
  reason       TEXT,
  status       VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED
  requested_at TIMESTAMPTZ DEFAULT NOW(),
  resolved_at  TIMESTAMPTZ
)
Indexes: idx_cancellation_requests_booking_id
```

---

#### V32 — New migration
```sql
-- V32__fix_e_tickets_status_default_and_unique_item.sql
ALTER TABLE e_tickets
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

UPDATE e_tickets SET status = 'ACTIVE' WHERE status = 'VALID';

ALTER TABLE e_tickets
    ADD CONSTRAINT uq_e_tickets_booking_item_id UNIQUE (booking_item_id);
```

---

---

## Stage 5 Outline — API

### Public endpoints (JWT `ROLE_USER`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/payments/checkout/{cartId}` | Returns `clientSecret` + `paymentIntentId` for Stripe Element initialisation |
| `POST` | `/api/v1/payments/confirm` | Frontend reports `paymentIntentId` after Stripe redirect; backend verifies + confirms booking |
| `POST` | `/api/v1/payments/failed` | Frontend reports payment failure; releases seat locks |
| `GET` | `/api/v1/bookings` | Paginated list of caller's bookings |
| `GET` | `/api/v1/bookings/{bookingRef}` | Single booking detail with items + e-tickets |
| `POST` | `/api/v1/bookings/{bookingRef}/cancel` | Buyer-initiated cancellation; triggers Stripe refund |
| `GET` | `/api/v1/tickets/{bookingRef}` | List e-tickets for a booking (QR code data + PDF URL) |

### Internal endpoint (service-account / no public JWT)
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/internal/payments/by-cart/{cartId}` | Used by booking-inventory `PaymentTimeoutWatchdog` to check if payment confirmed |

### Webhook endpoint (public, Stripe-Signature verified — no JWT)
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/webhooks/stripe` | Stripe webhook receiver — handles `payment_intent.*`, `refund.*` events |

---

Does this look complete? Anything to add, remove, or change before I write Stage 5 in full and move to Stage 6 (Business Logic)?

User: next stage 

GitHub Copilot: ## Stage 5 — API (Full)

---

### `GET /api/v1/payments/checkout/{cartId}`
**Auth:** JWT `ROLE_USER`
**Purpose:** Called immediately after cart confirm. Returns Stripe `clientSecret` for frontend Payment Element initialisation.

**Response `200 OK`:**
```json
{
  "cartId": 42,
  "bookingRef": "BK-20260304-001",
  "paymentIntentId": "pi_3MtwBw...",
  "clientSecret": "pi_3MtwBw..._secret_...",
  "amountInSmallestUnit": 150000,
  "currency": "inr"
}
```
**Errors:** `404 CART_NOT_FOUND` | `409 PAYMENT_ALREADY_EXISTS` (idempotent — returns existing intent) | `400 CART_NOT_CONFIRMED` (cart not in HARD_LOCKED state)

---

### `POST /api/v1/payments/confirm`
**Auth:** JWT `ROLE_USER`
**Purpose:** Frontend calls this after `stripe.confirmPayment()` redirect with the `paymentIntentId`.

**Request:**
```json
{ "paymentIntentId": "pi_3MtwBw..." }
```
**Response `200 OK`:**
```json
{
  "bookingRef": "BK-20260304-001",
  "status": "CONFIRMED",
  "totalAmount": 150000,
  "currency": "inr",
  "items": [
    { "bookingItemId": 1, "seatId": 101, "ticketClassId": "TC-001", "unitPrice": 75000 }
  ]
}
```
**Errors:** `402 PAYMENT_NOT_CONFIRMED` (Stripe status ≠ `succeeded`, includes Stripe status in body) | `404 PAYMENT_INTENT_NOT_FOUND` | `409 BOOKING_ALREADY_CONFIRMED` (idempotent — returns existing `bookingRef`)

---

### `POST /api/v1/payments/failed`
**Auth:** JWT `ROLE_USER`
**Purpose:** Frontend reports payment failure so seat locks are released immediately (before webhook arrives).

**Request:**
```json
{ "paymentIntentId": "pi_3MtwBw..." }
```
**Response `200 OK`:**
```json
{ "message": "Seat locks released. Cart cleared." }
```
**Errors:** `404 PAYMENT_INTENT_NOT_FOUND`

---

### `GET /api/v1/bookings?page=0&size=10`
**Auth:** JWT `ROLE_USER`
**Purpose:** Paginated booking history for the calling user.

**Response `200 OK`:**
```json
{
  "content": [
    {
      "bookingRef": "BK-20260304-001",
      "slotId": 5,
      "status": "CONFIRMED",
      "totalAmount": 150000,
      "currency": "inr",
      "createdAt": "2026-03-04T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1
}
```

---

### `GET /api/v1/bookings/{bookingRef}`
**Auth:** JWT `ROLE_USER`, must own booking
**Purpose:** Full booking detail with line items and e-ticket stubs.

**Response `200 OK`:**
```json
{
  "bookingRef": "BK-20260304-001",
  "status": "CONFIRMED",
  "slotId": 5,
  "totalAmount": 150000,
  "currency": "inr",
  "stripePaymentIntentId": "pi_3MtwBw...",
  "items": [
    {
      "bookingItemId": 1,
      "seatId": 101,
      "ticketClassId": "TC-001",
      "unitPrice": 75000,
      "status": "ACTIVE",
      "eTicket": {
        "ticketCode": "BK-20260304-001:1",
        "qrCodeData": "QlItMjAyNjAzMDQtMDAxOjE=",
        "pdfUrl": "/tickets/BK-20260304-001/1.pdf",
        "status": "ACTIVE"
      }
    }
  ]
}
```
**Errors:** `404 BOOKING_NOT_FOUND` | `403 FORBIDDEN` (not owner)

---

### `POST /api/v1/bookings/{bookingRef}/cancel`
**Auth:** JWT `ROLE_USER`, must own booking
**Purpose:** Buyer-initiated cancellation — triggers Stripe refund.

**Request:**
```json
{ "reason": "REQUESTED_BY_CUSTOMER" }
```
**Response `200 OK`:**
```json
{
  "bookingRef": "BK-20260304-001",
  "status": "CANCELLATION_PENDING",
  "refund": {
    "stripeRefundId": "re_1Nispe...",
    "amount": 150000,
    "currency": "inr",
    "status": "PENDING"
  }
}
```
**Errors:** `404 BOOKING_NOT_FOUND` | `403 FORBIDDEN` | `409 CANCELLATION_NOT_ALLOWED` (already cancelled, outside window, or wrong status)

---

### `GET /api/v1/tickets/{bookingRef}`
**Auth:** JWT `ROLE_USER`, must own booking
**Purpose:** Returns all e-tickets for a booking for download/display.

**Response `200 OK`:**
```json
{
  "bookingRef": "BK-20260304-001",
  "tickets": [
    {
      "ticketCode": "BK-20260304-001:1",
      "bookingItemId": 1,
      "qrCodeData": "QlItMjAyNjAzMDQtMDAxOjE=",
      "pdfUrl": "/tickets/BK-20260304-001/1.pdf",
      "status": "ACTIVE"
    }
  ]
}
```
**Errors:** `404 BOOKING_NOT_FOUND` | `403 FORBIDDEN`

---

### `GET /api/v1/internal/payments/by-cart/{cartId}`
**Auth:** No public JWT — internal service call (permit via IP/path restriction in `SecurityConfig`)
**Purpose:** Answers booking-inventory's `PaymentTimeoutWatchdog` — is payment confirmed?

**Response `200 OK`:**
```json
{ "cartId": 42, "confirmed": true }
```

---

### `POST /api/v1/webhooks/stripe`
**Auth:** None (public) — verified via `Stripe-Signature` header inside handler
**Purpose:** Safety net for async Stripe events.

**Handled events:**

| Stripe Event | Action |
|---|---|
| `payment_intent.succeeded` | Idempotent booking confirm (skip if already `CONFIRMED`) |
| `payment_intent.payment_failed` | Publish `PaymentFailedEvent`; set `payments.status=FAILED` |
| `payment_intent.canceled` | Same as `payment_failed` |
| `refund.updated` | Update `refunds.status` |
| `refund.failed` | Set `refunds.status=FAILED`, log ops alert |

**Response:** Always `200 OK` for recognised events; `400` on signature failure; `422` on unrecognised event type (Stripe will not retry).

---

## Stage 6 Outline — Business Logic

Here are the rules, state transitions, and side effects to document:

---

### Payment Lifecycle
1. `CartAssembledEvent` received → create PENDING `Booking` shell → call `StripePaymentService.createPaymentIntent()` → store `Payment(PENDING)`
2. Frontend confirms → `POST /confirm` → `StripePaymentService.retrievePaymentIntent()` → verify `succeeded` → transition `Booking(PENDING → CONFIRMED)`, `Payment(PENDING → SUCCESS)`, backfill `stripe_charge_id`
3. Idempotency checks at every write — duplicate `paymentIntentId` always short-circuits and returns existing state

### Booking Confirmation Rules
4. `BookingItem` rows created from cart snapshot — `seat_id` populated for reserved items, `ga_claim_id` for GA items
5. `bookingRef` generation: `BK-{YYYYMMDD}-{atomicSeq}` — sequence per day, zero-padded to 3 digits
6. All booking creation (Booking + BookingItems + Payment update + ETicket generation) within single `@Transactional`
7. `BookingConfirmedEvent` published **after** transaction commits (`@TransactionalEventListener(AFTER_COMMIT)`)

### E-Ticket Generation Rules
8. One `ETicket` per `BookingItem` — enforced by `UNIQUE (booking_item_id)` constraint
9. QR code data = Base64(`{bookingRef}:{bookingItemId}`) — no external barcode service
10. PDF URL = placeholder path `/tickets/{bookingRef}/{bookingItemId}.pdf` (actual PDF generation deferred)

### Cancellation Policy Rules
11. Cancellation only allowed if `booking.status = CONFIRMED` (not PENDING, not already CANCELLED)
12. Cancellation window: configurable — default 24 hours before event start (read from `show_slot.start_time` via `slotId`)
13. Full refund if within window; partial refund (deduct `cancellationFeePercent`) if configurable fee applies — default no fee
14. `CancellationRequest` record created first; `Booking → CANCELLATION_PENDING` before Stripe call

### Refund State Machine
15. `Refund.status` transitions: `PENDING → SUCCEEDED` (on `refund.updated` webhook) or `PENDING → FAILED`
16. On `SUCCEEDED`: `Booking → CANCELLED`, all `ETicket → VOIDED`, all `BookingItem → CANCELLED`, publish `BookingCancelledEvent`
17. On `FAILED`: `Booking` stays `CANCELLATION_PENDING`, ops alert logged — cancellation NOT finalised without successful refund

### Payment Failure Rules
18. On failure: publish `PaymentFailedEvent(cartId, userId)` → booking-inventory releases `HARD_LOCKED → AVAILABLE`
19. `Booking → CANCELLED`, `Payment → FAILED` — booking shell is discarded
20. Webhook and frontend-reported failure paths are both idempotent — whichever arrives first wins; second is a no-op

### Watchdog Cross-Module Rule
21. `PaymentTimeoutWatchdog` in booking-inventory calls `GET /internal/payments/by-cart/{cartId}` — module boundary enforced; no direct repo import

---

Does this look complete? Anything to add, remove, or change before I write Stage 6 in full and move to Stage 7 (Error Handling)?

User: next stage 

GitHub Copilot: ## Stage 6 — Business Logic (Full)

---

### 6.1 — PaymentIntent Creation (`CartAssembledListener` → `PaymentService`)

**Trigger:** `CartAssembledEvent(cartId, slotId, userId, orgId, ebEventId, couponCode, totalAmountInSmallestUnit, currency, userEmail)`

**Steps (all within `@Transactional`):**
1. Idempotency check: if `Payment` already exists with a `PENDING` or `SUCCESS` record referencing this `cartId` via its booking → short-circuit and return existing `paymentIntentId`. Do not create a duplicate `PaymentIntent`.
2. Generate `bookingRef` = `"BK-" + LocalDate.now(UTC) formatted as yyyyMMdd + "-" + leftPad(dailyAtomicSeq, 3, '0')`. Daily sequence is an atomic counter (in-memory `AtomicLong`, reset at midnight via `@Scheduled`).
3. Create `Booking` entity: `status=PENDING`, `cartId`, `userId`, `slotId`, `eventId` (from event resolved via slotId), `totalAmount`, `currency`, `bookingRef`. Persist immediately — this is the anchor for the `Payment` FK.
4. Call `StripePaymentService.createPaymentIntent(amount, currency, userEmail, metadata)` where `metadata = { booking_ref: bookingRef, user_id: userId }`.
5. `clientSecret` from response is returned to the caller — **never stored in DB**.
6. Persist `Payment`: `bookingId`, `stripePaymentIntentId`, `amount`, `currency`, `status=PENDING`.
7. Return `CheckoutInitResponse(cartId, bookingRef, paymentIntentId, clientSecret, amount, currency)`.

---

### 6.2 — Payment Confirmation (`PaymentService.confirmPayment()`)

**Trigger:** `POST /api/v1/payments/confirm { paymentIntentId }`

**Steps:**
1. Look up `Payment` by `stripePaymentIntentId` — throw `PaymentIntentNotFoundException` if absent.
2. Idempotency: if `payment.status = SUCCESS` (and `booking.status = CONFIRMED`) → return existing `bookingRef` immediately.
3. Call `StripePaymentService.retrievePaymentIntent(paymentIntentId)`.
4. If Stripe returns status ≠ `succeeded` → throw `PaymentNotConfirmedException(stripeStatus)`.
5. Within `@Transactional`:
   - Set `payment.stripeChargeId = response.latestCharge`.
   - Set `payment.status = SUCCESS`.
   - Set `booking.stripeChargeId = response.latestCharge`.
   - Set `booking.status = CONFIRMED`.
   - Create `BookingItem` for each `CartItem` (fetched via `GET /api/v1/booking/carts/{cartId}/items` REST call to booking-inventory — no direct repo import):
     - `bookingId`, `seatId` (nullable), `gaClaimId` (nullable), `ticketClassId`, `unitPrice`, `currency`, `status=ACTIVE`.
   - Generate one `ETicket` per `BookingItem`:
     - `qrCodeData = Base64.encodeToString("{bookingRef}:{bookingItemId}")`.
     - `pdfUrl = "/tickets/{bookingRef}/{bookingItemId}.pdf"` (placeholder).
     - `status = ACTIVE`.
6. Publish `BookingConfirmedEvent(cartId, seatIds, stripePaymentIntentId, userId)` via `@TransactionalEventListener(AFTER_COMMIT)`.

---

### 6.3 — Payment Failure (`PaymentService.handleFailure()`)

**Triggers:** `POST /api/v1/payments/failed` (frontend) OR Stripe webhook `payment_intent.payment_failed` / `payment_intent.canceled`.

**Steps (idempotent — whichever trigger arrives first wins):**
1. Look up `Payment` by `stripePaymentIntentId`.
2. If `payment.status` is already `FAILED` → return immediately (no-op).
3. Within `@Transactional`:
   - Set `payment.status = FAILED`, `payment.failureCode`, `payment.failureMessage` from Stripe response (if available).
   - Set `booking.status = CANCELLED`.
4. Publish `PaymentFailedEvent(cartId, userId)` → booking-inventory listener calls `SeatLockStateMachine.RELEASE` for all `HARD_LOCKED` seats in the cart.

---

### 6.4 — Buyer-Initiated Cancellation (`CancellationService.cancel()`)

**Trigger:** `POST /api/v1/bookings/{bookingRef}/cancel { reason }`

**Steps:**
1. Load `Booking` by `bookingRef`, assert `userId` matches JWT principal → throw `ForbiddenException` if not.
2. Assert `booking.status = CONFIRMED` → throw `CancellationNotAllowedException` otherwise.
3. Resolve `slotStartTime` via `GET /api/v1/scheduling/slots/{slotId}` (REST read) — assert current time is before `slotStartTime - cancellationWindowHours` — throw `CancellationNotAllowedException("Outside cancellation window")` if past window.
4. Create `CancellationRequest`: `bookingId`, `userId`, `reason`, `status=PENDING`.
5. Set `booking.status = CANCELLATION_PENDING`.
6. Compute refund amount:
   - If `cancellationFeePercent > 0`: `refundAmount = totalAmount - floor(totalAmount * feePercent / 100)`.
   - Default: full refund (`refundAmount = booking.totalAmount`).
7. Call `StripeRefundService.createRefund(stripePaymentIntentId, refundAmount, reason)`.
8. On Stripe success response:
   - Persist `Refund`: `bookingId`, `stripeRefundId`, `amount`, `reason`, `status` from Stripe response (`PENDING` or `SUCCEEDED`).
   - Update `CancellationRequest.status = APPROVED`.
   - If Stripe returned `status = succeeded` immediately: call `CancellationService.finalise(bookingId)` inline.
   - If Stripe returned `status = pending`: finalisation deferred to `refund.updated` webhook.
9. Return `CancellationResponse(bookingRef, status=CANCELLATION_PENDING, refund{...})`.

**Finalisation (`finaliseAfterRefund()`) — called inline or from webhook:**
1. Assert not already finalised (`booking.status ≠ CANCELLED`) — idempotent guard.
2. Within `@Transactional`:
   - `booking.status = CANCELLED`.
   - All `BookingItem.status = CANCELLED`.
   - All `ETicket.status = VOIDED`.
   - `Refund.status = SUCCEEDED`.
   - `CancellationRequest.status = APPROVED`, `resolvedAt = NOW()`.
3. Publish `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)` via `@TransactionalEventListener(AFTER_COMMIT)`.

---

### 6.5 — Stripe Webhook Handler (`StripeWebhookController` → `RefundService` / `PaymentService`)

**All webhook handlers are idempotent.**

| Event | Handler Logic |
|---|---|
| `payment_intent.succeeded` | Look up `Payment` by `stripePaymentIntentId`; if `status ≠ SUCCESS` → call `confirmPayment()` (same as Step 6.2 minus Stripe retrieve — status already known); skip if already `CONFIRMED` |
| `payment_intent.payment_failed` | Call `handleFailure()` (6.3); skip if already `FAILED` |
| `payment_intent.canceled` | Same as `payment_failed` |
| `refund.updated` | Load `Refund` by `stripeRefundId`; update `status`; if `status = succeeded` → call `finaliseAfterRefund(bookingId)`; if `status = failed` → call `handleRefundFailed()` |
| `refund.failed` | `Refund.status = FAILED`; `booking` stays `CANCELLATION_PENDING`; log `ERROR` ops alert; do NOT automatically retry |

**Signature verification:** `StripeWebhookHandler.constructEvent(rawBody, stripeSignatureHeader)` — throws `StripeWebhookSignatureException` on failure → `400 Bad Request` returned to Stripe (Stripe will retry).

---

### 6.6 — Internal Watchdog API (`InternalPaymentController`)

`GET /api/v1/internal/payments/by-cart/{cartId}`:
- Load `Booking` by `cartId` where `status = CONFIRMED` — return `{ confirmed: true }`.
- If no such booking exists → `{ confirmed: false }`.
- No authentication (permitted via `SecurityConfig` path rule); must be accessible only from localhost / internal network in production.

---

### 6.7 — Cross-Cutting Rules

- **Module boundary:** payments-ticketing never imports booking-inventory or scheduling `@Service`, `@Repository`, or `@Entity`. Cart items are fetched via REST. Slot data fetched via REST.
- **Event publishing:** All `ApplicationEvent` publications use `@TransactionalEventListener(AFTER_COMMIT)` — events fire only after DB transaction commits successfully.
- **No `clientSecret` in DB:** `StripePaymentService` returns it; `PaymentService` passes it directly to the response DTO and discards it — never assigned to any entity field.
- **Currency:** All amounts stored and computed in **smallest currency unit** (paise for INR). Conversion to display units (divide by 100) happens only in response DTOs.
- **Cancellation fee config:** Read from `application.yaml` `payments.cancellation.fee-percent` (default `0`). No DB table for cancellation policy in FR5 scope.

---

## Stage 7 Outline — Error Handling

Here are the failure scenarios to document:

---

### Payment Path
1. `CartAssembledEvent` arrives but Stripe `createPaymentIntent` call fails (network, API key invalid, Stripe down)
2. `PaymentIntent` already exists for this cart (duplicate event / retry)
3. Frontend calls `/confirm` but Stripe status is not `succeeded` (e.g. `requires_action`, `processing`, `canceled`)
4. Frontend calls `/confirm` but booking is already `CONFIRMED` (double-submit)
5. `retrievePaymentIntent` Stripe call fails (network timeout)

### Cart Snapshot Fetch
6. REST call to booking-inventory for cart items returns `404` or `503` during booking confirmation

### Cancellation Path
7. Cancellation requested for booking not owned by the calling user
8. Cancellation requested but `booking.status ≠ CONFIRMED` (already cancelled, pending, etc.)
9. Cancellation requested but outside the cancellation window
10. `StripeRefundService.createRefund()` call fails (Stripe error, network)
11. Refund created but Stripe returns `status = failed` asynchronously via webhook

### Webhook Path
12. Stripe webhook arrives with invalid signature
13. Stripe webhook arrives for unknown `paymentIntentId` or `refundId` (out-of-order delivery)
14. Webhook event type not in handled list

### E-Ticket Generation
15. `ETicket` insert fails due to `UNIQUE (booking_item_id)` constraint violation (duplicate confirm attempt mid-transaction)

### Booking Ownership
16. User requests booking detail or cancellation for a `bookingRef` that belongs to a different user

---

Does this look complete? Anything to add, remove, or change before I write Stage 7 in full and move to Stage 8 (Tests)?

User: next stage

GitHub Copilot: ## Stage 7 — Error Handling (Full)

---

### 7.1 — Payment Path

---

**E1 — Stripe `createPaymentIntent` fails**
- **When:** `StripePaymentService.createPaymentIntent()` throws `StripeIntegrationException` (network timeout, invalid API key, Stripe 5xx)
- **Action:** Rollback transaction (no `Booking` or `Payment` row persisted). Log `ERROR` with `cartId` and exception.
- **Response to caller:** `CartAssembledEvent` is a Spring Event — no HTTP response. The `Booking` shell is never created. The next `GET /checkout/{cartId}` from the frontend will retry (listener can be re-triggered or frontend can poll).
- **HTTP surface (if checkout endpoint re-called):** `502 BAD_GATEWAY` with error code `STRIPE_UNAVAILABLE`.

---

**E2 — Duplicate `CartAssembledEvent` / PaymentIntent already exists for cart**
- **When:** Idempotency check in `6.1` finds existing `Payment` for this `cartId` with `status = PENDING` or `SUCCESS`
- **Action:** Short-circuit — do not call Stripe again. Return existing `paymentIntentId` from DB.
- **HTTP surface:** `GET /checkout/{cartId}` returns `200 OK` with existing `CheckoutInitResponse`. `clientSecret` is re-fetched from Stripe via `retrievePaymentIntent` and returned (Stripe allows retrieval but never re-issues the same secret — omit `clientSecret` from idempotent response if already `SUCCESS`).
- **Error code if already SUCCESS:** `409 PAYMENT_ALREADY_EXISTS` with `bookingRef` in body.

---

**E3 — Frontend calls `/confirm` but Stripe status ≠ `succeeded`**
- **When:** `retrievePaymentIntent` returns `status` of `requires_action`, `processing`, `requires_confirmation`, or `canceled`
- **Action:** Do not confirm booking. Do not modify DB. Log `WARN` with `paymentIntentId` and Stripe status.
- **HTTP response:** `402 Payment Required`
  ```json
  {
    "errorCode": "PAYMENT_NOT_CONFIRMED",
    "stripeStatus": "requires_action",
    "message": "Payment has not been completed. Please complete payment via Stripe."
  }
  ```

---

**E4 — Frontend calls `/confirm` but booking already `CONFIRMED` (double-submit)**
- **When:** `booking.status = CONFIRMED` and `payment.status = SUCCESS` already exist for this `paymentIntentId`
- **Action:** Idempotent — return existing `bookingRef` immediately. No DB writes.
- **HTTP response:** `200 OK` with existing `BookingResponse` (same as first confirm response).

---

**E5 — `retrievePaymentIntent` Stripe call times out during `/confirm`**
- **When:** Stripe HTTP call throws `StripeIntegrationException` with timeout cause
- **Action:** Do not modify `Booking` or `Payment` status. Log `ERROR`. The Stripe webhook `payment_intent.succeeded` will arrive as safety net and trigger confirmation.
- **HTTP response:** `504 Gateway Timeout`
  ```json
  { "errorCode": "STRIPE_TIMEOUT", "message": "Payment verification timed out. Your booking will be confirmed automatically." }
  ```

---

### 7.2 — Cart Snapshot Fetch

---

**E6 — REST call to booking-inventory for cart items returns `404` or `503`**
- **When:** `GET /api/v1/booking/carts/{cartId}/items` returns non-2xx during booking confirmation
- **Action:** Throw `CartItemsFetchException`. Rollback entire booking confirmation transaction (`Booking` stays `PENDING`, `Payment` stays `PENDING`, no `BookingItem` or `ETicket` created).
- **HTTP response** (propagated from `/confirm`): `503 SERVICE_UNAVAILABLE`
  ```json
  { "errorCode": "CART_ITEMS_UNAVAILABLE", "message": "Unable to retrieve cart items. Please retry." }
  ```
- **Note:** Stripe webhook safety net will retry `/confirm` logic via `payment_intent.succeeded`. Cart items must be available before finalisation succeeds.

---

### 7.3 — Cancellation Path

---

**E7 — Booking not owned by calling user**
- **When:** `booking.userId ≠ JWT principal userId`
- **Action:** Never reveal that the booking exists. Treat as not found.
- **HTTP response:** `404 NOT_FOUND`
  ```json
  { "errorCode": "BOOKING_NOT_FOUND", "bookingRef": "BK-20260304-001" }
  ```

---

**E8 — `booking.status ≠ CONFIRMED`**
- **When:** Booking is `PENDING`, `CANCELLATION_PENDING`, or `CANCELLED`
- **Action:** Reject immediately. No Stripe call made.
- **HTTP response:** `409 Conflict`
  ```json
  {
    "errorCode": "CANCELLATION_NOT_ALLOWED",
    "reason": "Booking is not in CONFIRMED state",
    "currentStatus": "CANCELLATION_PENDING"
  }
  ```

---

**E9 — Cancellation requested outside the window**
- **When:** `NOW() > slotStartTime - cancellationWindowHours`
- **Action:** Reject. No `CancellationRequest` created. No Stripe call.
- **HTTP response:** `409 Conflict`
  ```json
  {
    "errorCode": "CANCELLATION_NOT_ALLOWED",
    "reason": "Cancellation window has closed",
    "windowClosedAt": "2026-03-04T08:00:00Z"
  }
  ```

---

**E10 — `StripeRefundService.createRefund()` fails**
- **When:** Stripe returns error (charge already fully refunded, invalid charge ID, Stripe 5xx)
- **Action:** Do NOT finalise cancellation. Set `CancellationRequest.status = REJECTED`. Revert `booking.status = CONFIRMED`. Log `ERROR` with full Stripe error body. Propagate `RefundFailedException`.
- **HTTP response:** `502 BAD_GATEWAY`
  ```json
  {
    "errorCode": "REFUND_FAILED",
    "message": "Unable to process refund at this time. Please contact support.",
    "stripeError": "charge_already_refunded"
  }
  ```

---

**E11 — Refund async `status = failed` arrives via `refund.failed` webhook**
- **When:** Stripe fires `refund.failed` after initial `PENDING` status
- **Action:**
  - Set `Refund.status = FAILED`.
  - `Booking` stays `CANCELLATION_PENDING` — seats remain `CONFIRMED`.
  - `CancellationRequest.status = REJECTED`, `resolvedAt = NOW()`.
  - Log `ERROR` ops alert: `"REFUND_FAILED: bookingRef={}, stripeRefundId={}"`.
  - Do NOT publish `BookingCancelledEvent` — seats must not be released until refund succeeds.
- **No HTTP response** (webhook handler always returns `200 OK` to Stripe).

---

### 7.4 — Webhook Path

---

**E12 — Stripe webhook invalid signature**
- **When:** `StripeWebhookHandler.constructEvent()` throws `StripeWebhookSignatureException`
- **Action:** Log `WARN` with truncated payload. Do not process event.
- **HTTP response:** `400 Bad Request`
  ```json
  { "errorCode": "INVALID_WEBHOOK_SIGNATURE" }
  ```
- **Note:** Stripe will NOT retry on `4xx` — signature failures indicate a configuration problem or a non-Stripe sender.

---

**E13 — Webhook arrives for unknown `paymentIntentId` or `refundId`**
- **When:** Out-of-order delivery or replay of events for objects not in DB
- **Action:** Log `WARN` with event ID and object ID. Return `200 OK` (do not signal error to Stripe — no retry needed for unknown objects).
- **Do not throw:** Returning `4xx` here would cause Stripe to retry indefinitely.

---

**E14 — Unrecognised webhook event type**
- **When:** Stripe sends an event type not in the handled list (e.g. `customer.created`)
- **Action:** Log `DEBUG`. Return `200 OK` silently — Stripe considers the webhook delivered.

---

### 7.5 — E-Ticket Generation

---

**E15 — `ETicket` insert fails due to `UNIQUE (booking_item_id)` constraint violation**
- **When:** Concurrent duplicate confirm requests both pass idempotency check before either writes
- **Action:** Catch `DataIntegrityViolationException` from JPA. Treat as idempotent success — reload and return the existing `ETicket`. Do not surface a `500` to the user.
- **Implementation:** wrap `eTicketRepository.save()` in try/catch `DataIntegrityViolationException` → fallback to `eTicketRepository.findByBookingItemId(id)`.

---

### 7.6 — Booking Ownership

---

**E16 — User accesses `GET /bookings/{bookingRef}` or `GET /tickets/{bookingRef}` for another user's booking**
- **When:** `booking.userId ≠ JWT principal userId`
- **Action:** Same as E7 — never reveal existence.
- **HTTP response:** `404 NOT_FOUND`
  ```json
  { "errorCode": "BOOKING_NOT_FOUND", "bookingRef": "BK-20260304-001" }
  ```

---

### 7.7 — Exception → HTTP Status Mapping (GlobalExceptionHandler in shared)

| Exception | HTTP Status | Error Code |
|---|---|---|
| `BookingNotFoundException` | `404` | `BOOKING_NOT_FOUND` |
| `PaymentNotConfirmedException` | `402` | `PAYMENT_NOT_CONFIRMED` |
| `DuplicatePaymentException` | `409` | `PAYMENT_ALREADY_EXISTS` |
| `CancellationNotAllowedException` | `409` | `CANCELLATION_NOT_ALLOWED` |
| `RefundFailedException` | `502` | `REFUND_FAILED` |
| `CartItemsFetchException` | `503` | `CART_ITEMS_UNAVAILABLE` |
| `StripeIntegrationException` | `502` | `STRIPE_UNAVAILABLE` |
| `StripeWebhookSignatureException` | `400` | `INVALID_WEBHOOK_SIGNATURE` |
| `ForbiddenException` (booking not owned) | `404` | `BOOKING_NOT_FOUND` |

> All handlers are registered in the single `GlobalExceptionHandler` in `shared/common/exception/` — no `@ControllerAdvice` in payments-ticketing (Hard Rule #5).

---

## Stage 8 Outline — Tests

Test cases grouped by layer and area:

---

### `domain/` — Unit Tests
1. `BookingTest` — status transitions: PENDING→CONFIRMED, CONFIRMED→CANCELLATION_PENDING, CANCELLATION_PENDING→CANCELLED; invalid transitions throw
2. `RefundTest` — status transitions: PENDING→SUCCEEDED, PENDING→FAILED
3. `ETicketTest` — QR code data format assertion; void transition

### `service/` — Mockito Unit Tests

**PaymentService**
4. `createCheckout_success` — CartAssembledEvent → Booking shell + Payment persisted, clientSecret returned
5. `createCheckout_idempotent` — duplicate cartId returns existing paymentIntentId, no Stripe call
6. `confirmPayment_success` — retrievePaymentIntent returns succeeded → Booking CONFIRMED, BookingItems + ETickets created
7. `confirmPayment_alreadyConfirmed` — idempotent return, no DB writes
8. `confirmPayment_stripeNotSucceeded` — status=requires_action → PaymentNotConfirmedException
9. `confirmPayment_stripeTimeout` — StripeIntegrationException → 504, booking stays PENDING
10. `handleFailure_success` — PaymentFailedEvent published, Booking CANCELLED
11. `handleFailure_idempotent` — already FAILED → no-op

**CancellationService**
12. `cancel_fullRefund_success` — within window, Stripe returns succeeded → BookingCancelledEvent published
13. `cancel_partialRefund_withFee` — fee deducted from refund amount, correct amount sent to Stripe
14. `cancel_outsideWindow` → CancellationNotAllowedException
15. `cancel_wrongStatus` → CancellationNotAllowedException
16. `cancel_stripeFails` → RefundFailedException, booking reverts to CONFIRMED
17. `cancel_asyncRefund_pendingThenSucceeded` — refund initially PENDING, webhook finalises

**RefundService**
18. `updateStatus_succeededWebhook` → finaliseAfterRefund called, BookingCancelledEvent published
19. `updateStatus_failedWebhook` → Refund FAILED, Booking stays CANCELLATION_PENDING, no event published

**ETicketService**
20. `generate_createsOnePerItem` — correct QR data format
21. `generate_duplicateConstraint` — DataIntegrityViolationException caught, existing ticket returned

### `api/` — `@WebMvcTest`
22. `PaymentControllerTest` — GET /checkout/{cartId} returns 200 with clientSecret; 404 on unknown cart
23. `PaymentControllerTest` — POST /confirm 200 on success; 402 on not-succeeded; 409 on duplicate
24. `PaymentControllerTest` — POST /failed 200 triggers event
25. `BookingControllerTest` — GET /bookings returns paginated list for owner
26. `BookingControllerTest` — GET /bookings/{ref} 200 for owner; 404 for non-owner
27. `BookingControllerTest` — POST /cancel 200 within window; 409 outside window; 409 wrong status
28. `ETicketControllerTest` — GET /tickets/{ref} 200 for owner; 404 for non-owner
29. `StripeWebhookControllerTest` — 400 on bad signature; 200 on payment_intent.succeeded; 200 on refund.updated; 200 on unknown event type
30. `InternalPaymentControllerTest` — GET /internal/payments/by-cart/{cartId} confirmed=true/false

### `arch/` — ArchUnit
31. `PaymentsTicketingArchTest` — no class in `paymentsticketing` imports from `bookinginventory`, scheduling, or identity packages directly
32. `PaymentsTicketingArchTest` — no `@ControllerAdvice` in `paymentsticketing` package
33. `PaymentsTicketingArchTest` — no Stripe SDK calls outside `shared/stripe/` package

---

Does this look complete? Anything to add, remove, or change before I write Stage 8 in full and produce the final `SPEC_5.md` file?

User: Write all the stages into a\the md file

GitHub Copilot: Ran terminal command:  ls /home/dell/Multi-City-Event-Booking/POQ/Payments/

Created 1 todo

Created [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_5.md)

Completed: *Write SPEC_5.md all stages* (1/1)

SPEC_5.md written — all 8 stages complete.

**What's in the file:**
- **Stage 1** — 10 requirement areas (A–J), 30+ tagged `[MUST]`/`[SHOULD]`/`[WONT]` requirements
- **Stage 2** — Full domain model: `Booking`, `BookingItem`, `Payment`, `Refund`, `ETicket`, `CancellationRequest`, cross-module ID references
- **Stage 3** — Complete file structure: what already exists (pre-FR5 scaffold) vs what to create (28 new files)
- **Stage 4** — All 6 table schemas with V32 migration for `e_tickets` status fix + unique constraint
- **Stage 5** — All 9 endpoints with full request/response shapes and error codes
- **Stage 6** — 7 business logic sections with step-by-step rules, state transitions, and idempotency guards
- **Stage 7** — 16 error scenarios mapped to HTTP codes + `GlobalExceptionHandler` registration table
- **Stage 8** — 33 test cases across `domain/`, `service/`, `api/`, `arch/` layers
- **Open Questions** — 5 unresolved design decisions flagged for review

Made changes.