User: Now create a SPEC_6.md for functional flow 6 the functional flow 6 needs to handle the creation and cancellation of individual ticket and also when an entire event is cancelled both the cases. For now the wallet credit can be skipped . Need to have the cancelation window, the percentage and value to be created and teh policies also to be mentioned in the buisness logic. If the event gets canceled the refund needs to be initiated the admin(Organizer/manager) cancels we will stub these as of now. Also plan where to use the interface and where to use the HTTP calls so there is no hotspot, i dont think there will be a hotspot in this if it is there do mention

GitHub Copilot: Let me read the key reference files before drafting the spec.  

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_5.md#1-1), lines 1 to 120

Read [](file:///home/dell/Multi-City-Event-Booking/agents/CODING_AGENT.md#1-1), lines 1 to 80

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_5.md#120-120), lines 120 to 400

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java)

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_5.md#400-400), lines 400 to 650

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform)

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_5.md#650-650), lines 650 to 900

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/paymentsticketing)

Now let me check the scheduling and shared event structures to complete the cross-module picture.  

Read [](file:///home/dell/Multi-City-Event-Booking/scheduling/src/main/java/com/eventplatform/scheduling)

Searched for files matching `**/shared/src/main/java/com/eventplatform/shared/common/event/published/*.java`, 12 matches

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/ShowSlotCancelledEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_5.md#900-900), lines 900 to 1074

I have everything I need. Let me now author the full SPEC_6.md in one pass. 

Using "Create File"

User: Lets go stage by stage

GitHub Copilot: I have all the context I need. Starting Stage 1 — drafting the **Requirements outline** first for confirmation before writing full content.

---

## SPEC_6.md — FR6: Cancellation Policies → Individual Ticket Cancel → Event-Level Cancel & Refund

### Stage 1 — Requirements Outline

---

**Area A — Cancellation Policy Definition**
- `[MUST]` Define `CancellationPolicy` as a DB-backed entity per org — not a flat `application.yaml` value (supersedes FR5's `fee-percent` config)
- `[MUST]` Policy contains: `windowTiers` — ordered list of `(hoursBeforeEvent, refundPercent)` e.g. `>72h → 100%`, `24–72h → 50%`, `<24h → 0%`
- `[MUST]` A default system-wide policy exists as fallback if no org-specific policy is configured
- `[MUST]` Policy can be created/updated by admin only
- `[SHOULD]` Policy is attached at the org level; future: per-event override (out of scope now)
- `[WONT]` Per-seat-tier or per-ticket-class policy granularity

---

**Area B — Individual Ticket Cancellation (Buyer-Initiated, Partial)**
- `[MUST]` Buyer can cancel one or more specific `BookingItem`s within a `CONFIRMED` booking (not necessarily the entire booking)
- `[MUST]` After partial cancel: remaining items stay `ACTIVE`; booking stays `CONFIRMED` with updated `totalAmount`; cancelled items get status `CANCELLED`; their `ETicket`s get `VOIDED`
- `[MUST]` After full partial cancel (all items cancelled): booking transitions to `CANCELLED`
- `[MUST]` Refund amount = sum of `unitPrice` of cancelled items × applicable `refundPercent` from policy window
- `[MUST]` Only one active `CancellationRequest` per `BookingItem` at a time — reject duplicate
- `[MUST]` Seats released back to `AVAILABLE` in booking-inventory via `BookingCancelledEvent` (carry cancelled `seatIds` only)
- `[SHOULD]` Individual ticket cancel endpoint: `POST /api/v1/bookings/{bookingRef}/cancel-items`
- `[WONT]` Partial refund to wallet (wallet credit deferred as stated)

---

**Area C — Full Booking Cancellation (Buyer-Initiated — existing FR5 path, now policy-driven)**
- `[MUST]` Existing `POST /api/v1/bookings/{bookingRef}/cancel` now reads refund percent from `CancellationPolicy` instead of `application.yaml`
- `[MUST]` If refund percent = 0% (inside no-refund window) → Stripe refund amount = 0; still cancel booking and void tickets
- `[MUST]` Zero-refund cancellation still creates a `Refund` record with `amount=0` and `status=SUCCEEDED` immediately (no Stripe call needed)

---

**Area D — Event-Level Cancellation (Admin/Organizer-Initiated) — Stub**
- `[MUST]` Admin triggers event cancellation via `POST /api/v1/admin/slots/{slotId}/cancel` (admin-module, stub)
- `[MUST]` scheduling transitions `ShowSlot.status → CANCELLED` and publishes `ShowSlotCancelledEvent(slotId, ebEventId, orgId, venueId, cityId)` (event already exists in shared)
- `[MUST]` payments-ticketing listens to `ShowSlotCancelledEvent` → finds all `CONFIRMED` bookings for that `slotId` → triggers full refund (100%, regardless of policy window — event cancel is always full refund)
- `[MUST]` Each affected booking follows: `CONFIRMED → CANCELLATION_PENDING → CANCELLED`; each affected `ETicket → VOIDED`
- `[MUST]` Each affected booking produces a separate `Refund` record and a Stripe refund call
- `[MUST]` Stripe refund calls are executed in batches — not in a single transaction (one failure must not block others)
- `[MUST]` Stub the actual Stripe batch processing — `EventCancellationRefundService.processEventRefunds(slotId)` stubbed with `// TODO: implement batch Stripe refunds`
- `[MUST]` Publish one `BookingCancelledEvent` per affected booking after refund succeeds
- `[SHOULD]` Track per-booking refund outcome — `EventCancellationRefundAudit` record (stubbed entity)
- `[WONT]` Eventbrite `EbEventSyncService.cancelEvent()` call from payments-ticketing — that is owned by scheduling (already handled in FR2/SPEC_2.md)
- `[WONT]` Organizer-side notification and payout handling

---

**Area E — Cancellation Policy Enforcement (cross-cutting)**
- `[MUST]` `CancellationPolicyService.computeRefundPercent(orgId, slotStartTime)` resolves applicable tier by comparing `NOW()` to `slotStartTime`
- `[MUST]` Policy lookup order: org-specific policy → system default policy → hardcoded 100% fallback
- `[MUST]` `slotStartTime` is fetched once at cancellation time and cached on the `Booking` record at creation (fixes FR5 Open Question #3)

---

**Area F — Non-Functional**
- `[MUST]` All cancellation endpoints remain in payments-ticketing — no new cancellation logic in admin
- `[MUST]` Admin slot cancel endpoint in admin is a thin delegator — calls scheduling REST only
- `[MUST]` Follow module boundary rules — no direct imports across modules
- `[WONT]` Wallet credit (explicitly deferred)
- `[WONT]` Email notification delivery

---

> Does this look complete? Anything to add, remove, or change before I write Stage 1 in full?

User: Next stage 

GitHub Copilot: Stage 1 confirmed. Now drafting the **Stage 2 — Domain Model outline** for confirmation.

---

## Stage 2 — Domain Model Outline

Entities to add/extend, with one-line descriptions and relationships:

---

**New Entities**

| Entity | One-line description | Key Relationships |
|---|---|---|
| `CancellationPolicy` | Org-scoped refund window tiers — the source of truth for all refund percent calculations | Belongs to one org (via `orgId`); has many `CancellationPolicyTier` |
| `CancellationPolicyTier` | One window tier row: `hoursBeforeEvent`, `refundPercent`, `sortOrder` | Belongs to one `CancellationPolicy` |
| `EventCancellationRefundAudit` | Tracks per-booking outcome when an entire event is cancelled (stub entity) | Belongs to one `Booking`; references `slotId` |

---

**Extended Entities (from FR5)**

| Entity | Extension | Why |
|---|---|---|
| `Booking` | Add `slotStartTime TIMESTAMPTZ` column | Cache it at booking creation so cancellation path never does a cross-module REST call for it |
| `Booking` | Add `orgId BIGINT` column | Needed to resolve `CancellationPolicy` at cancel time |
| `BookingItem` | No schema change — cancellation is already `status=CANCELLED`; new `CancellationRequest` per item | Partial cancel requires one `CancellationRequest` per item, not one per booking |
| `CancellationRequest` | Existing entity — scope changes: one per `BookingItem` (not one per `Booking`) | FR5 modelled it at booking level; FR6 needs item-level granularity |
| `Refund` | Add `cancellationType` column: `BUYER_PARTIAL`, `BUYER_FULL`, `EVENT_CANCELLED` | Needed for audit/reporting to distinguish refund origin |

---

**Non-Persisted Concepts**

| Concept | Description |
|---|---|
| `RefundCalculationResult` | Value object (record): `refundPercent`, `refundAmountInSmallestUnit`, `windowTierLabel` — computed by `CancellationPolicyService`, passed to `CancellationService` |
| `EventCancellationBatch` | In-memory grouping: list of `Booking` records for a `slotId`; iterated by `EventCancellationRefundService`; never persisted |

---

**Cross-module ID references added to `Booking`**

| New field | Points to | Module |
|---|---|---|
| `Booking.orgId` | `organizations.id` | identity |
| `Booking.slotStartTime` | cached from `show_slots.start_time` | scheduling (cached, no live FK) |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 2 in full?

User: next stage 

GitHub Copilot: Stage 2 confirmed. Drafting the **Stage 3 — Architecture & File Structure outline** for confirmation.

---

## Stage 3 — Architecture & File Structure Outline

---

### Module ownership

| Module | Role in FR6 |
|---|---|
| payments-ticketing | **Primary** — cancellation policy enforcement, individual ticket cancel, full cancel, event-cancel listener, refund orchestration |
| scheduling | Publishes `ShowSlotCancelledEvent` on admin-triggered slot cancel (already exists from FR2) |
| admin | Thin stub endpoint `POST /api/v1/admin/slots/{slotId}/cancel` — delegates to scheduling REST only; no business logic |
| booking-inventory | Event consumer — receives `BookingCancelledEvent` to release seats (listener already exists from FR5, carries `seatIds`) |
| `shared/common/event/published/` | `ShowSlotCancelledEvent` already exists; need **one new event**: `EventCancellationInitiatedEvent` — optional, for audit fanout |
| `shared/common/service/` | No new reader interfaces needed (explained below) |

---

### Interface vs HTTP call decision

This is the key architectural question for FR6. Here is the full breakdown:

| Data Needed | From Module | Mechanism | Rationale |
|---|---|---|---|
| `slotStartTime` at cancellation | scheduling | **Cached on `Booking.slotStartTime`** at booking creation (FR5's booking confirm step) — no live call needed | Hot path: every cancel reads this. Caching on `Booking` eliminates the cross-module call entirely. No hotspot. |
| `orgId` at cancellation | scheduling / `CartAssembledEvent` | **Cached on `Booking.orgId`** at booking creation — already in `CartAssembledEvent` payload | Same reasoning — already available at booking time. |
| All `CONFIRMED` bookings for a `slotId` on event cancel | internal payments-ticketing | **Internal DB query** (`bookingRepository.findAllBySlotIdAndStatus(slotId, CONFIRMED)`) | Same module — direct repo access, no cross-module call. |
| Cart items to build `BookingItem`s | booking-inventory | **REST call** `GET /api/v1/booking/carts/{cartId}/items` | Only needed once at booking confirmation (FR5 path). Not on cancellation path. Not a hotspot. |
| Seat IDs to release on cancel | internal payments-ticketing | **Internal query** — `bookingItemRepository.findSeatIdsByBookingId(bookingId)` on `BookingItem.seatId` | Already in payments-ticketing DB. No cross-module call. |

**Conclusion: No new shared reader interfaces needed for FR6.** No hotspot identified — the cancellation path reads only from payments-ticketing's own DB. The event-cancel batch path iterates bookings one at a time; Stripe calls are per-booking and stubbed, so no fan-out concurrency issue within a single transaction.

---

### New files to create

```
payments-ticketing/
├── domain/
│   ├── CancellationPolicy.java                  ← new entity
│   ├── CancellationPolicyTier.java               ← new entity
│   ├── EventCancellationRefundAudit.java         ← new stub entity
│   └── enums/
│       ├── CancellationPolicyScope.java          ← ORG, SYSTEM_DEFAULT
│       └── RefundCancellationType.java           ← BUYER_PARTIAL, BUYER_FULL, EVENT_CANCELLED

├── repository/
│   ├── CancellationPolicyRepository.java
│   └── EventCancellationRefundAuditRepository.java

├── service/
│   ├── CancellationPolicyService.java            ← resolves refund % from policy tiers
│   └── EventCancellationRefundService.java       ← stubbed batch refund processor for event cancel

├── event/listener/
│   └── ShowSlotCancelledListener.java            ← listens ShowSlotCancelledEvent → triggers batch refund

├── api/controller/
│   └── CancellationPolicyController.java         ← admin CRUD for policies

├── api/dto/request/
│   ├── CancelItemsRequest.java                   ← { bookingItemIds: [1,2,3], reason }
│   └── CancellationPolicyRequest.java            ← admin create/update policy

├── api/dto/response/
│   ├── CancelItemsResponse.java                  ← updated booking + per-item refund breakdown
│   └── CancellationPolicyResponse.java

├── mapper/
│   └── CancellationPolicyMapper.java

└── exception/
    ├── CancellationPolicyNotFoundException.java
    └── DuplicateItemCancellationException.java
```

**Modified existing files:**
- `domain/Booking.java` — add `slotStartTime`, `orgId` fields
- `domain/enums/RefundReason.java` — add `EVENT_CANCELLED`
- `domain/Refund.java` — add `cancellationType` field
- `domain/CancellationRequest.java` — scope to item level (add `bookingItemId` FK)
- `service/CancellationService.java` — add `cancelItems()` method; wire `CancellationPolicyService`
- `service/BookingService.java` — cache `slotStartTime` + `orgId` at booking creation

**New shared event (optional — for audit fanout):**
- `shared/common/event/published/EventCancellationInitiatedEvent.java` — `(slotId, orgId, affectedBookingCount)`

**Admin stub (new file):**
- `admin/src/.../api/controller/AdminSlotCancelController.java` — `POST /api/v1/admin/slots/{slotId}/cancel` → REST call to scheduling

---

> Does this look complete? Anything to add, remove, or change before I write Stage 3 in full?

User: next stage 

GitHub Copilot: Stage 3 confirmed. Drafting the **Stage 4 — DB Schema outline** for confirmation.

---

## Stage 4 — DB Schema Outline

Table names with key fields only (no types, indexes, or constraints yet).

---

### New Tables

**`cancellation_policies`** — V33
- `id`, `org_id` (unique — one policy per org; null = system default), `scope` (`ORG` | `SYSTEM_DEFAULT`), `created_by_admin_id`, `created_at`, `updated_at`

**`cancellation_policy_tiers`** — V33
- `id`, `policy_id → cancellation_policies`, `hours_before_event` (upper bound of this tier — `NULL` = catch-all / earliest tier), `refund_percent` (0–100), `sort_order`
- Example rows for a policy: `sort_order=1: hours_before_event=72, refund_percent=100` | `sort_order=2: hours_before_event=24, refund_percent=50` | `sort_order=3: hours_before_event=NULL, refund_percent=0`

**`event_cancellation_refund_audits`** — V35 (stub)
- `id`, `slot_id`, `booking_id → bookings`, `refund_id → refunds` (nullable), `status` (`PENDING` | `SUCCEEDED` | `FAILED`), `error_message` (nullable), `processed_at`

---

### Modified Tables

**`bookings`** — V34
- Add `slot_start_time` — cached from scheduling at booking creation; used for policy window evaluation on cancel path
- Add `org_id` — cached from `CartAssembledEvent`; used to resolve `CancellationPolicy`

**`cancellation_requests`** — V34
- Add `booking_item_id → booking_items` (nullable for backward compat — existing full-booking requests have null here; new item-level requests populate it)
- Add unique constraint on `(booking_item_id)` where `booking_item_id IS NOT NULL` — prevents duplicate active cancellation per item

**`refunds`** — V34
- Add `cancellation_type` (`BUYER_PARTIAL` | `BUYER_FULL` | `EVENT_CANCELLED`)

---

### Migration plan

| Migration | Covers |
|---|---|
| V33 | Create `cancellation_policies` + `cancellation_policy_tiers`; seed system default policy (100%/50%/0% tiers) |
| V34 | Alter `bookings` (add `slot_start_time`, `org_id`); alter `cancellation_requests` (add `booking_item_id`); alter `refunds` (add `cancellation_type`) |
| V35 | Create `event_cancellation_refund_audits` (stub table) |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 4 in full?

User: next stage

GitHub Copilot: Stage 4 confirmed. Drafting the **Stage 5 — API outline** for confirmation.

---

## Stage 5 — API Outline

`METHOD /path` — one-line description

---

### Buyer-Facing (payments-ticketing)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/bookings/{bookingRef}/cancel-items` | Cancel one or more specific `BookingItem`s within a confirmed booking — partial refund per policy |
| `POST` | `/api/v1/bookings/{bookingRef}/cancel` | *(existing FR5)* Full booking cancel — now reads refund % from `CancellationPolicy` instead of `application.yaml` |
| `GET` | `/api/v1/bookings/{bookingRef}/cancellation-quote` | Dry-run — returns applicable policy tier, refund percent, and computed refund amount for a set of items WITHOUT actually cancelling; allows frontend to show "you will get back ₹X" before user confirms |

---

### Admin-Facing (payments-ticketing — policy management)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/admin/cancellation-policies` | Create a cancellation policy for an org (admin JWT `ROLE_ADMIN`) |
| `PUT` | `/api/v1/admin/cancellation-policies/{policyId}` | Replace all tiers for an existing policy |
| `GET` | `/api/v1/admin/cancellation-policies/{policyId}` | Read a single policy with its tiers |
| `GET` | `/api/v1/admin/cancellation-policies?orgId={orgId}` | Get active policy for an org (returns system default if no org-specific policy exists) |

---

### Admin-Facing (admin module — slot cancel stub)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/admin/slots/{slotId}/cancel` | Admin triggers show slot cancellation — delegates to scheduling REST `POST /api/v1/scheduling/slots/{slotId}/cancel`; scheduling publishes `ShowSlotCancelledEvent` |

---

### Internal / Cross-Module

| Method | Path | Description | Consumed By |
|---|---|---|---|
| *(none new)* | — | `slotStartTime` and `orgId` are cached on `Booking` at creation — no new REST read needed on cancel path | — |

---

### Events (not HTTP — listed for completeness)

| Event | Direction | Description |
|---|---|---|
| `ShowSlotCancelledEvent` | Consumed by payments-ticketing | Triggers batch refund for all confirmed bookings on that slot |
| `BookingCancelledEvent` | Published by payments-ticketing | Per-booking; consumed by booking-inventory (seat release) and `engagement` |
| `EventCancellationInitiatedEvent` *(optional)* | Published by payments-ticketing | One-shot after `ShowSlotCancelledEvent` processed; carries `slotId`, `orgId`, `affectedBookingCount` |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 5 in full?

User: next stage 

GitHub Copilot: Stage 5 confirmed. Drafting the **Stage 6 — Business Logic outline** for confirmation.

---

## Stage 6 — Business Logic Outline

Rules, state transitions, and side effects to be documented (not full prose yet).

---

### BL-1 — Policy Resolution (`CancellationPolicyService.computeRefundPercent`)
- Lookup order: org-specific policy → system default policy → hardcoded 100% last-resort
- `hoursUntilEvent = ChronoUnit.HOURS.between(NOW(), slotStartTime)`
- Tiers evaluated in ascending `sort_order`; first tier where `hoursUntilEvent >= hours_before_event` wins
- Last tier (`hours_before_event = NULL`) is the catch-all — always matches if no earlier tier matched
- Returns `RefundCalculationResult(refundPercent, refundAmountInSmallestUnit, tierLabel)`
- `refundAmountInSmallestUnit = floor(unitPriceSum * refundPercent / 100)`

---

### BL-2 — Cancellation Quote (`CancellationService.computeQuote`)
- Validate `bookingRef` ownership (same `userId` check as cancel)
- Validate all `bookingItemIds` belong to this booking and are `ACTIVE`
- Call `CancellationPolicyService.computeRefundPercent(orgId, slotStartTime, unitPriceSum)`
- Return `QuoteResponse` — **no DB writes, no Stripe call**
- If `hoursUntilEvent < 0` (event already started): refund percent = 0, include `PAST_EVENT` flag

---

### BL-3 — Individual Ticket Cancellation (`CancellationService.cancelItems`)
- Validate: booking `CONFIRMED`, all requested `bookingItemId`s are `ACTIVE` and belong to this booking, no existing `PENDING` `CancellationRequest` on any of them
- Compute `RefundCalculationResult` for sum of selected items' `unitPrice`s
- If `refundPercent = 0`: skip Stripe call; create `Refund(amount=0, status=SUCCEEDED, type=BUYER_PARTIAL)` directly
- If `refundPercent > 0`: call `StripeRefundService.createRefund(stripePaymentIntentId, refundAmount, REQUESTED_BY_CUSTOMER)`
- On success: create `CancellationRequest` per item; set each `BookingItem.status = CANCELLED`; void each item's `ETicket`
- Recompute `booking.totalAmount` = sum of remaining `ACTIVE` items' `unitPrice`s
- If all items are now `CANCELLED`: transition `booking.status = CANCELLED`; else booking stays `CONFIRMED`
- Publish `BookingCancelledEvent` carrying only the cancelled `seatIds`
- State transitions:
  - `BookingItem`: `ACTIVE → CANCELLED`
  - `ETicket`: `ACTIVE → VOIDED`
  - `Booking`: stays `CONFIRMED` (partial) OR `CONFIRMED → CANCELLATION_PENDING → CANCELLED` (all items)
  - `CancellationRequest` (per item): `PENDING → APPROVED`

---

### BL-4 — Full Booking Cancellation (`CancellationService.cancel` — FR5 updated)
- Same flow as FR5 §6.4, but replaces `fee-percent` config read with `CancellationPolicyService.computeRefundPercent(orgId, slotStartTime, totalAmount)`
- If `refundPercent = 0`: no Stripe call; `Refund(amount=0, status=SUCCEEDED, type=BUYER_FULL)` created immediately → call `finaliseAfterRefund()` inline
- All items cancelled, all tickets voided, `BookingCancelledEvent` published with all `seatIds`

---

### BL-5 — Event-Level Cancellation (`ShowSlotCancelledListener` → `EventCancellationRefundService`)
- **Trigger:** `ShowSlotCancelledEvent(slotId, ebEventId, orgId, venueId, cityId)` (async `@EventListener`)
- Step 1: Query `bookingRepository.findAllBySlotIdAndStatus(slotId, CONFIRMED)` — get all affected bookings
- Step 2: For each booking (processed one at a time — not bulk transaction):
  - Set `booking.status = CANCELLATION_PENDING`; create `EventCancellationRefundAudit(status=PENDING)`
  - **Refund percent = always 100%** — policy window does NOT apply on organizer/event cancel
  - **STUB:** `// TODO: call StripeRefundService.createRefund(paymentIntentId, totalAmount, EVENT_CANCELLED)` 
  - On stub success (simulated): create `Refund(amount=totalAmount, type=EVENT_CANCELLED, status=SUCCEEDED)`; call `finaliseAfterRefund(bookingId)`; update audit `status=SUCCEEDED`
  - On failure: update audit `status=FAILED`, `errorMessage`; log `ERROR`; continue to next booking — one failure must not block others
- Step 3: After all bookings processed, publish one `EventCancellationInitiatedEvent(slotId, orgId, affectedBookingCount)` (optional audit fanout)
- **Idempotency guard:** if audit row already exists with `status=SUCCEEDED` for a `(slotId, bookingId)` pair — skip

---

### BL-6 — `finaliseAfterRefund` (shared helper — unchanged from FR5, called by all cancel paths)
- Idempotency guard: skip if `booking.status = CANCELLED`
- Set `booking.status = CANCELLED`; all `BookingItem.status = CANCELLED`; all `ETicket.status = VOIDED`; `Refund.status = SUCCEEDED`; `CancellationRequest.status = APPROVED`
- Publish `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)` via `@TransactionalEventListener(AFTER_COMMIT)`

---

### BL-7 — Policy Seed / Fallback
- On first deploy: V33 migration seeds a `SYSTEM_DEFAULT` policy with tiers:
  - `sort_order=1`: `hours_before_event=72`, `refund_percent=100`
  - `sort_order=2`: `hours_before_event=24`, `refund_percent=50`
  - `sort_order=3`: `hours_before_event=NULL` (catch-all), `refund_percent=0`
- If no policy found at all (empty DB, policy deleted): hardcoded fallback in `CancellationPolicyService` returns 100%

---

### BL-8 — `slotStartTime` caching at booking creation
- In `BookingService` (FR5 §6.1 Step 3, extended): after creating `Booking` shell, populate `booking.slotStartTime` from `CartAssembledEvent` (requires `slotStartTime` added to `CartAssembledEvent` payload — **breaking change on that record**)
- Alternative: fetch via `SlotSummaryReader.getSlotSummary(slotId).startTime()` at booking creation (already injected in the module via shared reader)
- `SlotSummaryReader` is the correct mechanism — already exists, already in booking-inventory's dependency graph; payments-ticketing can consume the same interface
- **Decision to confirm:** add `slotStartTime` to `CartAssembledEvent` (simpler) OR use `SlotSummaryReader` in payments-ticketing (cleaner boundary)

---

> Does this look complete? Also — **which mechanism do you prefer for caching `slotStartTime` on `Booking`**: add it to `CartAssembledEvent` payload (one field addition to a shared record), or inject `SlotSummaryReader` into payments-ticketing's `CartAssembledListener`?

User: next stage 

GitHub Copilot: Defaulting **`slotStartTime` added to `CartAssembledEvent`** — it's already the cross-module handoff record, the field is available at cart-confirm time in booking-inventory, and it avoids adding a `SlotSummaryReader` dependency into payments-ticketing. Stage 6 confirmed.

---

Drafting the **Stage 7 — Error Handling outline** for confirmation.

---

## Stage 7 — Error Handling Outline

Failure scenarios only (no messages or actions yet).

---

### Policy Resolution
- `E1` — No org-specific policy AND no system default policy exists in DB
- `E2` — Policy exists but has no tiers (empty tier list)
- `E3` — Admin submits policy with overlapping tier boundaries (e.g. two tiers both claiming `hours_before_event=24`)
- `E4` — Admin submits policy with `refund_percent` outside 0–100 range
- `E5` — Admin attempts to create a second policy for an org that already has one

---

### Cancellation Quote
- `E6` — `bookingRef` not found or not owned by calling user
- `E7` — One or more `bookingItemIds` in the quote request do not belong to this booking
- `E8` — One or more `bookingItemIds` are already `CANCELLED` (not `ACTIVE`)
- `E9` — Booking is not `CONFIRMED` (cannot quote on a `PENDING` or `CANCELLED` booking)

---

### Individual Ticket Cancellation (`cancel-items`)
- `E10` — Booking not found / not owned by calling user
- `E11` — Booking not in `CONFIRMED` state
- `E12` — One or more `bookingItemIds` not `ACTIVE` or not belonging to this booking
- `E13` — Duplicate cancellation — a `PENDING` `CancellationRequest` already exists for one of the requested items
- `E14` — `StripeRefundService.createRefund` fails (network, charge already refunded, Stripe 5xx)
- `E15` — Async `refund.failed` webhook arrives after `cancel-items` returned `CANCELLATION_PENDING`
- `E16` — Zero-item request body (empty `bookingItemIds` list)

---

### Full Booking Cancellation (FR5 updated path)
- `E17` — Outside all policy window tiers — `refundPercent = 0` — not an error but must be handled cleanly (no Stripe call, booking still cancels)
- *(FR5 errors E7–E11 carry forward unchanged)*

---

### Event-Level Cancellation (batch path)
- `E18` — `ShowSlotCancelledEvent` arrives but no `CONFIRMED` bookings exist for that `slotId` (empty batch — not an error, log info and exit)
- `E19` — Individual Stripe refund fails for one booking in the batch (must not block remaining bookings)
- `E20` — `ShowSlotCancelledEvent` delivered twice for same `slotId` (idempotency — audit row already `SUCCEEDED`)
- `E21` — `booking.status` is already `CANCELLATION_PENDING` or `CANCELLED` when event-cancel listener picks it up (race with buyer-initiated cancel)

---

### Admin Slot Cancel stub (admin)
- `E22` — scheduling REST call returns `404` (slot not found)
- `E23` — scheduling REST call returns `409` (slot already `CANCELLED`)
- `E24` — scheduling REST call is unavailable / times out

---

### `CartAssembledEvent` extended payload
- `E25` — `slotStartTime` is null on `CartAssembledEvent` (scheduling bug — slot has no start time set) — booking creation must reject / log critical and not persist a `Booking` with null `slotStartTime`

---

> Does this look complete? Anything to add, remove, or change before I write Stage 7 in full?

User: Next stage 

GitHub Copilot: Stage 7 confirmed. Drafting the **Stage 8 — Tests outline** for confirmation.

---

## Stage 8 — Tests Outline

Test case titles grouped by area (no Given/When/Then yet).

---

### `domain/` — Unit Tests

**`CancellationPolicyTest`**
- `tier_resolution_returns_100_percent_when_72h_before_event`
- `tier_resolution_returns_50_percent_when_between_24h_and_72h`
- `tier_resolution_returns_0_percent_when_under_24h`
- `tier_resolution_catch_all_tier_matches_when_no_other_tier_applies`
- `refund_amount_floors_correctly_on_fractional_paise`
- `empty_tier_list_throws_IllegalStateException`

**`BookingCancellationStateTest`**
- `partial_cancel_leaves_booking_confirmed_when_remaining_items_exist`
- `partial_cancel_all_items_transitions_booking_to_cancelled`
- `full_cancel_transitions_confirmed_to_cancellation_pending_to_cancelled`
- `cancelled_booking_is_terminal_no_further_transitions`

---

### `service/` — Mockito Unit Tests

**`CancellationPolicyServiceTest`**
- `computeRefundPercent_uses_org_policy_when_exists`
- `computeRefundPercent_falls_back_to_system_default_when_no_org_policy`
- `computeRefundPercent_falls_back_to_100_hardcoded_when_no_policy_in_db`
- `computeRefundPercent_returns_0_when_event_already_started`
- `computeRefundPercent_returns_correct_amount_floors_fractional_paise`
- `computeRefundPercent_selects_first_matching_tier_in_sort_order`

**`CancellationServiceTest` — cancel-items (partial)**
- `cancelItems_within_window_full_refund_percent_calls_stripe_and_voids_tickets`
- `cancelItems_within_window_partial_refund_percent_applies_correct_amount`
- `cancelItems_zero_refund_percent_skips_stripe_creates_zero_refund_directly`
- `cancelItems_all_items_cancelled_transitions_booking_to_cancelled`
- `cancelItems_some_items_cancelled_booking_stays_confirmed_totalAmount_updated`
- `cancelItems_duplicate_pending_request_throws_DuplicateItemCancellationException`
- `cancelItems_item_not_belonging_to_booking_throws_BookingNotFoundException`
- `cancelItems_item_not_active_throws_CancellationNotAllowedException`
- `cancelItems_empty_list_throws_validation_error`
- `cancelItems_stripe_fails_reverts_items_to_active_throws_RefundFailedException`

**`CancellationServiceTest` — full cancel (FR5 updated)**
- `cancel_full_booking_zero_percent_policy_skips_stripe_finalises_immediately`
- `cancel_full_booking_partial_percent_policy_calls_stripe_with_correct_amount`
- `cancel_full_booking_100_percent_policy_calls_stripe_full_amount`

**`CancellationServiceTest` — quote**
- `computeQuote_returns_correct_refund_percent_and_amount_no_db_writes`
- `computeQuote_past_event_returns_zero_with_PAST_EVENT_flag`
- `computeQuote_non_owner_throws_BookingNotFoundException`
- `computeQuote_already_cancelled_items_throws_CancellationNotAllowedException`

**`EventCancellationRefundServiceTest`**
- `processEventRefunds_processes_all_confirmed_bookings_for_slot`
- `processEventRefunds_empty_booking_list_exits_without_error`
- `processEventRefunds_one_failure_continues_processing_remaining_bookings`
- `processEventRefunds_idempotent_skips_already_succeeded_audit_rows`
- `processEventRefunds_race_with_buyer_cancel_skips_already_cancelled_booking`
- `processEventRefunds_always_100_percent_regardless_of_policy_window`

**`CancellationPolicyAdminServiceTest`**
- `createPolicy_succeeds_for_org_with_no_existing_policy`
- `createPolicy_throws_when_org_already_has_policy`
- `updatePolicy_replaces_all_tiers`
- `createPolicy_throws_on_overlapping_tier_boundaries`
- `createPolicy_throws_on_refund_percent_out_of_range`

---

### `api/` — `@WebMvcTest`

**`CancellationItemsControllerTest`**
- `POST /cancel-items` — `200 OK` partial cancel within window
- `POST /cancel-items` — `200 OK` zero-refund cancel (no Stripe call, still cancels)
- `POST /cancel-items` — `200 OK` all items cancelled transitions booking to CANCELLED
- `POST /cancel-items` — `404` for non-owner
- `POST /cancel-items` — `409` booking not CONFIRMED
- `POST /cancel-items` — `409` item already has PENDING cancellation request
- `POST /cancel-items` — `400` empty bookingItemIds list
- `POST /cancel-items` — `502` on Stripe failure
- `GET /cancellation-quote` — `200 OK` with refund breakdown, no DB side effects
- `GET /cancellation-quote` — `200 OK` with zero refund and PAST_EVENT flag for started event

**`CancellationPolicyControllerTest`**
- `POST /admin/cancellation-policies` — `201 Created` for valid policy
- `POST /admin/cancellation-policies` — `409` when org already has policy
- `POST /admin/cancellation-policies` — `400` on overlapping tiers
- `POST /admin/cancellation-policies` — `400` on refund_percent > 100
- `PUT /admin/cancellation-policies/{id}` — `200 OK` replaces tiers
- `GET /admin/cancellation-policies/{id}` — `200 OK` with tier list
- `GET /admin/cancellation-policies?orgId=X` — `200 OK` returns org policy; falls back to system default
- All admin endpoints — `403` without `ROLE_ADMIN`

**`AdminSlotCancelControllerTest`** (admin module)
- `POST /admin/slots/{slotId}/cancel` — `200 OK` delegates to scheduling REST and returns
- `POST /admin/slots/{slotId}/cancel` — `404` when scheduling returns 404
- `POST /admin/slots/{slotId}/cancel` — `409` when scheduling returns 409 (already cancelled)
- `POST /admin/slots/{slotId}/cancel` — `503` when scheduling is unavailable

---

### `arch/` — ArchUnit

**`PaymentsTicketingArchTest` (additions)**
- `no_class_in_paymentsticketing_calls_stripe_sdk_directly` — carries forward from FR5
- `CancellationPolicyService_does_not_import_any_module_service` — only imports own repo + shared
- `EventCancellationRefundService_does_not_import_booking_inventory_classes`
- `ShowSlotCancelledListener_only_calls_EventCancellationRefundService` — no direct repo access in listener

---

> Does this look complete? Anything to add, remove, or change before I write Stage 8 — and then all stages — in full?

User: Write all the stages in full into SPEC_6.md 

GitHub Copilot: I’ll now create `SPEC_6.md` with the complete Stage 1–8 content, incorporating the policy window/percentage/value rules, individual and event-level cancellation flows, wallet-skip scope, and the interface-vs-HTTP/hotspot plan. I’ll first confirm the target folder and then write the full file in one pass.

Read [](file:///home/dell/Multi-City-Event-Booking/POQ)

Searched for files matching `**/POQ/**/SPEC_6.md`, no matches

Created 3 todos

I’ve confirmed the file doesn’t exist, so I’m creating it now under Payments with all eight stages fully written and aligned to your constraints.

Created [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_6.md)

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Payments/SPEC_6.md#1-1), lines 1 to 80

Completed: *Draft full SPEC_6 content* (1/3)

**Completed**
- Wrote the full Stage 1–8 specification into SPEC_6.md.
- Included both requested cancellation paths:
  - Individual ticket/item cancellation
  - Entire event cancellation (admin/organizer initiated, refund flow stubbed)
- Explicitly kept wallet credit out of scope.
- Added cancellation window + percentage + computed refund value rules in business logic.
- Added interface-vs-HTTP plan and hotspot assessment (no module-call hotspot; only Stripe batch throughput sensitivity).

If you want, I can now generate the matching implementation checklist (class-by-class + migration-by-migration) directly from this spec.

Made changes.