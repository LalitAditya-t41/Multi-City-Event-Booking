# SPEC_5.md — FR5: Stripe Payment → Booking Confirmation → E-Ticket Generation

**Owner:** payments-ticketing
**Module(s):** `payments-ticketing` (primary), `shared/stripe/` (ACL facades), `booking-inventory` (event consumer — seat release), `identity` (event consumer — order history), `engagement` (event consumer — review unlock)
**Flow:** Flow 5 — CartAssembledEvent → Stripe PaymentIntent → Payment Confirm → Booking → E-Ticket → Cancellation & Refund → Webhook Safety Net
**Last Updated:** March 4, 2026
**Status:** Stages 1–8 Complete — Ready for implementation

**Depends on:**
- `SPEC_4.md` (FR4) — `CartAssembledEvent` published with `totalAmountInSmallestUnit`, `currency`, `userEmail`; `HARD_LOCKED` seats must exist before payment begins
- `SPEC_3.MD` (FR3) — JWT with `sub=userId`, `role`, `orgId` claims required for all protected endpoints
- `PRE5 changes.md` — All 16 pre-FR5 issues resolved; `payments-ticketing` module scaffolded; `shared/stripe/` ACL layer created; `BookingCancelledEvent`, `PaymentFailedEvent`, `BookingConfirmedEvent` all in `shared/`

**Key Architecture Decision:** Eventbrite is removed from the payment and order flow entirely. Stripe (sandbox) owns checkout, payment processing, and refunds. All orders are created internally. Eventbrite is retained only as a passive sync layer for event listing and organizer-initiated cancellation webhooks.

---

## Stage 1 — Requirements

### Area A — PaymentIntent Creation

- `[MUST]` On `CartAssembledEvent`, create a Stripe `PaymentIntent` via `StripePaymentService` with `amount`, `currency`, `receipt_email`, `metadata[booking_ref]`, `metadata[user_id]`
- `[MUST]` Store `stripe_payment_intent_id` + `status=PENDING` in `payments` table
- `[MUST]` Return `client_secret` to frontend via `GET /api/v1/payments/checkout/{cartId}`
- `[MUST]` Never log or store `client_secret` server-side — return it once and discard
- `[MUST]` If `CartAssembledEvent` arrives for a cart that already has a `PENDING` or `SUCCESS` payment, return idempotent response (no duplicate `PaymentIntent`)

### Area B — Payment Confirmation (Frontend → Backend)

- `[MUST]` Expose `POST /api/v1/payments/confirm` accepting `{ paymentIntentId }` (JWT `ROLE_USER`)
- `[MUST]` Call `StripePaymentService.retrievePaymentIntent(id)` — verify `status = succeeded`
- `[MUST]` Save `stripe_charge_id` (`latest_charge`) from the response to `payments` table
- `[MUST]` Idempotency: if booking already `CONFIRMED` for this `paymentIntentId`, return existing `bookingRef` — no duplicate records
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
- `[MUST]` Publish `PaymentFailedEvent(cartId, userId)` → `booking-inventory` listener releases `HARD_LOCKED` seats
- `[MUST]` Update `payments.status = FAILED`
- `[MUST]` `payment_intent.payment_failed` and `payment_intent.canceled` Stripe webhooks must also trigger the same failure path (idempotent)

### Area F — Cancellation & Refund (Buyer-Initiated)

- `[MUST]` Expose `POST /api/v1/bookings/{bookingRef}/cancel` (JWT `ROLE_USER`, owns booking only)
- `[MUST]` Validate cancellation policy: `booking.status = CONFIRMED`, not already cancelled, within cancellation window
- `[MUST]` Call `StripeRefundService.createRefund(paymentIntentId, amount, reason)` — full or partial based on policy
- `[MUST]` Set `Booking.status = CANCELLATION_PENDING` before Stripe call; `CANCELLED` after refund succeeds
- `[MUST]` Persist `Refund` record with `stripeRefundId`, `amount`, `reason`, `status`
- `[MUST]` Void all `ETicket` records for the booking (`status = VOIDED`)
- `[MUST]` Set all `BookingItem.status = CANCELLED`
- `[MUST]` Publish `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)` → `booking-inventory` (seat release) + `engagement`
- `[SHOULD]` Support partial refund when a cancellation fee is configured (deduct fee, refund remainder)
- `[WONT]` Organizer-initiated bulk cancel — lives in admin/scheduling, not this flow

### Area G — Stripe Webhook Safety Net

- `[MUST]` Expose `POST /api/v1/webhooks/stripe` (public, no JWT, Stripe-Signature verified)
- `[MUST]` Verify signature via `StripeWebhookHandler.constructEvent(payload, sigHeader)` — reject `400` on invalid signature
- `[MUST]` Handle `payment_intent.succeeded` → idempotent booking confirmation (skip if already `CONFIRMED`)
- `[MUST]` Handle `payment_intent.payment_failed` + `payment_intent.canceled` → `PaymentFailedEvent`
- `[MUST]` Handle `refund.updated` → update `refunds.status`
- `[MUST]` Handle `refund.failed` → mark `refunds.status = FAILED`, log ops alert
- `[SHOULD]` Return `200 OK` for all handled events (Stripe retries on non-200)
- `[WONT]` EB `order.placed`, `order.refunded`, `order.updated` webhook handling — Stripe is the order authority

### Area H — Internal Payment Status API (Cross-Module)

- `[MUST]` Provide `PaymentConfirmationReader` in `shared/common/service/` for `booking-inventory`'s `PaymentTimeoutWatchdog`
- `[MUST]` `PaymentConfirmationReader.isPaymentConfirmed(cartId)` returns `true/false` based on whether a `CONFIRMED` booking exists for the cart

### Area I — Booking History

- `[MUST]` Expose `GET /api/v1/bookings` (JWT `ROLE_USER`) — paginated list of caller's bookings
- `[MUST]` Expose `GET /api/v1/bookings/{bookingRef}` (JWT `ROLE_USER`, owns booking) — single booking detail with items + e-tickets

### Area J — Async Refund Handling

- `[MUST]` `refund.updated` webhook updates `Refund.status` in DB
- `[SHOULD]` On `refund.failed`, send notification alert (log ops-level alert; no email delivery in this flow)
- `[WONT]` Email delivery of refund confirmation (token/status only)

---

## Stage 2 — Domain Model

A `Booking` is created as a `PENDING` shell when `CartAssembledEvent` arrives, promoted to `CONFIRMED` after Stripe payment succeeds, and optionally transitioned to `CANCELLATION_PENDING → CANCELLED` via buyer-initiated cancellation. A `Booking` owns many `BookingItem` records (one per cart item — reserved seat or GA claim), one `Payment` record, and many `ETicket` records (one per `BookingItem`). A `Refund` belongs to a `Booking` and is created if the buyer cancels. A `CancellationRequest` records the buyer's intent and tracks approval/rejection state independently of the refund outcome.

### Entities & Relationships

**`Booking`**
- Identity: `id`, `bookingRef` (human-readable unique — `BK-{YYYYMMDD}-{seq}`)
- Lifecycle: `PENDING → CONFIRMED → CANCELLATION_PENDING → CANCELLED`
- Relationships: belongs to one `User` (via `userId` — cross-module ID only); references one `Cart` (via `cartId` — cross-module ID only); has many `BookingItem`; has one `Payment`
- Ownership: `payments-ticketing`

**`BookingItem`**
- Identity: `id`
- Lifecycle: `ACTIVE → CANCELLED`
- Relationships: belongs to one `Booking`; references one seat (via `seatId` nullable — cross-module ID) or one GA claim (via `gaClaimId` nullable — cross-module ID); references pricing tier via `ticketClassId`
- Ownership: `payments-ticketing`

**`Payment`**
- Identity: `id`, `stripePaymentIntentId` (unique)
- Lifecycle: `PENDING → SUCCESS | FAILED`
- Relationships: belongs to one `Booking` (1:1)
- Ownership: `payments-ticketing`

**`Refund`**
- Identity: `id`, `stripeRefundId` (unique)
- Lifecycle: `PENDING → SUCCEEDED | FAILED`
- Relationships: belongs to one `Booking`
- Ownership: `payments-ticketing`

**`ETicket`**
- Identity: `id`, `ticketCode` = `"{bookingRef}:{bookingItemId}"` (unique per item)
- Lifecycle: `ACTIVE → VOIDED`
- Relationships: belongs to one `BookingItem` (1:1, enforced by UNIQUE constraint)
- Ownership: `payments-ticketing`

**`CancellationRequest`**
- Identity: `id`
- Lifecycle: `PENDING → APPROVED | REJECTED`
- Relationships: belongs to one `Booking`
- Ownership: `payments-ticketing`

### Non-Persisted Concepts

- `PaymentIntentResult` — DTO from `StripePaymentService`; carries `clientSecret` and `paymentIntentId`; never stored server-side
- `RefundResult` — DTO from `StripeRefundService`; carries `stripeRefundId` and `status`

### Cross-Module References (IDs only — no entity imports)

| Field | Points To | Module |
|---|---|---|
| `Booking.userId` | `users.id` | identity |
| `Booking.cartId` | `carts.id` | booking-inventory |
| `Booking.slotId` | `show_slots.id` | scheduling |
| `Booking.eventId` | `event_catalog.id` | discovery-catalog |
| `BookingItem.seatId` | `seats.id` (nullable) | booking-inventory |
| `BookingItem.gaClaimId` | `ga_inventory_claims.id` (nullable) | booking-inventory |

---

## Stage 3 — Architecture & File Structure

### Architecture Pattern
**Modular Monolith (Fixed)** — module registry in `PRODUCT.md`.

### Module Ownership
**Primary module:** `payments-ticketing`
**Supporting:** `shared/stripe/` (ACL — already created in pre-FR5)
**Event consumers (no new files needed):** `booking-inventory` (`PaymentFailedListener`, `BookingConfirmedListener`), `engagement`, `identity`

### Already Exists (pre-FR5 scaffold — do not recreate)

**`payments-ticketing`:**
- `domain/Booking.java`, `domain/Payment.java`
- `domain/enums/BookingStatus.java`, `domain/enums/PaymentStatus.java`
- `repository/BookingRepository.java`, `repository/PaymentRepository.java`
- `event/listener/CartAssembledListener.java` (stub)
- `event/listener/BookingConfirmedByPaymentListener.java` (stub)

**`shared/stripe/` (all exist):**
- `service/StripePaymentService.java`, `service/StripeRefundService.java`
- `webhook/StripeWebhookHandler.java`
- `config/StripeConfig.java`
- `dto/StripePaymentIntentRequest.java`, `dto/StripePaymentIntentResponse.java`
- `dto/StripeRefundRequest.java`, `dto/StripeRefundResponse.java`
- `dto/StripeWebhookEvent.java`
- `exception/StripeIntegrationException.java`, `exception/StripeWebhookSignatureException.java`

### New Files to Create for FR5

```
payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/

domain/
├── BookingItem.java                        ← new entity
├── Refund.java                             ← new entity
├── ETicket.java                            ← new entity
├── CancellationRequest.java                ← new entity
├── enums/BookingItemStatus.java            ← ACTIVE, CANCELLED
├── enums/RefundStatus.java                 ← PENDING, SUCCEEDED, FAILED
├── enums/ETicketStatus.java                ← ACTIVE, VOIDED
├── enums/CancellationRequestStatus.java    ← PENDING, APPROVED, REJECTED
└── enums/RefundReason.java                 ← REQUESTED_BY_CUSTOMER, DUPLICATE, FRAUDULENT

repository/
├── BookingItemRepository.java
├── RefundRepository.java
├── ETicketRepository.java
└── CancellationRequestRepository.java

service/
├── PaymentService.java           ← PaymentIntent creation + confirmation logic
├── BookingService.java           ← booking record lifecycle + history queries
├── ETicketService.java           ← ticket generation + QR code + void
├── CancellationService.java      ← buyer-initiated cancel + refund orchestration
└── RefundService.java            ← refund status updates (webhook-driven)

event/listener/
├── CartAssembledListener.java    ← extend stub: listens CartAssembledEvent → PaymentService.createCheckout()
└── BookingConfirmedByPaymentListener.java  ← extend stub: publishes BookingConfirmedEvent after confirm

api/controller/
├── PaymentController.java        ← GET /checkout/{cartId}, POST /confirm, POST /failed
├── BookingController.java        ← GET /bookings, GET /bookings/{ref}, POST /bookings/{ref}/cancel
├── ETicketController.java        ← GET /tickets/{bookingRef}
└── StripeWebhookController.java  ← POST /webhooks/stripe

api/dto/request/
├── PaymentConfirmRequest.java    ← { paymentIntentId }
├── PaymentFailedRequest.java     ← { paymentIntentId }
└── CancellationRequest.java      ← { reason }

api/dto/response/
├── CheckoutInitResponse.java     ← { cartId, bookingRef, paymentIntentId, clientSecret, amount, currency }
├── BookingResponse.java          ← booking + items + e-ticket stubs
├── BookingItemResponse.java      ← item-level detail
├── BookingSummaryResponse.java   ← light summary for list endpoint
├── ETicketResponse.java          ← { ticketCode, qrData, pdfUrl, status }
└── RefundResponse.java           ← { stripeRefundId, amount, status }

mapper/
├── BookingMapper.java            ← Booking → BookingResponse / BookingSummaryResponse
└── ETicketMapper.java            ← ETicket → ETicketResponse

exception/
├── BookingNotFoundException.java
├── PaymentNotConfirmedException.java
├── DuplicatePaymentException.java
├── CancellationNotAllowedException.java
├── RefundFailedException.java
└── CartItemsFetchException.java

arch/
└── PaymentsTicketingArchTest.java   ← ArchUnit rules

src/test/java/com/eventplatform/paymentsticketing/
├── domain/
│   ├── BookingTest.java
│   ├── RefundTest.java
│   └── ETicketTest.java
├── service/
│   ├── PaymentServiceTest.java
│   ├── CancellationServiceTest.java
│   ├── RefundServiceTest.java
│   ├── ETicketServiceTest.java
│   └── PaymentConfirmationReaderImplTest.java
├── api/
│   ├── PaymentControllerTest.java
│   ├── BookingControllerTest.java
│   ├── ETicketControllerTest.java
│   └── StripeWebhookControllerTest.java
└── arch/
    └── PaymentsTicketingArchTest.java
```

### Shared Events (all exist — no new files)

| Event | Direction | Shared File |
|---|---|---|
| `CartAssembledEvent` | Consumed | ✅ `shared/common/event/published/CartAssembledEvent.java` |
| `BookingConfirmedEvent` | Published | ✅ `shared/common/event/published/BookingConfirmedEvent.java` |
| `PaymentFailedEvent` | Published | ✅ `shared/common/event/published/PaymentFailedEvent.java` |
| `BookingCancelledEvent` | Published | ✅ `shared/common/event/published/BookingCancelledEvent.java` |

---

## Stage 4 — DB Schema

All 6 tables were created by pre-FR5 migrations (V26–V31). One new migration V32 fixes `e_tickets.status` default and adds the `UNIQUE (booking_item_id)` constraint.

---

### `bookings` — V26 (no changes)

```sql
bookings (
  id                       BIGSERIAL PRIMARY KEY,
  booking_ref              VARCHAR(50)  NOT NULL UNIQUE,   -- BK-{YYYYMMDD}-{seq}
  cart_id                  BIGINT       NOT NULL,          -- FK → carts.id (booking-inventory, cross-module ID)
  user_id                  BIGINT       NOT NULL,          -- FK → users.id (identity, cross-module ID)
  event_id                 BIGINT       NOT NULL,          -- FK → event_catalog.id
  slot_id                  BIGINT       NOT NULL,          -- FK → show_slots.id
  status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
                                                           -- PENDING | CONFIRMED | CANCELLATION_PENDING | CANCELLED
  stripe_payment_intent_id VARCHAR(255),
  stripe_charge_id         VARCHAR(255),
  total_amount             BIGINT       NOT NULL,          -- smallest currency unit (paise for INR)
  currency                 VARCHAR(10)  NOT NULL DEFAULT 'INR',
  created_at               TIMESTAMPTZ  DEFAULT NOW(),
  updated_at               TIMESTAMPTZ  DEFAULT NOW()
)
INDEXES: idx_bookings_user_id, idx_bookings_cart_id, idx_bookings_booking_ref
```

**Lifecycle note:** Created as `PENDING` when `CartAssembledEvent` arrives. Promoted to `CONFIRMED` after `retrievePaymentIntent` returns `succeeded`. Transitions to `CANCELLATION_PENDING` when buyer requests cancel. Transitions to `CANCELLED` after refund finalises.

---

### `booking_items` — V27 (no changes)

```sql
booking_items (
  id              BIGSERIAL PRIMARY KEY,
  booking_id      BIGINT       NOT NULL REFERENCES bookings(id),
  seat_id         BIGINT,                         -- nullable: null for GA items
  ga_claim_id     BIGINT,                         -- nullable: null for reserved seats
  ticket_class_id VARCHAR(255) NOT NULL,           -- eb_ticket_class_id from pricing tier
  unit_price      BIGINT       NOT NULL,           -- smallest currency unit
  currency        VARCHAR(10)  NOT NULL DEFAULT 'INR',
  status          VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | CANCELLED
  created_at      TIMESTAMPTZ  DEFAULT NOW()
)
INDEXES: idx_booking_items_booking_id
```

---

### `payments` — V28 (no changes)

```sql
payments (
  id                       BIGSERIAL PRIMARY KEY,
  booking_id               BIGINT       NOT NULL REFERENCES bookings(id),
  stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
  stripe_charge_id         VARCHAR(255),          -- latest_charge from Stripe; filled on succeed
  amount                   BIGINT       NOT NULL,
  currency                 VARCHAR(10)  NOT NULL DEFAULT 'inr',
  status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING', -- PENDING | SUCCESS | FAILED
  failure_code             VARCHAR(100),
  failure_message          TEXT,
  created_at               TIMESTAMPTZ  DEFAULT NOW(),
  updated_at               TIMESTAMPTZ  DEFAULT NOW()
)
INDEXES: idx_payments_booking_id, idx_payments_stripe_payment_intent_id
```

**Note:** `booking_id` is populated immediately because a `PENDING` `Booking` shell is always created before calling Stripe (Option B from Stage 4 outline).

---

### `e_tickets` — V29 + V32 patch

```sql
e_tickets (
  id              BIGSERIAL PRIMARY KEY,
  booking_id      BIGINT NOT NULL REFERENCES bookings(id),
  booking_item_id BIGINT NOT NULL REFERENCES booking_items(id),
  qr_code_data    TEXT   NOT NULL,    -- Base64("{bookingRef}:{bookingItemId}")
  pdf_url         TEXT,               -- object storage URL; nullable until generated
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | VOIDED  (V32 fixes 'VALID')
  issued_at       TIMESTAMPTZ DEFAULT NOW()
)
INDEXES: idx_e_tickets_booking_id
CONSTRAINT: UNIQUE (booking_item_id)   -- one ticket per item; added by V32
```

---

### `refunds` — V30 (no changes)

```sql
refunds (
  id               BIGSERIAL PRIMARY KEY,
  booking_id       BIGINT       NOT NULL REFERENCES bookings(id),
  stripe_refund_id VARCHAR(255) UNIQUE,
  amount           BIGINT       NOT NULL,          -- smallest currency unit
  currency         VARCHAR(10)  NOT NULL DEFAULT 'inr',
  reason           VARCHAR(100),  -- REQUESTED_BY_CUSTOMER | DUPLICATE | FRAUDULENT
  status           VARCHAR(30)  NOT NULL DEFAULT 'PENDING', -- PENDING | SUCCEEDED | FAILED
  created_at       TIMESTAMPTZ  DEFAULT NOW(),
  updated_at       TIMESTAMPTZ  DEFAULT NOW()
)
INDEXES: idx_refunds_booking_id
```

---

### `cancellation_requests` — V31 (no changes)

```sql
cancellation_requests (
  id           BIGSERIAL PRIMARY KEY,
  booking_id   BIGINT NOT NULL REFERENCES bookings(id),
  user_id      BIGINT NOT NULL,
  reason       TEXT,
  status       VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING | APPROVED | REJECTED
  requested_at TIMESTAMPTZ DEFAULT NOW(),
  resolved_at  TIMESTAMPTZ
)
INDEXES: idx_cancellation_requests_booking_id
```

---

### V32 — New Migration

```sql
-- V32__fix_e_tickets_status_default_and_unique_item.sql

-- Fix status default from VALID to ACTIVE to match domain enum
ALTER TABLE e_tickets
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

UPDATE e_tickets SET status = 'ACTIVE' WHERE status = 'VALID';

-- Enforce one ticket per booking item
ALTER TABLE e_tickets
    ADD CONSTRAINT uq_e_tickets_booking_item_id UNIQUE (booking_item_id);
```

---

## Stage 5 — API

### `GET /api/v1/payments/checkout/{cartId}`

**Auth:** JWT `ROLE_USER`
**Purpose:** Called immediately after cart confirm. Returns Stripe `clientSecret` for frontend Stripe Payment Element initialisation.

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

**Errors:**
- `404 CART_NOT_FOUND`
- `409 PAYMENT_ALREADY_EXISTS` (idempotent — returns existing intent if already `PENDING`)
- `400 CART_NOT_CONFIRMED` (cart not in `HARD_LOCKED` state)

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
    {
      "bookingItemId": 1,
      "seatId": 101,
      "ticketClassId": "TC-001",
      "unitPrice": 75000
    }
  ]
}
```

**Errors:**
- `402 PAYMENT_NOT_CONFIRMED` — Stripe status ≠ `succeeded`; includes `stripeStatus` in body
- `404 PAYMENT_INTENT_NOT_FOUND`
- `409 BOOKING_ALREADY_CONFIRMED` (idempotent — returns existing `bookingRef`)

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

**Errors:**
- `404 BOOKING_NOT_FOUND` (also returned when booking belongs to different user — never reveal existence)
- `403 FORBIDDEN` omitted in favour of `404` to prevent enumeration

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

**Errors:**
- `404 BOOKING_NOT_FOUND`
- `409 CANCELLATION_NOT_ALLOWED` — already cancelled, wrong status, or outside window; includes `reason` and `windowClosedAt` where applicable

---

### `GET /api/v1/tickets/{bookingRef}`

**Auth:** JWT `ROLE_USER`, must own booking
**Purpose:** Returns all e-tickets for a booking for display and download.

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

**Errors:** `404 BOOKING_NOT_FOUND`

---

### `POST /api/v1/webhooks/stripe`

**Auth:** None (public) — verified via `Stripe-Signature` header inside handler
**Purpose:** Safety net for async Stripe events; handles cases where frontend callback was missed.

**Handled events:**

| Stripe Event | Your Action |
|---|---|
| `payment_intent.succeeded` | Idempotent booking confirm — skip if already `CONFIRMED` |
| `payment_intent.payment_failed` | Publish `PaymentFailedEvent`; set `payments.status = FAILED` |
| `payment_intent.canceled` | Same as `payment_failed` |
| `refund.updated` | Update `refunds.status`; finalise cancellation if `succeeded` |
| `refund.failed` | Set `refunds.status = FAILED`; log ops alert |

**Response:** `200 OK` for all recognised and unrecognised events; `400` on signature failure only.

---

## Stage 6 — Business Logic

### 6.1 — PaymentIntent Creation (`CartAssembledListener` → `PaymentService.createCheckout()`)

**Trigger:** `CartAssembledEvent(cartId, slotId, userId, orgId, ebEventId, couponCode, totalAmountInSmallestUnit, currency, userEmail)`

**Steps (within `@Transactional`):**
1. **Idempotency check:** query `bookingRepository.findByCartId(cartId)`. If a `Booking` exists and its `Payment.status` is `PENDING` or `SUCCESS` — return existing `paymentIntentId` immediately. No Stripe call.
2. **Generate `bookingRef`:** `"BK-" + LocalDate.now(UTC).format("yyyyMMdd") + "-" + leftPad(dailyAtomicSeq.incrementAndGet(), 3, '0')`. `dailyAtomicSeq` is an in-memory `AtomicLong` reset at midnight via `@Scheduled(cron = "0 0 0 * * *")`.
3. **Create `Booking` shell:** `status=PENDING`, `cartId`, `userId`, `slotId`, `eventId` (resolved via `slotId` from scheduling REST), `totalAmount = totalAmountInSmallestUnit`, `currency`, `bookingRef`. Persist immediately — required as FK anchor for `Payment`.
4. **Call Stripe:** `StripePaymentService.createPaymentIntent(amount, currency, userEmail, Map.of("booking_ref", bookingRef, "user_id", userId.toString()))`.
5. **`clientSecret` handling:** stored in local variable only — passed directly into response DTO, never assigned to any entity field.
6. **Persist `Payment`:** `bookingId`, `stripePaymentIntentId`, `amount`, `currency`, `status=PENDING`.
7. **Return** `CheckoutInitResponse(cartId, bookingRef, paymentIntentId, clientSecret, amount, currency)` to `GET /checkout/{cartId}` caller.

**On `StripeIntegrationException`:** rollback transaction (no `Booking` or `Payment` row). Log `ERROR`. Frontend will retry via `GET /checkout/{cartId}`.

---

### 6.2 — Payment Confirmation (`PaymentService.confirmPayment()`)

**Trigger:** `POST /api/v1/payments/confirm { paymentIntentId }`

**Steps:**
1. Load `Payment` by `stripePaymentIntentId` — throw `PaymentIntentNotFoundException` if absent.
2. **Idempotency:** if `payment.status = SUCCESS` (and `booking.status = CONFIRMED`) — return existing `bookingRef` immediately.
3. Call `StripePaymentService.retrievePaymentIntent(paymentIntentId)`.
4. If returned status ≠ `succeeded` — throw `PaymentNotConfirmedException(stripeStatus)`.
5. **Within `@Transactional`:**
   - Set `payment.stripeChargeId = response.latestCharge`.
   - Set `payment.status = SUCCESS`, `payment.updatedAt = NOW()`.
   - Set `booking.stripeChargeId = response.latestCharge`, `booking.status = CONFIRMED`, `booking.updatedAt = NOW()`.
   - Fetch cart items via REST: `GET /api/v1/booking/carts/{cartId}/items` on `booking-inventory`. Throw `CartItemsFetchException` on non-2xx — rollback everything.
   - For each `CartItem`: create `BookingItem(bookingId, seatId, gaClaimId, ticketClassId, unitPrice, currency, status=ACTIVE)`. Persist.
   - For each `BookingItem`: create `ETicket(bookingId, bookingItemId, qrCodeData=Base64("{bookingRef}:{bookingItemId}"), pdfUrl="/tickets/{bookingRef}/{bookingItemId}.pdf", status=ACTIVE)`. Persist.
6. **Publish** `BookingConfirmedEvent(cartId, seatIds, stripePaymentIntentId, userId)` via `@TransactionalEventListener(AFTER_COMMIT)`.

---

### 6.3 — Payment Failure (`PaymentService.handleFailure()`)

**Triggers:** `POST /api/v1/payments/failed` (frontend) **OR** Stripe webhook `payment_intent.payment_failed` / `payment_intent.canceled`.

**Steps (fully idempotent — whichever trigger arrives first wins):**
1. Load `Payment` by `stripePaymentIntentId`.
2. If `payment.status = FAILED` already — return immediately (no-op).
3. **Within `@Transactional`:**
   - Set `payment.status = FAILED`, populate `failureCode`/`failureMessage` from Stripe response where available.
   - Set `booking.status = CANCELLED`, `booking.updatedAt = NOW()`.
4. **Publish** `PaymentFailedEvent(cartId, userId)` — `booking-inventory`'s `PaymentFailedListener` fires `RELEASE` transition on all `HARD_LOCKED` seats in the cart.

---

### 6.4 — Buyer-Initiated Cancellation (`CancellationService.cancel()`)

**Trigger:** `POST /api/v1/bookings/{bookingRef}/cancel { reason }`

**Steps:**
1. Load `Booking` by `bookingRef`. If `booking.userId ≠ JWT principal userId` — throw `BookingNotFoundException` (never `403` — do not reveal existence to non-owners).
2. Assert `booking.status = CONFIRMED` — throw `CancellationNotAllowedException("Booking is not in CONFIRMED state")` otherwise.
3. Resolve `slotStartTime` via `GET /api/v1/scheduling/slots/{slotId}` (REST read to scheduling). Assert `NOW() < slotStartTime - cancellationWindowHours` — throw `CancellationNotAllowedException("Outside cancellation window", windowClosedAt)` if past window.
4. Create `CancellationRequest(bookingId, userId, reason, status=PENDING)`. Persist.
5. Set `booking.status = CANCELLATION_PENDING`. Persist.
6. Compute refund amount:
   - Read `payments.cancellation.fee-percent` from `application.yaml` (default `0`).
   - `refundAmount = totalAmount - floor(totalAmount * feePercent / 100)`.
7. Call `StripeRefundService.createRefund(stripePaymentIntentId, refundAmount, reason)`.
8. On Stripe response:
   - Persist `Refund(bookingId, stripeRefundId, refundAmount, reason, status from Stripe response)`.
   - Update `CancellationRequest.status = APPROVED`.
   - If Stripe returned `status = succeeded` immediately → call `finaliseAfterRefund(bookingId)` inline.
   - If Stripe returned `status = pending` → finalisation deferred to `refund.updated` webhook.
9. Return `CancellationResponse(bookingRef, status=CANCELLATION_PENDING, refund{...})`.

**On `StripeIntegrationException` from `createRefund`:**
- Revert `booking.status = CONFIRMED`.
- Set `CancellationRequest.status = REJECTED`.
- Throw `RefundFailedException`.

---

### 6.4.1 — Cancellation Finalisation (`CancellationService.finaliseAfterRefund()`)

Called inline (when Stripe refund is immediately `succeeded`) or from `RefundService` when `refund.updated` webhook delivers `status = succeeded`.

**Steps (idempotent guard: skip if `booking.status = CANCELLED`):**
1. **Within `@Transactional`:**
   - `booking.status = CANCELLED`, `booking.updatedAt = NOW()`.
   - All `BookingItem.status = CANCELLED`.
   - All `ETicket.status = VOIDED`.
   - `Refund.status = SUCCEEDED`.
   - `CancellationRequest.status = APPROVED`, `resolvedAt = NOW()`.
2. **Publish** `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)` via `@TransactionalEventListener(AFTER_COMMIT)`.
   - `booking-inventory` listener releases seats (`CONFIRMED → AVAILABLE`).
   - `engagement` listener notifies user.

---

### 6.5 — Stripe Webhook Handler (`StripeWebhookController`)

**Signature verification first:** `StripeWebhookHandler.constructEvent(rawBody, stripeSignatureHeader)` — throw `StripeWebhookSignatureException` on failure → `400`.

All event handlers are **idempotent**:

| Event | Handler |
|---|---|
| `payment_intent.succeeded` | Load `Payment` by `stripePaymentIntentId`; if `status ≠ SUCCESS` → call `PaymentService.confirmPayment()` (skip Stripe retrieve — status already known from payload); skip if already `CONFIRMED` |
| `payment_intent.payment_failed` | Call `PaymentService.handleFailure()`; skip if already `FAILED` |
| `payment_intent.canceled` | Same as `payment_failed` |
| `refund.updated` | Load `Refund` by `stripeRefundId`; update `status`; if `succeeded` → call `CancellationService.finaliseAfterRefund(bookingId)`; if `failed` → call `handleRefundFailed()` |
| `refund.failed` | `Refund.status = FAILED`; `CancellationRequest.status = REJECTED`; `Booking` stays `CANCELLATION_PENDING`; log `ERROR` ops alert |
| Unknown event type | Log `DEBUG`; return `200 OK` silently |

---

### 6.6 — Internal Watchdog Read Path (`PaymentConfirmationReader`)

`PaymentConfirmationReader.isPaymentConfirmed(cartId)`:
- Implemented in `payments-ticketing` as `PaymentConfirmationReaderImpl`.
- Queries `bookingRepository.existsByCartIdAndStatus(cartId, CONFIRMED)`.
- Consumed by `booking-inventory` `PaymentTimeoutWatchdog` through `shared/common/service/` interface injection (no internal HTTP call).

---

### 6.7 — Cross-Cutting Rules

| Rule | Detail |
|---|---|
| Module boundary | `payments-ticketing` never imports `booking-inventory`, `scheduling`, or `identity` `@Service`, `@Repository`, or `@Entity`. Cart items and slot data fetched via REST only. |
| Event publishing | All `ApplicationEvent` publications use `@TransactionalEventListener(AFTER_COMMIT)` — events fire only after DB transaction commits. |
| `clientSecret` | Never stored in DB. Passed directly from `StripePaymentService` response to response DTO and discarded. |
| Currency | All amounts stored and computed in smallest currency unit (paise for INR). Display conversion (÷ 100) happens only in response DTOs. |
| Cancellation fee config | Read from `application.yaml` `payments.cancellation.fee-percent` (default `0`). No DB table for cancellation policy in FR5 scope. |
| `bookingRef` uniqueness | Daily atomic sequence enforced in-memory. Collision risk is negligible at expected booking volumes. If high volume requires DB sequence, replace `AtomicLong` with `SELECT nextval('booking_ref_seq')`. |

---

## Stage 7 — Error Handling

### 7.1 — Payment Path

**E1 — Stripe `createPaymentIntent` fails**
- Cause: `StripeIntegrationException` (network timeout, invalid API key, Stripe 5xx)
- Action: Rollback transaction; no `Booking` or `Payment` row persisted. Log `ERROR` with `cartId`.
- Response: `CartAssembledEvent` is async — no HTTP response to Spring event. `GET /checkout/{cartId}` retries will eventually succeed when Stripe recovers.
- HTTP surface (when checkout endpoint re-called): `502 BAD_GATEWAY`
  ```json
  { "errorCode": "STRIPE_UNAVAILABLE", "message": "Payment service unavailable. Please retry." }
  ```

**E2 — PaymentIntent already exists for cart (duplicate event / retry)**
- Cause: `CartAssembledEvent` delivered twice, or frontend retries `GET /checkout/{cartId}`
- Action: Short-circuit — do not call Stripe again. Return existing `paymentIntentId`. If already `SUCCESS`: omit `clientSecret` from response.
- HTTP response: `200 OK` with existing `CheckoutInitResponse` (if `PENDING`); `409 PAYMENT_ALREADY_EXISTS` with `bookingRef` in body (if already `CONFIRMED`).

**E3 — POST /confirm but Stripe status ≠ `succeeded`**
- Cause: Status is `requires_action`, `processing`, `requires_confirmation`, or `canceled`
- Action: No DB writes. Log `WARN` with `paymentIntentId` + Stripe status.
- HTTP response: `402 Payment Required`
  ```json
  { "errorCode": "PAYMENT_NOT_CONFIRMED", "stripeStatus": "requires_action", "message": "Payment has not been completed. Please complete payment via Stripe." }
  ```

**E4 — POST /confirm but booking already `CONFIRMED` (double-submit)**
- Cause: Frontend calls `/confirm` twice
- Action: Idempotent — no DB writes. Return existing `bookingRef`.
- HTTP response: `200 OK` with existing `BookingResponse`.

**E5 — `retrievePaymentIntent` Stripe call times out**
- Cause: Stripe HTTP timeout during `/confirm`
- Action: No DB state change. Log `ERROR`. Stripe webhook `payment_intent.succeeded` arrives as safety net.
- HTTP response: `504 Gateway Timeout`
  ```json
  { "errorCode": "STRIPE_TIMEOUT", "message": "Payment verification timed out. Your booking will be confirmed automatically." }
  ```

---

### 7.2 — Cart Snapshot Fetch

**E6 — REST call to `booking-inventory` for cart items returns `404` or `503`**
- Cause: `booking-inventory` service degraded during booking confirmation
- Action: Throw `CartItemsFetchException`. Rollback entire confirmation transaction — `Booking` stays `PENDING`, `Payment` stays `PENDING`, no `BookingItem` or `ETicket` created.
- HTTP response: `503 SERVICE_UNAVAILABLE`
  ```json
  { "errorCode": "CART_ITEMS_UNAVAILABLE", "message": "Unable to retrieve cart items. Please retry." }
  ```
- Recovery: Stripe webhook `payment_intent.succeeded` will retry `/confirm` logic; cart items must be available before finalisation succeeds.

---

### 7.3 — Cancellation Path

**E7 — Booking not owned by calling user**
- Cause: `booking.userId ≠ JWT principal userId`
- Action: Treat as not found — never reveal existence.
- HTTP response: `404 NOT_FOUND`
  ```json
  { "errorCode": "BOOKING_NOT_FOUND", "bookingRef": "BK-20260304-001" }
  ```

**E8 — `booking.status ≠ CONFIRMED`**
- Cause: Booking is `PENDING`, `CANCELLATION_PENDING`, or `CANCELLED`
- Action: Reject immediately — no Stripe call.
- HTTP response: `409 Conflict`
  ```json
  { "errorCode": "CANCELLATION_NOT_ALLOWED", "reason": "Booking is not in CONFIRMED state", "currentStatus": "CANCELLATION_PENDING" }
  ```

**E9 — Outside cancellation window**
- Cause: `NOW() > slotStartTime - cancellationWindowHours`
- Action: Reject — no `CancellationRequest` created, no Stripe call.
- HTTP response: `409 Conflict`
  ```json
  { "errorCode": "CANCELLATION_NOT_ALLOWED", "reason": "Cancellation window has closed", "windowClosedAt": "2026-03-04T08:00:00Z" }
  ```

**E10 — `StripeRefundService.createRefund()` fails**
- Cause: Stripe error (charge already refunded, invalid charge ID, Stripe 5xx)
- Action: Revert `booking.status = CONFIRMED`. Set `CancellationRequest.status = REJECTED`. Log `ERROR` with full Stripe error body. Throw `RefundFailedException`.
- HTTP response: `502 BAD_GATEWAY`
  ```json
  { "errorCode": "REFUND_FAILED", "message": "Unable to process refund at this time. Please contact support.", "stripeError": "charge_already_refunded" }
  ```

**E11 — Async `refund.failed` arrives via webhook**
- Cause: Stripe fires `refund.failed` after initial `PENDING`
- Action: `Refund.status = FAILED`. `Booking` stays `CANCELLATION_PENDING` — seats remain `CONFIRMED`. `CancellationRequest.status = REJECTED`, `resolvedAt = NOW()`. Log `ERROR` ops alert. Do NOT publish `BookingCancelledEvent` — seats must not be released.
- No HTTP response (webhook returns `200 OK` to Stripe unconditionally).

---

### 7.4 — Webhook Path

**E12 — Invalid Stripe signature**
- Cause: `StripeWebhookHandler.constructEvent()` throws `StripeWebhookSignatureException`
- Action: Log `WARN` with truncated payload. Do not process event.
- HTTP response: `400 Bad Request`
  ```json
  { "errorCode": "INVALID_WEBHOOK_SIGNATURE" }
  ```
- Note: Stripe does NOT retry on `4xx` — signature failures indicate a config problem or non-Stripe sender.

**E13 — Webhook for unknown `paymentIntentId` or `refundId`**
- Cause: Out-of-order delivery or replay of events for objects not in DB
- Action: Log `WARN` with event ID and object ID. Return `200 OK` — do not signal error to Stripe.
- Note: Returning `4xx` here would cause Stripe to retry indefinitely.

**E14 — Unrecognised webhook event type**
- Cause: Stripe sends an event type not in the handled set (e.g. `customer.created`)
- Action: Log `DEBUG`. Return `200 OK` silently.

---

### 7.5 — E-Ticket Generation

**E15 — `UNIQUE (booking_item_id)` constraint violation on ETicket insert**
- Cause: Concurrent duplicate `/confirm` requests both pass idempotency check before either writes
- Action: Catch `DataIntegrityViolationException`. Treat as idempotent — reload and return existing `ETicket`. Do not surface `500`.
- Implementation:
  ```java
  try {
      return eTicketRepository.save(newTicket);
  } catch (DataIntegrityViolationException e) {
      return eTicketRepository.findByBookingItemId(bookingItemId)
          .orElseThrow(() -> e);
  }
  ```

---

### 7.6 — Booking Ownership (Read Endpoints)

**E16 — User accesses booking or tickets for another user**
- Cause: `booking.userId ≠ JWT principal userId` on `GET /bookings/{ref}` or `GET /tickets/{ref}`
- Action: Same as E7 — treat as not found.
- HTTP response: `404 NOT_FOUND`

---

### 7.7 — Exception → HTTP Status Map

> All handlers registered in `GlobalExceptionHandler` in `shared/common/exception/` — no `@ControllerAdvice` in `payments-ticketing` (Hard Rule #5).

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
| `ForbiddenException` (non-owner) | `404` | `BOOKING_NOT_FOUND` |

---

## Stage 8 — Tests

### `domain/` — Unit Tests

**`BookingTest`**
- `transition_pending_to_confirmed_succeeds`
- `transition_confirmed_to_cancellation_pending_succeeds`
- `transition_cancellation_pending_to_cancelled_succeeds`
- `transition_confirmed_to_cancelled_directly_throws` — CONFIRMED cannot skip CANCELLATION_PENDING
- `transition_cancelled_to_any_throws` — CANCELLED is terminal

**`RefundTest`**
- `transition_pending_to_succeeded_succeeds`
- `transition_pending_to_failed_succeeds`
- `transition_succeeded_to_failed_throws` — SUCCEEDED is terminal

**`ETicketTest`**
- `qrCodeData_format_is_bookingRef_colon_itemId` — asserts `Base64("{bookingRef}:{bookingItemId}")`
- `void_transition_active_to_voided_succeeds`
- `void_already_voided_is_idempotent`

---

### `service/` — Mockito Unit Tests

**`PaymentServiceTest`**
- `createCheckout_newCart_createsBookingAndPayment_returnsClientSecret`
- `createCheckout_duplicateCartId_returnsExistingPaymentIntentId_noStripeCall`
- `createCheckout_stripeThrows_rollsBackTransaction`
- `confirmPayment_stripeSucceeded_confirmsBookingAndCreatesItemsAndTickets`
- `confirmPayment_alreadyConfirmed_returnsExistingBookingRef_noDbWrites`
- `confirmPayment_stripeRequiresAction_throwsPaymentNotConfirmedException`
- `confirmPayment_stripeTimeout_throwsAndLeavesBookingPending`
- `confirmPayment_cartItemsFetchFails_rollsBackTransaction`
- `handleFailure_setsPaymentFailedAndBookingCancelled_publishesPaymentFailedEvent`
- `handleFailure_alreadyFailed_isNoOp`

**`CancellationServiceTest`**
- `cancel_withinWindow_fullRefund_finalisesImmediatelyOnStripeSuccess`
- `cancel_withinWindow_partialRefund_deductsFeeFromStripeAmount`
- `cancel_withinWindow_asyncRefund_leavesBookingCancellationPending`
- `cancel_outsideWindow_throwsCancellationNotAllowedException`
- `cancel_wrongStatus_pending_throwsCancellationNotAllowedException`
- `cancel_wrongStatus_alreadyCancelled_throwsCancellationNotAllowedException`
- `cancel_nonOwner_throwsBookingNotFoundException`
- `cancel_stripeFails_revertsBookingToConfirmed_throwsRefundFailedException`
- `finaliseAfterRefund_succeeds_voidsTicketsAndPublishesBookingCancelledEvent`
- `finaliseAfterRefund_alreadyCancelled_isIdempotent`

**`RefundServiceTest`**
- `updateStatus_succeededWebhook_callsFinaliseAfterRefund`
- `updateStatus_failedWebhook_setsRefundFailedAndLeavesBookingCancellationPending_noEventPublished`
- `updateStatus_unknownRefundId_logsWarnAndReturns`

**`ETicketServiceTest`**
- `generate_createsOneTicketPerItem_qrDataMatchesFormat`
- `generate_constraintViolation_returnsExistingTicket`
- `voidTickets_setsAllStatusToVoided`

---

### `api/` — `@WebMvcTest`

**`PaymentControllerTest`**
- `GET /checkout/{cartId}` — `200 OK` with `clientSecret` for valid cart
- `GET /checkout/{cartId}` — `404` for unknown cart
- `GET /checkout/{cartId}` — `409` with existing `paymentIntentId` when payment already exists
- `POST /confirm` — `200 OK` on `succeeded` status
- `POST /confirm` — `402` when Stripe status is `requires_action`
- `POST /confirm` — `409` when booking already `CONFIRMED`
- `POST /failed` — `200 OK` triggers `PaymentFailedEvent`
- `POST /failed` — `404` for unknown `paymentIntentId`

**`BookingControllerTest`**
- `GET /bookings` — `200 OK` with paginated results for authenticated user
- `GET /bookings` — `401` without JWT
- `GET /bookings/{ref}` — `200 OK` with full detail for owner
- `GET /bookings/{ref}` — `404` for non-owner or unknown ref
- `POST /bookings/{ref}/cancel` — `200 OK` within window
- `POST /bookings/{ref}/cancel` — `409` outside window
- `POST /bookings/{ref}/cancel` — `409` when status is not CONFIRMED
- `POST /bookings/{ref}/cancel` — `404` for non-owner

**`ETicketControllerTest`**
- `GET /tickets/{ref}` — `200 OK` with ticket list for owner
- `GET /tickets/{ref}` — `404` for non-owner

**`StripeWebhookControllerTest`**
- `POST /webhooks/stripe` — `400` on invalid `Stripe-Signature`
- `POST /webhooks/stripe` — `200` on `payment_intent.succeeded` (idempotent confirm)
- `POST /webhooks/stripe` — `200` on `payment_intent.payment_failed` (publishes event)
- `POST /webhooks/stripe` — `200` on `refund.updated` with `status=succeeded`
- `POST /webhooks/stripe` — `200` on `refund.failed` (logs alert, no event)
- `POST /webhooks/stripe` — `200` on unknown event type (silent)
- `POST /webhooks/stripe` — `200` on unknown `paymentIntentId` (logs WARN, no-op)

**`PaymentConfirmationReaderImplTest`**
- `isPaymentConfirmed_returnsTrue_whenBookingExistsWithConfirmedStatus`
- `isPaymentConfirmed_returnsFalse_whenNoConfirmedBookingExists`

---

### `arch/` — ArchUnit

**`PaymentsTicketingArchTest`**
- `no_class_in_paymentsticketing_imports_from_bookinginventory_package` — classes in `com.eventplatform.paymentsticketing` must not import any class from `com.eventplatform.bookinginventory`
- `no_class_in_paymentsticketing_imports_from_scheduling_package` — same for `com.eventplatform.scheduling`
- `no_class_in_paymentsticketing_imports_from_identity_package` — same for `com.eventplatform.identity`
- `no_ControllerAdvice_annotation_in_paymentsticketing_package` — `@ControllerAdvice` must not appear; error handling is in `shared/`
- `no_stripe_sdk_import_outside_shared_stripe_package` — classes outside `com.eventplatform.shared.stripe` must not import `com.stripe.*` directly

---

## Open Questions

1. **`bookingRef` sequence at scale:** Current design uses an in-memory `AtomicLong` reset daily. If the app runs on multiple pods, sequences will collide. In a single-instance deployment this is fine. For multi-instance, replace with a DB sequence (`nextval`) or a UUID-based ref. Defer to ops decision before launch.

2. **Cart items REST contract:** `GET /api/v1/booking/carts/{cartId}/items` is called from `payments-ticketing` during booking confirmation. This endpoint must be verified to exist in `booking-inventory` as part of FR4 implementation. Add to pre-FR5 checklist if not present.

3. **Cancellation window source:** `slotStartTime` is fetched via REST to `scheduling`. If the scheduling service is temporarily unavailable, cancellation requests will fail. Consider caching `slotStartTime` on the `Booking` record at creation time to avoid a cross-module call on the cancellation hot path.

4. **PDF generation:** E-ticket `pdfUrl` is currently a placeholder path. Actual PDF generation (e.g. via iText or a PDF microservice) is deferred. The `ETicketService` should expose a `generatePdf(Long bookingItemId)` method stub for future implementation.

5. **`payments.cancellation.fee-percent` config:** Currently a flat global percentage. Per-event or per-organiser cancellation policies are out of scope for FR5. If required in future, a `cancellation_policy` table should be introduced in `payments-ticketing`.
