# PRE-FR5 Changes Required

**Scope:** All issues, bugs, and gaps in the existing codebase that must be resolved before
implementing Functional Flow 5 (Stripe-based Payments & Ticketing).

> Ordered by fix priority. Fix P0 before P1, P1 before P2, etc.
> Module dependency rule: shared/ changes must land before booking-inventory changes.

---

## Priority Legend

| Level | Meaning |
|---|---|
| **P0 — Blocker** | Prevents compilation or correct runtime behaviour; must fix first |
| **P1 — Critical** | FR5 cannot function without this; fix before any FR5 code is written |
| **P2 — Important** | Semantically wrong; will cause confusion or silent bugs |
| **P3 — Cleanup** | Stale naming or misleading comments; fix to avoid technical debt |

---

## P0 — Blockers

---

### I1 — `payments-ticketing` Maven module does not exist

| Field | Value |
|---|---|
| **Type** | GAP |
| **Severity** | P0 — Blocker |
| **Location** | Project root, `pom.xml` |

**Problem:**
The `payments-ticketing/` directory does not exist. It is not listed in the parent `pom.xml`
`<modules>` section. The current registered modules are:

```
app, shared, discovery-catalog, identity, scheduling, booking-inventory, admin
```

`payments-ticketing` is entirely absent. FR5 (`BookingService`, `PaymentService`, `ETicketService`,
`CancellationService`, `RefundService`) all live in this module.

**Fix Required:**
1. Create directory `payments-ticketing/` with standard Maven layout
2. Create `payments-ticketing/pom.xml` with parent reference and correct module name
3. Add `<module>payments-ticketing</module>` to root `pom.xml` after `booking-inventory`

---

### I2 — Stripe Java SDK not declared in any `pom.xml`

| Field | Value |
|---|---|
| **Type** | GAP |
| **Severity** | P0 — Blocker |
| **Location** | `/home/dell/Multi-City-Event-Booking/pom.xml` (parent), `shared/pom.xml`, `payments-ticketing/pom.xml` (future) |

**Problem:**
`grep stripe pom.xml` returns no matches across all modules. The `com.stripe:stripe-java`
dependency is missing everywhere. `StripePaymentService`, `StripeRefundService`, and
`StripeWebhookHandler` cannot be compiled without it.

**Fix Required:**
Add to parent `pom.xml` `<dependencyManagement>`:
```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>25.3.0</version>  <!-- latest stable as of mid-2025 -->
</dependency>
```
Add to `shared/pom.xml` `<dependencies>` (Stripe ACL lives in `shared/`):
```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
</dependency>
```

---

### I3 — `CartAssembledEvent` missing `totalAmountInSmallestUnit` and `userEmail`

| Field | Value |
|---|---|
| **Type** | BUG |
| **Severity** | P0 — Blocker |
| **Location** | `shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java` |

**Problem:**
Current record signature:
```java
public record CartAssembledEvent(
    Long cartId, Long slotId, Long userId, Long orgId,
    String ebEventId, String couponCode
)
```

`payments-ticketing` listens on this event to call `POST /v1/payment_intents`. Stripe requires:
- `amount` (integer, smallest currency unit — paise for INR)
- `currency` (e.g. `"inr"`)
- `receipt_email` (for email receipts)

None of these are present in the event. `payments-ticketing` cannot derive them independently
because it has no direct access to `booking-inventory` entities (module boundary rule).

The comment in the file also says "initiate Eventbrite checkout widget display" — completely stale.

**Fix Required:**
Replace the record with:
```java
public record CartAssembledEvent(
    Long cartId,
    Long slotId,
    Long userId,
    Long orgId,
    String ebEventId,
    String couponCode,
    long totalAmountInSmallestUnit,   // paise — for Stripe PaymentIntent `amount`
    String currency,                  // e.g. "inr"
    String userEmail                  // for Stripe `receipt_email`
)
```

Update the publish call in `CartService.confirm()` to populate the two new fields using
`CartPricingResult.total()` (which returns a `Money` object) and a user email lookup.

**Note:** `CartPricingService.CartPricingResult` already exposes a `Money total` field with
`amount` (BigDecimal) and `currency` (String). Convert `total.amount()` to long paise at
publish time: `total.amount().multiply(BigDecimal.valueOf(100)).longValue()`.

---

### I4 — `BookingConfirmedEvent.ebOrderId` field is wrong

| Field | Value |
|---|---|
| **Type** | BUG |
| **Severity** | P0 — Blocker |
| **Location** | `shared/src/main/java/com/eventplatform/shared/common/event/published/BookingConfirmedEvent.java` |

**Problem:**
Current record:
```java
public record BookingConfirmedEvent(Long cartId, List<Long> seatIds, String ebOrderId, Long userId)
```

In FR5, `payments-ticketing` publishes this event after Stripe confirms payment. There is no
Eventbrite order ID — the reference is a `stripePaymentIntentId`. Using the wrong field name will:
1. Silently carry the wrong value at runtime (whatever `payments-ticketing` sets it to)
2. Mislead future developers
3. Break `BookingConfirmedListener` in `booking-inventory` (see I5)

**Fix Required:**
```java
public record BookingConfirmedEvent(
    Long cartId,
    List<Long> seatIds,
    String stripePaymentIntentId,   // was: ebOrderId
    Long userId
)
```

---

### I5 — `BookingCancelledEvent` does not exist in `shared/`

| Field | Value |
|---|---|
| **Type** | GAP |
| **Severity** | P0 — Blocker |
| **Location** | `shared/src/main/java/com/eventplatform/shared/common/event/published/` |

**Problem:**
FR6 (Buyer-Initiated Cancellation) requires `payments-ticketing` to publish a
`BookingCancelledEvent` after a cancellation is processed. `booking-inventory` must listen
to this event to release the confirmed seats back to `AVAILABLE`. The event class does not
exist anywhere in the codebase.

**Fix Required:**
Create `BookingCancelledEvent.java` in `shared/common/event/published/`:
```java
package com.eventplatform.shared.common.event.published;

import java.util.List;

public record BookingCancelledEvent(
    Long bookingId,
    Long cartId,
    List<Long> seatIds,
    Long userId,
    String reason                  // "BUYER_CANCEL", "ADMIN_CANCEL", "PAYMENT_FAILED"
) {}
```

---

## P1 — Critical

---

### I6 — `shared/stripe/` ACL package does not exist

| Field | Value |
|---|---|
| **Type** | GAP |
| **Severity** | P1 — Critical |
| **Location** | `shared/src/main/java/com/eventplatform/shared/stripe/` |

**Problem:**
The entire Stripe ACL layer is absent. Per PRODUCT.md Hard Rule #2, no module may call
Stripe HTTP directly — only through `shared/` facades. The required facades:

| Facade | Responsibility |
|---|---|
| `StripePaymentService` | Create PaymentIntent, capture, confirm, cancel |
| `StripeRefundService` | Create refunds; read refund status |
| `StripeWebhookHandler` | Parse and verify Stripe webhook signatures |

None of these classes exist.

**Fix Required:**
Create the following package structure under `shared/`:
```
shared/src/main/java/com/eventplatform/shared/stripe/
  service/
    StripePaymentService.java
    StripeRefundService.java
  webhook/
    StripeWebhookHandler.java
  config/
    StripeConfig.java
  dto/
    StripePaymentIntentRequest.java
    StripePaymentIntentResponse.java
    StripeRefundRequest.java
    StripeRefundResponse.java
    StripeWebhookEvent.java
```

`StripeConfig.java` must read `stripe.secret-key` from `application.yaml` and call
`Stripe.apiKey = secretKey` at `@PostConstruct`.

---

### I7 — `PaymentTimeoutWatchdog` uses `EbOrderService` to skip timed-out seats

| Field | Value |
|---|---|
| **Type** | CRITICAL BUG |
| **Severity** | P1 — Critical |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/scheduler/PaymentTimeoutWatchdog.java` |

**Problem:**
```java
private final EbOrderService ebOrderService;

private boolean hasPlacedOrder(String ebOrderId) {
    EbOrderResponse order = ebOrderService.getOrder(ebOrderId);
    return order != null && "placed".equalsIgnoreCase(order.status());
}
```

The watchdog calls Eventbrite to check whether a seat's payment is confirmed before deciding
whether to release it. In FR5:
1. There is no Eventbrite order — `seat.getEbOrderId()` will be null for Stripe-paid seats
2. Calling `EbOrderService.getOrder(null)` will throw or return null → every confirmed seat
   appears "not placed" → confirmed seats will be incorrectly released by the watchdog

**Fix Required:**
The watchdog must check the internal `payments` table (owned by `payments-ticketing`).
Since `booking-inventory` cannot import `payments-ticketing` repositories directly (module rule),
the check must be done via:

**Option A (recommended):** Inject a thin `PaymentStatusClient` REST client in `booking-inventory`
that calls `GET /internal/payments/by-cart/{cartId}` on `payments-ticketing`.

**Option B:** Publish a `PaymentStatusQuery` Spring event from `booking-inventory` and have
`payments-ticketing` respond via a callback (complex — avoid).

The `hasPlacedOrder` method signature changes to:
```java
private boolean isPaymentConfirmed(Long cartId) {
    // REST call to payments-ticketing internal API
}
```

All references to `seat.getEbOrderId()` in the watchdog as a lookup key must also be
replaced — use `seat.getCartId()` or a new `bookingRef` field (see I8).

---

### I8 — `Seat.ebOrderId` / `eb_order_id` column stores the wrong identifier

| Field | Value |
|---|---|
| **Type** | BUG |
| **Severity** | P1 — Critical |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Seat.java`, `V19__create_seats.sql` |

**Problem:**
```java
@Column(name = "eb_order_id")
private String ebOrderId;

public void confirm(String orderId) {
    this.lockState = SeatLockState.CONFIRMED;
    this.ebOrderId = orderId;
}

public String getEbOrderId() { return ebOrderId; }
```

In FR5, the value stored here should be the internal `bookingRef` (e.g. `BK-20240101-000123`),
not an Eventbrite order ID. Using a field named `ebOrderId` to store a `bookingRef` is a semantic
lie that will confuse every developer who reads it.

DB migration `V19__create_seats.sql` has:
```sql
eb_order_id VARCHAR(255),
CREATE INDEX idx_seats_eb_order ON seats(eb_order_id);
```

**Fix Required:**

1. **New DB migration** `V25__rename_seat_eb_order_id_to_booking_ref.sql`:
   ```sql
   ALTER TABLE seats RENAME COLUMN eb_order_id TO booking_ref;
   DROP INDEX IF EXISTS idx_seats_eb_order;
   CREATE INDEX idx_seats_booking_ref ON seats(booking_ref);
   ```

2. **`Seat.java`** — rename field and methods:
   ```java
   @Column(name = "booking_ref")
   private String bookingRef;
   
   public void confirm(String bookingRef) {
       this.lockState = SeatLockState.CONFIRMED;
       this.bookingRef = bookingRef;
   }
   
   public String getBookingRef() { return bookingRef; }
   ```

---

### I9 — DB migrations for all FR5 tables are missing (V25–V30+)

| Field | Value |
|---|---|
| **Type** | GAP |
| **Severity** | P1 — Critical |
| **Location** | `app/src/main/resources/db/migration/` |

**Problem:**
Highest existing migration: `V24__create_seat_lock_audit_log.sql`. The following tables required
by FR5 and FR6 have **no migration files**:

| Table | Purpose |
|---|---|
| `bookings` | One record per confirmed purchase; owns `booking_ref`, `status`, Stripe IDs |
| `booking_items` | Line items per booking (one per seat / GA claim) |
| `payments` | Stripe payment record: `payment_intent_id`, `charge_id`, `amount`, `currency`, `status` |
| `e_tickets` | QR code, PDF URL, validity per booking item |
| `refunds` | Stripe refund record: `refund_id`, `amount`, `reason`, `status` |
| `cancellation_requests` | Buyer cancel request: `booking_id`, `reason`, `status`, `requested_at` |

**Fix Required:**
Create the following migration files (exact column sets to be finalised at SPEC stage):

**`V25__rename_seat_eb_order_id_to_booking_ref.sql`** — (see I8)

**`V26__create_bookings.sql`**:
```sql
CREATE TABLE bookings (
    id                       BIGSERIAL PRIMARY KEY,
    booking_ref              VARCHAR(50) NOT NULL UNIQUE,
    cart_id                  BIGINT NOT NULL,
    user_id                  BIGINT NOT NULL,
    event_id                 BIGINT NOT NULL,
    slot_id                  BIGINT NOT NULL,
    status                   VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    stripe_payment_intent_id VARCHAR(255),
    stripe_charge_id         VARCHAR(255),
    total_amount             BIGINT NOT NULL,
    currency                 VARCHAR(10) NOT NULL DEFAULT 'INR',
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_bookings_user_id    ON bookings(user_id);
CREATE INDEX idx_bookings_cart_id    ON bookings(cart_id);
CREATE INDEX idx_bookings_booking_ref ON bookings(booking_ref);
```

**`V27__create_booking_items.sql`**:
```sql
CREATE TABLE booking_items (
    id               BIGSERIAL PRIMARY KEY,
    booking_id       BIGINT NOT NULL REFERENCES bookings(id),
    seat_id          BIGINT,
    ga_claim_id      BIGINT,
    ticket_class_id  VARCHAR(255) NOT NULL,
    unit_price       BIGINT NOT NULL,
    currency         VARCHAR(10) NOT NULL DEFAULT 'INR',
    status           VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_booking_items_booking_id ON booking_items(booking_id);
```

**`V28__create_payments.sql`**:
```sql
CREATE TABLE payments (
    id                       BIGSERIAL PRIMARY KEY,
    booking_id               BIGINT NOT NULL REFERENCES bookings(id),
    stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
    stripe_charge_id         VARCHAR(255),
    amount                   BIGINT NOT NULL,
    currency                 VARCHAR(10) NOT NULL DEFAULT 'inr',
    status                   VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    failure_code             VARCHAR(100),
    failure_message          TEXT,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_payments_booking_id               ON payments(booking_id);
CREATE INDEX idx_payments_stripe_payment_intent_id ON payments(stripe_payment_intent_id);
```

**`V29__create_e_tickets.sql`**:
```sql
CREATE TABLE e_tickets (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT NOT NULL REFERENCES bookings(id),
    booking_item_id BIGINT NOT NULL REFERENCES booking_items(id),
    qr_code_data    TEXT NOT NULL,
    pdf_url         TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'VALID',
    issued_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_e_tickets_booking_id ON e_tickets(booking_id);
```

**`V30__create_refunds.sql`**:
```sql
CREATE TABLE refunds (
    id               BIGSERIAL PRIMARY KEY,
    booking_id       BIGINT NOT NULL REFERENCES bookings(id),
    stripe_refund_id VARCHAR(255) UNIQUE,
    amount           BIGINT NOT NULL,
    currency         VARCHAR(10) NOT NULL DEFAULT 'inr',
    reason           VARCHAR(100),
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_refunds_booking_id ON refunds(booking_id);
```

**`V31__create_cancellation_requests.sql`**:
```sql
CREATE TABLE cancellation_requests (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   BIGINT NOT NULL REFERENCES bookings(id),
    user_id      BIGINT NOT NULL,
    reason       TEXT,
    status       VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resolved_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_cancellation_requests_booking_id ON cancellation_requests(booking_id);
```

---

### I10 — No Stripe configuration in `application.yaml`

| Field | Value |
|---|---|
| **Type** | GAP |
| **Severity** | P1 — Critical |
| **Location** | `app/src/main/resources/application.yaml` |

**Problem:**
`application.yaml` has Eventbrite config (`eventbrite.base-url`, `eventbrite.api-key`) but
zero Stripe configuration. `StripeConfig.java` (to be created in `shared/`) must read:
- `stripe.secret-key` — used to authenticate all server-side API calls
- `stripe.webhook-secret` — used to verify `Stripe-Signature` header on webhook POST
- `stripe.publishable-key` — returned to the frontend for `Stripe.js`

**Fix Required:**
Add to `application.yaml`:
```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:sk_test_placeholder}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_placeholder}
  publishable-key: ${STRIPE_PUBLISHABLE_KEY:pk_test_placeholder}
  currency: inr
  payment-intent:
    capture-method: automatic
```

---

## P2 — Important

---

### I11 — `BookingConfirmedListener` references `event.ebOrderId()` — will fail to compile

| Field | Value |
|---|---|
| **Type** | BUG |
| **Severity** | P2 — Important (will become P0 when I4 is applied) |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/event/listener/BookingConfirmedListener.java` |

**Problem:**
```java
cartService.onBookingConfirmed(event.cartId(), event.seatIds(), event.ebOrderId(), event.userId());
```

When `BookingConfirmedEvent.ebOrderId` is renamed to `stripePaymentIntentId` (I4), this line
will fail to compile. Must be updated in the same change set as I4.

**Fix Required:**
```java
cartService.onBookingConfirmed(
    event.cartId(), event.seatIds(), event.stripePaymentIntentId(), event.userId()
);
```

Also update `CartService.onBookingConfirmed(Long cartId, List<Long> seatIds, String orderId, Long userId)` —
rename parameter `orderId` to `bookingRef`:
```java
public void onBookingConfirmed(Long cartId, List<Long> seatIds, String bookingRef, Long userId)
```

---

### I12 — `SeatActionContext.orderId` is semantically EB-specific

| Field | Value |
|---|---|
| **Type** | BUG |
| **Severity** | P2 — Important |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockStateMachineService.java` |

**Problem:**
```java
public record SeatActionContext(Long userId, String orderId, String reason) {}
```

`orderId` was designed to carry the Eventbrite order ID when transitioning to `CONFIRMED`.
In FR5 the same transition carries `bookingRef`. The name `orderId` will cause confusion.

**Fix Required:**
```java
public record SeatActionContext(Long userId, String bookingRef, String reason) {}
```

Update all callers that construct `SeatActionContext` to pass `bookingRef` in that position.

---

### I13 — `SeatLockEvent.CHECKOUT_INITIATE` is named after the EB checkout widget

| Field | Value |
|---|---|
| **Type** | ISSUE |
| **Severity** | P2 — Important |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockStateMachineConfig.java` (and the `SeatLockEvent` enum) |

**Problem:**
```java
transitions.put(SeatLockState.HARD_LOCKED, Map.of(
    SeatLockEvent.CHECKOUT_INITIATE, SeatLockState.PAYMENT_PENDING,
    ...
));
```

`CHECKOUT_INITIATE` refers to the old Eventbrite Checkout Widget flow, which is now removed.
In FR5, the transition is triggered when `payments-ticketing` creates a Stripe PaymentIntent.

**Fix Required:**
1. Rename enum value: `CHECKOUT_INITIATE` → `PAYMENT_INITIATE` in `SeatLockEvent`
2. Update `SeatLockStateMachineConfig.java` transition map key
3. Update all call sites that fire `CHECKOUT_INITIATE` on the state machine

---

### I14 — `CartItem.ebTicketClassId` field name is EB-specific

| Field | Value |
|---|---|
| **Type** | ISSUE |
| **Severity** | P2 — Important |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/CartItem.java` |

**Problem:**
```java
@Column(name = "eb_ticket_class_id", nullable = false)
private String ebTicketClassId;
```

`CartItem` stores the Eventbrite ticket class ID for each line item. Per PRODUCT.md, the passive
EB availability check (calling `EbTicketService.getTicketClass()`) is retained in `CartService.confirm()`.
The field itself is necessary, but the name prefix `eb` is acceptable here since it genuinely references
the Eventbrite ticket class.

**Decision:** This field can stay as-is. The passive EB check in `CartService.confirm()` is an
intentional architectural decision (per PRODUCT.md FR4 flow). However, the Java and DB column
names should remain `eb_ticket_class_id` — do NOT rename.

*No code change required.* Document this as "kept by design."

---

## P3 — Cleanup

---

### I15 — Audit reason "ORDER_PLACED" in `CartService.onBookingConfirmed` is stale EB language

| Field | Value |
|---|---|
| **Type** | ISSUE |
| **Severity** | P3 — Cleanup |
| **Location** | `booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java` |

**Problem:**
`writeAudit(... "ORDER_PLACED", orderId)` — "ORDER_PLACED" was the Eventbrite order lifecycle term.
In Stripe flow this event means "Stripe payment confirmed."

**Fix Required:**
```java
writeAudit(cartId, seatId, "STRIPE_PAYMENT_CONFIRMED", bookingRef);
```

---

### I16 — `CartAssembledEvent` comment references "Eventbrite checkout widget"

| Field | Value |
|---|---|
| **Type** | ISSUE |
| **Severity** | P3 — Cleanup |
| **Location** | `shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java` |

**Problem:**
JavaDoc/inline comment says "initiate Eventbrite checkout widget display" — completely stale.

**Fix Required:**
Update comment to:
```
// Published by booking-inventory when cart lock is confirmed.
// Consumed by payments-ticketing to create a Stripe PaymentIntent.
```

---

## Summary Checklist

| # | Issue | Location | Type | Priority | Status |
|---|---|---|---|---|---|
| I1 | `payments-ticketing` module absent | root `pom.xml` | GAP | P0 | ❌ TODO |
| I2 | Stripe SDK not in any `pom.xml` | parent + `shared` pom | GAP | P0 | ❌ TODO |
| I3 | `CartAssembledEvent` missing amount + email | `shared/events/` | BUG | P0 | ❌ TODO |
| I4 | `BookingConfirmedEvent.ebOrderId` wrong field name | `shared/events/` | BUG | P0 | ❌ TODO |
| I5 | `BookingCancelledEvent` does not exist | `shared/events/` | GAP | P0 | ❌ TODO |
| I6 | `shared/stripe/` ACL package absent | `shared/` | GAP | P1 | ❌ TODO |
| I7 | `PaymentTimeoutWatchdog` uses `EbOrderService` | `booking-inventory` | CRITICAL BUG | P1 | ❌ TODO |
| I8 | `Seat.ebOrderId` / `eb_order_id` wrong column | `booking-inventory` + V19 | BUG | P1 | ❌ TODO |
| I9 | Missing DB migrations V25–V31 | `db/migration/` | GAP | P1 | ❌ TODO |
| I10 | No Stripe config in `application.yaml` | `app/resources/` | GAP | P1 | ❌ TODO |
| I11 | `BookingConfirmedListener` will fail to compile after I4 | `booking-inventory` | BUG | P2 | ❌ TODO |
| I12 | `SeatActionContext.orderId` is semantically wrong | `booking-inventory` | BUG | P2 | ❌ TODO |
| I13 | `SeatLockEvent.CHECKOUT_INITIATE` is EB-named | `booking-inventory` | ISSUE | P2 | ❌ TODO |
| I14 | `CartItem.ebTicketClassId` — kept by design | `booking-inventory` | — | N/A | ✅ Intentional |
| I15 | Audit reason "ORDER_PLACED" is stale | `booking-inventory` | CLEANUP | P3 | ❌ TODO |
| I16 | `CartAssembledEvent` comment is stale | `shared/events/` | CLEANUP | P3 | ❌ TODO |

---

## Recommended Fix Order

```
Phase 1 — Foundation (no compilation dependency)
  I9  → Create DB migrations V25–V31
  I10 → Add Stripe config to application.yaml

Phase 2 — shared/ layer (must be built before any module uses new events/services)
  I2  → Add Stripe SDK dependency (parent + shared pom)
  I6  → Create shared/stripe/ ACL package
  I3  → Add totalAmountInSmallestUnit + userEmail to CartAssembledEvent
  I4  → Rename BookingConfirmedEvent.ebOrderId → stripePaymentIntentId
  I5  → Create BookingCancelledEvent
  I16 → Update stale comment in CartAssembledEvent

Phase 3 — booking-inventory (depend on shared/ changes)
  I8  → Rename Seat.ebOrderId → bookingRef + V25 migration
  I12 → Rename SeatActionContext.orderId → bookingRef
  I13 → Rename SeatLockEvent.CHECKOUT_INITIATE → PAYMENT_INITIATE
  I11 → Fix BookingConfirmedListener (cascades from I4)
  I15 → Update audit reason "ORDER_PLACED" → "STRIPE_PAYMENT_CONFIRMED"

Phase 4 — Module scaffold (can start after Phase 2)
  I1  → Create payments-ticketing module (directory + pom + registration)

Phase 5 — Watchdog fix (depends on payments-ticketing REST API existing)
  I7  → Replace EbOrderService usage in PaymentTimeoutWatchdog
```
