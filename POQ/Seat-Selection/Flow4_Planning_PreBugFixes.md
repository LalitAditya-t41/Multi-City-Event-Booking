User: Now Lets create a SPEC_4.md for the Seat Selection → Pricing Tier Validation → Cart Assembly — Validate seat availability against Eventbrite API with tier pricing and group discount rules read the product.md file for more context, and the other SPEC_*.md for the previous build. Write the outline for stage one for verification. Read the Flow4.md file for more clear understanding

GitHub Copilot: Searched for files matching `**/POQ/**/*.md`, 17 matches

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#1-1), lines 1 to 120

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#120-120), lines 120 to 400

Searched for files matching `**/POQ/User-Register/**`, 2 matches

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/User-Register/SPEC_3.MD#1-1), lines 1 to 100

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#400-400), lines 400 to 700

I now have full context from FLOW4.md, SPEC_1, SPEC_2, SPEC_3, and PRODUCT.md. Here is the **Stage 1 — Requirements Outline** for SPEC_4.md:

---

## SPEC_4.md — Stage 1 Requirements Outline

**Feature:** FR4 — Seat Selection → Pricing Tier Validation → Cart Assembly
**Module:** booking-inventory (primary) | `payments-ticketing` (event consumer) | scheduling (REST read-only)
**Depends on:** SPEC_2 (`show_slot` ACTIVE + `eb_event_id` populated), SPEC_3 (JWT), SPEC_AUTH (`JwtAuthenticationFilter`)

---

### Area A — Pre-conditions & Slot Guard

- `[MUST]` Before exposing seat availability, verify `show_slot.status = ACTIVE` via REST GET to scheduling.
- `[MUST]` If `eb_event_id` is null on the slot, return `503` with `EB_EVENT_NOT_SYNCED` — not a 404. *(Hard Rule #10)*
- `[MUST]` If any `eb_ticket_class_id` is null on a pricing tier, block cart confirm with `TIER_NOT_SYNCED`.
- `[MUST]` If org token is not in `CONNECTED` state (`EbTokenStore.getValidToken`), block checkout initiation.
- `[MUST]` Reject requests from users whose `status != ACTIVE`.
- `[MUST]` Reject a second `PENDING` cart for the same `(userId, slotId)` — enforced by partial unique index.

---

### Area B — Seat Availability Read

- `[MUST]` Expose `GET /api/v1/booking/shows/{slotId}/available-seats`.
- `[MUST]` For `RESERVED` seating: return seats where `lock_state = AVAILABLE` OR (`SOFT_LOCKED` AND `locked_until < NOW()`).
- `[MUST]` For `GA` seating: return per-tier quota and available quantity (`quota - confirmed_count`), via REST to scheduling.
- `[MUST]` No Eventbrite call on this path — internal DB only.

---

### Area C — Seat Selection / Soft Lock

- `[MUST]` Expose `POST /api/v1/booking/cart/add-seat` (JWT `ROLE_USER`).
- `[MUST]` Acquire Redis lock atomically via Lua script (`SET NX PX`) before any DB write.
- `[MUST]` If Redis NX returns `CONFLICT`, throw `SeatAlreadyLockedException` → 409 with up to 5 alternative seats in same section/tier.
- `[MUST]` On successful Redis lock, DB-update seat to `SOFT_LOCKED` with `locked_until = now + 5 min`.
- `[MUST]` If DB write fails after Redis lock acquired, rollback Redis key (`DEL`) before rethrowing.
- `[MUST]` Upsert `Cart` (one per `(userId, slotId)`, `PENDING`); create `CartItem`.
- `[MUST]` For `GA`: decrement Redis atomic counter (`ga:available:{slotId}:{tierId}`) via Lua; reject with `INSUFFICIENT_INVENTORY` if count < requested.
- `[MUST]` Handle double-submit idempotency: Redis `ALREADY_YOURS` result → treat as success, upsert `CartItem` without duplication.
- `[SHOULD]` Expose `DELETE /api/v1/booking/cart/remove-seat` that fires `RELEASE` event and recomputes cart total.

---

### Area D — Pricing Tier Validation & Cart Total

- `[MUST]` Re-compute cart total inline on every `add-seat` and `remove-seat` call — never deferred.
- `[MUST]` Pricing source of truth is `show_slot_pricing_tier` from scheduling — read via REST, never imported.
- `[MUST]` Apply group discount per tier: if `cartItemsForTier >= groupDiscountThreshold`, apply `groupDiscountPercent` to ALL items in that tier retroactively.
- `[MUST]` Removing a seat that drops count below threshold removes the discount for the entire tier retroactively.
- `[MUST]` Group discount is always recomputed from scratch — never incrementally accumulated.
- `[SHOULD]` Return `CartResponse` including line items, per-tier subtotals, discount breakdown, and `cartTotal`.

---

### Area E — Cart Confirm / Hard Lock

- `[MUST]` Expose `POST /api/v1/booking/cart/confirm` (JWT `ROLE_USER`).
- `[MUST]` Verify cart `status = PENDING` and `expires_at > now`.
- `[MUST]` Verify Redis lock is still owned by this user for every seat (ownership-checked Lua script).
- `[MUST]` Call `EbTicketService.getTicketClass(ebEventId, ebTicketClassId)` — this is the **only Eventbrite call on the hot path** — verify `on_sale_status = AVAILABLE`.
- `[MUST]` If any tier is `SOLD_OUT` on Eventbrite: release all soft locks for that tier, return `409 TIER_SOLD_OUT` with alternatives.
- `[MUST]` Transition all seats `SOFT_LOCKED → HARD_LOCKED`, extend `locked_until = now + 30 min`.
- `[MUST]` Refresh Redis TTLs to 1800 seconds and extend `cart.expires_at` to match.
- `[MUST]` Publish `CartAssembledEvent(cartId, slotId, userId, orgId)` — `payments-ticketing` listens.

---

### Area F — Distributed Seat Lock State Machine

- `[MUST]` Implement `SeatLockStateMachine` in `booking-inventory/statemachine/` using Spring State Machine.
- `[MUST]` States: `AVAILABLE → SOFT_LOCKED → HARD_LOCKED → PAYMENT_PENDING → CONFIRMED`; `RELEASE` from any non-CONFIRMED state returns to `AVAILABLE`.
- `[MUST]` Machine is rebuilt from `seats.lock_state` DB column on each call — no SSM store in Redis.
- `[MUST]` All Redis operations (acquire, extend, release, GA claim) are Lua scripts — no Java read-then-write races.
- `[MUST]` Write to `seat_lock_audit_log` on every state transition (append-only, never update/delete).
- `[MUST]` `AvailabilityGuard` (SELECT), `CartTtlGuard` (CONFIRM) must be distinct Spring beans.

---

### Area G — Background Cleanup Jobs

- `[MUST]` `SoftLockCleanupJob` (every 60s): set `lock_state = AVAILABLE` for all `SOFT_LOCKED` seats where `locked_until < NOW()`.
- `[MUST]` `PaymentTimeoutWatchdog` (every 60s): for `HARD_LOCKED` / `PAYMENT_PENDING` seats with `locked_until < NOW()`, check `EbOrderService` — if no order, fire `RELEASE` event.
- `[MUST]` On watchdog release, publish `PaymentTimeoutEvent(cartId, List<seatId>, userId)` → `payments-ticketing` marks booking attempt abandoned.
- `[SHOULD]` GA: restore Redis counter on any RELEASE event atomically via `INCRBY` Lua.

---

### Area H — Spring Events (Inter-Module)

- `[MUST]` Listen to `SlotDraftCreatedEvent(slotId, ebEventId, pricingTierIds, seatingMode, orgId)` from scheduling to provision seats (RESERVED mode) or initialize GA counters.
- `[MUST]` Publish `CartAssembledEvent(cartId, slotId, userId, orgId)` after hard lock — `payments-ticketing` listens.
- `[MUST]` Listen to `BookingConfirmedEvent(cartId, List<seatId>, ebOrderId)` from `payments-ticketing` → transition seats `PAYMENT_PENDING → CONFIRMED`.
- `[MUST]` Listen to `PaymentFailedEvent(cartId, List<seatId>)` from `payments-ticketing` → fire `RELEASE` on all seats.
- `[MUST]` Spring Events carry only primitives, IDs, enums — never `@Entity` objects. *(Hard Rule #4)*

---

### Area I — Conflict Resolution

- `[MUST]` On `SEAT_UNAVAILABLE` (409): return up to 5 nearest alternative seats in same section/tier.
- `[MUST]` On `SOFT_LOCK_EXPIRED` (410): return list of expired `seatIds` and prompt re-selection.
- `[SHOULD]` On `TIER_SOLD_OUT` (409): return available alternative tiers or adjacent slots.

---

### Area J — Out of Scope

- `[WONT]` Payment processing (owned by `payments-ticketing`, FR5).
- `[WONT]` Coupon/promotion application (owned by `promotions`, FR7).
- `[WONT]` E-ticket generation (owned by `payments-ticketing`).
- `[WONT]` Eventbrite seat map creation — seats provisioned from internal venue floor plan data only.
- `[WONT]` Admin seat override or manual lock controls.

---

Does this look complete? Anything to add, remove, or change before I write Stage 1 in full and move to SPEC_4.md?

User: Good next stage

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#155-155), lines 155 to 310

## Stage 2 — Domain Modeling Outline

Here are the entities, their identities, lifecycles, relationships, and ownership:

---

### Entities Owned by booking-inventory

**Seat** *(RESERVED seating only)*
- Identity: `id` (BIGSERIAL); unique by `(show_slot_id, seat_number)`
- Lifecycle: `AVAILABLE → SOFT_LOCKED → HARD_LOCKED → PAYMENT_PENDING → CONFIRMED`; any non-CONFIRMED state can RELEASE back to `AVAILABLE`
- Relationships: belongs to one `show_slot` (scalar ID, no JPA FK to scheduling); belongs to one pricing tier (scalar `pricing_tier_id`); optionally referenced by one `CartItem`
- Ownership: booking-inventory; provisioned from internal venue floor plan on `SlotDraftCreatedEvent`

**GaInventoryClaim** *(GA seating only)*
- Identity: `id`; unique by `(show_slot_id, pricing_tier_id, user_id, cart_id)`
- Lifecycle: `SOFT_LOCKED → HARD_LOCKED → CONFIRMED` or `RELEASE → (removed)`
- Relationships: belongs to one `show_slot` and one pricing tier (both scalar IDs); linked to one `Cart`
- Ownership: booking-inventory

**Cart**
- Identity: `id`; business identity `(user_id, show_slot_id)` where `status = PENDING` (partial unique index)
- Lifecycle: `PENDING → CONFIRMED | ABANDONED | EXPIRED`
- Relationships: one `Cart` has many `CartItems`; references one `show_slot` (scalar ID); references one user (scalar `user_id`)
- Ownership: booking-inventory

**CartItem**
- Identity: `id`
- Lifecycle: created on seat selection; deleted on seat removal; finalized when cart confirms
- Relationships: belongs to one `Cart`; optionally references one `Seat` (RESERVED: NOT NULL) or one `GaInventoryClaim` (GA: NOT NULL); stores scalar `pricing_tier_id` + denormalized `eb_ticket_class_id`
- Ownership: booking-inventory; constraint: exactly one of `seat_id` or `ga_claim_id` must be set

**GroupDiscountRule**
- Identity: `id`; unique by `(show_slot_id, pricing_tier_id)`
- Lifecycle: created when slot is activated (from `SlotDraftCreatedEvent` pricing tier data); updated if admin edits pricing
- Relationships: one rule per pricing tier per slot; used by `CartPricingService` for group discount computation
- Ownership: booking-inventory; mirrors data from scheduling's `show_slot_pricing_tier` — stored here to avoid REST calls on every cart operation

**SeatLockAuditLog**
- Identity: `id` (BIGSERIAL, append-only)
- Lifecycle: immutable — one row written per state transition, never updated or deleted
- Relationships: references `seat_id` (nullable — null for GA), `ga_claim_id` (nullable), `show_slot_id`, `user_id`
- Ownership: booking-inventory

---

### Non-Persisted Operational Concepts

**SeatLockStateMachine** *(in-memory per request)*
- Not persisted in SSM store; rebuilt from `seats.lock_state` DB column on each invocation
- Guards: `AvailabilityGuard` (SELECT), `CartTtlGuard` + `EbTierCheckGuard` (CONFIRM)
- Actions: `SoftLockAction`, `HardLockAction`, `PaymentPendingAction`, `ConfirmAction`, `ReleaseAction`

**Redis Seat Lock** *(key: `seat:lock:{seatId}` or `ga:available:{slotId}:{tierId}`)*
- Not persisted in DB; TTL-based; all operations via Lua script only
- RESERVED: NX key per seat, value = `userId`
- GA: atomic counter per `(slotId, tierId)`

---

### Read-Only External References (not owned by this module)

**ShowSlotPricingTier** — owned by scheduling; read via REST; never imported as `@Entity`
**User** — owned by identity; scalar `user_id` stored on entities; JWT claims used for auth

---

### Domain Summary

A `Cart` belongs to one user (by scalar ID) and one `ShowSlot` (by scalar ID). Only one `PENDING` cart may exist per `(user, slot)` pair at a time. A `Cart` has many `CartItems`. In RESERVED mode, each `CartItem` references one `Seat` which progresses through the lock state machine. In GA mode, each `CartItem` references one `GaInventoryClaim` representing a quantity reservation against a tier pool. A `GroupDiscountRule` governs whether a tier's items in the cart qualify for bulk pricing. Every state transition on a `Seat` or `GaInventoryClaim` writes an immutable row to `SeatLockAuditLog`.

---

Does this look complete? Anything to add, remove, or change before I write Stage 2 in full?

User: Good next stage

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/docs/MODULE_STRUCTURE_TEMPLATE.md#1-1), lines 1 to 120

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java)

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/booking-inventory/src/main -type f -name "*.java" | sort

Read [](file:///home/dell/Multi-City-Event-Booking/docs/MODULE_STRUCTURE_TEMPLATE.md#120-120), lines 120 to 273

## Stage 3 — Architecture & File Structure Outline

**Owning module:** booking-inventory (primary)
**Supporting modules touched:** shared (Redis + `EbTicketService`); `payments-ticketing` (two new event listeners)
**Read-only via REST:** scheduling (no new files — existing `SchedulingSlotClient` reused)

---

### New Files — booking-inventory

**`api/controller/`**
- `SeatAvailabilityController` — `GET /api/v1/booking/shows/{slotId}/available-seats`
- `CartController` — `POST /api/v1/booking/cart/add-seat`, `DELETE /api/v1/booking/cart/remove-seat`, `POST /api/v1/booking/cart/confirm`, `GET /api/v1/booking/cart/{cartId}`

**`api/dto/request/`**
- `AddSeatRequest` — `slotId`, `seatId` (nullable), `tierId`, `quantity`
- `RemoveSeatRequest` — `cartId`, `seatId` (nullable), `tierId`, `quantity`
- `ConfirmCartRequest` — `cartId`

**`api/dto/response/`**
- `AvailableSeatResponse` — `seatId`, `seatNumber`, `rowLabel`, `section`, `tierId`, `basePrice`
- `GaTierAvailabilityResponse` — `tierId`, `tierName`, `quota`, `available`, `basePrice`
- `CartResponse` — `cartId`, `slotId`, `status`, `expiresAt`, `items`, `subtotal`, `discountAmount`, `total`, `currency`
- `CartItemResponse` — `seatId` (nullable), `tierId`, `tierName`, `quantity`, `unitPrice`, `discountApplied`
- `SeatAlternativesResponse` — `unavailableSeatId`, `alternatives: List<AvailableSeatResponse>`

**`domain/`**
- `Seat` — entity with `lockState`, `lockedByUserId`, `lockedUntil`, behavior methods `softLock()`, `hardLock()`, `confirm()`, `release()`
- `GaInventoryClaim` — entity with `quantity`, `lockState`, `lockedUntil`
- `Cart` — entity with `status`, `expiresAt`, `discountAmount`; methods `confirm()`, `abandon()`, `isExpired()`
- `CartItem` — entity; constraint: exactly one of `seatId` or `gaClaimId` not null
- `GroupDiscountRule` — value-object-like entity: `slotId`, `tierId`, `threshold`, `discountPercent`
- `SeatLockAuditLog` — append-only entity; no update/delete methods

**`domain/` (enums)**
- `SeatLockState` — `AVAILABLE, SOFT_LOCKED, HARD_LOCKED, PAYMENT_PENDING, CONFIRMED, RELEASED`
- `SeatLockEvent` — `SELECT, CONFIRM, CHECKOUT_INITIATE, CONFIRM_PAYMENT, RELEASE`
- `CartStatus` — `PENDING, CONFIRMED, ABANDONED, EXPIRED`
- `SeatingMode` — `RESERVED, GA`
- `LockReleaseReason` — `TTL_EXPIRED, USER_REMOVED, CART_ABANDONED, PAYMENT_FAILED, ORDER_ABANDONED, PAYMENT_TIMEOUT, TIER_SOLD_OUT`

**`repository/`**
- `SeatRepository`
- `GaInventoryClaimRepository`
- `CartRepository`
- `CartItemRepository`
- `GroupDiscountRuleRepository`
- `SeatLockAuditLogRepository`

**`service/`**
- `SeatAvailabilityService` — reads available seats / GA tier inventory
- `CartService` — orchestrates add-seat, remove-seat, confirm-cart; delegates to state machine
- `CartPricingService` — reads pricing tiers via REST, applies group discount rules, computes `CartTotal`
- `SeatProvisioningService` — listens to `SlotDraftCreatedEvent`, creates `Seat` rows (RESERVED) or Redis GA counters (GA); populates `GroupDiscountRule`
- `ConflictResolutionService` — finds alternative seats on 409 SEAT_UNAVAILABLE
- `SoftLockCleanupJob` — `@Scheduled` every 60s, releases expired soft locks
- `PaymentTimeoutWatchdog` — `@Scheduled` every 60s, verifies no EB order then releases hard/payment-pending locks

**`service/redis/`**  *(new sub-package for Lua script executors)*
- `SeatLockRedisService` — all Lua scripts: acquire soft lock, extend to hard lock, ownership-checked release
- `GaInventoryRedisService` — GA atomic counter: claim, release (`INCRBY`/`DECRBY` Lua)

**`statemachine/`**
- `SeatLockStateMachineConfig` — `@EnableStateMachineFactory`, transitions + guards + actions wired
- `SeatLockStateMachineService` — rebuilds machine from DB state, sends events, catches invalid transitions
- `guard/AvailabilityGuard` — SELECT: checks DB state + executes Redis Lua acquire
- `guard/CartTtlGuard` — CONFIRM: checks cart TTL + Redis ownership + calls `EbTicketService`
- `action/SoftLockAction`
- `action/HardLockAction`
- `action/PaymentPendingAction`
- `action/ConfirmAction`
- `action/ReleaseAction`

**`mapper/`**
- `SeatMapper` — `Seat` ↔ `AvailableSeatResponse`
- `CartMapper` — `Cart` + `CartItem` list ↔ `CartResponse`

**`event/published/`**
- `CartAssembledEvent` — `cartId`, `slotId`, `userId`, `orgId`
- `PaymentTimeoutEvent` — `cartId`, `seatIds: List<Long>`, `userId`

**`event/listener/`**
- `SlotDraftCreatedListener` *(already exists — extend to handle seat provisioning and GA counter init)*
- `BookingConfirmedListener` — fires `CONFIRM_PAYMENT` on each seat; populates `eb_order_id`
- `PaymentFailedListener` — fires `RELEASE` on all cart seats

**`exception/`**
- `SeatAlreadyLockedException` — 409
- `SeatUnavailableException` — 409
- `SoftLockExpiredException` — 410
- `HardLockException` — 500 (DB write failure after Redis extend)
- `TierSoldOutException` — 409
- `InvalidSeatTransitionException` — 422
- `CartExpiredException` — 410
- `CartNotFoundException` — 404

---

### New Files — `payments-ticketing` *(event consumers only)*

**`event/listener/`**
- `CartAssembledListener` — receives `CartAssembledEvent`; initiates Eventbrite Checkout Widget context; returns `eb_event_id` to frontend

---

### No New Files — scheduling
`SchedulingSlotClient` (already exists) is reused for slot + pricing tier reads. No FR4-specific additions.

### No New Files — shared
`EbTicketService` (already exists) is called by `CartTtlGuard`. No new ACL facades needed for FR4.

---

Does this look complete? Anything to add, remove, or change before I write Stage 3 in full?

User: Good Next stage 

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#700-700), lines 700 to 900

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/app/src/main/resources -name "V*.sql" | sort

## Stage 4 — DB Schema Outline

Last migration is **V16** (`create_password_reset_tokens`). FR4 migrations start at **V17**.

---

### V17 — `seats` *(RESERVED seating only)*

Key fields: `id`, `show_slot_id`, `pricing_tier_id` (scalar, no JPA FK to scheduling), `eb_ticket_class_id` (denormalized at provisioning time), `seat_number`, `row_label`, `section`, `lock_state` (enum), `locked_by_user_id` (scalar, no FK to `users`), `locked_until`, `eb_order_id`

Indexes: `(show_slot_id, lock_state)`, `locked_until` (partial, where not AVAILABLE), `locked_by_user_id` (partial, where SOFT/HARD/PAYMENT_PENDING)

---

### V18 — `ga_inventory_claims` *(GA seating only)*

Key fields: `id`, `show_slot_id`, `pricing_tier_id`, `user_id` (scalar), `cart_id`, `quantity`, `lock_state`, `locked_until`, `eb_order_id`

Unique constraint: `(show_slot_id, pricing_tier_id, user_id, cart_id)`

Indexes: `(show_slot_id, pricing_tier_id, lock_state)`, `(user_id, lock_state)`, `locked_until` (partial)

---

### V19 — `carts`

Key fields: `id`, `user_id` (scalar), `show_slot_id`, `org_id`, `seating_mode` (enum: RESERVED/GA), `status` (enum: PENDING/CONFIRMED/ABANDONED/EXPIRED), `expires_at`, `coupon_code`, `discount_amount`, `currency`

Partial unique index: `(user_id, show_slot_id)` WHERE `status = 'PENDING'` — enforces one active cart per user per slot

Indexes: `user_id`, `show_slot_id`, `status`, `expires_at` (partial where PENDING)

---

### V20 — `cart_items`

Key fields: `id`, `cart_id`, `seat_id` (nullable FK → `seats`), `ga_claim_id` (nullable FK → `ga_inventory_claims`), `pricing_tier_id` (scalar), `eb_ticket_class_id` (denormalized), `base_price_amount`, `currency`, `quantity`

Check constraint: exactly one of `seat_id` or `ga_claim_id` must be non-null (enforces RESERVED vs GA mode)

Indexes: `cart_id`, `seat_id`, `pricing_tier_id`

---

### V21 — `group_discount_rules`

Key fields: `id`, `show_slot_id`, `pricing_tier_id`, `group_discount_threshold` (min quantity to trigger discount), `group_discount_percent`

Unique constraint: `(show_slot_id, pricing_tier_id)` — one rule per tier per slot

Indexes: `(show_slot_id, pricing_tier_id)`

---

### V22 — `seat_lock_audit_log`

Key fields: `id`, `seat_id` (nullable — null for GA), `ga_claim_id` (nullable), `show_slot_id`, `user_id`, `from_state` (nullable — null on initial AVAILABLE creation), `to_state`, `event_type` (e.g. `SELECT`, `RELEASE`), `reason` (e.g. `PAYMENT_FAILED`), `eb_order_id`, `occurred_at`

Note: append-only — no `updated_at`; no delete constraint. Every transition writes exactly one row.

Indexes: `seat_id`, `show_slot_id`, `user_id`, `occurred_at`

---

Does this look complete? Anything to add, remove, or change before I write Stage 4 in full?

User: Good next stage

GitHub Copilot: ## Stage 5 — API Outline

**Module:** booking-inventory (all endpoints below unless noted)

---

### Seat Availability

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/booking/shows/{slotId}/available-seats` | Returns available seat map (RESERVED) or tier inventory (GA); no Eventbrite call; internal DB only |

---

### Cart Management

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/booking/cart/add-seat` | Acquires Redis soft lock + creates/upserts cart and cart item; recomputes cart total with group discounts |
| `DELETE` | `/api/v1/booking/cart/items/{itemId}` | Releases soft lock for a single item; removes cart item; recomputes cart total |
| `GET` | `/api/v1/booking/cart/{cartId}` | Returns current cart state — items, pricing breakdown, discount, total, TTL remaining |
| `POST` | `/api/v1/booking/cart/confirm` | Validates Redis ownership + EB tier availability; transitions all seats to HARD_LOCKED; publishes `CartAssembledEvent` |
| `POST` | `/api/v1/booking/cart/abandon` | Explicitly abandons a PENDING cart; fires RELEASE on all soft-locked seats; marks cart ABANDONED |

---

### Admin / Internal

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/booking/slots/{slotId}/seat-map` | Admin view of full seat map including all lock states and locked-by-user info |
| `POST` | `/api/v1/admin/booking/slots/{slotId}/provision-seats` | Manually trigger seat provisioning for a RESERVED slot (fallback if `SlotDraftCreatedEvent` was missed) |

---

### Payment Callback (owned by `payments-ticketing`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/payments/checkout/confirm` | Receives `orderId` from frontend after `onOrderComplete`; reads order via `EbOrderService`; publishes `BookingConfirmedEvent` |
| `POST` | `/api/v1/payments/checkout/cancel` | Frontend signals widget closed without completion; `payments-ticketing` publishes `PaymentFailedEvent` → booking-inventory releases locks |

---

### Response Codes Summary

| Code | Scenario |
|---|---|
| `200` | Success |
| `409` | Seat unavailable / tier sold out on Eventbrite — body includes `alternatives` |
| `410` | Soft lock expired / cart expired — body includes expired seat IDs |
| `422` | Invalid state machine transition attempted |
| `500` | DB write failed after Redis lock acquired |
| `503` | `eb_event_id` is null on slot — `EB_EVENT_NOT_SYNCED` |

---

Does this look complete? Anything to add, remove, or change before I write Stage 5 in full?

User: good next stage

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#550-550), lines 550 to 700

## Stage 6 — Business Logic Outline

Key rules, state transitions, and side effects to be documented in full:

---

### A — Seat Provisioning (on `SlotDraftCreatedEvent`)

- **RESERVED:** `SeatProvisioningService` creates one `Seat` row per physical seat from the internal venue floor plan (discovery-catalog venue data); each seat gets `lock_state = AVAILABLE`, `eb_ticket_class_id` denormalized from the matching pricing tier
- **GA:** Initialize one Redis atomic counter per tier (`ga:available:{slotId}:{tierId}` = `quota`); no `Seat` rows created
- **Both modes:** Create one `GroupDiscountRule` row per pricing tier capturing `threshold` and `discountPercent`
- **Idempotency:** provisioning must be a no-op if seats already exist for the slot (guard against duplicate events)

---

### B — Availability Read Rules

- RESERVED: a seat counts as available if `lock_state = AVAILABLE` OR (`lock_state = SOFT_LOCKED` AND `locked_until < NOW()`) — expired soft locks are optimistically shown as available
- GA: available quantity = Redis counter value preferred; DB fallback = `quota - sum(confirmed_count)` if counter is missing

---

### C — State Machine Transitions & Invariants

- Machine is rebuilt from DB `lock_state` on every call — no SSM persistence store
- All DB operations within actions use `SELECT FOR UPDATE` to prevent phantom reads
- **SELECT → SOFT_LOCKED:** Redis Lua NX acquire first; DB write second; if DB fails → Redis rollback (DEL); TTL = 5 minutes
- **CONFIRM → HARD_LOCKED:** Redis Lua ownership-checked extend; DB write second; if DB fails → compensate Redis + revert DB to SOFT_LOCKED; TTL extended to 30 minutes; `cart.expires_at` extended to match
- **CHECKOUT_INITIATE → PAYMENT_PENDING:** DB state update only; Redis TTL NOT extended (hard lock TTL continues)
- **CONFIRM_PAYMENT → CONFIRMED:** idempotent — check `eb_order_id IS NULL` before writing; DB update sets `locked_by_user_id = NULL`, `locked_until = NULL`; Redis key released
- **RELEASE (any source → AVAILABLE):** DB update first (seat immediately queryable as available); then Redis DEL; reason code written to audit log

---

### D — Group Discount Computation (always full recompute)

- On every `add-seat` or `remove-seat`, `PricingCalculationService` performs a scratch recompute — never incremental
- Per tier: count `CartItems` for that tier across the entire cart
- If `count >= groupDiscountThreshold` → apply `groupDiscountPercent` to ALL items of that tier (including previously added ones)
- If removing a seat drops a tier below threshold → discount removed from all remaining items of that tier retroactively
- `cart.discount_amount` = sum of all tier discounts; `cartTotal` = `subtotal - discount_amount`
- Eventbrite fees are NOT included — they are additive at checkout widget time

---

### E — Cart Confirm Guard Logic (the only hot-path EB call)

- Verify `cart.status = PENDING` and `cart.expires_at > now`
- For each seat: Redis ownership check via Lua GET — if `NOT_OWNER` or `EXPIRED` → `SoftLockExpiredException(expiredSeatIds)`
- For each unique `eb_ticket_class_id` in cart: call `EbTicketService.getTicketClass(ebEventId, tcId)` — verify `on_sale_status = AVAILABLE`; if `SOLD_OUT` → release all soft locks for that tier → `TierSoldOutException` with alternatives

---

### F — Concurrent Conflict Resolution

- Two users same seat simultaneously: loser gets Redis `CONFLICT` → `409` with up to 5 nearest same-section/same-tier `AVAILABLE` alternatives + up to 3 adjacent-section alternatives
- Double-submit (network retry): Redis `ALREADY_YOURS` → idempotent success; `CartItem` upsert on `(cart_id, seat_id)` — no-op if exists
- Soft lock expiry at confirm time: `410 SOFT_LOCK_EXPIRED` with list of expired seat IDs — user must re-select
- Partial `CONFIRM_PAYMENT` batch failure: entire `@Transactional` rolls back; all seats revert to `PAYMENT_PENDING`; watchdog retries; idempotent via `eb_order_id IS NULL` check
- EB webhook arrives before `CartAssembledEvent`: `payments-ticketing` stores payload in `pending_order_events` table (keyed by `eb_order_id`); matched and immediately fired when event arrives; abandoned after 10-minute window

---

### G — Background Job Logic

- **`SoftLockCleanupJob` (60s):** bulk UPDATE seats to `AVAILABLE` where `SOFT_LOCKED AND locked_until < NOW()`; DELETE expired `ga_inventory_claims` and restore Redis counter via atomic `INCRBY`; write audit log per row
- **`CartExpiryCleanupJob` (5 min):** find `PENDING` carts where `expires_at < NOW()` → set `EXPIRED`; fire `RELEASE` on all remaining `SOFT_LOCKED` seats; does NOT touch `HARD_LOCKED` or `PAYMENT_PENDING`
- **`PaymentTimeoutWatchdog` (60s):** find `HARD_LOCKED` / `PAYMENT_PENDING` seats where `locked_until < NOW()`; call `EbOrderService` to verify no placed order; if none → fire `RELEASE(reason=PAYMENT_TIMEOUT)`; publish `PaymentTimeoutEvent`
- **`EbInventoryValidationJob` (2 min):** call `EbTicketService.listTicketClasses(ebEventId)` per ACTIVE slot; if any tier `on_sale_status = SOLD_OUT` → write `SET tier:blocked:{slotId}:{tierId} 1 EX 300` to Redis; log `WARN` and emit `InventoryDriftDetectedEvent`

---

### H — Eventbrite Call Rules

- `add-seat` / `remove-seat`: **NO** Eventbrite call
- Availability read: **NO** Eventbrite call (`quantity_sold` lags, not reliable for hot path)
- `cart/confirm` (SOFT_LOCKED → HARD_LOCKED): **ONE** call per unique tier in cart via `EbTicketService.getTicketClass()`
- Post-`onOrderComplete`: `EbOrderService.getOrder(orderId)` — order status read, `eb_order_id` stored
- Background validation only: `EbTicketService.listTicketClasses()` / `EbTicketService.getInventoryTier()` every 2 minutes

---

### I — Audit Log Rule

Every state transition — without exception — writes one append-only row to `seat_lock_audit_log`. No transition silently succeeds. No rows are ever updated or deleted from this table.

---

Does this look complete? Anything to add, remove, or change before I write Stage 6 in full?

User: goo next stage

GitHub Copilot: ## Stage 7 — Error Handling Outline

Failure scenarios to be documented (not full messages/actions yet — just the scenarios):

---

### A — Pre-condition Failures (before any lock attempt)

1. Slot not found
2. Slot not `ACTIVE` (DRAFT, PENDING_SYNC, CANCELLED)
3. `eb_event_id` is null on slot
4. Any pricing tier has null `eb_ticket_class_id`
5. Org token not in `CONNECTED` state (`EbAuthException` from token store)
6. Requesting user's `status != ACTIVE`
7. A `PENDING` cart already exists for this `(userId, slotId)`

---

### B — Seat Selection / Soft Lock Failures

8. Redis Lua returns `CONFLICT` — seat already locked by another user
9. DB write fails after Redis lock successfully acquired (compensation: Redis DEL before rethrow)
10. GA Redis counter returns `INSUFFICIENT` — not enough GA inventory for requested quantity
11. GA Redis counter returns `NO_COUNTER` — counter missing (slot not provisioned)
12. `tier:blocked:{slotId}:{tierId}` flag is set in Redis — tier blocked by `EbInventoryValidationJob`
13. Double-submit idempotency — Redis `ALREADY_YOURS` (not an error — treated as success)

---

### C — Cart Confirm Failures

14. Cart not found
15. Cart `status != PENDING`
16. Cart `expires_at <= now` (cart expired before confirm)
17. Redis ownership check returns `NOT_OWNER` — lock expired or stolen between add and confirm
18. Redis ownership check returns `EXPIRED` — soft lock TTL passed before cart confirm
19. Eventbrite returns `on_sale_status = SOLD_OUT` for a tier in the cart
20. Eventbrite unreachable / timeout during `CartTtlGuard` EB tier check
21. DB write fails during `HardLockAction` (compensation: Redis extend rolled back, DB reverted to SOFT_LOCKED)

---

### D — Cart Item Removal Failures

22. Cart item not found
23. Seat is not in `SOFT_LOCKED` state when removal attempted (already HARD_LOCKED or CONFIRMED)
24. Redis release returns `NOT_OWNER` on removal attempt

---

### E — Background Job Failures

25. `SoftLockCleanupJob` — DB batch update fails partway through
26. `SoftLockCleanupJob` — GA Redis `INCRBY` restore fails after DB claim deletion
27. `PaymentTimeoutWatchdog` — `EbOrderService` call fails/times out (should skip this seat on this cycle, retry next cycle)
28. `PaymentTimeoutWatchdog` — RELEASE transition fails for a seat (should log and continue, not abort the whole batch)
29. `EbInventoryValidationJob` — `EbTicketService` call fails (log warning, skip this slot, do NOT block availability reads)

---

### F — Event / Async Failures

30. `SlotDraftCreatedEvent` received but seat provisioning fails (should not re-provision if seats already exist — idempotency check)
31. `BookingConfirmedEvent` arrives but cart is already `CONFIRMED` (duplicate event delivery — idempotent no-op)
32. `PaymentFailedEvent` arrives but seats already `AVAILABLE` (already released by watchdog — idempotent no-op)
33. Partial `CONFIRM_PAYMENT` batch — one seat write fails, whole `@Transactional` rolls back, all revert to `PAYMENT_PENDING`
34. EB `order.placed` webhook arrives before `CartAssembledEvent` — stored in `pending_order_events`, matched within 10-minute window; unmatched after 10 minutes → log warning, discard

---

Does this look complete? Anything to add, remove, or change before I write Stage 7 in full?

User: Yes next stage 7

GitHub Copilot: ## Stage 7 — Error Handling (Full)

---

### A — Pre-condition Failures

**Slot not found**
- When: `slotId` does not exist in `show_slots`
- Response: `404 NOT_FOUND` — `{ "errorCode": "SLOT_NOT_FOUND", "slotId": "..." }`

**Slot not ACTIVE**
- When: `show_slot.status` is `DRAFT`, `PENDING_SYNC`, or `CANCELLED`
- Response: `409 CONFLICT` — `{ "errorCode": "SLOT_NOT_ACTIVE", "currentStatus": "CANCELLED" }`

**`eb_event_id` null on slot**
- When: slot exists and is `ACTIVE` but `eb_event_id IS NULL` (sync never completed)
- Response: `503 SERVICE_UNAVAILABLE` — `{ "errorCode": "EB_EVENT_NOT_SYNCED", "slotId": "..." }` *(Hard Rule #10 — never 404)*

**Pricing tier missing `eb_ticket_class_id`**
- When: `CartTtlGuard` finds any tier in cart with null `eb_ticket_class_id`
- Response: `503 SERVICE_UNAVAILABLE` — `{ "errorCode": "TIER_NOT_SYNCED", "tierId": "..." }`

**Org token not connected**
- When: `EbTokenStore.getValidToken(orgId)` throws `EbAuthException`
- Response: `503 SERVICE_UNAVAILABLE` — `{ "errorCode": "ORG_NOT_CONNECTED", "orgId": "..." }` — do NOT expose token details

**User not active**
- When: `users.status != ACTIVE` for the requesting userId from JWT
- Response: `403 FORBIDDEN` — `{ "errorCode": "USER_INACTIVE" }`

**Duplicate pending cart**
- When: `INSERT INTO carts` violates the partial unique index on `(user_id, show_slot_id) WHERE status = 'PENDING'`
- Response: `409 CONFLICT` — `{ "errorCode": "CART_ALREADY_EXISTS", "cartId": "<existingCartId>" }` — return the existing cart ID so the frontend can resume

---

### B — Seat Selection / Soft Lock Failures

**Seat locked by another user (`CONFLICT`)**
- When: Redis Lua acquire returns `CONFLICT`
- Action: call `ConflictResolutionService.findAlternatives(seatId, tierId, slotId)`
- Response: `409 CONFLICT`
  ```json
  {
    "errorCode": "SEAT_UNAVAILABLE",
    "requestedSeatId": 5001,
    "alternatives": {
      "sameSection": [{ "seatId": 5002, "seatNumber": "A2", "section": "Orchestra" }],
      "adjacentSection": [{ "seatId": 6010, "seatNumber": "B1", "section": "Mezzanine" }]
    }
  }
  ```

**DB write fails after Redis lock acquired**
- When: `SoftLockAction` DB UPDATE throws and Redis key is already set
- Action: execute Redis release Lua (ownership-checked DEL) before rethrowing
- Response: `500 INTERNAL_SERVER_ERROR` — `{ "errorCode": "SEAT_LOCK_DB_FAILURE", "seatId": "..." }` — Redis state is cleaned up; seat returns to `AVAILABLE` for other users immediately

**GA inventory insufficient**
- When: Redis Lua `DECRBY` check returns `INSUFFICIENT`
- Response: `409 CONFLICT` — `{ "errorCode": "INSUFFICIENT_GA_INVENTORY", "tierId": "...", "requested": 3, "available": 1 }`

**GA counter missing (`NO_COUNTER`)**
- When: Redis key `ga:available:{slotId}:{tierId}` does not exist (provisioning never ran or Redis flushed)
- Action: fallback — compute from DB (`quota - confirmed_count`); if still missing, return 503
- Response (if fallback also fails): `503 SERVICE_UNAVAILABLE` — `{ "errorCode": "GA_COUNTER_NOT_INITIALIZED", "slotId": "...", "tierId": "..." }`

**Tier blocked by inventory drift**
- When: Redis key `tier:blocked:{slotId}:{tierId}` is set (written by `EbInventoryValidationJob`)
- Response: `409 CONFLICT` — `{ "errorCode": "TIER_INVENTORY_BLOCKED", "tierId": "...", "reason": "EB_SOLD_OUT_DETECTED" }`

---

### C — Cart Confirm Failures

**Cart not found**
- Response: `404 NOT_FOUND` — `{ "errorCode": "CART_NOT_FOUND", "cartId": "..." }`

**Cart not in PENDING status**
- When: `cart.status` is `CONFIRMED`, `ABANDONED`, or `EXPIRED`
- Response: `409 CONFLICT` — `{ "errorCode": "CART_NOT_PENDING", "currentStatus": "EXPIRED" }`

**Cart TTL expired**
- When: `cart.expires_at <= now` at confirm time
- Response: `410 GONE` — `{ "errorCode": "CART_EXPIRED", "cartId": "...", "expiredAt": "..." }` — frontend must start a new cart

**Soft lock expired or stolen (Redis NOT_OWNER / EXPIRED)**
- When: `CartTtlGuard` Redis ownership check fails for one or more seats
- Action: release all soft locks in the cart that are still owner-verified; return the list of seats that failed
- Response: `410 GONE`
  ```json
  {
    "errorCode": "SOFT_LOCK_EXPIRED",
    "expiredSeatIds": [5001, 5003],
    "message": "These seats expired while you were browsing. Please re-select."
  }
  ```

**Eventbrite tier sold out at confirm**
- When: `EbTicketService.getTicketClass()` returns `on_sale_status = SOLD_OUT`
- Action: release all soft locks in cart for the sold-out tier; find alternative tiers/slots
- Response: `409 CONFLICT`
  ```json
  {
    "errorCode": "TIER_SOLD_OUT",
    "soldOutTierId": 55,
    "alternatives": {
      "otherTiers": [{ "tierId": 56, "tierName": "Standard", "available": 12 }]
    }
  }
  ```

**Eventbrite unreachable during `CartTtlGuard`**
- When: `EbTicketService` throws timeout or `EbIntegrationException`
- Action: do NOT hard lock; soft locks remain intact; return 503 so frontend can retry confirm
- Response: `503 SERVICE_UNAVAILABLE` — `{ "errorCode": "EB_UNAVAILABLE", "retryAfterSeconds": 10 }` — locks are preserved; user can retry within soft lock TTL window

**DB write fails during `HardLockAction`**
- When: UPDATE to `HARD_LOCKED` throws after Redis TTL was already extended
- Action: compensate — run Redis release Lua, then DB UPDATE back to `SOFT_LOCKED` with original TTL
- Response: `500 INTERNAL_SERVER_ERROR` — `{ "errorCode": "HARD_LOCK_DB_FAILURE" }` — seat reverts to soft lock; user may retry confirm within remaining soft lock TTL

---

### D — Cart Item Removal Failures

**Cart item not found**
- Response: `404 NOT_FOUND` — `{ "errorCode": "CART_ITEM_NOT_FOUND", "itemId": "..." }`

**Seat in wrong state for removal**
- When: seat is `HARD_LOCKED`, `PAYMENT_PENDING`, or `CONFIRMED` — cannot remove after cart confirm
- Response: `409 CONFLICT` — `{ "errorCode": "SEAT_REMOVAL_NOT_ALLOWED", "lockState": "HARD_LOCKED" }`

**Redis NOT_OWNER on removal**
- When: Redis release Lua returns `NOT_OWNER` (should not happen in normal flow — signals desync)
- Action: log `WARN` with `seatId` and `userId`; proceed with DB release anyway (DB is source of truth)
- Response: `200 OK` — removal succeeds; Redis state inconsistency is self-healing (key will expire naturally)

---

### E — Background Job Failures

**`SoftLockCleanupJob` batch DB failure**
- Action: catch exception, log `ERROR` with affected seat IDs, continue next run — do NOT abort; partial cleanup is better than no cleanup

**`SoftLockCleanupJob` GA Redis restore failure**
- When: DB claim deleted but `INCRBY` to Redis counter fails
- Action: log `ERROR` with `slotId`, `tierId`, `quantity`; schedule a reconciliation check on next `EbInventoryValidationJob` run; counter will be re-seeded from DB confirmed count on next background pass

**`PaymentTimeoutWatchdog` EB order check fails**
- When: `EbOrderService.getOrder()` times out or throws
- Action: **skip this seat on this cycle** — do NOT release; log `WARN`; retry on next 60s interval — better to leave in `HARD_LOCKED` briefly than to release a seat that may have a valid order

**`PaymentTimeoutWatchdog` RELEASE transition fails for one seat**
- Action: log `ERROR`, continue with remaining seats in the batch — per-seat failure must not abort the entire watchdog run

**`EbInventoryValidationJob` EB call fails**
- Action: log `WARN`, skip this slot for this cycle, do NOT block availability reads or soft locks — inventory validation is a best-effort background check, not a hard gate

---

### F — Event / Async Failures

**`SlotDraftCreatedEvent` received but seats already exist**
- Action: `SeatProvisioningService` checks `IF EXISTS (SELECT 1 FROM seats WHERE show_slot_id = slotId)` before inserting — if yes, skip silently (idempotent)

**`BookingConfirmedEvent` arrives but cart already `CONFIRMED`**
- When: duplicate event delivery from Spring Events retry
- Action: `ConfirmAction` checks `IF seat.eb_order_id IS NULL` before writing — if already set, no-op; log `DEBUG`; return without error

**`PaymentFailedEvent` arrives but seats already `AVAILABLE`**
- When: watchdog already released the seats before the event arrived
- Action: `PaymentFailedListener` checks current `lock_state` before firing `RELEASE`; if already `AVAILABLE`, no-op; log `DEBUG`

**Partial `CONFIRM_PAYMENT` batch failure**
- When: processing `BookingConfirmedEvent` — one of N seats fails the DB write
- Action: entire `@Transactional` rolls back; all seats remain `PAYMENT_PENDING`; `PaymentTimeoutWatchdog` retries on next cycle; since EB order exists, `EbOrderService` confirms it as `placed` and retry succeeds
- Do NOT publish an error event — let the watchdog retry silently

**EB `order.placed` webhook arrives before `CartAssembledEvent`**
- When: `EbWebhookDispatcher` fires `payments-ticketing` listener before booking-inventory has confirmed the cart
- Action: `payments-ticketing` stores the payload in `pending_order_events (eb_order_id, eb_event_id, payload, received_at)`; when `CartAssembledEvent` is processed, `payments-ticketing` checks `pending_order_events` for a matching `eb_event_id` → immediately fires `CONFIRM_PAYMENT`
- If no match within 10 minutes: log `WARN: unmatched order webhook`, discard row — do NOT confirm without a cart

---

### G — Invalid State Machine Transitions

**Any illegal transition attempted**
- When: `SeatLockStateMachineService.sendEvent()` returns `accepted = false`
- Response: `422 UNPROCESSABLE_ENTITY`
  ```json
  {
    "errorCode": "INVALID_SEAT_TRANSITION",
    "seatId": 5001,
    "currentState": "CONFIRMED",
    "attemptedEvent": "SELECT"
  }
  ```

---

Stage 7 complete. Ready to move to the final stage — **Stage 8 (Tests outline)**?

User: Now write the SPEC_4.md File 

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/FLOW4.md#900-900), lines 900 to 1159

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/User-Register/SPEC_3.MD#100-100), lines 100 to 444

Created 1 todo

Created [](file:///home/dell/Multi-City-Event-Booking/POQ/Seat-Selection/SPEC_4.md)

Completed: *Write full SPEC_4.md* (1/1)

SPEC_4.md has been written with all 8 stages complete.

**What's in the file:**

| Stage | Content |
|---|---|
| 1 — Requirements | 10 areas, 30+ MUST/SHOULD/WONT items covering pre-conditions, availability, locks, pricing, state machine, jobs, and events |
| 2 — Domain Model | 6 entities (`Seat`, `GaInventoryClaim`, `Cart`, `CartItem`, `GroupDiscountRule`, `SeatLockAuditLog`) + non-persisted Redis concepts |
| 3 — Architecture | Full file tree for booking-inventory + new endpoints required on scheduling and discovery-catalog |
| 4 — DB Schema | 6 migrations V17–V22 with full SQL including indexes, constraints, and the GA/RESERVED CHECK constraint |
| 5 — API Contracts | 7 endpoints with full request/response JSON, all error shapes and codes |
| 6 — Business Logic | State machine transitions, group discount recompute algorithm, guard logic, all 4 background job rules, EB call rules |
| 7 — Error Handling | 34 scenarios across 7 groups with compensation actions for each |
| 8 — Tests | 50+ test titles across 5 layers: domain, state machine, service, `@WebMvcTest`, ArchUnit |

**5 gaps documented** requiring changes to other modules — notably the `orgId` JWT claim (SPEC_AUTH), the pricing tier REST endpoint (scheduling), the seat layout endpoint (discovery-catalog), and the updated `CartAssembledEvent` payload.

Made changes.

User: Can u tell what the 5 gaps and the rest issues are list them out

GitHub Copilot: Here are all 5 gaps and the additional issues identified in SPEC_4.md:

---

## 5 Gaps (Changes Required to Other Modules)

**Gap 1 — `orgId` JWT claim missing** *(affects `shared/security`)*
- Current JWT only has `sub` (userId) and `role`
- `CartTtlGuard` and `PaymentTimeoutWatchdog` need `orgId` to call `EbTokenStore.getValidToken(orgId)` on every cart confirm
- Without it: hidden DB lookup on every hot-path confirm request
- **Fix:** Add `orgId` claim to `JwtTokenProvider.generateToken()` — update SPEC_AUTH.MD

**Gap 2 — No REST endpoint for pricing tier data** *(affects scheduling)*
- booking-inventory needs `basePrice`, `groupDiscountThreshold`, `groupDiscountPercent`, and `eb_ticket_class_id` from `show_slot_pricing_tier`
- Cannot import scheduling's `@Repository` or `@Entity` (Hard Rules #1 & #3)
- **Fix:** Add `GET /api/v1/scheduling/slots/{slotId}/pricing-tiers` to scheduling; booking-inventory caches result in Redis (TTL 10 min, key `pricing:slot:{slotId}`)

**Gap 3 — No REST endpoint for venue seat floor plan** *(affects discovery-catalog)*
- `SeatProvisioningService` needs the physical seat list (seatNumber, row, section, tierName) to bulk-insert `Seat` rows
- Eventbrite has **no API** to list individual seats from a copied seatmap
- **Fix:** Add `VenueSeat` entity to discovery-catalog (venue-level static data) + expose `GET /api/v1/catalog/venues/{venueId}/seat-layout`

**Gap 4 — `CartAssembledEvent` payload insufficient** *(affects `shared/event` + `payments-ticketing`)*
- Current definition only carries `cartId` and `slotId`
- `payments-ticketing` needs `orgId` (to resolve EB token) and `ebEventId` (to render the Checkout Widget)
- **Fix:** Update to:
  ```java
  public record CartAssembledEvent(
      Long cartId, Long slotId, Long userId,
      Long orgId, String ebEventId, String couponCode
  ) {}
  ```

**Gap 5 — Duplicate `GroupDiscountRule` ownership** *(affects booking-inventory schema)*
- The Implementation Guide placed a `PricingTier` entity (with discount fields) in booking-inventory
- SPEC_2's `show_slot_pricing_tier` (V9 migration, owned by scheduling) already has these fields — two conflicting sources of truth
- **Fix:** Remove the standalone `PricingTier` entity from booking-inventory. `GroupDiscountRule` (V21) is a denormalized local copy populated at provisioning time from REST data — scheduling remains the authoritative source

---

## Additional Issues from the Issues Table (FLOW4.md Section 14)

These are architectural/design issues resolved within FR4 itself:

| # | Issue | Resolution |
|---|---|---|
| 1 | GA seats cannot use Seat row model | Separate code path: `GaInventoryClaim` + Redis counter; `CartItem.seat_id` nullable |
| 2 | DB/Redis split-brain on write failure | Redis NX in guard → DB write in action → Redis rollback on DB failure; DB-first on release |
| 3 | Race condition on concurrent seat lock | Lua scripts for all Redis read-modify-write — no Java `GET + SET NX` races |
| 4 | Stale `SOFT_LOCKED` rows after Redis TTL expires | `SoftLockCleanupJob` every 60s; availability query treats expired locks as AVAILABLE |
| 5 | Group discount threshold crosses mid-session | Full cart recompute on every `add-seat` / `remove-seat` — never incremental |
| 6 | Eventbrite `quantity_sold` lags | Not used on hot path; called once at confirm; background validates every 2 min |
| 7 | `onOrderComplete` webhook arrives before `CartAssembledEvent` | `pending_order_events` store in `payments-ticketing`; 10-min reconciliation window |
| 8 | Payment failure produces no Eventbrite webhook | Path A: frontend `POST /checkout/cancel`; Path B: `PaymentTimeoutWatchdog` every 60s |
| 9 | `CartItem.seat_id NOT NULL` in Implementation Guide breaks GA | Change to nullable + CHECK constraint: exactly one of `seat_id` or `ga_claim_id` must be set |
| 10 | Partial unique index needed for cart uniqueness | `CREATE UNIQUE INDEX ... WHERE status = 'PENDING'` — allows multiple historical carts |
| 11 | Redis unavailable blocks entire seat selection | Fallback: if Redis ping fails, route through DB-only `SELECT FOR UPDATE`; add `RedisHealthIndicator` |
| 12 | RESERVED seatmap has no Eventbrite API | Use internal `VenueSeat` floor plan in discovery-catalog — EB seatmap is display only, not a data source |

---

**Summary of what needs updating across modules:**

| Module | Change |
|---|---|
| `shared/security` | Add `orgId` claim to `JwtTokenProvider` |
| SPEC_AUTH.MD | Update JWT claim contract table |
| scheduling | Add `GET /api/v1/scheduling/slots/{slotId}/pricing-tiers` endpoint |
| discovery-catalog | Add `VenueSeat` entity + `GET /api/v1/catalog/venues/{venueId}/seat-layout` |
| `shared/event` (or `booking-inventory/event/published`) | Update `CartAssembledEvent` record signature |
| `payments-ticketing` | Add `CartAssembledListener`; add `pending_order_events` store |

User: Ok create the changes_required file that needs to be done before i move to the flow 4 implementation

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/POQ/User-Register/SPEC_AUTH.MD#1-1), lines 1 to 80

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/shared/src/main -type f -name "*.java" | sort && find /home/dell/Multi-City-Event-Booking/discovery-catalog/src/main -type f -name "*.java" | sort && find /home/dell/Multi-City-Event-Booking/scheduling/src/main -type f -name "*.java" | sort

Read [](file:///home/dell/.config/Code/User/workspaceStorage/d0c32092166abb554a6d3bb3dc965310/GitHub.copilot-chat/chat-session-resources/c20cd5af-7c36-4ca5-89bf-cab22e1f1a2e/toolu_bdrk_01RdXKBxqzJCBKLBw7mcEqSX__vscode-1772612424506/content.txt#1-1), lines 1 to 100

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/User-Register/SPEC_AUTH.MD#80-80), lines 80 to 200

Summarized conversation history

Read [](file:///home/dell/.config/Code/User/workspaceStorage/d0c32092166abb554a6d3bb3dc965310/GitHub.copilot-chat/chat-session-resources/c20cd5af-7c36-4ca5-89bf-cab22e1f1a2e/toolu_bdrk_01RdXKBxqzJCBKLBw7mcEqSX__vscode-1772612424506/content.txt#100-100), lines 100 to 174

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/User-Register/SPEC_AUTH.MD#200-200), lines 200 to 320

Now let me check the existing event records and the ShowSlotController for the pricing-tier endpoint:   

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/ShowSlotActivatedEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/ShowSlotCancelledEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/scheduling/src/main/java/com/eventplatform/scheduling/api/controller/ShowSlotController.java#1-1), lines 1 to 60

Read [](file:///home/dell/Multi-City-Event-Booking/scheduling/src/main/java/com/eventplatform/scheduling/api/controller/ShowSlotController.java#60-60), lines 60 to 150

Read [](file:///home/dell/Multi-City-Event-Booking/scheduling/src/main/java/com/eventplatform/scheduling/api/dto/response/ShowSlotPricingTierResponse.java)

Now let me check the remaining gaps — the pricing tier's entity fields, CartAssembledEvent existence, and the VenueSeat entity:  

Read [](file:///home/dell/Multi-City-Event-Booking/scheduling/src/main/java/com/eventplatform/scheduling/domain/ShowSlotPricingTier.java#1-1), lines 1 to 60

Searched for text `CartAssembledEvent`, 20 results

Good. Now let me verify if `CartAssembledEvent` already exists in shared, and check the `ShowSlotPricingTier` entity for `groupDiscountThreshold`/`groupDiscountPercent` fields, then check the `Venue.java` entity:  

Searched for files matching `**/CartAssembledEvent.java`, no matches

Read [](file:///home/dell/Multi-City-Event-Booking/scheduling/src/main/java/com/eventplatform/scheduling/domain/ShowSlotPricingTier.java#60-60), lines 60 to 106

I have everything I need. Let me create the `CHANGES_REQUIRED.md` file now. 

Created 1 todo

Created [](file:///home/dell/Multi-City-Event-Booking/POQ/Seat-Selection/CHANGES_REQUIRED.md)

Completed: *Create CHANGES_REQUIRED.md* (1/1)

CHANGES_REQUIRED.md created with 8 changes across 4 priority levels.

**Key findings from the actual codebase scan:**

- `ShowSlotActivatedEvent` and `ShowSlotCancelledEvent` already exist with `orgId` and `venueId` — only `seatingMode` is missing (CHANGE-08)
- `ShowSlotPricingTier` entity already exists with `ebInventoryTierId` — only the two group discount fields are missing (CHANGE-03)
- `ShowSlotPricingTierResponse` DTO exists but is missing `ebInventoryTierId`, `groupDiscountThreshold`, and `groupDiscountPercent` (CHANGE-04)
- No `GET /{id}/pricing-tiers` endpoint exists in `ShowSlotController` (CHANGE-05)
- `CartAssembledEvent` doesn't exist anywhere — needs to be created from scratch (CHANGE-01)
- No `VenueSeat` entity or seat-layout endpoint in discovery-catalog (CHANGE-07)

**Critical flyway note:** The pre-FR4 migrations consume V17 and V18, so all SPEC_4.md FR4 table migrations renumber to **V19–V24** before you write any migration files.

Made changes.