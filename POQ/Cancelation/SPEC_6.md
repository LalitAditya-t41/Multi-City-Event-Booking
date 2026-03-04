# SPEC_6.md — FR6: Individual Ticket Cancellation + Event-Level Cancellation Refunds

**Owner:** payments-ticketing  
**Module(s):** `payments-ticketing` (primary), `admin/` (orchestration stub), `scheduling` (slot cancel source), `booking-inventory` (seat release consumer), `shared/stripe/` (refund ACL)  
**Flow:** Flow 6 — Cancellation Policy Definition → Individual Item Cancel → Full Booking Cancel (Policy-driven) → Organizer Event Cancel → Batch Refund Stub  
**Last Updated:** March 5, 2026  
**Status:** Stages 1–8 Complete — Ready for implementation planning

**Depends on:**
- `SPEC_5.md` (FR5) — booking/payment/refund/ticket baseline
- `SPEC_4.md` (FR4) — cart assembly and seat lock lifecycle
- `SPEC_3.MD` (FR3) — JWT auth/ownership enforcement
- `SPEC_2.md` (FR2) — scheduling owns slot lifecycle and publishes slot cancellation signals

**Scope Clarification (Requested):**
- Includes both cancellation cases:
  1) **Individual ticket/item cancellation** within a booking
  2) **Entire event cancellation** (organizer/admin initiated)
- **Wallet credit is explicitly out of scope** for this flow.
- Cancellation policy must include **window**, **refund percentage**, and **refund value computation**.
- Event-cancel refund processing is **stubbed** for now (integration-safe placeholder design).

---

## Stage 1 — Requirements

### Area A — Cancellation Policy Management

- `[MUST]` System must support a DB-backed `CancellationPolicy` per organization.
- `[MUST]` Policy must be tier-based with cancellation windows and percentages.
- `[MUST]` Each tier defines: `hoursBeforeEvent` + `refundPercent`.
- `[MUST]` Refund amount for cancellation must be derived from policy tiers and booking/item amount.
- `[MUST]` A system default policy must exist as fallback when org policy is absent.
- `[MUST]` Policy create/update endpoints must be admin-only.
- `[SHOULD]` Policy retrieval endpoint should always return effective policy (org or fallback default).
- `[WONT]` Per-seat, per-ticket-class, or wallet-credit-specific policy rules in FR6.

### Area B — Individual Ticket/Item Cancellation (Buyer-Initiated)

- `[MUST]` Buyer can cancel one or more `BookingItem`s from a `CONFIRMED` booking.
- `[MUST]` Item cancel must support partial cancellation (remaining items continue as active).
- `[MUST]` Cancelled item(s) must transition `BookingItem.status: ACTIVE → CANCELLED`.
- `[MUST]` Related e-ticket(s) must transition `ETicket.status: ACTIVE → VOIDED`.
- `[MUST]` Refund must be policy-driven using applicable cancellation window and percentage.
- `[MUST]` If all items are cancelled via item-cancel flow, booking must end in `CANCELLED`.
- `[MUST]` Duplicate cancellation for same active item must be prevented.
- `[MUST]` `BookingCancelledEvent` must publish only cancelled `seatIds` for seat release.
- `[WONT]` Wallet credit payout route for buyer cancellation in FR6.

### Area C — Full Booking Cancellation (Buyer-Initiated)

- `[MUST]` Existing booking cancel path remains available.
- `[MUST]` Full cancel must use `CancellationPolicy` (not static config fee-percent).
- `[MUST]` If applicable refund percentage is 0, booking still cancels and tickets are voided.
- `[MUST]` Zero-refund case must persist a refund record with `amount=0` and terminal success semantics.

### Area D — Event-Level Cancellation (Organizer/Admin-Initiated)

- `[MUST]` Admin/organizer event cancellation starts in scheduling/admin flow and emits slot/event cancellation signal.
- `[MUST]` `payments-ticketing` must consume cancellation signal and process all affected confirmed bookings.
- `[MUST]` Event-level cancellation refunds must be **100%**, regardless of buyer policy windows.
- `[MUST]` Per-booking event-cancel handling must cancel booking, cancel items, void tickets, and release seats.
- `[MUST]` Event cancellation batch processing must tolerate per-booking failures (continue processing others).
- `[MUST]` Event-cancel Stripe refund execution is stubbed in FR6 with explicit TODO seam.
- `[WONT]` Organizer notification campaign workflows and wallet credits in FR6.

### Area E — Integration & Boundary Rules

- `[MUST]` Modules cannot call Stripe SDK directly outside shared ACL (`StripeRefundService`).
- `[MUST]` No cross-module entity/service imports.
- `[MUST]` Events contain only primitives/IDs/value fields, no entities.
- `[MUST]` Admin module stays orchestration-only (no domain tables).

---

## Stage 2 — Domain Model

### Core Aggregate Behavior

A `Booking` can be partially cancelled at item granularity while remaining `CONFIRMED` if at least one item stays active. If all items become cancelled, booking reaches `CANCELLED`. Refunds are policy-driven for buyer-initiated cancels and always full for event-level cancel.

### Entities

**Existing (extended):**

- **`Booking`**
  - Add `orgId` (for policy resolution)
  - Add `slotStartTime` (cached at booking creation)
  - Lifecycle remains: `PENDING → CONFIRMED → CANCELLATION_PENDING → CANCELLED`

- **`BookingItem`**
  - Lifecycle: `ACTIVE → CANCELLED`
  - Cancellation now independently addressable by item ID

- **`ETicket`**
  - Lifecycle: `ACTIVE → VOIDED`

- **`Refund`**
  - Add cancellation source type: `BUYER_PARTIAL`, `BUYER_FULL`, `EVENT_CANCELLED`

- **`CancellationRequest`**
  - Extended to support item-level tracking (`bookingItemId` nullable for backward compatibility)
  - Lifecycle: `PENDING → APPROVED | REJECTED`

**New:**

- **`CancellationPolicy`**
  - Fields: `id`, `orgId` (nullable for system default), `scope`, timestamps

- **`CancellationPolicyTier`**
  - Fields: `policyId`, `hoursBeforeEvent`, `refundPercent`, `sortOrder`

- **`EventCancellationRefundAudit`** (stub-focused operational record)
  - Tracks each affected booking during event-level cancel batch run
  - Fields: `slotId`, `bookingId`, `status`, `errorMessage`, `processedAt`

### Non-Persisted Value Objects

- `RefundCalculationResult(refundPercent, refundAmountInSmallestUnit, tierLabel)`
- `EventCancellationBatchContext(slotId, orgId, affectedBookingCount)`

### Cross-Module References (IDs only)

| Field | Points To | Module |
|---|---|---|
| `Booking.userId` | users.id | identity |
| `Booking.cartId` | carts.id | booking-inventory |
| `Booking.slotId` | show_slots.id | scheduling |
| `Booking.orgId` | organization id | identity/scheduling context |
| `BookingItem.seatId` | seats.id | booking-inventory |

---

## Stage 3 — Architecture & File Structure

### Module Ownership

- **Primary ownership:** `payments-ticketing`
- **Admin trigger (stub endpoint):** `admin/`
- **Slot cancellation source of truth:** `scheduling`
- **Seat release consumer:** `booking-inventory`

### Interface vs HTTP Calls Plan (Hotspot Prevention)

#### 1) Use Interface (in-process abstraction) where read is frequent and local-contract stable

- Use shared reader interface for payment confirmation checks already established in FR5 (`PaymentConfirmationReader`).
- Use domain services/repositories in `payments-ticketing` for cancellation execution and policy resolution.

#### 2) Use HTTP only for cross-module command/read boundaries that are not hot-loop paths

- `admin/` → `scheduling` cancel command via REST (single command per admin action).
- No per-item or per-booking hot-loop HTTP calls inside cancellation batch.

#### 3) Hotspot assessment

- **Expected hotspot risk in FR6 is low** because cancellation paths primarily operate on `payments-ticketing` local DB state.
- Potential throughput concentration point is event-level batch refund fan-out to Stripe.
- Mitigation in FR6 design:
  - Process booking refunds independently (failure isolation)
  - Add audit table for retries/visibility
  - Keep Stripe integration behind one service seam (`StripeRefundService`)
- Conclusion: **no architectural hotspot in module communication**; only external refund throughput sensitivity (expected and controllable).

### New/Changed Files (planned)

```text
payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/

domain/
├── CancellationPolicy.java
├── CancellationPolicyTier.java
├── EventCancellationRefundAudit.java
└── enums/
    ├── CancellationPolicyScope.java
    └── RefundCancellationType.java

repository/
├── CancellationPolicyRepository.java
├── CancellationPolicyTierRepository.java
└── EventCancellationRefundAuditRepository.java

service/
├── CancellationPolicyService.java
├── EventCancellationRefundService.java
└── CancellationService.java (extend)

event/listener/
└── ShowSlotCancelledListener.java

api/controller/
├── CancellationPolicyController.java
└── BookingCancellationController.java (extend existing booking controller if preferred)

api/dto/request/
├── CancelItemsRequest.java
└── CancellationPolicyRequest.java

api/dto/response/
├── CancelItemsResponse.java
├── CancellationQuoteResponse.java
└── CancellationPolicyResponse.java

mapper/
└── CancellationPolicyMapper.java

exception/
├── CancellationPolicyNotFoundException.java
└── DuplicateItemCancellationException.java
```

```text
admin/src/main/java/.../api/controller/
└── AdminSlotCancelController.java (stub orchestration endpoint)
```

---

## Stage 4 — DB Schema

### New Tables

#### `cancellation_policies`

```sql
cancellation_policies (
  id                  BIGSERIAL PRIMARY KEY,
  org_id              BIGINT,
  scope               VARCHAR(30) NOT NULL,   -- ORG | SYSTEM_DEFAULT
  created_by_admin_id BIGINT,
  created_at          TIMESTAMPTZ DEFAULT NOW(),
  updated_at          TIMESTAMPTZ DEFAULT NOW()
)
INDEXES: idx_cancellation_policies_org_id
CONSTRAINTS:
  - uq_cancellation_policies_org_id UNIQUE (org_id) WHERE org_id IS NOT NULL
  - uq_cancellation_policies_default UNIQUE (scope) WHERE scope = 'SYSTEM_DEFAULT'
```

#### `cancellation_policy_tiers`

```sql
cancellation_policy_tiers (
  id                 BIGSERIAL PRIMARY KEY,
  policy_id          BIGINT NOT NULL REFERENCES cancellation_policies(id),
  hours_before_event INTEGER,
  refund_percent     INTEGER NOT NULL,
  sort_order         INTEGER NOT NULL,
  created_at         TIMESTAMPTZ DEFAULT NOW()
)
INDEXES: idx_policy_tiers_policy_id, idx_policy_tiers_sort_order
CONSTRAINTS:
  - chk_refund_percent_range CHECK (refund_percent >= 0 AND refund_percent <= 100)
  - uq_policy_tier_sort UNIQUE (policy_id, sort_order)
```

#### `event_cancellation_refund_audits`

```sql
event_cancellation_refund_audits (
  id             BIGSERIAL PRIMARY KEY,
  slot_id        BIGINT NOT NULL,
  booking_id     BIGINT NOT NULL REFERENCES bookings(id),
  refund_id      BIGINT REFERENCES refunds(id),
  status         VARCHAR(30) NOT NULL, -- PENDING | SUCCEEDED | FAILED
  error_message  TEXT,
  processed_at   TIMESTAMPTZ,
  created_at     TIMESTAMPTZ DEFAULT NOW()
)
INDEXES: idx_event_cancel_audit_slot_id, idx_event_cancel_audit_booking_id
CONSTRAINTS:
  - uq_event_cancel_slot_booking UNIQUE (slot_id, booking_id)
```

### Alter Existing Tables

#### `bookings`

```sql
ALTER TABLE bookings
  ADD COLUMN org_id BIGINT,
  ADD COLUMN slot_start_time TIMESTAMPTZ;

CREATE INDEX idx_bookings_org_id ON bookings(org_id);
CREATE INDEX idx_bookings_slot_start_time ON bookings(slot_start_time);
```

#### `refunds`

```sql
ALTER TABLE refunds
  ADD COLUMN cancellation_type VARCHAR(30);
```

#### `cancellation_requests`

```sql
ALTER TABLE cancellation_requests
  ADD COLUMN booking_item_id BIGINT REFERENCES booking_items(id);

CREATE UNIQUE INDEX uq_cancellation_requests_item_pending
  ON cancellation_requests(booking_item_id)
  WHERE booking_item_id IS NOT NULL AND status = 'PENDING';
```

### Migrations

- `V33__create_cancellation_policy_tables.sql`
- `V34__extend_booking_refund_cancellation_request_for_fr6.sql`
- `V35__create_event_cancellation_refund_audit.sql`

### Seed Data (V33)

System default tiers (example baseline):
- `>=72h`: `100%`
- `>=24h and <72h`: `50%`
- `<24h`: `0%`

---

## Stage 5 — API

### Buyer APIs (`payments-ticketing`)

### 1) `POST /api/v1/bookings/{bookingRef}/cancel-items`

**Auth:** JWT `ROLE_USER` (owner-only)

**Request:**
```json
{
  "bookingItemIds": [101, 102],
  "reason": "REQUESTED_BY_CUSTOMER"
}
```

**Response `200 OK`:**
```json
{
  "bookingRef": "BK-20260305-001",
  "bookingStatus": "CONFIRMED",
  "cancelledItemIds": [101, 102],
  "refund": {
    "type": "BUYER_PARTIAL",
    "percent": 50,
    "amount": 75000,
    "currency": "inr",
    "status": "PENDING"
  }
}
```

**Errors:**
- `404 BOOKING_NOT_FOUND`
- `409 CANCELLATION_NOT_ALLOWED`
- `409 DUPLICATE_ITEM_CANCELLATION`
- `400 INVALID_CANCEL_ITEMS_REQUEST`
- `502 REFUND_FAILED`

---

### 2) `GET /api/v1/bookings/{bookingRef}/cancellation-quote?bookingItemIds=101,102`

**Auth:** JWT `ROLE_USER` (owner-only)

**Response `200 OK`:**
```json
{
  "bookingRef": "BK-20260305-001",
  "policyTier": "24_TO_72_HOURS",
  "refundPercent": 50,
  "requestedItemsAmount": 150000,
  "refundAmount": 75000,
  "currency": "inr"
}
```

---

### 3) `POST /api/v1/bookings/{bookingRef}/cancel`

(Existing FR5 endpoint, now policy-driven in FR6)

**Auth:** JWT `ROLE_USER` (owner-only)

**Behavior change:** Refund percent/value derived from `CancellationPolicyService`.

---

### Admin APIs (`payments-ticketing`)

### 4) `POST /api/v1/admin/cancellation-policies`

**Auth:** JWT `ROLE_ADMIN`

**Request:**
```json
{
  "orgId": 88,
  "tiers": [
    { "hoursBeforeEvent": 72, "refundPercent": 100, "sortOrder": 1 },
    { "hoursBeforeEvent": 24, "refundPercent": 50, "sortOrder": 2 },
    { "hoursBeforeEvent": null, "refundPercent": 0, "sortOrder": 3 }
  ]
}
```

**Response `201 Created`:** policy payload with persisted IDs.

---

### 5) `PUT /api/v1/admin/cancellation-policies/{policyId}`

Replace policy tiers atomically.

---

### 6) `GET /api/v1/admin/cancellation-policies/{policyId}`

Read policy + tiers.

---

### 7) `GET /api/v1/admin/cancellation-policies?orgId=88`

Returns effective org policy or system default.

---

### Admin Orchestration Stub (`admin/` module)

### 8) `POST /api/v1/admin/slots/{slotId}/cancel`

**Auth:** JWT `ROLE_ADMIN` (organizer/manager)

**Behavior:** Delegates to scheduling cancellation API. Scheduling emits `ShowSlotCancelledEvent`; FR6 listener handles downstream refunds.

---

## Stage 6 — Business Logic

### 6.1 Policy Resolution (`CancellationPolicyService`)

1. Resolve effective policy:
   - org policy by `orgId`
   - fallback: system default
   - final fallback: hardcoded 100%
2. Compute `hoursUntilEvent` using booking `slotStartTime`.
3. Match first tier in `sortOrder` where threshold applies.
4. Compute:
   - `refundPercent`
   - `refundAmount = floor(baseAmount * refundPercent / 100)`
5. Return `RefundCalculationResult`.

### 6.2 Cancellation Quote (`computeQuote`)

1. Validate booking ownership + status.
2. Validate item IDs are in booking and `ACTIVE`.
3. Sum selected item prices.
4. Compute refund via policy service.
5. Return quote DTO with tier/percent/value.
6. No DB mutation, no Stripe calls.

### 6.3 Individual Item Cancellation (`cancelItems`)

1. Validate owner + booking in `CONFIRMED`.
2. Validate each requested item is `ACTIVE` and belongs to booking.
3. Reject if any item already has pending cancellation request.
4. Compute refund percent/value from policy using selected-item amount.
5. Create item-level `CancellationRequest` rows (`PENDING`).
6. Set booking to `CANCELLATION_PENDING` only if all active items are being cancelled; else keep `CONFIRMED`.
7. Refund execution:
   - if computed refund amount > 0: call `StripeRefundService.createRefund(...)`
   - if amount = 0: mark local refund success without Stripe call
8. On successful refund (or zero-value auto-success):
   - mark selected `BookingItem` as `CANCELLED`
   - mark related `ETicket` as `VOIDED`
   - update `CancellationRequest` to `APPROVED`
   - persist `Refund(type=BUYER_PARTIAL)`
9. Recompute booking total from remaining active items.
10. If no active items remain:
   - set `Booking.status = CANCELLED`
11. Publish `BookingCancelledEvent` with only cancelled seat IDs.

### 6.4 Full Booking Cancellation (`cancel` from FR5, updated)

1. Validate owner + status `CONFIRMED`.
2. Compute policy-based refund percent/value on full booking amount.
3. If refund amount > 0 → Stripe refund call.
4. If refund amount = 0 → local terminal refund record; no Stripe call.
5. Finalize cancellation:
   - booking cancelled
   - all booking items cancelled
   - all tickets voided
   - refund persisted as `BUYER_FULL`
6. Publish `BookingCancelledEvent` with all seat IDs.

### 6.5 Event-Level Cancellation Listener (Stubbed Refund Execution)

**Trigger:** `ShowSlotCancelledEvent(slotId, ebEventId, orgId, venueId, cityId)`

1. Find all `CONFIRMED` bookings for the slot.
2. For each booking (independent unit-of-work):
   - create audit row `PENDING`
   - set booking `CANCELLATION_PENDING`
   - compute event-cancel refund amount = full booking amount (100%)
   - invoke stub seam (`EventCancellationRefundService`) for refund execution
   - on success:
     - persist refund (`type=EVENT_CANCELLED`)
     - finalize booking/items/tickets to cancelled
     - audit `SUCCEEDED`
     - publish `BookingCancelledEvent`
   - on failure:
     - audit `FAILED` with reason
     - continue next booking
3. Optional summary event publication after run.

### 6.6 Event-Cancel Stub Contract

`EventCancellationRefundService.processEventRefunds(slotId)`

- Current implementation:
  - creates orchestration/audit scaffolding
  - performs no production Stripe network loop by default in FR6
  - leaves clear seam/TODO for full implementation
- Purpose: safely validate architecture and downstream event behavior before enabling real batch refund traffic.

### 6.7 Interface vs HTTP Decisions (Final)

- `admin/` to `scheduling` uses HTTP (command boundary).
- `payments-ticketing` cancellation execution uses internal services + repositories.
- Slot start time needed for policy evaluation is read from cached `Booking.slotStartTime` (set during booking creation), avoiding cancellation-time remote calls.
- No hotspot expected in inter-module communication; external Stripe throughput remains the only scale-sensitive edge.

---

## Stage 7 — Error Handling

### Policy APIs

- `CANCELLATION_POLICY_NOT_FOUND` → `404`
- `DUPLICATE_ORG_POLICY` → `409`
- `INVALID_POLICY_TIER_CONFIG` (range/order/overlap) → `400`

### Quote + Item Cancel

- `BOOKING_NOT_FOUND` (including non-owner masking) → `404`
- `CANCELLATION_NOT_ALLOWED` (wrong booking/item status) → `409`
- `DUPLICATE_ITEM_CANCELLATION` → `409`
- `INVALID_CANCEL_ITEMS_REQUEST` (empty or malformed) → `400`
- `REFUND_FAILED` (Stripe call failure) → `502`

### Event-Cancel Batch

- Per-booking refund failure does not fail whole batch.
- Failed bookings are persisted to audit with reason.
- Idempotency via `(slot_id, booking_id)` unique audit key.
- Duplicate slot cancel signals must short-circuit succeeded booking rows.

### Stripe/Webhook

- Existing FR5 webhook signature/unknown-event handling remains unchanged.
- `refund.failed` keeps booking in pending/needs-manual-review path if finalization not completed.

---

## Stage 8 — Tests

### Domain Tests

- `CancellationPolicy` tier selection and percent boundaries.
- Refund value computation rounding/floor semantics.
- Booking/item/ticket state transitions for partial/full/event cancellation.

### Service Tests

- `CancellationPolicyServiceTest` for org/default/fallback resolution.
- `CancellationService.cancelItems` happy path, zero-refund path, invalid item path, duplicate request path, Stripe failure rollback path.
- `CancellationService.cancel` full-cancel policy-driven flow.
- `EventCancellationRefundService` batch continuation and audit semantics with mixed success/failure outcomes.

### API Tests (`@WebMvcTest`)

- `POST /cancel-items` success + each major error.
- `GET /cancellation-quote` quote correctness and ownership checks.
- Admin policy CRUD endpoint auth + validation.
- `POST /admin/slots/{slotId}/cancel` stub orchestration response mapping.

### Arch Tests

- No cross-module service/entity imports in `payments-ticketing`.
- No direct `com.stripe.*` usage outside shared stripe ACL.
- No module-level `@ControllerAdvice` in `payments-ticketing`.

---

## Open Questions

1. Should event-level cancellation processing be synchronous request-triggered or async queued worker once Stripe execution is enabled?
2. Should `cancellation_requests.booking_item_id` become NOT NULL in a later migration after old booking-level records are backfilled?
3. Do we need per-org default policy bootstrapping at org creation time, or rely solely on global fallback?
4. Should refund quote endpoint return both pre-fee and post-policy values for UI transparency?

---

## Non-Goals (Explicit)

- Wallet credit routes and wallet refund ledgering
- Organizer notification campaigns
- Eventbrite refund submission (not available for this payment architecture)
- Admin domain ownership of booking/payment data
