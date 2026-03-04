# SPEC_4.md — FR4: Seat Selection → Pricing Tier Validation → Cart Assembly

**Owner:** booking-inventory (primary), payments-ticketing (downstream consumer)
**Module(s):** `booking-inventory` (primary), `shared/eventbrite` (`EbTicketService`), `payments-ticketing` (event consumer), `scheduling` (REST read-only), `discovery-catalog` (REST read-only for seat floor plan)
**Flow:** Flow 4 — Seat Availability Read → Soft Lock → Pricing Validation → Cart Assembly → Hard Lock → Eventbrite Checkout Handoff
**Last Updated:** March 4, 2026
**Status:** Stages 1–7 Complete — All pre-FR4 gaps resolved ✅  
**Interface Migration (2026-03-04):** scheduling → booking-inventory REST reads replaced with shared reader interfaces ✅

**Depends on:**
- `SPEC_2.md` (FR2) — `show_slot.status = ACTIVE`, `eb_event_id` populated, `show_slot_pricing_tier` rows with `eb_ticket_class_id` must all exist before FR4 is reachable
- `SPEC_3.MD` (FR3) — JWT with `sub=userId`, `role` claims required for all protected endpoints
- `SPEC_AUTH.MD` — `JwtAuthenticationFilter` + `SecurityConfig` in place

**Pre-FR4 Changes Implemented (as of 2026-03-04):**
- ✅ `orgId` JWT claim added to `JwtTokenProvider` + `AuthenticatedUser` principal record in `shared/`
- ✅ `JwtAuthenticationFilter` now sets `AuthenticatedUser(userId, role, orgId)` as `SecurityContext` principal
- ✅ `ShowSlotActivatedEvent` now carries `seatingMode` as 6th component
- ✅ `CartAssembledEvent` record created in `shared/` with full payload `(cartId, slotId, userId, orgId, ebEventId, couponCode)`
- ✅ `GET /api/v1/scheduling/slots/{slotId}/pricing-tiers` endpoint implemented in `scheduling`
- ✅ `GET /api/v1/catalog/venues/{venueId}/seat-layout` endpoint + `VenueSeat` entity + `VenueSeatRepository` implemented in `discovery-catalog`
- ✅ `show_slot_pricing_tier` group discount columns added (V17 migration)
- ✅ `venue_seat` table created (V18 migration)
- ✅ `SecurityConfig` public routes updated: `/api/v1/booking/slots/*/seats` and `/api/v1/booking/slots/*/availability` are `permitAll()`

**Interface Migration Implemented (as of 2026-03-04):**
- ✅ `SlotSummaryReader` + `SlotPricingReader` interfaces added to `shared/common/service/`
- ✅ `SlotSummaryDto` + `PricingTierDto` records added to `shared/common/dto/`
- ✅ `SlotSummaryReaderImpl` + `SlotPricingReaderImpl` `@Service` beans added to `scheduling/service/`
- ✅ All `booking-inventory` callers migrated from `SchedulingSlotClient` HTTP calls to shared reader interfaces
- ✅ `GaInventoryService.initCounters()` signature updated to accept `List<PricingTierDto>`
- ✅ `CartPricingService` extended with `getSlotPricingCached(Long slotId)` — cache-aside via `StringRedisTemplate` + `ObjectMapper`
- ✅ `SchedulingSlotClient`, `SchedulingSlotResponse`, `SchedulingPricingTierResponse` deleted from `booking-inventory`
- ✅ `BookingInventoryArchTest` updated with two explicit named ArchUnit rules
- ✅ `SlotSummaryReaderImplTest`, `SlotPricingReaderImplTest`, `CartPricingServiceTest`, `SlotTicketSyncServiceTest` added/updated

---

## Stage 1 — Requirements

### Area A — Pre-conditions & Slot Guard

- `[MUST]` Before exposing seat availability, verify `show_slot.status = ACTIVE` via REST GET to `scheduling`.
- `[MUST]` If `eb_event_id` is null on the slot, return `503 SERVICE_UNAVAILABLE` with `EB_EVENT_NOT_SYNCED` — never `404`. *(PRODUCT.md Hard Rule #10)*
- `[MUST]` If any pricing tier in the cart has a null `eb_ticket_class_id`, block cart confirm with `TIER_NOT_SYNCED`.
- `[MUST]` If org token is not in `CONNECTED` state (`EbTokenStore.getValidToken(orgId)` throws `EbAuthException`), block checkout initiation.
- `[MUST]` Reject requests from users whose `status != ACTIVE`.
- `[MUST]` Reject a second `PENDING` cart for the same `(userId, slotId)` — enforced by partial unique index on `carts`.

### Area B — Seat Availability Read

- `[MUST]` Expose `GET /api/v1/booking/shows/{slotId}/available-seats`.
- `[MUST]` For `RESERVED` seating: return seats where `lock_state = AVAILABLE` OR (`lock_state = SOFT_LOCKED` AND `locked_until < NOW()`).
- `[MUST]` For `GA` seating: return per-tier quota and available quantity (`quota - confirmed_count`), resolved via REST to `scheduling`.
- `[MUST]` No Eventbrite call on this path — internal DB only. Eventbrite `quantity_sold` lags and does not include in-flight checkouts.

### Area C — Seat Selection / Soft Lock

- `[MUST]` Expose `POST /api/v1/booking/cart/add-seat` (JWT `ROLE_USER`).
- `[MUST]` Acquire Redis lock atomically via Lua script (`SET NX PX`) before any DB write.
- `[MUST]` If Redis NX returns `CONFLICT`, throw `SeatAlreadyLockedException` — return `409` with up to 5 alternative seats from same section/tier.
- `[MUST]` On successful Redis lock, DB-update seat to `SOFT_LOCKED` with `locked_until = NOW() + 5 min`.
- `[MUST]` If DB write fails after Redis lock acquired, rollback Redis key (`DEL` via ownership-checked Lua) before rethrowing.
- `[MUST]` Upsert `Cart` (one per `(userId, slotId)`, `status = PENDING`); create `CartItem`.
- `[MUST]` For GA: decrement Redis atomic counter (`ga:available:{slotId}:{tierId}`) via Lua `DECRBY`; reject with `INSUFFICIENT_GA_INVENTORY` if available < requested.
- `[MUST]` Handle double-submit idempotency: Redis `ALREADY_YOURS` result → treat as success; `CartItem` upsert on `(cart_id, seat_id)` is a no-op if row exists.
- `[SHOULD]` Expose `DELETE /api/v1/booking/cart/items/{itemId}` that fires `RELEASE` event and recomputes cart total.

### Area D — Pricing Tier Validation & Cart Total

- `[MUST]` Recompute cart total inline on every `add-seat` and `remove-seat` call — never deferred.
- `[MUST]` Pricing source of truth is `show_slot_pricing_tier` owned by `scheduling` — accessed via `SlotPricingReader` shared interface (implemented in `scheduling`, injected into `booking-inventory`), never via direct REST call from `booking-inventory` and never imported as a JPA entity.
- `[MUST]` Apply group discount per tier: if `cartItemsForTier >= groupDiscountThreshold`, apply `groupDiscountPercent` to ALL items of that tier retroactively.
- `[MUST]` Removing a seat that drops a tier count below threshold removes the discount from all remaining items of that tier retroactively.
- `[MUST]` Group discount is always recomputed from scratch — never incrementally accumulated.
- `[SHOULD]` Return `CartResponse` including line items, per-tier subtotals, discount breakdown, and `cartTotal`.

### Area E — Cart Confirm / Hard Lock

- `[MUST]` Expose `POST /api/v1/booking/cart/confirm` (JWT `ROLE_USER`).
- `[MUST]` Verify `cart.status = PENDING` and `cart.expires_at > NOW()`.
- `[MUST]` Verify Redis lock is still owned by this user for every seat via ownership-checked Lua GET.
- `[MUST]` Call `EbTicketService.getTicketClass(ebEventId, ebTicketClassId)` — this is the **only Eventbrite call on the hot path** — verify `on_sale_status = AVAILABLE` per unique tier in cart.
- `[MUST]` If any tier is `SOLD_OUT` on Eventbrite: release all soft locks for that tier, return `409 TIER_SOLD_OUT` with alternative tiers.
- `[MUST]` Transition all seats `SOFT_LOCKED → HARD_LOCKED`, extend `locked_until = NOW() + 30 min`.
- `[MUST]` Refresh Redis TTLs to 1800 seconds and extend `cart.expires_at` to match.
- `[MUST]` Publish `CartAssembledEvent(cartId, slotId, userId, orgId, ebEventId, couponCode)` — `payments-ticketing` listens.

### Area F — Distributed Seat Lock State Machine

- `[MUST]` Implement `SeatLockStateMachine` in `booking-inventory/statemachine/` using Spring State Machine factory.
- `[MUST]` States: `AVAILABLE → SOFT_LOCKED → HARD_LOCKED → PAYMENT_PENDING → CONFIRMED`; `RELEASE` from any non-CONFIRMED state returns to `AVAILABLE`.
- `[MUST]` Machine is rebuilt from `seats.lock_state` DB column on each call — no SSM Redis persistence store.
- `[MUST]` All Redis operations (acquire, extend, release, GA claim/restore) are Lua scripts — no Java read-then-write races.
- `[MUST]` Write to `seat_lock_audit_log` on every state transition — append-only, never update or delete rows.
- `[MUST]` `AvailabilityGuard` (SELECT event) and `CartTtlGuard` (CONFIRM event) must be distinct Spring beans.

### Area G — Background Cleanup Jobs

- `[MUST]` `SoftLockCleanupJob` (every 60s): bulk UPDATE seats to `AVAILABLE` where `lock_state = SOFT_LOCKED AND locked_until < NOW()`; delete expired `ga_inventory_claims` and restore Redis counter via atomic `INCRBY`.
- `[MUST]` `CartExpiryCleanupJob` (every 5 min): find `PENDING` carts where `expires_at < NOW()` → set `EXPIRED`; fire `RELEASE` on all `SOFT_LOCKED` seats; do NOT touch `HARD_LOCKED` or `PAYMENT_PENDING` seats.
- `[MUST]` `PaymentTimeoutWatchdog` (every 60s): find `HARD_LOCKED`/`PAYMENT_PENDING` seats where `locked_until < NOW()`; call `EbOrderService` to verify no placed order; if none → fire `RELEASE(reason=PAYMENT_TIMEOUT)`; publish `PaymentTimeoutEvent`.
- `[SHOULD]` `EbInventoryValidationJob` (every 2 min): call `EbTicketService.listTicketClasses(ebEventId)` per ACTIVE slot; if any tier `on_sale_status = SOLD_OUT` → write `SET tier:blocked:{slotId}:{tierId} 1 EX 300` to Redis; emit `InventoryDriftDetectedEvent`.

### Area H — Spring Events (Inter-Module)

- `[MUST]` Listen to `ShowSlotActivatedEvent(slotId, ebEventId, orgId, venueId, seatingMode)` from `scheduling` to provision seats (RESERVED) or initialize GA Redis counters.
- `[MUST]` Publish `CartAssembledEvent(cartId, slotId, userId, orgId, ebEventId, couponCode)` after hard lock transition. *(Hard Rule #4 — primitives/IDs only)*
- `[MUST]` Listen to `BookingConfirmedEvent(cartId, List<seatId>, ebOrderId, userId)` from `payments-ticketing` → batch-transition seats `PAYMENT_PENDING → CONFIRMED`.
- `[MUST]` Listen to `PaymentFailedEvent(cartId, List<seatId>, userId)` from `payments-ticketing` → fire `RELEASE` on all seats.
- `[MUST]` Publish `PaymentTimeoutEvent(cartId, List<seatId>, userId)` when watchdog releases locks — `payments-ticketing` marks booking attempt abandoned.

### Area I — Conflict Resolution

- `[MUST]` On `SEAT_UNAVAILABLE` (409): return up to 5 nearest alternatives in same section/tier + up to 3 from adjacent section.
- `[MUST]` On `SOFT_LOCK_EXPIRED` (410): return list of expired `seatIds` and prompt re-selection from updated availability.
- `[SHOULD]` On `TIER_SOLD_OUT` (409): return available alternative tiers or nearby slots.

### Area J — Out of Scope

- `[WONT]` Payment processing — owned by `payments-ticketing` (FR5).
- `[WONT]` Coupon/promotion application — owned by `promotions` (FR7).
- `[WONT]` E-ticket generation — owned by `payments-ticketing`.
- `[WONT]` Eventbrite seat map creation — seats provisioned from internal venue floor plan only.
- `[WONT]` Admin seat override or manual lock controls.

---

## Stage 2 — Domain Model

### Domain Summary

A `Cart` belongs to one user (scalar `user_id`) and one `ShowSlot` (scalar `show_slot_id`). Only one `PENDING` cart may exist per `(user, slot)` pair at a time, enforced by a partial unique index. A `Cart` has many `CartItems`. In RESERVED mode, each `CartItem` references one `Seat` that progresses through the lock state machine. In GA mode, each `CartItem` references one `GaInventoryClaim` representing a quantity reservation against a tier pool. A `GroupDiscountRule` governs whether a tier's items qualify for bulk pricing. Every state transition on a `Seat` or `GaInventoryClaim` writes one immutable row to `SeatLockAuditLog`.

### Entities Owned by `booking-inventory`

**Seat** *(RESERVED seating only)*
- Identity: `id` (BIGSERIAL); unique by `(show_slot_id, seat_number)`
- Lifecycle: `AVAILABLE → SOFT_LOCKED → HARD_LOCKED → PAYMENT_PENDING → CONFIRMED`; any non-CONFIRMED state can RELEASE back to `AVAILABLE`
- Relationships: belongs to one `show_slot` (scalar ID — no JPA FK to `scheduling`); belongs to one pricing tier (scalar `pricing_tier_id`); optionally referenced by one `CartItem`
- Ownership: `booking-inventory`; provisioned from internal venue floor plan (`discovery-catalog` `VenueSeat` entity) on `ShowSlotActivatedEvent`
- Domain methods: `softLock(userId, ttl)`, `hardLock(ttl)`, `markPaymentPending()`, `confirm(ebOrderId)`, `release(reason)`

**GaInventoryClaim** *(GA seating only)*
- Identity: `id`; unique by `(show_slot_id, pricing_tier_id, user_id, cart_id)`
- Lifecycle: `SOFT_LOCKED → HARD_LOCKED → CONFIRMED` or released
- Relationships: belongs to one `show_slot` and one pricing tier (both scalar IDs); linked to one `Cart`
- Ownership: `booking-inventory`

**Cart**
- Identity: `id`; business identity `(user_id, show_slot_id)` where `status = PENDING` (partial unique index)
- Lifecycle: `PENDING → CONFIRMED | ABANDONED | EXPIRED`
- Relationships: one `Cart` has many `CartItems`; references one `show_slot` (scalar ID); references one user (scalar `user_id`)
- Ownership: `booking-inventory`
- Domain methods: `confirm()`, `abandon()`, `expire()`, `isExpired()`, `extendTtl(minutes)`

**CartItem**
- Identity: `id`
- Lifecycle: created on seat selection; removed on seat removal; finalized when cart confirms
- Relationships: belongs to one `Cart`; references one `Seat` (RESERVED — NOT NULL) or one `GaInventoryClaim` (GA — NOT NULL); stores scalar `pricing_tier_id` + denormalized `eb_ticket_class_id`
- Ownership: `booking-inventory`
- Constraint: exactly one of `seat_id` or `ga_claim_id` must be set (CHECK constraint)

**GroupDiscountRule**
- Identity: `id`; unique by `(show_slot_id, pricing_tier_id)`
- Lifecycle: created when slot is activated from `ShowSlotActivatedEvent` pricing tier data; updated if admin edits pricing
- Relationships: one rule per pricing tier per slot; consumed by `CartPricingService`
- Ownership: `booking-inventory`; mirrors `scheduling`'s `show_slot_pricing_tier` discount fields — stored here to avoid REST calls on every cart compute

**SeatLockAuditLog**
- Identity: `id` (BIGSERIAL, append-only)
- Lifecycle: immutable — one row per state transition, never updated or deleted
- Relationships: references `seat_id` (nullable — null for GA), `ga_claim_id` (nullable), `show_slot_id`, `user_id`
- Ownership: `booking-inventory`

### Non-Persisted Operational Concepts

**SeatLockStateMachine** *(in-memory per request)*
- Not persisted in SSM store; rebuilt from `seats.lock_state` DB column on each invocation
- Guards: `AvailabilityGuard` (SELECT), `CartTtlGuard` (CONFIRM)
- Actions: `SoftLockAction`, `HardLockAction`, `PaymentPendingAction`, `ConfirmAction`, `ReleaseAction`

**Redis Seat Lock** *(key: `seat:lock:{seatId}` / `ga:available:{slotId}:{tierId}`)*
- Not persisted in DB; TTL-based; all read-modify-write operations via Lua scripts only
- RESERVED: NX key per seat, value = `userId` string
- GA: atomic integer counter per `(slotId, tierId)`

**Tier Blocked Flag** *(key: `tier:blocked:{slotId}:{tierId}`)*
- Written by `EbInventoryValidationJob` when Eventbrite reports `SOLD_OUT`
- TTL = 300s (5 min); gates new soft locks for the affected tier

### Read-Only External References (not owned by this module)

- `ShowSlotPricingTier` — owned by `scheduling`; accessed via `SlotPricingReader` shared interface (`SlotPricingReaderImpl` bean in `scheduling`); cached in `booking-inventory` via `CartPricingService.getSlotPricingCached()` — `StringRedisTemplate` JSON cache, key `pricing:slot:{slotId}`, TTL 10 min. REST endpoint `GET /api/v1/scheduling/slots/{slotId}/pricing-tiers` still exists for admin/external consumers only.
- `ShowSlot` metadata — owned by `scheduling`; accessed via `SlotSummaryReader` shared interface (`SlotSummaryReaderImpl` bean in `scheduling`); no caching (fast single-row read, called only on validation path).
- `User` — owned by `identity`; scalar `user_id` only; JWT claims used for authentication
- `VenueSeat` — owned by `discovery-catalog`; read via REST `GET /api/v1/catalog/venues/{venueId}/seat-layout` for seat provisioning (provisioning path only — not hot path)

---

## Stage 3 — Architecture & File Structure

### Module Ownership
- **Primary:** `booking-inventory`
- **Event consumers added:** `payments-ticketing` (new listeners for `CartAssembledEvent`)
- **Shared reader interfaces (in-process, no HTTP):** `SlotSummaryReader` → `scheduling/SlotSummaryReaderImpl`; `SlotPricingReader` → `scheduling/SlotPricingReaderImpl`
- **REST reads (provisioning path only):** `discovery-catalog` `GET /api/v1/catalog/venues/{venueId}/seat-layout` via `CatalogSeatLayoutClient`
- **ACL facade used:** `shared/eventbrite/service/EbTicketService` (existing), `EbOrderService` (existing, watchdog only)

### Feature File Structure

```
booking-inventory/src/main/java/com/eventplatform/bookinginventory/

├── api/
│   ├── controller/
│   │   ├── SeatAvailabilityController.java      ← GET  /api/v1/booking/shows/{slotId}/available-seats
│   │   └── CartController.java                  ← POST add-seat, DELETE item, POST confirm, POST abandon, GET cart
│   └── dto/
│       ├── request/
│       │   ├── AddSeatRequest.java              ← slotId, seatId (nullable), tierId, quantity
│       │   ├── RemoveSeatRequest.java           ← cartId, itemId
│       │   └── ConfirmCartRequest.java          ← cartId
│       └── response/
│           ├── AvailableSeatResponse.java       ← seatId, seatNumber, rowLabel, section, tierId, basePrice, lockState
│           ├── GaTierAvailabilityResponse.java  ← tierId, tierName, quota, available, basePrice
│           ├── CartResponse.java                ← cartId, slotId, status, expiresAt, items, subtotal, discountAmount, total, currency
│           ├── CartItemResponse.java            ← itemId, seatId (nullable), tierId, tierName, quantity, unitPrice, discountApplied
│           └── SeatAlternativesResponse.java    ← unavailableSeatId, sameSection: List, adjacentSection: List

├── domain/
│   ├── Seat.java
│   ├── GaInventoryClaim.java
│   ├── Cart.java
│   ├── CartItem.java
│   ├── GroupDiscountRule.java
│   ├── SeatLockAuditLog.java
│   └── enums/
│       ├── SeatLockState.java        ← AVAILABLE, SOFT_LOCKED, HARD_LOCKED, PAYMENT_PENDING, CONFIRMED, RELEASED
│       ├── SeatLockEvent.java        ← SELECT, CONFIRM, CHECKOUT_INITIATE, CONFIRM_PAYMENT, RELEASE
│       ├── CartStatus.java           ← PENDING, CONFIRMED, ABANDONED, EXPIRED
│       ├── SeatingMode.java          ← RESERVED, GA
│       └── LockReleaseReason.java    ← TTL_EXPIRED, USER_REMOVED, CART_ABANDONED, PAYMENT_FAILED, ORDER_ABANDONED, PAYMENT_TIMEOUT, TIER_SOLD_OUT

├── repository/
│   ├── SeatRepository.java                      ← findBySlotIdAndLockState, findExpiredSoftLocks, findByIdWithLock (SELECT FOR UPDATE)
│   ├── GaInventoryClaimRepository.java
│   ├── CartRepository.java                      ← findPendingByUserAndSlot
│   ├── CartItemRepository.java
│   ├── GroupDiscountRuleRepository.java
│   └── SeatLockAuditLogRepository.java          ← save only — no updates

├── service/
│   ├── SeatAvailabilityService.java             ← reads DB, assembles seat map (RESERVED) or GA tier view; tiers via CartPricingService.getSlotPricingCached()
│   ├── CartService.java                         ← orchestrates add-seat, remove-seat, confirm, abandon; tiers via CartPricingService.getSlotPricingCached()
│   ├── CartPricingService.java                  ← group discount full recompute; getSlotPricingCached(Long slotId) — cache-aside (key: pricing:slot:{slotId}, TTL 10 min, StringRedisTemplate+ObjectMapper)
│   ├── SlotValidationService.java               ← uses SlotSummaryReader; requireActiveAndSynced() returns SlotSummaryDto
│   ├── SeatProvisioningService.java             ← listens ShowSlotActivatedEvent; uses SlotPricingReader directly (provisioning path, no cache needed); bulk-inserts Seat rows (RESERVED); inits GA counters
│   ├── GaInventoryService.java                  ← initCounters(Long slotId, List<PricingTierDto> tiers); manages ga_inventory_claims + Redis quantity counters
│   ├── SlotTicketSyncService.java               ← uses SlotSummaryReader + SlotPricingReader (two separate reads)
│   ├── ConflictResolutionService.java           ← finds alternative seats/sections on 409
│   └── redis/
│       ├── SeatLockRedisService.java            ← all RESERVED Lua scripts: acquire, extend, release
│       └── GaInventoryRedisService.java         ← GA Lua scripts: DECRBY claim, INCRBY release

├── statemachine/
│   ├── SeatLockStateMachineConfig.java          ← @EnableStateMachineFactory; wires states, transitions, guards, actions
│   ├── SeatLockStateMachineService.java         ← sendEvent(); rebuilds machine from DB lock_state; SELECT FOR UPDATE
│   ├── guard/
│   │   ├── AvailabilityGuard.java               ← SELECT: DB lock_state check + Redis Lua NX acquire
│   │   └── CartTtlGuard.java                    ← CONFIRM: cart TTL + Redis owner check + EbTicketService call
│   └── action/
│       ├── SoftLockAction.java                  ← DB write; Redis rollback on DB failure
│       ├── HardLockAction.java                  ← Redis TTL extend; DB write; compensate on failure
│       ├── PaymentPendingAction.java             ← DB state update; audit log
│       ├── ConfirmAction.java                    ← DB confirm (idempotent); Redis DEL; audit log
│       └── ReleaseAction.java                   ← DB → AVAILABLE first; Redis DEL; audit log

├── scheduler/
│   ├── SoftLockCleanupJob.java                  ← every 60s
│   ├── CartExpiryCleanupJob.java                ← every 5 min
│   ├── PaymentTimeoutWatchdog.java              ← every 60s
│   └── EbInventoryValidationJob.java            ← every 2 min

├── mapper/
│   ├── SeatMapper.java                          ← MapStruct: Seat ↔ AvailableSeatResponse
│   └── CartMapper.java                          ← MapStruct: Cart + CartItem list ↔ CartResponse

├── event/
│   ├── listener/
│   │   ├── SlotDraftCreatedListener.java        ← already exists; extend for GA counter init via ShoslotActivatedEvent
│   │   ├── ShowSlotActivatedListener.java       ← new: triggers SeatProvisioningService or GaInventoryService
│   │   ├── BookingConfirmedListener.java        ← CONFIRM_PAYMENT batch; stores eb_order_id
│   │   └── PaymentFailedListener.java           ← RELEASE all cart seats
│   └── published/
│       ├── CartAssembledEvent.java              ← cartId, slotId, userId, orgId, ebEventId, couponCode
│       └── PaymentTimeoutEvent.java             ← cartId, List<seatId>, userId

└── exception/
    ├── SeatUnavailableException.java            ← 409
    ├── SeatAlreadyLockedException.java          ← 409
    ├── SeatAlreadyConfirmedException.java       ← 409
    ├── SoftLockExpiredException.java            ← 410
    ├── HardLockException.java                   ← 500 (DB failure after Redis extend)
    ├── TierSoldOutException.java                ← 409
    ├── TierBlockedException.java                ← 409 (EB drift detection)
    ├── InvalidSeatTransitionException.java      ← 422
    ├── CartExpiredException.java                ← 410
    ├── CartNotFoundException.java               ← 404
    └── SeatLockException.java                   ← 500 (DB write failed after Redis lock acquired)
```

**New in other modules (no ownership change):**

| Module | Change | Status | Consumer |
|---|---|---|---|
| `scheduling` | `GET /api/v1/scheduling/slots/{slotId}/pricing-tiers` | ✅ Already implemented (pre-FR4) | admin/external only — `booking-inventory` now uses `SlotPricingReader` |
| `scheduling` | `SlotSummaryReaderImpl` — `@Service` implementing `shared/SlotSummaryReader` | ✅ Implemented (interface migration) | `booking-inventory` slot validation path |
| `scheduling` | `SlotPricingReaderImpl` — `@Service` implementing `shared/SlotPricingReader` | ✅ Implemented (interface migration) | `booking-inventory` pricing path + provisioning |
| `discovery-catalog` | `GET /api/v1/catalog/venues/{venueId}/seat-layout` | ✅ Already implemented (pre-FR4) | `booking-inventory` seat provisioning only |

**New shared contracts:**

| File | Description |
|---|---|
| `shared/common/service/SlotSummaryReader.java` | Interface: `SlotSummaryDto getSlotSummary(Long slotId)` |
| `shared/common/service/SlotPricingReader.java` | Interface: `List<PricingTierDto> getSlotPricing(Long slotId)` |
| `shared/common/dto/SlotSummaryDto.java` | Record: `slotId, status, ebEventId, seatingMode, orgId, venueId, cityId, sourceSeatMapId` |
| `shared/common/dto/PricingTierDto.java` | Record: `tierId, tierName, price(Money), quota, tierType, ebTicketClassId, ebInventoryTierId, groupDiscountThreshold, groupDiscountPercent` |

**New file — `payments-ticketing`:**
- `event/listener/CartAssembledListener.java` — receives `CartAssembledEvent`; prepares Eventbrite Checkout Widget context; returns `ebEventId` to frontend

---

## Stage 4 — DB Schema

> Continuing from the pre-FR4 changes session:
> - V16 = `create_password_reset_tokens` (FR3)
> - V17 = `add_group_discount_to_pricing_tier` (**already applied — pre-FR4**)
> - V18 = `create_venue_seats` (**already applied — pre-FR4**)
>
> FR4 migrations start at **V19**. All migrations live in `app/src/main/resources/db/migration/`.

### V19 — `seats` *(RESERVED seating only)*

```sql
-- V19__create_seats.sql
-- owner: booking-inventory

CREATE TYPE seat_lock_state AS ENUM (
    'AVAILABLE', 'SOFT_LOCKED', 'HARD_LOCKED',
    'PAYMENT_PENDING', 'CONFIRMED', 'RELEASED'
);

CREATE TYPE seating_mode AS ENUM ('RESERVED', 'GA');

CREATE TABLE seats (
    id                  BIGSERIAL       PRIMARY KEY,
    show_slot_id        BIGINT          NOT NULL,
    pricing_tier_id     BIGINT          NOT NULL,
    eb_ticket_class_id  VARCHAR(255)    NOT NULL,
    seat_number         VARCHAR(20)     NOT NULL,
    row_label           VARCHAR(10),
    section             VARCHAR(100),
    lock_state          seat_lock_state NOT NULL DEFAULT 'AVAILABLE',
    locked_by_user_id   BIGINT,
    locked_until        TIMESTAMPTZ,
    eb_order_id         VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_seat_position UNIQUE (show_slot_id, seat_number)
);

-- Notes:
-- pricing_tier_id: scalar ref to show_slot_pricing_tier.id in scheduling. NOT a JPA @ManyToOne.
-- eb_ticket_class_id: denormalized from pricing tier at provisioning time; avoids REST call on every cart op.
-- locked_by_user_id: scalar user ID. NOT a FK to users table.

CREATE INDEX idx_seats_slot_state    ON seats(show_slot_id, lock_state);
CREATE INDEX idx_seats_locked_until  ON seats(locked_until) WHERE lock_state != 'AVAILABLE';
CREATE INDEX idx_seats_user_lock     ON seats(locked_by_user_id)
    WHERE lock_state IN ('SOFT_LOCKED', 'HARD_LOCKED', 'PAYMENT_PENDING');
CREATE INDEX idx_seats_eb_order      ON seats(eb_order_id) WHERE eb_order_id IS NOT NULL;
```

### V20 — `ga_inventory_claims` *(GA seating only)*

```sql
-- V20__create_ga_inventory_claims.sql
-- owner: booking-inventory

CREATE TABLE ga_inventory_claims (
    id                  BIGSERIAL       PRIMARY KEY,
    show_slot_id        BIGINT          NOT NULL,
    pricing_tier_id     BIGINT          NOT NULL,
    user_id             BIGINT          NOT NULL,
    cart_id             BIGINT,
    quantity            INTEGER         NOT NULL CHECK (quantity > 0),
    lock_state          seat_lock_state NOT NULL DEFAULT 'SOFT_LOCKED',
    locked_until        TIMESTAMPTZ     NOT NULL,
    eb_order_id         VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_ga_claim_user_tier UNIQUE (show_slot_id, pricing_tier_id, user_id, cart_id)
);

CREATE INDEX idx_ga_claim_slot_tier ON ga_inventory_claims(show_slot_id, pricing_tier_id, lock_state);
CREATE INDEX idx_ga_claim_user      ON ga_inventory_claims(user_id, lock_state);
CREATE INDEX idx_ga_claim_expiry    ON ga_inventory_claims(locked_until)
    WHERE lock_state IN ('SOFT_LOCKED', 'HARD_LOCKED');
```

### V21 — `carts`

```sql
-- V21__create_carts.sql
-- owner: booking-inventory

CREATE TYPE cart_status AS ENUM ('PENDING', 'CONFIRMED', 'ABANDONED', 'EXPIRED');

CREATE TABLE carts (
    id              BIGSERIAL     PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    show_slot_id    BIGINT        NOT NULL,
    org_id          BIGINT        NOT NULL,
    seating_mode    seating_mode  NOT NULL,
    status          cart_status   NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ   NOT NULL,
    coupon_code     VARCHAR(100),
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'INR',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Enforces one active cart per user per slot; allows multiple historical (ABANDONED/EXPIRED) carts.
CREATE UNIQUE INDEX uq_cart_user_slot_pending
    ON carts(user_id, show_slot_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_cart_user   ON carts(user_id);
CREATE INDEX idx_cart_slot   ON carts(show_slot_id);
CREATE INDEX idx_cart_status ON carts(status);
CREATE INDEX idx_cart_expiry ON carts(expires_at) WHERE status = 'PENDING';
```

### V22 — `cart_items`

```sql
-- V22__create_cart_items.sql
-- owner: booking-inventory

CREATE TABLE cart_items (
    id                  BIGSERIAL     PRIMARY KEY,
    cart_id             BIGINT        NOT NULL,
    seat_id             BIGINT,
    ga_claim_id         BIGINT,
    pricing_tier_id     BIGINT        NOT NULL,
    eb_ticket_class_id  VARCHAR(255)  NOT NULL,
    base_price_amount   DECIMAL(12,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL DEFAULT 'INR',
    quantity            INTEGER       NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_cart_item_cart  FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_seat  FOREIGN KEY (seat_id) REFERENCES seats(id),
    -- Exactly one mode: RESERVED has seat_id NOT NULL, ga_claim_id NULL; GA is inverse.
    CONSTRAINT chk_cart_item_mode CHECK (
        (seat_id IS NOT NULL AND ga_claim_id IS NULL) OR
        (seat_id IS NULL     AND ga_claim_id IS NOT NULL)
    )
);

CREATE INDEX idx_cart_item_cart ON cart_items(cart_id);
CREATE INDEX idx_cart_item_seat ON cart_items(seat_id);
CREATE INDEX idx_cart_item_tier ON cart_items(pricing_tier_id);
```

### V23 — `group_discount_rules`

```sql
-- V23__create_group_discount_rules.sql
-- owner: booking-inventory

CREATE TABLE group_discount_rules (
    id                        BIGSERIAL     PRIMARY KEY,
    show_slot_id              BIGINT        NOT NULL,
    pricing_tier_id           BIGINT        NOT NULL,
    group_discount_threshold  INTEGER       NOT NULL,
    group_discount_percent    DECIMAL(5,2)  NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_discount_rule_tier UNIQUE (show_slot_id, pricing_tier_id)
);

CREATE INDEX idx_group_discount_slot_tier ON group_discount_rules(show_slot_id, pricing_tier_id);
```

### V24 — `seat_lock_audit_log`

```sql
-- V24__create_seat_lock_audit_log.sql
-- owner: booking-inventory

CREATE TABLE seat_lock_audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    seat_id         BIGINT,
    ga_claim_id     BIGINT,
    show_slot_id    BIGINT          NOT NULL,
    user_id         BIGINT,
    from_state      seat_lock_state,
    to_state        seat_lock_state NOT NULL,
    event_type      VARCHAR(50)     NOT NULL,
    reason          VARCHAR(500),
    eb_order_id     VARCHAR(255),
    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Append-only. NEVER UPDATE or DELETE rows from this table.
-- from_state is nullable — null only on initial AVAILABLE creation from provisioning.
CREATE INDEX idx_audit_seat     ON seat_lock_audit_log(seat_id);
CREATE INDEX idx_audit_slot     ON seat_lock_audit_log(show_slot_id);
CREATE INDEX idx_audit_user     ON seat_lock_audit_log(user_id);
CREATE INDEX idx_audit_occurred ON seat_lock_audit_log(occurred_at);
```

---

## Stage 5 — API Contracts

### Seat Availability

#### `GET /api/v1/booking/shows/{slotId}/available-seats`
**Auth:** JWT `ROLE_USER`
**No Eventbrite call. Internal DB only.**

**Response 200 — RESERVED:**
```json
{
  "slotId": 101,
  "seatingMode": "RESERVED",
  "seats": [
    {
      "seatId": 5001,
      "seatNumber": "A1",
      "rowLabel": "A",
      "section": "Orchestra",
      "tierId": 55,
      "tierName": "VIP",
      "basePrice": { "amount": 1500.00, "currency": "INR" },
      "lockState": "AVAILABLE"
    }
  ]
}
```

**Response 200 — GA:**
```json
{
  "slotId": 101,
  "seatingMode": "GA",
  "tiers": [
    {
      "tierId": 55,
      "tierName": "Standard",
      "quota": 200,
      "available": 143,
      "basePrice": { "amount": 500.00, "currency": "INR" }
    }
  ]
}
```

**Response 503:** `{ "errorCode": "EB_EVENT_NOT_SYNCED", "slotId": 101 }`

---

### Cart Management

#### `POST /api/v1/booking/cart/add-seat`
**Auth:** JWT `ROLE_USER`

**Request:**
```json
{ "slotId": 101, "seatId": 5001, "tierId": 55, "quantity": 1 }
```
*`seatId` is `null` for GA. `quantity` must be ≥ 1.*

**Response 200 — `CartResponse`:**
```json
{
  "cartId": 999,
  "slotId": 101,
  "status": "PENDING",
  "expiresAt": "2026-03-04T12:05:00Z",
  "seatingMode": "RESERVED",
  "items": [
    {
      "itemId": 1,
      "seatId": 5001,
      "seatNumber": "A1",
      "tierId": 55,
      "tierName": "VIP",
      "quantity": 1,
      "unitPrice": { "amount": 1500.00, "currency": "INR" },
      "discountApplied": { "amount": 0.00, "currency": "INR" }
    }
  ],
  "subtotal": { "amount": 1500.00, "currency": "INR" },
  "discountAmount": { "amount": 0.00, "currency": "INR" },
  "total": { "amount": 1500.00, "currency": "INR" }
}
```

**Response 409 — Seat unavailable:**
```json
{
  "errorCode": "SEAT_UNAVAILABLE",
  "requestedSeatId": 5001,
  "alternatives": {
    "sameSection": [
      { "seatId": 5002, "seatNumber": "A2", "section": "Orchestra", "tierId": 55 }
    ],
    "adjacentSection": [
      { "seatId": 6010, "seatNumber": "B1", "section": "Mezzanine", "tierId": 55 }
    ]
  }
}
```

**Response 409 — GA insufficient inventory:**
```json
{ "errorCode": "INSUFFICIENT_GA_INVENTORY", "tierId": 55, "requested": 3, "available": 1 }
```

**Response 503 — Slot not synced:** `{ "errorCode": "EB_EVENT_NOT_SYNCED", "slotId": 101 }`

---

#### `DELETE /api/v1/booking/cart/items/{itemId}`
**Auth:** JWT `ROLE_USER`

**Response 200:** Updated `CartResponse` with recomputed pricing (discount may be removed if tier drops below threshold)
**Response 404:** `{ "errorCode": "CART_ITEM_NOT_FOUND", "itemId": 1 }`
**Response 409:** `{ "errorCode": "SEAT_REMOVAL_NOT_ALLOWED", "lockState": "HARD_LOCKED" }`

---

#### `GET /api/v1/booking/cart/{cartId}`
**Auth:** JWT `ROLE_USER`

**Response 200:** `CartResponse`
**Response 404:** `{ "errorCode": "CART_NOT_FOUND" }`

---

#### `POST /api/v1/booking/cart/confirm`
**Auth:** JWT `ROLE_USER`

**Request:** `{ "cartId": 999 }`

**Response 200:** `CartResponse` with updated `status = "PENDING"`, `expiresAt` extended to `NOW() + 30 min`

**Response 409 — Tier sold out:**
```json
{
  "errorCode": "TIER_SOLD_OUT",
  "soldOutTierId": 55,
  "alternatives": {
    "otherTiers": [{ "tierId": 56, "tierName": "Standard", "available": 12 }]
  }
}
```

**Response 410 — Soft lock expired:**
```json
{
  "errorCode": "SOFT_LOCK_EXPIRED",
  "expiredSeatIds": [5001, 5003],
  "message": "These seats expired while you were browsing. Please re-select."
}
```

**Response 410 — Cart expired:** `{ "errorCode": "CART_EXPIRED", "cartId": 999, "expiredAt": "2026-03-04T12:05:00Z" }`

**Response 503 — Eventbrite unreachable:**
```json
{ "errorCode": "EB_UNAVAILABLE", "retryAfterSeconds": 10 }
```
*Soft locks preserved. User can retry within remaining TTL.*

---

#### `POST /api/v1/booking/cart/abandon`
**Auth:** JWT `ROLE_USER`

**Request:** `{ "cartId": 999 }`
**Response 200:** `{ "message": "Cart abandoned. All seat locks released." }`

---

### Admin Endpoints

#### `GET /api/v1/admin/booking/slots/{slotId}/seat-map`
**Auth:** JWT `ROLE_ADMIN`
Returns full seat map with all lock states, `lockedByUserId`, and `lockedUntil` for admin visibility.

#### `POST /api/v1/admin/booking/slots/{slotId}/provision-seats`
**Auth:** JWT `ROLE_ADMIN`
Manually trigger seat provisioning for a RESERVED slot (fallback if `ShowSlotActivatedEvent` was missed). Idempotent — no-op if seats already exist.

---

### Payment Callback Endpoints *(owned by `payments-ticketing`)*

#### `POST /api/v1/payments/checkout/confirm`
Receives `orderId` from frontend after Eventbrite `onOrderComplete`. Reads order via `EbOrderService`. Publishes `BookingConfirmedEvent`.

#### `POST /api/v1/payments/checkout/cancel`
Frontend signals widget closed without completion. Publishes `PaymentFailedEvent` → `booking-inventory` releases all seat locks.

---

### Response Code Reference

| Code | Scenario |
|---|---|
| `200` | Success |
| `404` | Cart / cart item not found |
| `409` | Seat unavailable / tier sold out / duplicate cart / seat in wrong state for removal |
| `410` | Soft lock expired / cart expired |
| `422` | Invalid state machine transition |
| `500` | DB write failed after Redis lock acquired |
| `503` | `eb_event_id` null (`EB_EVENT_NOT_SYNCED`) / Eventbrite unreachable (`EB_UNAVAILABLE`) |

---

## Stage 6 — Business Logic

### A — Seat Provisioning on `ShowSlotActivatedEvent`

`SeatProvisioningService` listens to `ShowSlotActivatedEvent(slotId, ebEventId, orgId, venueId, seatingMode)`:

**RESERVED:**
1. Check `IF EXISTS (SELECT 1 FROM seats WHERE show_slot_id = slotId)` — if yes, skip (idempotent).
2. REST call to `discovery-catalog`: `GET /api/v1/catalog/venues/{venueId}/seat-layout` → list of `VenueSeat(seatNumber, rowLabel, section, tierName)`.
3. Call `SlotPricingReader.getSlotPricing(slotId)` → `List<PricingTierDto>` of `(tierId, tierName, price, quota, ebTicketClassId, groupDiscountThreshold, groupDiscountPercent)`. This is an in-process call to `SlotPricingReaderImpl` in `scheduling` — no HTTP.
4. Bulk-insert `seats` rows: one per `VenueSeat`, matching `section` → `tierName` → `pricing_tier_id`. Denormalize `eb_ticket_class_id` onto each seat row.
5. Insert one `GroupDiscountRule` per pricing tier.

**GA:**
1. Call `SlotPricingReader.getSlotPricing(slotId)` for pricing tiers (same as above — in-process call).
2. `GaInventoryService.initCounters(slotId, List<PricingTierDto> tiers)` → `SET ga:available:{slotId}:{tierId} {quota}` per tier via Lua.
3. Insert one `GroupDiscountRule` per pricing tier.

---

### B — Availability Read Rules

- **RESERVED:** `SELECT * FROM seats WHERE show_slot_id = :slotId AND (lock_state = 'AVAILABLE' OR (lock_state = 'SOFT_LOCKED' AND locked_until < NOW()))`
- **GA:** Primary source = Redis counter value. Fallback if counter missing = `quota - COUNT(*) FROM ga_inventory_claims WHERE lock_state = 'CONFIRMED' AND show_slot_id = :slotId AND pricing_tier_id = :tierId`.
- **Tier blocked flag:** Check Redis `GET tier:blocked:{slotId}:{tierId}` — if set, mark tier as `BLOCKED` in response; `add-seat` rejects with `TIER_INVENTORY_BLOCKED`.

---

### C — State Machine Transition Rules

All DB operations within actions use `SELECT FOR UPDATE` on the seat row.

**SELECT → SOFT_LOCKED (`SoftLockAction`):**
1. Redis Lua acquire (NX) — if `CONFLICT`, guard returns false, alternatives served.
2. DB: `UPDATE seats SET lock_state='SOFT_LOCKED', locked_until=NOW()+5min, locked_by_user_id=:userId`.
3. If DB fails → Redis rollback (`DEL seat:lock:{seatId}`) → throw `SeatLockException`.
4. Write `seat_lock_audit_log` (AVAILABLE → SOFT_LOCKED, event=SELECT).

**CONFIRM → HARD_LOCKED (`HardLockAction`):**
1. Redis Lua ownership-checked extend (extend TTL to 1800s).
2. DB: `UPDATE seats SET lock_state='HARD_LOCKED', locked_until=NOW()+30min`.
3. If DB fails → compensate: Redis release Lua + DB revert to SOFT_LOCKED with original TTL → throw `HardLockException`.
4. Write `seat_lock_audit_log`.
5. `cart.expires_at = NOW() + 30 min`.

**CHECKOUT_INITIATE → PAYMENT_PENDING (`PaymentPendingAction`):**
1. DB state update only (`lock_state='PAYMENT_PENDING'`).
2. Redis TTL NOT extended — hard lock TTL continues.
3. Write `seat_lock_audit_log`.

**CONFIRM_PAYMENT → CONFIRMED (`ConfirmAction`):**
1. Idempotency check: `IF seat.eb_order_id IS NULL` — if already set, no-op.
2. DB: `UPDATE seats SET lock_state='CONFIRMED', eb_order_id=:orderId, locked_by_user_id=NULL, locked_until=NULL`.
3. Redis release Lua (DEL key, no longer needed).
4. Write `seat_lock_audit_log` (PAYMENT_PENDING → CONFIRMED, reason=ORDER_PLACED).

**RELEASE → AVAILABLE (`ReleaseAction`):**
1. DB UPDATE first: `lock_state='AVAILABLE', locked_by_user_id=NULL, locked_until=NULL` — seat immediately queryable as available.
2. Redis release Lua (ownership-checked DEL).
3. Write `seat_lock_audit_log` with reason code from event header.
4. Reason codes: `TTL_EXPIRED | USER_REMOVED | CART_ABANDONED | PAYMENT_FAILED | ORDER_ABANDONED | PAYMENT_TIMEOUT | TIER_SOLD_OUT`

---

### D — Group Discount Computation (always full recompute)

`CartPricingService.recompute(Cart cart)` runs on every `add-seat` and `remove-seat`:

```
For each unique pricing_tier_id in cart:
  tierItems = cart.items.filter(i -> i.tierId == tierId)
  count      = sum(tierItems.quantity)
  rule       = GroupDiscountRule for (slotId, tierId)
  
  if count >= rule.threshold:
    tierDiscount = sum(item.basePrice * item.quantity for item in tierItems)
                   * (rule.discountPercent / 100)
  else:
    tierDiscount = 0

cartSubtotal    = sum(all items, basePrice * quantity)
cartDiscount    = sum(all tierDiscounts)
cartTotal       = cartSubtotal - cartDiscount
cart.discountAmount = cartDiscount
```

Key invariants:
- Always recompute from scratch — never increment.
- Removing a seat that drops a tier below threshold removes the discount retroactively from all remaining items.
- Eventbrite fees are NOT included — additive at checkout widget time.

---

### E — Cart Confirm Guard Logic

`CartTtlGuard` checks three things in order:

1. `cart.status = PENDING AND cart.expires_at > NOW()` — else throw `CartExpiredException`.
2. For each seat in cart: Redis Lua GET `seat:lock:{seatId}` — if `NOT_OWNER` or `EXPIRED` → collect all failed seatIds → throw `SoftLockExpiredException(expiredSeatIds)`.
3. For each unique `eb_ticket_class_id`: call `EbTicketService.getTicketClass(ebEventId, tcId)` — if `on_sale_status = SOLD_OUT` → release all soft locks for that tier → throw `TierSoldOutException`.

Only if all three pass does the guard return `true` and `HardLockAction` fires.

---

### F — Concurrent Conflict Scenarios

| Scenario | Behaviour |
|---|---|
| Two users click same seat simultaneously | Loser: Redis `CONFLICT` → `409` with 5 nearest same-section + 3 adjacent-section alternatives |
| Double-submit (network retry) | Redis `ALREADY_YOURS` → idempotent success; `CartItem` upsert on `(cart_id, seat_id)` is no-op |
| Soft lock expires at confirm | `410 SOFT_LOCK_EXPIRED` with expired seat IDs; user must re-select |
| Partial `CONFIRM_PAYMENT` batch failure | Entire `@Transactional` rolls back; all seats stay `PAYMENT_PENDING`; watchdog retries; idempotent via `eb_order_id IS NULL` check |
| EB webhook arrives before `CartAssembledEvent` | `payments-ticketing` stores in `pending_order_events`; matched on `CartAssembledEvent` arrival; discarded after 10-min window |

---

### G — Background Job Logic

**`SoftLockCleanupJob` (every 60s):**
- Bulk UPDATE: `SET lock_state='AVAILABLE', locked_by_user_id=NULL, locked_until=NULL WHERE lock_state='SOFT_LOCKED' AND locked_until < NOW()`
- DELETE expired `ga_inventory_claims` + restore Redis counter via `INCRBY` Lua per released quantity
- Write one `seat_lock_audit_log` row per seat cleaned up

**`CartExpiryCleanupJob` (every 5 min):**
- Find `PENDING` carts where `expires_at < NOW()` → mark `EXPIRED`
- Fire `RELEASE` on all remaining `SOFT_LOCKED` seats in expired carts
- Do NOT touch `HARD_LOCKED` or `PAYMENT_PENDING` seats — those belong to the watchdog

**`PaymentTimeoutWatchdog` (every 60s):**
- Find seats where `lock_state IN ('HARD_LOCKED', 'PAYMENT_PENDING') AND locked_until < NOW()`
- Per seat: call `EbOrderService` — if no placed order → fire `RELEASE(reason=PAYMENT_TIMEOUT)`
- If `EbOrderService` call fails: **skip this seat on this cycle** (log WARN, retry next run)
- Per-seat failure must NOT abort the entire watchdog batch
- Publish `PaymentTimeoutEvent(cartId, List<seatId>, userId)`

**`EbInventoryValidationJob` (every 2 min):**
- Call `EbTicketService.listTicketClasses(ebEventId)` per ACTIVE slot with `eb_event_id`
- If any tier `on_sale_status = SOLD_OUT` → `SET tier:blocked:{slotId}:{tierId} 1 EX 300` in Redis; emit `InventoryDriftDetectedEvent(slotId, tierId, ebStatus)` (log WARN)
- If EB call fails: log WARN, skip this slot — do NOT block availability reads

---

### H — Eventbrite Call Rules

| Operation | EB Call? | Why |
|---|---|---|
| Availability read | No | `quantity_sold` lags; internal DB is authoritative |
| `add-seat` / soft lock | No | Redis + DB only |
| `remove-seat` | No | Internal operation only |
| `cart/confirm` (SOFT→HARD) | **Yes — one call per unique tier** | Sanity check `on_sale_status` before entering checkout |
| Payment confirm callback | Yes (`EbOrderService.getOrder`) | Read order status, store `eb_order_id` |
| `PaymentTimeoutWatchdog` | Yes (`EbOrderService.getOrder`) | Verify no valid order before releasing |
| Background validation | Yes (`EbTicketService.listTicketClasses`) | Detect EB-side SOLD_OUT drift |

---

## Stage 7 — Error Handling

All exceptions handled by `GlobalExceptionHandler` in `shared/common/exception/`. No `@ControllerAdvice` in `booking-inventory`. *(Hard Rule #5)*

### A — Pre-condition Failures

| Scenario | Exception | HTTP | Error Code | Notes |
|---|---|---|---|---|
| Slot not found | `ResourceNotFoundException` | 404 | `SLOT_NOT_FOUND` | |
| Slot not ACTIVE | `BusinessRuleException` | 409 | `SLOT_NOT_ACTIVE` | Returns `currentStatus` |
| `eb_event_id` null | `BusinessRuleException` | 503 | `EB_EVENT_NOT_SYNCED` | Never 404 — Hard Rule #10 |
| Tier missing `eb_ticket_class_id` | `BusinessRuleException` | 503 | `TIER_NOT_SYNCED` | Returns `tierId` |
| Org token not connected | `EbAuthException` propagated | 503 | `ORG_NOT_CONNECTED` | Do NOT expose token details |
| User not active | `BusinessRuleException` | 403 | `USER_INACTIVE` | |
| Duplicate PENDING cart | DB unique constraint caught as `BusinessRuleException` | 409 | `CART_ALREADY_EXISTS` | Return existing `cartId` so frontend can resume |

### B — Seat Selection / Soft Lock Failures

| Scenario | HTTP | Error Code | Compensation |
|---|---|---|---|
| Redis `CONFLICT` — seat locked by another user | 409 | `SEAT_UNAVAILABLE` | Return alternatives (5 same-section + 3 adjacent-section) |
| DB write fails after Redis lock acquired | 500 | `SEAT_LOCK_DB_FAILURE` | Redis DEL (ownership-checked) before rethrow; seat returns to AVAILABLE |
| GA `INSUFFICIENT` from Redis counter | 409 | `INSUFFICIENT_GA_INVENTORY` | Return `requested` and `available` counts |
| GA `NO_COUNTER` from Redis | 503 (after fallback attempt) | `GA_COUNTER_NOT_INITIALIZED` | Try DB fallback first; 503 only if DB also fails |
| Tier blocked by `EbInventoryValidationJob` | 409 | `TIER_INVENTORY_BLOCKED` | Return `reason: EB_SOLD_OUT_DETECTED` |

### C — Cart Confirm Failures

| Scenario | HTTP | Error Code | Action |
|---|---|---|---|
| Cart not found | 404 | `CART_NOT_FOUND` | |
| Cart `status != PENDING` | 409 | `CART_NOT_PENDING` | Return `currentStatus` |
| `cart.expires_at <= NOW()` | 410 | `CART_EXPIRED` | Return `expiredAt`; user must start new cart |
| Redis `NOT_OWNER`/`EXPIRED` on ownership check | 410 | `SOFT_LOCK_EXPIRED` | Release still-owned locks; return list of `expiredSeatIds` |
| Eventbrite `SOLD_OUT` for tier | 409 | `TIER_SOLD_OUT` | Release all soft locks for sold-out tier; return alternative tiers |
| Eventbrite unreachable / timeout | 503 | `EB_UNAVAILABLE` | **Do NOT hard lock**; soft locks preserved; return `retryAfterSeconds: 10` |
| DB write fails in `HardLockAction` | 500 | `HARD_LOCK_DB_FAILURE` | Redis TTL extended — compensate: Redis release Lua + DB revert to SOFT_LOCKED |

### D — Cart Item Removal Failures

| Scenario | HTTP | Error Code | Action |
|---|---|---|---|
| Cart item not found | 404 | `CART_ITEM_NOT_FOUND` | |
| Seat in `HARD_LOCKED` / `PAYMENT_PENDING` / `CONFIRMED` | 409 | `SEAT_REMOVAL_NOT_ALLOWED` | Return current `lockState` |
| Redis `NOT_OWNER` on removal Lua | 200 (still succeeds) | — | Log WARN; proceed with DB release; Redis key will expire naturally |

### E — Background Job Failures

| Job | Failure | Behaviour |
|---|---|---|
| `SoftLockCleanupJob` | DB batch update fails partway | Log ERROR; continue next run — partial cleanup better than none |
| `SoftLockCleanupJob` | GA Redis `INCRBY` fails after DB delete | Log ERROR; reconcile on next `EbInventoryValidationJob` run |
| `PaymentTimeoutWatchdog` | `EbOrderService` call fails/times out | **Skip seat on this cycle** — do NOT release; retry next 60s interval |
| `PaymentTimeoutWatchdog` | `RELEASE` transition fails for one seat | Log ERROR; continue remaining seats — per-seat failure must not abort batch |
| `EbInventoryValidationJob` | EB call fails | Log WARN; skip slot; do NOT block availability reads or add-seat |

### F — Event / Async Failures

| Scenario | Behaviour |
|---|---|
| `ShowSlotActivatedEvent` received; seats already exist | `SeatProvisioningService` checks `IF EXISTS` — idempotent no-op |
| `BookingConfirmedEvent` arrives; cart already `CONFIRMED` | `ConfirmAction` checks `eb_order_id IS NULL` — if already set, no-op; log DEBUG |
| `PaymentFailedEvent` arrives; seats already `AVAILABLE` | `PaymentFailedListener` checks `lock_state` before firing RELEASE — if already AVAILABLE, no-op; log DEBUG |
| Partial `CONFIRM_PAYMENT` batch DB failure | Entire `@Transactional` rolls back; all seats stay `PAYMENT_PENDING`; watchdog retries; idempotent via `eb_order_id IS NULL` |
| EB `order.placed` webhook arrives before `CartAssembledEvent` | Store in `pending_order_events (eb_order_id, eb_event_id, payload, received_at)`; match on `CartAssembledEvent`; discard after 10-min window with WARN log |

### G — Invalid State Machine Transitions

```json
{
  "errorCode": "INVALID_SEAT_TRANSITION",
  "seatId": 5001,
  "currentState": "CONFIRMED",
  "attemptedEvent": "SELECT"
}
```
HTTP `422 UNPROCESSABLE_ENTITY`. Thrown when `sm.sendEvent()` returns `accepted = false`.

---

## Stage 8 — Tests

### Layer 1 — `domain/` (Pure Unit Tests — no Spring)

**Cart & Pricing:**
- `should_apply_group_discount_to_all_items_when_threshold_crossed`
- `should_remove_group_discount_when_item_removed_drops_tier_below_threshold`
- `should_not_apply_group_discount_when_threshold_not_met`
- `should_compute_cart_total_correctly_with_mixed_tier_discounts`
- `should_always_recompute_discount_from_scratch_never_incrementally`

**Cart Item Constraints:**
- `should_reject_ga_cart_item_with_non_null_seat_id`
- `should_reject_reserved_cart_item_with_null_seat_id`
- `should_reject_cart_item_with_both_seat_id_and_ga_claim_id_null`

**Cart Lifecycle:**
- `should_mark_cart_expired_when_expires_at_in_past`
- `should_extend_cart_ttl_correctly`
- `should_transition_cart_to_confirmed`
- `should_transition_cart_to_abandoned`

### Layer 2 — `statemachine/` (Mockito — no full Spring context)

**AvailabilityGuard:**
- `should_pass_when_seat_is_AVAILABLE`
- `should_pass_when_seat_is_SOFT_LOCKED_and_expired` *(expired lock counts as available)*
- `should_fail_when_redis_returns_CONFLICT`
- `should_fail_when_seat_is_SOFT_LOCKED_and_not_expired`

**CartTtlGuard:**
- `should_pass_when_cart_pending_and_redis_owned_and_eb_tier_available`
- `should_fail_when_cart_expired`
- `should_fail_when_redis_returns_NOT_OWNER`
- `should_fail_when_redis_returns_EXPIRED`
- `should_fail_and_throw_TierSoldOutException_when_eb_tier_SOLD_OUT`
- `should_fail_gracefully_and_preserve_soft_locks_when_eb_unreachable`

**Actions:**
- `should_rollback_redis_lock_when_db_write_fails_in_SoftLockAction`
- `should_compensate_redis_and_db_when_HardLockAction_db_write_fails`
- `should_be_idempotent_when_ConfirmAction_called_twice_for_same_seat`
- `should_release_redis_key_in_ConfirmAction`
- `should_update_db_first_then_release_redis_in_ReleaseAction`
- `should_write_audit_log_on_every_transition`

**Invalid Transitions:**
- `should_throw_InvalidSeatTransitionException_on_illegal_transition`
- `should_throw_when_RELEASE_attempted_on_CONFIRMED_seat`

### Layer 3 — `service/` (Mockito + Spring slices)

**SlotSummaryReaderImplTest** *(scheduling module)*
- `should_return_SlotSummaryDto_with_all_fields_mapped_when_slot_exists`
- `should_map_orgId_from_ShowSlot_getOrganizationId`
- `should_map_status_as_string_via_enum_name`
- `should_throw_ResourceNotFoundException_when_slot_absent`

**SlotPricingReaderImplTest** *(scheduling module)*
- `should_return_mapped_list_of_PricingTierDto_when_tiers_exist`
- `should_return_empty_list_when_no_tiers_for_slot`
- `should_throw_ResourceNotFoundException_when_slot_does_not_exist`
- `should_map_tierId_tierName_price_groupDiscount_correctly`

**CartPricingServiceTest — cache-aside** *(booking-inventory module)*
- `getSlotPricingCached_should_return_cached_list_when_redis_hit_and_not_call_reader`
- `getSlotPricingCached_should_call_reader_and_write_cache_on_redis_miss`
- `getSlotPricingCached_should_fallback_to_reader_when_redis_read_throws`
- `getSlotPricingCached_should_not_propagate_exception_when_redis_write_fails`
- `getSlotPricingCached_should_fallback_to_reader_when_cached_json_is_corrupt`

**SeatAvailabilityService:**
- `should_return_available_seats_excluding_non_expired_locks`
- `should_include_expired_soft_locked_seats_in_available_response`
- `should_return_ga_tier_availability_from_redis_counter`
- `should_fallback_to_db_when_redis_counter_missing`
- `should_mark_tier_blocked_when_redis_block_flag_set`

**CartService:**
- `should_create_new_cart_on_first_add_seat_for_user_slot`
- `should_upsert_existing_pending_cart_on_repeat_add_seat`
- `should_reject_add_seat_when_slot_not_ACTIVE`
- `should_reject_add_seat_when_eb_event_id_null`
- `should_return_409_with_alternatives_when_seat_unavailable`
- `should_recompute_full_cart_total_on_every_add_and_remove`

**CartService — Confirm:**
- `should_publish_CartAssembledEvent_with_orgId_and_ebEventId_on_confirm`
- `should_not_publish_CartAssembledEvent_when_confirm_fails`
- `should_extend_cart_expires_at_to_30_min_on_hard_lock`

**SeatProvisioningService:**
- `should_bulk_insert_seats_from_venue_seat_layout_on_slot_activated`
- `should_be_idempotent_when_seats_already_exist_for_slot`
- `should_init_ga_redis_counters_from_tier_quotas_on_ga_slot_activated`

**SlotTicketSyncServiceTest** *(updated — mocks replaced)*
- `should_call_createTicketClasses_and_inventoryTiers_when_syncing` — stubs `SlotSummaryReader` + `SlotPricingReader`; no `SchedulingSlotClient`
- `should_call_copySeatMap_when_seatingMode_is_RESERVED` — stubs both readers
- `should_not_call_copySeatMap_when_seatingMode_is_GA`
- `should_publish_TicketSyncFailedEvent_when_ticket_class_creation_fails`

**ConflictResolutionService:**
- `should_return_up_to_5_same_section_alternatives`
- `should_return_up_to_3_adjacent_section_alternatives_when_same_section_insufficient`

### Layer 4 — `api/` (`@WebMvcTest`)

- `should_return_200_with_seat_map_for_active_reserved_slot`
- `should_return_200_with_ga_tier_availability_for_active_ga_slot`
- `should_return_503_with_EB_EVENT_NOT_SYNCED_when_eb_event_id_null`
- `should_return_409_with_alternatives_when_seat_unavailable`
- `should_return_200_with_group_discount_applied_in_cart_response`
- `should_return_410_when_soft_lock_expired_at_confirm`
- `should_return_409_when_tier_sold_out_on_eventbrite_at_confirm`
- `should_return_503_when_eventbrite_unreachable_at_confirm`
- `should_return_410_when_cart_expired_at_confirm`
- `should_return_409_with_existing_cart_id_when_duplicate_cart_attempted`
- `should_return_401_when_no_jwt_on_any_booking_endpoint`
- `should_return_422_when_invalid_state_transition_attempted`

### Layer 5 — `arch/` (ArchUnit)

**`BookingInventoryArchTest` — two explicit named rules (updated):**
- `should_not_depend_on_scheduling_domain_service_or_repository` — `noClasses().inPackage(..bookinginventory..).should().dependOnClassesThat().resideInAnyPackage(..scheduling.domain.., ..scheduling.service.., ..scheduling.repository..)` *(enforces Hard Rule #1 — no direct scheduling internals)*
- `should_use_shared_slot_reader_contracts_not_scheduling_http_client` — `noClasses().inPackage(..bookinginventory..).should().dependOnClassesThat().haveSimpleNameEndingWith("SchedulingSlotClient")` *(confirms dead client is gone)*

**Other ArchUnit rules:**
- `should_not_import_identity_entity_or_repository_in_booking_inventory`
- `should_not_import_payments_ticketing_in_booking_inventory`
- `should_not_call_eventbrite_http_directly_from_booking_inventory_service`
- `should_not_declare_ControllerAdvice_in_booking_inventory`
- `should_only_save_audit_log_no_update_or_delete`
- `should_not_publish_CartAssembledEvent_without_orgId_and_ebEventId`
- `should_only_publish_events_with_primitives_ids_enums_no_entities` *(Hard Rule #4)*
- `should_enforce_mapper_layer_for_all_domain_to_dto_conversions`

---

## Identified Gaps & Required Changes to Other Modules

> **All 5 gaps listed below were fully resolved in the pre-FR4 changes session (2026-03-04).** They are kept here for traceability. No further action required before coding FR4.

### Gap 1 — `orgId` JWT claim missing ✅ RESOLVED

**Problem:** Every Eventbrite call in `booking-inventory` (`CartTtlGuard`, `PaymentTimeoutWatchdog`) needs `orgId` to call `EbTokenStore.getValidToken(orgId)`. The original JWT spec only carried `sub` (userId) and `role`.

**Resolution applied:**
- `shared/.../security/JwtTokenProvider.java` — added `generateToken(Long userId, String role, Long orgId)` overload and `extractOrgId(String token)`
- `shared/.../security/AuthenticatedUser.java` — new record `AuthenticatedUser(Long userId, String role, Long orgId)` replaces raw `Long` as `SecurityContext` principal
- `shared/.../security/JwtAuthenticationFilter.java` — builds `AuthenticatedUser` principal from JWT claims on every request
- All three identity controllers updated to cast principal to `AuthenticatedUser`

Updated JWT claim contract:

| Claim | Value |
|---|---|
| `sub` | `userId` as String |
| `role` | `USER` or `ORG_MANAGER` |
| `orgId` | `organizationId` as Long *(added)* |
| `iat`, `exp`, `jti`, `typ` | as per SPEC_AUTH |

### Gap 2 — No access path for `ShowSlotPricingTier` data from `booking-inventory` ✅ RESOLVED

**Problem:** `booking-inventory` needs `basePrice`, `groupDiscountThreshold`, `groupDiscountPercent`, and `eb_ticket_class_id` from `scheduling`'s `show_slot_pricing_tier` table.

**Resolution applied (pre-FR4):**
- `scheduling/.../api/controller/ShowSlotController.java` — added `GET /api/v1/scheduling/slots/{id}/pricing-tiers` (for admin/external consumers)
- `scheduling/.../service/ShowSlotService.java` — added `getPricingTiers(Long slotId)`
- `scheduling/.../api/dto/response/ShowSlotPricingTierResponse.java` — added `ebInventoryTierId`, `groupDiscountThreshold`, `groupDiscountPercent` fields

**Superseded by interface migration (2026-03-04):**
- `booking-inventory` **no longer calls the REST endpoint** — it uses `SlotPricingReader` (in-process `@Service` bean in `scheduling`, injected via shared interface)
- `CartPricingService.getSlotPricingCached(Long slotId)` provides cache-aside (`StringRedisTemplate`, JSON via `ObjectMapper`, TTL 10 min, key `pricing:slot:{slotId}`) for the hot path
- The REST endpoint is preserved and continues to serve admin and any future external consumers
- `SchedulingSlotClient`, `SchedulingSlotResponse`, `SchedulingPricingTierResponse` deleted from `booking-inventory`

### Gap 3 — No REST endpoint for venue seat layout ✅ RESOLVED

**Problem:** `SeatProvisioningService` needs the venue's physical floor plan to create `Seat` rows.

**Resolution applied:**
- `discovery-catalog/.../domain/VenueSeat.java` — new JPA entity (table: `venue_seat`, V18 migration)
- `discovery-catalog/.../repository/VenueSeatRepository.java` — `findByVenueId`, `findByVenueIdAndTierName`, `countByVenueId`
- `discovery-catalog/.../api/controller/VenueCatalogController.java` — added `GET /api/v1/catalog/venues/{venueId}/seat-layout`
- `discovery-catalog/.../service/VenueCatalogService.java` — added `getVenueSeatLayout(Long venueId)`
- Response DTOs: `VenueSeatResponse`, `VenueSeatLayoutResponse`

### Gap 4 — `CartAssembledEvent` payload insufficient ✅ RESOLVED

**Problem:** Event only had `cartId` and `slotId`. `payments-ticketing` needs `orgId` and `ebEventId`.

**Resolution applied:**
```java
// shared/.../event/published/CartAssembledEvent.java
public record CartAssembledEvent(
    Long cartId,
    Long slotId,
    Long userId,
    Long orgId,
    String ebEventId,
    String couponCode   // nullable
) {}
```

### Gap 5 — `GroupDiscountRule` ownership conflict ✅ RESOLVED

**Problem:** Two places defined group discount data. Ownership was ambiguous.

**Resolution applied:**
- `show_slot_pricing_tier` in `scheduling` is the single authoritative source (V17 migration added `group_discount_threshold` and `group_discount_percent` columns)
- `ShowSlotPricingTier` entity's `setGroupDiscount()` domain method enforces validation rules
- `GroupDiscountRule` in `booking-inventory` (V23 migration) remains a **denormalized local copy** populated at provisioning time from REST data — not a separate source of truth
- No separate `PricingTier` entity in `booking-inventory`

---

## Finalized Decisions

| Decision | Value |
|---|---|
| State machine persistence | Rebuilt from DB on each call — no SSM Redis persistence |
| Redis role | Authoritative real-time lock; DB is durable mirror; Redis loss → fallback to `SELECT FOR UPDATE` |
| EB call placement | Inline only at confirm (SOFT→HARD) — once per cart; never on seat click |
| Availability source | Internal DB only on hot path; background job detects EB drift |
| GA model | `GaInventoryClaim` + Redis atomic counter — no phantom seat rows |
| Group discount ownership | `show_slot_pricing_tier` (scheduling) authoritative; denormalized into `group_discount_rules` (booking-inventory) at provisioning via `SlotPricingReader`; hot-path cache via `CartPricingService.getSlotPricingCached()` |
| Scheduling data access | In-process `SlotSummaryReader` + `SlotPricingReader` shared interfaces (not REST HTTP); implemented in `scheduling`, injected into `booking-inventory` |
| Pricing tier cache | `CartPricingService.getSlotPricingCached()` — key `pricing:slot:{slotId}`, TTL 10 min, `StringRedisTemplate` + `ObjectMapper` JSON; Redis failure → transparent fallback to reader, no exception propagated |
| Audit log | Append-only, every transition, no exceptions |
| JWT `orgId` claim | Must be added to `JwtTokenProvider` (update SPEC_AUTH) |
| Seat provisioning source | `discovery-catalog` `VenueSeat` floor plan — not Eventbrite |
| Cart uniqueness | Partial UNIQUE index on `(user_id, show_slot_id) WHERE status = 'PENDING'` |
| Flyway version range | V19–V24 (`seats`, `ga_inventory_claims`, `carts`, `cart_items`, `group_discount_rules`, `seat_lock_audit_log`). V17 and V18 were consumed by pre-FR4 changes. |

---

## Inter-Module Event Map (FR4 additions)

| Publisher | Event | Consumer | Payload | Purpose |
|---|---|---|---|---|
| `scheduling` | `ShowSlotActivatedEvent` | `booking-inventory` | `slotId, ebEventId, orgId, venueId, cityId, seatingMode` | Slot ACTIVE → provision seats (RESERVED) or init GA counters |
| `scheduling` | `ShowSlotCancelledEvent` | `booking-inventory` | `slotId, orgId` | Slot cancelled → release ALL seats regardless of lock state |
| `booking-inventory` | `CartAssembledEvent` | `payments-ticketing` | `cartId, slotId, userId, orgId, ebEventId, couponCode` | Cart confirmed → initiate Eventbrite checkout widget |
| `booking-inventory` | `PaymentTimeoutEvent` | `payments-ticketing` | `cartId, List<seatId>, userId` | Watchdog: expired lock with no order → mark booking abandoned |
| `payments-ticketing` | `PaymentFailedEvent` | `booking-inventory` | `cartId, List<seatId>, userId` | Widget closed without completion → release seat locks |
| `payments-ticketing` | `BookingConfirmedEvent` | `booking-inventory` | `cartId, List<seatId>, ebOrderId, userId` | Order placed → transition seats to CONFIRMED, store `eb_order_id` |
