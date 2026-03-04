# PRE-FR5 Changes — Implementation Record

**Date Implemented:** March 4, 2026
**Scope:** All 16 issues (I1–I16) from `docs/PRE5 changes.md` resolved before FR5 (Stripe-based Payments & Ticketing).
**Final Test Result:** 228 tests across 7 modules — ALL PASS ✅

---

## Summary Table

| # | Issue | Type | Priority | Status | Files Changed |
|---|---|---|---|---|---|
| I1 | `payments-ticketing` module absent | GAP | P0 | ✅ Fixed | root `pom.xml`, new module scaffold |
| I2 | Stripe SDK not declared | GAP | P0 | ✅ Fixed | parent `pom.xml`, `shared/pom.xml` |
| I3 | `CartAssembledEvent` missing amount + email | BUG | P0 | ✅ Fixed | `CartAssembledEvent.java`, `CartService.java` |
| I4 | `BookingConfirmedEvent.ebOrderId` wrong name | BUG | P0 | ✅ Fixed | `BookingConfirmedEvent.java` |
| I5 | `BookingCancelledEvent` missing | GAP | P0 | ✅ Fixed | new `BookingCancelledEvent.java` |
| I6 | `shared/stripe/` ACL package absent | GAP | P1 | ✅ Fixed | 9 new files under `shared/stripe/` |
| I7 | `PaymentTimeoutWatchdog` uses `EbOrderService` | CRITICAL BUG | P1 | ✅ Fixed | `PaymentTimeoutWatchdog.java`, new `PaymentStatusClient.java` |
| I8 | `Seat.ebOrderId` wrong column name | BUG | P1 | ✅ Fixed | `Seat.java`, new V25 migration |
| I9 | DB migrations V25–V31 missing | GAP | P1 | ✅ Fixed | 7 new SQL migration files |
| I10 | No Stripe config in `application.yaml` | GAP | P1 | ✅ Fixed | `application.yaml` |
| I11 | `BookingConfirmedListener` will fail to compile | BUG | P2 | ✅ Fixed | `BookingConfirmedListener.java`, `CartService.java` |
| I12 | `SeatActionContext.orderId` semantically wrong | BUG | P2 | ✅ Fixed | `SeatLockStateMachineService.java` + callers |
| I13 | `SeatLockEvent.CHECKOUT_INITIATE` EB-named | ISSUE | P2 | ✅ Fixed | `SeatLockEvent.java`, config, callers |
| I14 | `CartItem.ebTicketClassId` — kept by design | — | N/A | ✅ Intentional | No change |
| I15 | Audit reason `"ORDER_PLACED"` stale | CLEANUP | P3 | ✅ Fixed | `CartService.java` |
| I16 | `CartAssembledEvent` stale comment | CLEANUP | P3 | ✅ Fixed | `CartAssembledEvent.java` |

---

## Detailed Implementation

---

### I1 — `payments-ticketing` Module Created

**Problem:** The `payments-ticketing/` Maven module did not exist. FR5 domain (`BookingService`, `PaymentService`, `ETicketService`, `CancellationService`, `RefundService`) had nowhere to live.

**Fix Applied:**
- Created `payments-ticketing/` directory with full standard Maven layout:
  ```
  payments-ticketing/
    pom.xml
    src/main/java/com/eventplatform/paymentsticketing/
      PaymentsTicketingApplication.java
      api/internal/InternalPaymentController.java
      domain/ (Booking.java, Payment.java, etc.)
      repository/ (BookingRepository.java, PaymentRepository.java)
      service/ (BookingService.java)
      event/listener/ (CartAssembledListener.java, BookingConfirmedByPaymentListener.java)
    src/main/resources/
    src/test/java/com/eventplatform/paymentsticketing/
      PaymentsTicketingTestApplication.java
      api/internal/InternalPaymentControllerTest.java
  ```
- Added `<module>payments-ticketing</module>` to root `pom.xml` after `booking-inventory`.
- `payments-ticketing/pom.xml` declares parent, dependencies on `shared`, `booking-inventory` (REST only), `spring-boot-starter-web`, `spring-boot-starter-data-jpa`.

**Key files:**
- [payments-ticketing/pom.xml](payments-ticketing/pom.xml)
- [payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/PaymentsTicketingApplication.java](payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/PaymentsTicketingApplication.java)

---

### I2 — Stripe Java SDK Added

**Problem:** `com.stripe:stripe-java` was not declared in any `pom.xml`. All Stripe ACL classes failed to compile.

**Fix Applied:**
- Added to parent `pom.xml` `<dependencyManagement>`:
  ```xml
  <dependency>
      <groupId>com.stripe</groupId>
      <artifactId>stripe-java</artifactId>
      <version>25.3.0</version>
  </dependency>
  ```
- Added to `shared/pom.xml` `<dependencies>`:
  ```xml
  <dependency>
      <groupId>com.stripe</groupId>
      <artifactId>stripe-java</artifactId>
  </dependency>
  ```

**Key files:**
- [pom.xml](pom.xml)
- [shared/pom.xml](shared/pom.xml)

---

### I3 — `CartAssembledEvent` Extended with Amount and Email

**Problem:** `CartAssembledEvent` only had 6 fields (`cartId`, `slotId`, `userId`, `orgId`, `ebEventId`, `couponCode`). `payments-ticketing` needs `totalAmountInSmallestUnit`, `currency`, and `userEmail` to create a Stripe `PaymentIntent`.

**Before:**
```java
public record CartAssembledEvent(
    Long cartId, Long slotId, Long userId, Long orgId,
    String ebEventId, String couponCode
) {}
```

**After:**
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
) {}
```

**`CartService.confirm()` updated** to accept `userEmail` as a new parameter and populate the event:
```java
public CartSummary confirm(Long cartId, Long orgId, String userEmail, ConfirmCartRequest request) {
    // ...
    CartPricingResult pricing = cartPricingService.price(cart, request.couponCode());
    long amountInSmallestUnit = pricing.total().amount()
        .multiply(BigDecimal.valueOf(100)).longValue();

    applicationEventPublisher.publishEvent(new CartAssembledEvent(
        cartId, cart.getSlotId(), cart.getUserId(), orgId,
        ebEventId, request.couponCode(),
        amountInSmallestUnit, pricing.total().currency().toLowerCase(), userEmail
    ));
}
```

Also updated the stale comment (I16): `// Published by booking-inventory when cart lock is confirmed. Consumed by payments-ticketing to create a Stripe PaymentIntent.`

**Key files:**
- [shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java](shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java)
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java)

---

### I4 — `BookingConfirmedEvent.ebOrderId` Renamed to `stripePaymentIntentId`

**Problem:** `BookingConfirmedEvent` had a field named `ebOrderId`, but in FR5 this carries a Stripe `PaymentIntent` ID. Misleading name → silent runtime bugs and confused developers.

**Before:**
```java
public record BookingConfirmedEvent(Long cartId, List<Long> seatIds, String ebOrderId, Long userId) {}
```

**After:**
```java
public record BookingConfirmedEvent(
    Long cartId,
    List<Long> seatIds,
    String stripePaymentIntentId,
    Long userId
) {}
```

**Key files:**
- [shared/src/main/java/com/eventplatform/shared/common/event/published/BookingConfirmedEvent.java](shared/src/main/java/com/eventplatform/shared/common/event/published/BookingConfirmedEvent.java)

---

### I5 — `BookingCancelledEvent` Created

**Problem:** No `BookingCancelledEvent` existed. FR6 (Buyer-Initiated Cancellation) requires `payments-ticketing` to publish this event so `booking-inventory` can release confirmed seats back to `AVAILABLE`.

**Fix Applied:** Created new event record in `shared/common/event/published/`:
```java
public record BookingCancelledEvent(
    Long bookingId,
    Long cartId,
    List<Long> seatIds,
    Long userId,
    String reason                  // "BUYER_CANCEL", "ADMIN_CANCEL", "PAYMENT_FAILED"
) {}
```

**Key files:**
- [shared/src/main/java/com/eventplatform/shared/common/event/published/BookingCancelledEvent.java](shared/src/main/java/com/eventplatform/shared/common/event/published/BookingCancelledEvent.java)

---

### I6 — `shared/stripe/` ACL Package Created

**Problem:** The entire Stripe ACL layer was absent. Per `PRODUCT.md` Hard Rule #2, no module may call Stripe HTTP directly.

**Fix Applied:** Created the full Stripe ACL package under `shared/`:

```
shared/src/main/java/com/eventplatform/shared/stripe/
  config/
    StripeConfig.java              — reads stripe.secret-key; calls Stripe.apiKey = secretKey at @PostConstruct
  dto/
    StripePaymentIntentRequest.java
    StripePaymentIntentResponse.java
    StripeRefundRequest.java
    StripeRefundResponse.java
    StripeWebhookEvent.java
  exception/
    StripeIntegrationException.java
    StripeWebhookSignatureException.java
  service/
    StripePaymentService.java      — createPaymentIntent(), getPaymentIntent(), cancelPaymentIntent()
    StripeRefundService.java       — createRefund(), getRefund()
  webhook/
    StripeWebhookHandler.java      — parseEvent(), extractObjectId()
```

**`StripeConfig.java`** reads from `application.yaml` and initialises the SDK:
```java
@Configuration
public class StripeConfig {
    @Value("${stripe.secret-key}")
    private String secretKey;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }
}
```

**`StripePaymentService.java`** wraps SDK static calls in exception handling:
```java
public StripePaymentIntentResponse createPaymentIntent(StripePaymentIntentRequest request) {
    try {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
            .setAmount(request.amount())
            .setCurrency(request.currency())
            .setReceiptEmail(request.receiptEmail())
            .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
            .build();
        PaymentIntent intent = PaymentIntent.create(params);
        return new StripePaymentIntentResponse(intent.getId(), intent.getClientSecret(), intent.getStatus());
    } catch (StripeException e) {
        throw new StripeIntegrationException("Failed to create PaymentIntent", e);
    }
}
```

**`StripeWebhookHandler.java`** verifies signatures using the webhook secret:
```java
public StripeWebhookEvent parseEvent(String payload, String sigHeader) {
    try {
        Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        // extract objectId from event data
        return new StripeWebhookEvent(event.getId(), event.getType(), objectId);
    } catch (SignatureVerificationException e) {
        throw new StripeWebhookSignatureException("Invalid Stripe webhook signature", e);
    }
}
```

**Key files:**
- [shared/src/main/java/com/eventplatform/shared/stripe/config/StripeConfig.java](shared/src/main/java/com/eventplatform/shared/stripe/config/StripeConfig.java)
- [shared/src/main/java/com/eventplatform/shared/stripe/service/StripePaymentService.java](shared/src/main/java/com/eventplatform/shared/stripe/service/StripePaymentService.java)
- [shared/src/main/java/com/eventplatform/shared/stripe/service/StripeRefundService.java](shared/src/main/java/com/eventplatform/shared/stripe/service/StripeRefundService.java)
- [shared/src/main/java/com/eventplatform/shared/stripe/webhook/StripeWebhookHandler.java](shared/src/main/java/com/eventplatform/shared/stripe/webhook/StripeWebhookHandler.java)

---

### I7 — `PaymentTimeoutWatchdog` Decoupled from `EbOrderService`

**Problem:** The watchdog called `EbOrderService.getOrder(seat.getEbOrderId())` to determine if a seat's payment was confirmed. In FR5: (a) there is no Eventbrite order for Stripe-paid seats, (b) `getEbOrderId()` returns null → every confirmed seat appeared unconfirmed → watchdog would incorrectly release them.

**Fix Applied:**
1. Created `PaymentStatusClient.java` in `booking-inventory/service/client/` — a thin REST client that calls `GET /internal/payments/by-cart/{cartId}/status` on `payments-ticketing`:
   ```java
   @Service
   public class PaymentStatusClient {
       private final RestClient restClient;

       public boolean isPaymentConfirmed(Long cartId) {
           if (cartId == null) return false;
           try {
               PaymentStatusResponse response = restClient.get()
                   .uri("/internal/payments/by-cart/{cartId}/status", cartId)
                   .retrieve()
                   .body(PaymentStatusResponse.class);
               return response != null && "SUCCEEDED".equalsIgnoreCase(response.status());
           } catch (RestClientException e) {
               return false;   // treat unreachable payments-ticketing as "not confirmed"
           }
       }
   }
   ```

2. Created `InternalPaymentController.java` in `payments-ticketing/api/internal/` to expose that endpoint:
   ```java
   @RestController
   @RequestMapping("/internal/payments")
   public class InternalPaymentController {
       @GetMapping("/by-cart/{cartId}/status")
       public ResponseEntity<PaymentStatusResponse> getPaymentStatus(@PathVariable Long cartId) {
           // looks up booking + payment by cartId, returns status
       }
   }
   ```

3. Updated `PaymentTimeoutWatchdog.java`:
   - Removed `EbOrderService` dependency
   - Injected `PaymentStatusClient`
   - Renamed `hasPlacedOrder(String ebOrderId)` → `isPaymentConfirmed(Long cartId)`
   - Replaced `seat.getEbOrderId()` lookup → `seat.getCartId()` lookup

**Key files:**
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/client/PaymentStatusClient.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/client/PaymentStatusClient.java)
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/scheduler/PaymentTimeoutWatchdog.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/scheduler/PaymentTimeoutWatchdog.java)
- [payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/api/internal/InternalPaymentController.java](payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/api/internal/InternalPaymentController.java)

---

### I8 — `Seat.ebOrderId` Renamed to `bookingRef`

**Problem:** `Seat` had a field `ebOrderId` that stored the booking's internal reference in FR5. Storing a `bookingRef` in a column named `eb_order_id` is a semantic lie.

**Fix Applied:**

1. **`Seat.java`** — renamed field and accessor:
   ```java
   // Before
   @Column(name = "eb_order_id")
   private String ebOrderId;
   public void confirm(String orderId) { this.lockState = CONFIRMED; this.ebOrderId = orderId; }
   public String getEbOrderId() { return ebOrderId; }

   // After
   @Column(name = "booking_ref")
   private String bookingRef;
   public void confirm(String bookingRef) { this.lockState = CONFIRMED; this.bookingRef = bookingRef; }
   public String getBookingRef() { return bookingRef; }
   ```

2. Created DB migration **`V25__rename_seat_eb_order_id_to_booking_ref.sql`**:
   ```sql
   ALTER TABLE seats RENAME COLUMN eb_order_id TO booking_ref;
   DROP INDEX IF EXISTS idx_seats_eb_order;
   CREATE INDEX idx_seats_booking_ref ON seats(booking_ref);
   ```

**Key files:**
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Seat.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Seat.java)
- [app/src/main/resources/db/migration/V25__rename_seat_eb_order_id_to_booking_ref.sql](app/src/main/resources/db/migration/V25__rename_seat_eb_order_id_to_booking_ref.sql)

---

### I9 — DB Migrations V25–V31 Created

**Problem:** The highest existing migration was `V24__create_seat_lock_audit_log.sql`. All FR5 tables (`bookings`, `booking_items`, `payments`, `e_tickets`, `refunds`, `cancellation_requests`) had no migration files.

**Fix Applied:** Created 7 new migration files:

| File | Creates |
|---|---|
| `V25__rename_seat_eb_order_id_to_booking_ref.sql` | Renames column (see I8) |
| `V26__create_bookings.sql` | `bookings` table with `booking_ref`, `stripe_payment_intent_id`, `stripe_charge_id`, `status`, `total_amount` |
| `V27__create_booking_items.sql` | `booking_items` table with FK to `bookings`, `seat_id`, `ga_claim_id`, `ticket_class_id`, `unit_price` |
| `V28__create_payments.sql` | `payments` table with `stripe_payment_intent_id` UNIQUE, `stripe_charge_id`, `amount`, `status`, `failure_code`, `failure_message` |
| `V29__create_e_tickets.sql` | `e_tickets` table with `qr_code_data`, `pdf_url`, `status`, FK to `booking_items` |
| `V30__create_refunds.sql` | `refunds` table with `stripe_refund_id` UNIQUE, `amount`, `reason`, `status` |
| `V31__create_cancellation_requests.sql` | `cancellation_requests` table with FK to `bookings`, `user_id`, `reason`, `status`, `requested_at`, `resolved_at` |

**Key files:**
- [app/src/main/resources/db/migration/V25__rename_seat_eb_order_id_to_booking_ref.sql](app/src/main/resources/db/migration/V25__rename_seat_eb_order_id_to_booking_ref.sql)
- [app/src/main/resources/db/migration/V26__create_bookings.sql](app/src/main/resources/db/migration/V26__create_bookings.sql)
- [app/src/main/resources/db/migration/V27__create_booking_items.sql](app/src/main/resources/db/migration/V27__create_booking_items.sql)
- [app/src/main/resources/db/migration/V28__create_payments.sql](app/src/main/resources/db/migration/V28__create_payments.sql)
- [app/src/main/resources/db/migration/V29__create_e_tickets.sql](app/src/main/resources/db/migration/V29__create_e_tickets.sql)
- [app/src/main/resources/db/migration/V30__create_refunds.sql](app/src/main/resources/db/migration/V30__create_refunds.sql)
- [app/src/main/resources/db/migration/V31__create_cancellation_requests.sql](app/src/main/resources/db/migration/V31__create_cancellation_requests.sql)

---

### I10 — Stripe Configuration Added to `application.yaml`

**Problem:** `application.yaml` had Eventbrite config but zero Stripe configuration. `StripeConfig` had no properties to read.

**Fix Applied:** Added to `app/src/main/resources/application.yaml`:
```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:sk_test_placeholder}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_placeholder}
  publishable-key: ${STRIPE_PUBLISHABLE_KEY:pk_test_placeholder}
  currency: inr
  payment-intent:
    capture-method: automatic
```

**Key files:**
- [app/src/main/resources/application.yaml](app/src/main/resources/application.yaml)

---

### I11 — `BookingConfirmedListener` and `CartService` Updated for Renamed Field

**Problem:** `BookingConfirmedListener` called `event.ebOrderId()` which would fail to compile after I4 renamed the field to `stripePaymentIntentId`. `CartService.onBookingConfirmed()` had a parameter named `orderId`.

**Fix Applied:**

`BookingConfirmedListener.java`:
```java
// Before
cartService.onBookingConfirmed(event.cartId(), event.seatIds(), event.ebOrderId(), event.userId());

// After
cartService.onBookingConfirmed(event.cartId(), event.seatIds(), event.stripePaymentIntentId(), event.userId());
```

`CartService.java` — parameter rename:
```java
// Before
public void onBookingConfirmed(Long cartId, List<Long> seatIds, String orderId, Long userId)

// After
public void onBookingConfirmed(Long cartId, List<Long> seatIds, String bookingRef, Long userId)
```

**Key files:**
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/event/listener/BookingConfirmedListener.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/event/listener/BookingConfirmedListener.java)
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java)

---

### I12 — `SeatActionContext.orderId` Renamed to `bookingRef`

**Problem:** `SeatActionContext` record used `orderId` which was designed for an Eventbrite order ID. In FR5 this field carries an internal `bookingRef`.

**Fix Applied:**

`SeatLockStateMachineService.java`:
```java
// Before
public record SeatActionContext(Long userId, String orderId, String reason) {}

// After
public record SeatActionContext(Long userId, String bookingRef, String reason) {}
```

All callers that constructed `new SeatActionContext(userId, someOrderId, reason)` were updated to pass `bookingRef` in that position.

**Key files:**
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockStateMachineService.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockStateMachineService.java)

---

### I13 — `SeatLockEvent.CHECKOUT_INITIATE` Renamed to `PAYMENT_INITIATE`

**Problem:** `CHECKOUT_INITIATE` referred to the Eventbrite Checkout Widget. In FR5 this transition is triggered when `payments-ticketing` creates a Stripe `PaymentIntent`.

**Fix Applied:**

1. `SeatLockEvent.java` enum — renamed value:
   ```java
   // Before: CHECKOUT_INITIATE
   // After:  PAYMENT_INITIATE
   public enum SeatLockEvent {
       SOFT_LOCK, HARD_LOCK, PAYMENT_INITIATE, PAYMENT_CONFIRM, PAYMENT_FAIL,
       TIMEOUT_EXPIRE, ADMIN_OVERRIDE_RELEASE
   }
   ```

2. `SeatLockStateMachineConfig.java` — updated transition map:
   ```java
   // Before
   transitions.put(SeatLockState.HARD_LOCKED, Map.of(
       SeatLockEvent.CHECKOUT_INITIATE, SeatLockState.PAYMENT_PENDING, ...
   ));
   // After
   transitions.put(SeatLockState.HARD_LOCKED, Map.of(
       SeatLockEvent.PAYMENT_INITIATE, SeatLockState.PAYMENT_PENDING, ...
   ));
   ```

3. All call sites firing `CHECKOUT_INITIATE` updated to `PAYMENT_INITIATE`.

**Key files:**
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockEvent.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockEvent.java)
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockStateMachineConfig.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/statemachine/SeatLockStateMachineConfig.java)

---

### I14 — `CartItem.ebTicketClassId` — Kept by Design

**Decision:** No change made. The field genuinely references an Eventbrite ticket class ID (FR4 passive availability check retained in `CartService.confirm()`). The `eb` prefix is accurate because the value IS an Eventbrite entity ID. Documented in source code as intentional.

---

### I15 — Audit Reason `"ORDER_PLACED"` Updated

**Problem:** `CartService.onBookingConfirmed()` wrote `writeAudit(cartId, seatId, "ORDER_PLACED", orderId)`. "ORDER_PLACED" was Eventbrite lifecycle terminology, meaningless in the Stripe flow.

**Fix Applied:**
```java
// Before
writeAudit(cartId, seatId, "ORDER_PLACED", orderId);

// After
writeAudit(cartId, seatId, "STRIPE_PAYMENT_CONFIRMED", bookingRef);
```

**Key files:**
- [booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java](booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java)

---

### I16 — `CartAssembledEvent` Stale Comment Fixed

**Problem:** JavaDoc comment said "initiate Eventbrite checkout widget display".

**Fix Applied** (done alongside I3):
```java
// Published by booking-inventory when cart lock is confirmed.
// Consumed by payments-ticketing to create a Stripe PaymentIntent.
```

**Key files:**
- [shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java](shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java)

---

## Test Fixes Required by These Changes

The production API changes above broke several existing tests. All were fixed:

### Broken Tests Fixed

| Test File | What Broke | Fix Applied |
|---|---|---|
| `identity/…/UserSettingsControllerTest.java` | `AuthenticatedUser` 3-arg constructor | Changed to `new AuthenticatedUser(1L, "USER", null, null)` |
| `identity/…/AuthControllerTest.java` | `AuthenticatedUser` 3-arg constructor | Changed to `new AuthenticatedUser(1L, "USER", null, null)` |
| `identity/…/AuthServiceTest.java` | `generateToken(userId, role)` 2-arg stub mismatch | Changed to `generateToken(userId, role, null, user.getEmail())` (4-arg) |
| `booking-inventory/…/BookingInventoryControllerTest.java` | `AuthenticatedUser` 3-arg + `cartService.confirm()` 3-arg | Added `email` arg to `AuthenticatedUser`; added `any(String.class)` email param to all confirm stubs |
| `booking-inventory/…/CartServiceTest.java` | `cartService.confirm()` 3-arg | Added `"test@example.com"` as 3rd argument to all 3 confirm calls |
| `booking-inventory/…/BookingInventoryArchTest.java` | `CartAssembledEvent` field count changed (6 → 9) | Updated `containsOnly("Long", "long", "String")` for 3-type assertion |

**Root cause summary:** Three API signatures changed:
1. `AuthenticatedUser` record gained a 4th field (`email: String`) as part of I3 (email needed by `CartService.confirm()`)
2. `CartService.confirm()` gained an `email` parameter (String, 3rd arg) to populate `CartAssembledEvent.userEmail`
3. `JwtTokenProvider.generateToken()` gained a 4-arg overload `(userId, role, orgId, email)` to embed email in JWT

---

### New Tests Written

| Test File | Module | Tests | Purpose |
|---|---|---|---|
| `StripePaymentServiceTest.java` | `shared` | 6 | `createPaymentIntent`, `getPaymentIntent`, `cancelPaymentIntent`, exception wrapping |
| `StripeRefundServiceTest.java` | `shared` | 5 | `createRefund` (full + partial), `getRefund`, exception wrapping, `StripeIntegrationException` |
| `StripeWebhookHandlerTest.java` | `shared` | 4 | Valid event parsing, objectId extraction, invalid signature → `StripeWebhookSignatureException` |
| `JwtTokenProviderTest.java` (extended) | `shared` | +2 | 4-arg `generateToken` + `extractEmail()`; null email for 2-arg token |
| `PaymentStatusClientTest.java` | `booking-inventory` | 7 | SUCCEEDED→true, PENDING/FAILED→false, null response, `RestClientException`, null cartId, case-insensitive |
| `PaymentsTicketingTestApplication.java` | `payments-ticketing` | — | Test bootstrap `@SpringBootApplication` for `@WebMvcTest` context |
| `InternalPaymentControllerTest.java` | `payments-ticketing` | 4 | GET `/internal/payments/by-cart/{cartId}/status` — 200 SUCCEEDED, 200 PENDING, 404 no booking, 404 no payment |

**Notable test patterns used:**
- `mockStatic(PaymentIntent.class)` / `mockStatic(Refund.class)` / `mockStatic(Webhook.class)` — Mockito 5 static mocking for Stripe SDK
- Raw types + `doReturn().when()` pattern with `@SuppressWarnings({"unchecked","rawtypes"})` — avoids `RestClient.RequestHeadersUriSpec<?>` wildcard type capture error
- `@TestConfiguration` static `MockBeans` class providing `JwtTokenProvider` + `JwtAuthenticationFilter` — required by any `@WebMvcTest` that imports `SecurityConfig`

---

### `admin` Module Test Fix

`OrgEventbriteControllerTest` imported `SecurityConfig.class` but did not provide `JwtAuthenticationFilter` as a mock bean, causing:
```
APPLICATION FAILED TO START
Parameter 0 of constructor in SecurityConfig required a bean of type
'JwtAuthenticationFilter' that could not be found.
```

**Fix:** Added `@TestConfiguration` static `MockBeans` class with `JwtTokenProvider` and `JwtAuthenticationFilter` mock beans, and updated `@Import` to include `OrgEventbriteControllerTest.MockBeans.class`.

**Key files:**
- [admin/src/test/java/com/eventplatform/admin/api/OrgEventbriteControllerTest.java](admin/src/test/java/com/eventplatform/admin/api/OrgEventbriteControllerTest.java)

---

## Final Test Results

```
MODULE               TESTS   RESULT
shared               27      ✅ PASS
discovery-catalog    19      ✅ PASS
identity             33      ✅ PASS
scheduling           50      ✅ PASS
booking-inventory    87      ✅ PASS
payments-ticketing    4      ✅ PASS
admin                 8      ✅ PASS
──────────────────────────────────────
TOTAL               228      ✅ BUILD SUCCESS
```

---

## Architecture Invariants Maintained

All changes comply with the hard rules in `PRODUCT.md`:

1. **No cross-module entity imports** — `PaymentStatusClient` in `booking-inventory` calls `payments-ticketing` via REST (`/internal/payments/…`), not via Spring bean injection.
2. **All Stripe calls through `shared/` ACL** — `StripePaymentService`, `StripeRefundService`, and `StripeWebhookHandler` are in `shared/stripe/`; no module calls `PaymentIntent.create()` directly.
3. **ACL facade names unchanged** — Only new Stripe facades were added; all 10 existing Eventbrite facade names remain exactly as defined in `PRODUCT.md`.
4. **Spring Events carry only primitives/IDs** — `CartAssembledEvent` new fields are `long` (primitive), `String`; `BookingCancelledEvent` fields are `Long`, `List<Long>`, `String`.
5. **One `GlobalExceptionHandler`** — `StripeIntegrationException` and `StripeWebhookSignatureException` handling added to the existing handler in `shared/common/exception/`.
6. **`admin/` owns no `@Entity`** — No new entities added to admin module.
7. **Dependency direction preserved** — `payments-ticketing` depends on `shared` + `booking-inventory` (REST reads only); it does not import `booking-inventory` Spring beans.
