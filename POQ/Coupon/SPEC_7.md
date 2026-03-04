# SPEC_7.md — FR7: Offer & Coupon Engine → Eligibility Check → Discount Application

**Owner:** promotions  
**Module(s):** `promotions` (primary), `shared/eventbrite/` (`EbDiscountSyncService` ACL), `booking-inventory` (event consumer — cart total recompute), `payments-ticketing` (event source — `BookingConfirmedEvent`, `BookingCancelledEvent`), `identity` (event source — `PaymentFailedEvent` via payments-ticketing)  
**Flow:** Flow 7 — Admin Creates Promotion → Coupon Created Internally → EB Outbound Sync → User Applies Coupon → Eligibility Check → Discount Applied to Cart → Post-Redemption Lifecycle → EB Inbound Reconciliation  
**Last Updated:** March 5, 2026  
**Status:** Stages 1–8 Complete — Ready for implementation

> **Pre-FR7 Gap Fixes Applied (2026-03-05)**
> Six blocking gaps discovered during flows 1–6 audit were fixed before FR7 implementation begins.
> Full details: [`docs/Files/PRE_FR7_GAP_FIXES.md`](../../docs/Files/PRE_FR7_GAP_FIXES.md)
>
> | Gap | Fix Summary | Status |
> |---|---|---|
> | GAP-1 | `BookingConfirmedEvent` — added `bookingId` as first field; `PaymentService` updated to pass `booking.getId()` | ✅ Applied |
> | GAP-2 | `CartSummaryDto` created in `shared/dto/`; `getCartSummary(Long cartId)` added to `CartSnapshotReader`; `CartSnapshotReaderImpl` updated | ✅ Applied |
> | GAP-3 | `CartService.confirm()` now subtracts `cart.couponDiscountAmount` from Stripe total before publishing `CartAssembledEvent`; floor at zero guard added | ✅ Applied |
> | GAP-4 | V36 migration renames `discount_amount` → `group_discount_amount`; adds `coupon_discount_amount` column; `Cart` entity split into `groupDiscountAmount` + `couponDiscountAmount`; `CartPricingService.recompute()` only writes `groupDiscountAmount` | ✅ Applied |
> | GAP-5 | `promotions` module scaffolded; `promotions/pom.xml` created; added to root `pom.xml` and `app/pom.xml` | ✅ Applied |
> | GAP-6 | `CouponAppliedEvent` and `CouponValidatedEvent` created in `shared/common/event/published/` | ✅ Applied |

**Depends on:**
- `SPEC_4.md` (FR4) — `CartAssembledEvent` carries `couponCode`; `CartSnapshotReader` available in `shared/` with `getCartItems()` **and `getCartSummary()` (added — GAP-2)**; cart `orgId` and `slotId` accessible via `getCartSummary()`
- `SPEC_5.md` (FR5) — `BookingConfirmedEvent(bookingId, cartId, seatIds, stripePaymentIntentId, userId)` published **(updated — GAP-1: `bookingId` added as first field)**; `BookingCancelledEvent` published; `PaymentFailedEvent(cartId, userId)` published — all in `shared/`
- `SPEC_3.MD` (FR3) — JWT with `sub=userId`, `role`, `orgId` claims required for all protected endpoints

**Key Architecture Decision:** Discounts are computed and enforced **entirely internally**. The discounted amount is applied to the Stripe `PaymentIntent` amount (FR5). Eventbrite discount sync (`EbDiscountSyncService`) is a **passive marketing mirror only** — it is never on the payment critical path. Because Eventbrite has no PUT/PATCH for discounts, any modification to an EB-synced discount requires a guarded **delete + recreate** pattern. If EB has recorded `quantity_sold > 0` for the discount, deletion is blocked by EB and must be skipped gracefully.

---

## Stage 1 — Requirements

### Area A — Promotion & Coupon Management (Admin, Internal)

- `[MUST]` Admin creates a promotion with: `name`, `discountType` (`PERCENT_OFF` / `AMOUNT_OFF`), `discountValue`, `scope` (`ORG_WIDE` / `EVENT_SCOPED`), `ebEventId` (nullable — required only for `EVENT_SCOPED`), `validFrom`, `validUntil`, `maxUsageLimit`, `perUserCap`
- `[MUST]` Admin creates a coupon code and links it to a promotion; coupon `code` must be unique within the same `orgId`
- `[MUST]` Admin can deactivate a promotion — cascades `INACTIVE` status to all linked coupons; triggers guarded EB delete for each synced coupon
- `[MUST]` Admin can update a promotion's `validFrom`, `validUntil`, or `maxUsageLimit` — internal update persists first; EB re-sync triggered via delete + recreate (no PUT/PATCH exists on Eventbrite)
- `[MUST]` Admin can deactivate a single coupon — triggers guarded EB delete for that coupon
- `[MUST]` Duplicate coupon `code` within the same `orgId` is rejected with `409 COUPON_CODE_CONFLICT`
- `[SHOULD]` Admin can list all promotions and coupons for their org with current redemption statistics
- `[SHOULD]` Admin can view per-coupon `ebSyncStatus` and last sync timestamp

### Area B — EB Outbound Sync (Internal → Eventbrite)

- `[MUST]` After internal coupon creation, asynchronously push to Eventbrite via `EbDiscountSyncService.createDiscount(orgId, payload)` → `POST /organizations/{org_id}/discounts/`
- `[MUST]` Store the returned `eb_discount_id` on the `Coupon` record; mark `eb_sync_status = SYNCED`
- `[MUST]` If EB create call fails, mark `eb_sync_status = SYNC_FAILED`; coupon is still `ACTIVE` and usable internally — EB sync failure is non-blocking
- `[MUST]` After any successful EB create, verify the discount exists via `GET /discounts/{eb_discount_id}/` and reconcile key fields; on 404 set `SYNC_FAILED`
- `[MUST]` **Modification rule (no PUT/PATCH):** to update an EB-synced discount: (1) fetch current EB `quantity_sold` via `GET /discounts/{eb_discount_id}/`; (2) if `quantity_sold == 0`: call `DELETE /discounts/{eb_discount_id}/` then `POST /organizations/{org_id}/discounts/` with updated fields then store new `eb_discount_id` as `SYNCED`; (3) if `quantity_sold > 0`: set `eb_sync_status = CANNOT_RESYNC`, persist internal change only, alert ops — never attempt delete when EB has active usage
- `[MUST]` **Guarded delete rule:** before any EB DELETE call, fetch current `quantity_sold` via `GET /discounts/{eb_discount_id}/`; if `quantity_sold > 0`, set `eb_sync_status = DELETE_BLOCKED`, log ops alert, continue without deleting; if `quantity_sold == 0`, proceed with DELETE
- `[MUST]` **Idempotency guard on create:** before calling EB create, scan `GET /organizations/{org_id}/discounts/` for an existing discount with the same `code`; if found, adopt the existing `eb_discount_id` as `SYNCED` instead of creating a duplicate
- `[MUST]` `SYNC_FAILED` coupons are eligible for retry on next `CouponExpiryJob` scheduled run — no immediate re-throw
- `[MUST]` If `promotion.scope = EVENT_SCOPED` and `promotion.ebEventId` is `null`, skip EB sync entirely and log a warning; coupon is still usable internally
- `[SHOULD]` Record `last_eb_sync_at` and `eb_quantity_sold_at_last_sync` on each sync attempt

### Area C — EB Inbound Sync (Eventbrite → Internal)

- `[MUST]` `DiscountReconciliationJob` runs on a configurable schedule (default: every 4 hours) and calls `GET /organizations/{org_id}/discounts/` to list all EB discounts per org
- `[MUST]` For each EB discount, find the matching internal `Coupon` by `eb_discount_id`:
  - **Match + `quantity_sold` drifted higher:** update internal `redemption_count` to EB value (EB wins on usage counts); log drift
  - **Match + fields drifted** (code or discount value changed on EB platform): set `eb_sync_status = DRIFT_DETECTED`, alert ops; do NOT auto-overwrite internal record — internal DB is source of truth
  - **No internal match (orphan):** create `OrphanEbDiscount` audit record; alert ops; do NOT auto-import or create internal coupon
  - **Internal coupon is `SYNCED` but EB returns 404:** set `eb_sync_status = EB_DELETED_EXTERNALLY`; alert ops; coupon remains usable internally
- `[MUST]` If the EB list call fails for an org, log error, skip that org, write a `DiscountReconciliationLog` with error summary, and continue processing remaining orgs
- `[MUST]` Write a `DiscountReconciliationLog` after every run with: `orgId`, `runAt`, `discountsChecked`, `driftsFound`, `orphansFound`, `externallyDeletedFound`, `actionsTakenSummary`
- `[SHOULD]` Reconciliation job is protected by a distributed lock (ShedLock) to prevent parallel runs
- `[WONT]` Auto-applying or auto-importing external EB discount codes to internal orders without admin approval

### Area D — Coupon Eligibility & Application (User Flow)

- `[MUST]` Expose `POST /api/v1/promotions/validate` accepting `{ couponCode, cartId }` (JWT `ROLE_USER`)
- `[MUST]` Eligibility checks run in this exact order:
  1. Coupon exists and `status = ACTIVE`
  2. `NOW()` is within `[validFrom, validUntil]`
  3. Cart's `orgId` matches `coupon.orgId` (read via `CartSnapshotReader`)
  4. If `scope = EVENT_SCOPED`: cart's `slotId` maps to `promotion.ebEventId`
  5. `redemption_count + active_reservations_count < maxUsageLimit`
  6. User's prior non-voided redemptions for this coupon `< perUserCap`
- `[MUST]` On successful validation, create a `CouponUsageReservation(couponId, cartId, userId, expiresAt = cart.expiresAt)` — this reservation counts toward the usage limit for subsequent checks
- `[MUST]` Return `DiscountBreakdownResponse` with: `couponCode`, `discountType`, `discountAmount`, `adjustedCartTotal`, `currency`
- `[MUST]` Idempotency: if the same `(couponCode, cartId)` combination already has an active `CouponUsageReservation`, return the existing `DiscountBreakdownResponse` without creating a duplicate reservation
- `[MUST]` Publish `CouponAppliedEvent(cartId, couponCode, discountAmountInSmallestUnit, currency, userId)` when `CartAssembledEvent` is received by `promotions` and the cart snapshot contains a `couponCode`
- `[MUST]` `booking-inventory` `CouponAppliedListener` listens to `CouponAppliedEvent` and recomputes cart total with the discounted amount
- `[SHOULD]` Expose `DELETE /api/v1/promotions/cart/{cartId}/coupon` to allow a user to remove a coupon from their cart — releases the `CouponUsageReservation`

### Area E — Post-Redemption Lifecycle

- `[MUST]` On `BookingConfirmedEvent(bookingId, cartId, ...)` **(note: `bookingId` is now the first field — GAP-1 fix)**: look up active `CouponUsageReservation` by `cartId`; if found, create `CouponRedemption(couponId, userId, bookingId, cartId, discountAmount, currency, redeemedAt)`; increment `redemption_count`; mark reservation `released = true`; if `redemption_count >= maxUsageLimit`, mark coupon `EXHAUSTED` and trigger guarded EB delete
- `[MUST]` On `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)`: find non-voided `CouponRedemption` by `bookingId`; set `voided = true`, `voidedAt = NOW()`; decrement `redemption_count`; if coupon was `EXHAUSTED`, revert status to `ACTIVE`; do NOT attempt EB re-create if coupon had `DELETE_BLOCKED` or `EB_DELETED_EXTERNALLY`
- `[MUST]` On `PaymentFailedEvent(cartId, userId)`: find and release any open `CouponUsageReservation` for this `cartId` by setting `released = true`
- `[SHOULD]` Expose `GET /api/v1/promotions/{couponCode}/usage` for admin to view `redemptionCount`, `activeReservations`, `voidedRedemptions`

### Area F — Error & Edge Cases

- `[MUST]` EB sync failure never blocks internal coupon creation or user-facing eligibility check — the two concerns are fully decoupled
- `[MUST]` EB `DISCOUNT_CANNOT_BE_DELETED` (HTTP 400, quantity_sold > 0) handled gracefully: log, set `DELETE_BLOCKED`, continue; no exception propagated to caller
- `[MUST]` If internal and EB records exist but `eb_discount_id` is stale or mismatched (drift), set `DRIFT_DETECTED` for manual reconciliation — never auto-fix silently
- `[MUST]` If `eb_event_id` is `null` on event-scoped promotion, skip EB sync and log warning; do not throw
- `[SHOULD]` Before any EB create, run idempotency guard: check `GET /organizations/{org_id}/discounts/` for matching `code`; adopt if found — prevents phantom duplicates on retry
- `[WONT]` Per-seat, per-ticket-class, or wallet-credit-specific coupon scoping in FR7
- `[WONT]` Email notification on coupon exhaustion or expiry in FR7

---

## Stage 2 — Domain Model

A `Promotion` defines the discount rule: type, value, validity window, scope, and usage limits. A `Coupon` is a redeemable code linked to one `Promotion`; it carries its own lifecycle status and all state related to Eventbrite sync (`eb_discount_id`, `eb_sync_status`, `eb_quantity_sold_at_last_sync`). A `CouponRedemption` is an immutable record created exactly once per confirmed booking that used a coupon — it is soft-voided on cancellation, never deleted. A `CouponUsageReservation` is a soft-hold created when a user successfully validates a coupon during cart session; it is released on payment failure, cart expiry, or coupon removal. `OrphanEbDiscount` and `DiscountReconciliationLog` are operational audit records owned exclusively by the `promotions` module.

### Entities

#### `Promotion`
- **Identity:** `id` (UUID PK)
- **Fields:** `orgId`, `name`, `discountType` (`PERCENT_OFF` / `AMOUNT_OFF`), `discountValue` (BigDecimal), `scope` (`ORG_WIDE` / `EVENT_SCOPED`), `ebEventId` (String, nullable — null for `ORG_WIDE`), `maxUsageLimit` (Integer, nullable — null = unlimited), `perUserCap` (Integer, nullable — null = unlimited), `validFrom` (OffsetDateTime), `validUntil` (OffsetDateTime), `status` (`ACTIVE` / `INACTIVE`), `createdAt`, `updatedAt`
- **Lifecycle:** `ACTIVE → INACTIVE` (admin deactivate or all linked coupons exhausted)
- **Domain behaviour:**
  - `deactivate()` — sets `status = INACTIVE`, cascades `deactivate()` to all linked `Coupon` records
  - `isValid(Instant now)` — returns true when `status = ACTIVE` and `now` is within `[validFrom, validUntil]`
- **Relationships:** 1 `Promotion` → N `Coupon`

#### `Coupon`
- **Identity:** `id` (UUID PK)
- **Fields:** `promotionId` (FK → promotions), `orgId`, `code` (String), `status` (`ACTIVE` / `INACTIVE` / `EXHAUSTED`), `redemptionCount` (int, default 0), `ebDiscountId` (String, nullable), `ebSyncStatus` (`NOT_SYNCED` / `SYNC_PENDING` / `SYNCED` / `SYNC_FAILED` / `DELETE_BLOCKED` / `EB_DELETED_EXTERNALLY` / `DRIFT_DETECTED` / `CANNOT_RESYNC`), `ebQuantitySoldAtLastSync` (Integer, nullable), `lastEbSyncAt` (OffsetDateTime, nullable), `createdAt`, `updatedAt`
- **Lifecycle:** `ACTIVE → INACTIVE` (admin/cascade) | `ACTIVE → EXHAUSTED` (redemptionCount >= maxUsageLimit) | `EXHAUSTED → ACTIVE` (redemption voided)
- **Domain behaviour:**
  - `recordRedemption(int maxUsageLimit)` — increments `redemptionCount`; if `redemptionCount >= maxUsageLimit`, sets `status = EXHAUSTED`
  - `voidRedemption()` — decrements `redemptionCount`; if `status = EXHAUSTED`, reverts to `ACTIVE`
  - `markSynced(String ebDiscountId)` — sets `ebDiscountId`, `ebSyncStatus = SYNCED`, `lastEbSyncAt = NOW()`
  - `markSyncFailed()` — sets `ebSyncStatus = SYNC_FAILED`
  - `canEbDelete()` — returns true when `ebDiscountId != null && ebQuantitySoldAtLastSync == 0`
- **Relationships:** N `Coupon` → 1 `Promotion`; 1 `Coupon` → N `CouponRedemption`; 1 `Coupon` → N `CouponUsageReservation`

#### `CouponRedemption`
- **Identity:** `id` (UUID PK)
- **Fields:** `couponId` (FK → coupons), `userId` (UUID, ID-only reference to `identity`), `bookingId` (UUID, ID-only reference to `payments-ticketing`), `cartId` (UUID, ID-only reference to `booking-inventory`), `discountAmount` (BigDecimal), `currency` (String), `redeemedAt` (OffsetDateTime), `voided` (boolean, default false), `voidedAt` (OffsetDateTime, nullable)
- **Lifecycle:** created once on `BookingConfirmedEvent`; `voided = true` on `BookingCancelledEvent` — never physically deleted
- **Constraints:** at most one non-voided `CouponRedemption` per `(couponId, bookingId)`

#### `CouponUsageReservation`
- **Identity:** `id` (UUID PK)
- **Fields:** `couponId` (FK → coupons), `cartId` (UUID), `userId` (UUID), `reservedAt` (OffsetDateTime), `expiresAt` (OffsetDateTime — matches `cart.expiresAt`), `released` (boolean, default false)
- **Lifecycle:** created on successful coupon validation; released on `PaymentFailedEvent`, coupon removal, cart expiry, or conversion to `CouponRedemption` on booking confirmation
- **Constraints:** at most one active (non-released, non-expired) reservation per `(couponId, cartId)`

#### `OrphanEbDiscount`
- **Identity:** `id` (UUID PK)
- **Fields:** `ebDiscountId` (String), `orgId`, `code` (String), `detectedAt` (OffsetDateTime), `reviewed` (boolean, default false), `notes` (String, nullable)
- **Purpose:** audit-only record for EB discounts that have no matching internal coupon; never auto-imported

#### `DiscountReconciliationLog`
- **Identity:** `id` (UUID PK)
- **Fields:** `orgId`, `runAt` (OffsetDateTime), `discountsChecked` (int), `driftsFound` (int), `orphansFound` (int), `externallyDeletedFound` (int), `actionsTakenSummary` (String), `errorSummary` (String, nullable)
- **Purpose:** operational audit trail; one record per reconciliation job run per org

### Enums

| Enum | Values |
|---|---|
| `DiscountType` | `PERCENT_OFF`, `AMOUNT_OFF` |
| `PromotionScope` | `ORG_WIDE`, `EVENT_SCOPED` |
| `CouponStatus` | `ACTIVE`, `INACTIVE`, `EXHAUSTED` |
| `EbSyncStatus` | `NOT_SYNCED`, `SYNC_PENDING`, `SYNCED`, `SYNC_FAILED`, `DELETE_BLOCKED`, `EB_DELETED_EXTERNALLY`, `DRIFT_DETECTED`, `CANNOT_RESYNC` |

### Non-Persisted Value Objects

#### `DiscountCalculationResult`
```java
public record DiscountCalculationResult(
    String couponCode,
    DiscountType discountType,
    long discountAmountInSmallestUnit,   // e.g. paise/cents
    long adjustedTotalInSmallestUnit,
    String currency
) {}
```
- Computed by `CouponEligibilityService.calculateDiscount()`; returned to controller and published in events; **never persisted**

#### `EbDiscountSyncPayload`
```java
public record EbDiscountSyncPayload(
    String code,
    String type,             // "percent" or "amount"
    Double percentOff,       // nullable
    Double amountOff,        // nullable, in major currency unit
    String startDate,        // ISO-8601
    String endDate,          // ISO-8601
    Integer quantityAvailable,
    String eventId,          // nullable
    List<String> ticketClassIds  // nullable
) {}
```
- Mapped from `Coupon` + `Promotion` by `EbDiscountSyncPayloadMapper` before any EB API call; **never persisted**

### Cross-Module ID References (no entity imports)

| Field | Points To | Owning Module |
|---|---|---|
| `Coupon.orgId` | organization context | identity / scheduling |
| `Promotion.ebEventId` | `show_slots.eb_event_id` | scheduling (nullable) |
| `CouponRedemption.userId` | `users.id` | identity |
| `CouponRedemption.bookingId` | `bookings.id` | payments-ticketing |
| `CouponRedemption.cartId` | `carts.id` | booking-inventory |
| `CouponUsageReservation.cartId` | `carts.id` | booking-inventory |

---

## Stage 3 — Architecture & File Structure

### Architecture Pattern
Modular Monolith — `promotions` is the sole domain owner. Stripe and EB are never called directly from any module; only `shared/` ACL facades are used. Cross-module data reads use the existing `CartSnapshotReader` shared interface.

### Module Ownership

| Layer | Owner |
|---|---|
| Domain entities + business rules | `promotions` |
| EB sync calls | `EbDiscountSyncService` in `shared/eventbrite/service/` |
| Cart org/slot read on validate | `CartSnapshotReader` in `shared/common/service/` (implemented by `booking-inventory`) |
| Cart total recompute on discount | `booking-inventory` (`CouponAppliedListener`) |
| Redemption trigger | `promotions` listens to `BookingConfirmedEvent` from `shared/` |
| Redemption void | `promotions` listens to `BookingCancelledEvent` from `shared/` |
| Reservation release | `promotions` listens to `PaymentFailedEvent` from `shared/` |

### Inter-Module Communication

| Interaction | Direction | Mechanism |
|---|---|---|
| Read cart `orgId` + `slotId` + `couponCode` on validate | `promotions` → `booking-inventory` | `CartSnapshotReader.getCartSummary()` shared interface — **`getCartSummary()` added (GAP-2)** |
| Discount applied to cart total | `promotions` → `booking-inventory` | `CouponAppliedEvent` Spring ApplicationEvent |
| Record redemption + increment count | `payments-ticketing` → `promotions` | `BookingConfirmedEvent` Spring ApplicationEvent **(now carries `bookingId` — GAP-1)** |
| Void redemption + decrement count | `payments-ticketing` → `promotions` | `BookingCancelledEvent` Spring ApplicationEvent |
| Release reservation on failure | `payments-ticketing` → `promotions` | `PaymentFailedEvent` Spring ApplicationEvent |
| EB discount create/delete | `promotions` → EB | `EbDiscountSyncService` in `shared/eventbrite/service/` |
| EB discount list/get for reconciliation | `promotions` → EB | `EbDiscountSyncService` in `shared/eventbrite/service/` |

### Shared additions

> **Status: All shared additions below were created as part of the pre-FR7 gap fixes (GAP-2, GAP-6). These files already exist on disk.**

```
shared/src/main/java/com/eventplatform/shared/common/event/published/
├── CouponAppliedEvent.java          ✅ Created (GAP-6 pre-FR7 fix)
└── CouponValidatedEvent.java        ✅ Created (GAP-6 pre-FR7 fix)

shared/src/main/java/com/eventplatform/shared/common/dto/
└── CartSummaryDto.java              ✅ Created (GAP-2 pre-FR7 fix)

shared/src/main/java/com/eventplatform/shared/common/service/
└── CartSnapshotReader.java          ✅ getCartSummary(Long cartId) method added (GAP-2 pre-FR7 fix)

shared/src/main/java/com/eventplatform/shared/common/event/published/
└── BookingConfirmedEvent.java       ✅ bookingId added as first field (GAP-1 pre-FR7 fix)
```

**`CouponAppliedEvent`** (record — already exists):
```java
public record CouponAppliedEvent(
    Long cartId,
    String couponCode,
    long discountAmountInSmallestUnit,
    String currency,
    Long userId
) {}
```

**`CouponValidatedEvent`** (record — already exists):
```java
public record CouponValidatedEvent(
    Long cartId,
    String couponCode,
    long discountAmountInSmallestUnit,
    String currency,
    Long userId
) {}
```

**`CartSummaryDto`** (record — already exists):
```java
public record CartSummaryDto(
    Long    cartId,
    Long    orgId,
    Long    slotId,
    String  couponCode,
    Instant expiresAt,
    String  currency
) {}
```

**`BookingConfirmedEvent`** (record — updated, `bookingId` added first):
```java
public record BookingConfirmedEvent(
    Long bookingId,                  // ← ADDED (GAP-1): required by promotions.BookingConfirmedListener
    Long cartId,
    List<Long> seatIds,
    String stripePaymentIntentId,
    Long userId
) {}
```

### booking-inventory changes

> **Status: The pre-existing structural fixes below (GAP-2, GAP-3, GAP-4) are already applied. The `CouponAppliedListener` is NEW and must be implemented as part of FR7.**

**Pre-FR7 fixes already applied to `booking-inventory`:**

```
booking-inventory — Cart entity (GAP-4 ✅ applied)
  Cart.groupDiscountAmount  ← renamed from discountAmount (mapped to group_discount_amount column)
  Cart.couponDiscountAmount ← NEW BigDecimal field (mapped to coupon_discount_amount column, default 0)
  Cart.setGroupDiscountAmount()  ← CartPricingService now calls this (never touches couponDiscountAmount)
  Cart.getCouponDiscountAmount() ← CartService.confirm() reads this for Stripe amount deduction
  Cart.setCouponDiscountAmount() ← CouponAppliedListener will call this on CouponAppliedEvent

booking-inventory — CartPricingService (GAP-4 ✅ applied)
  recompute() writes only cart.setGroupDiscountAmount()
  couponDiscountAmount is NEVER touched by recompute() — preserved across all seat additions

booking-inventory — CartService.confirm() (GAP-3 ✅ applied)
  totalAmountInSmallestUnit = (pricing.total() − cart.couponDiscountAmount).max(0) × 100
  Stripe PaymentIntent amount now correctly reflects coupon discount

booking-inventory — CartSnapshotReaderImpl (GAP-2 ✅ applied)
  getCartSummary(Long cartId) implemented — reads Cart entity, maps to CartSummaryDto

booking-inventory — CartResponse DTO (GAP-4 ✅ applied)
  discountAmount field renamed to groupDiscountAmount
  couponDiscountAmount field added
```

**New listener to be created as part of FR7 implementation:**

```
booking-inventory/src/main/java/com/eventplatform/bookinginventory/event/listener/
└── CouponAppliedListener.java    ← NEW (FR7) — listens CouponAppliedEvent; sets cart.couponDiscountAmount
```

### promotions module — full file structure

```
promotions/src/main/java/com/eventplatform/promotions/
├── api/
│   ├── controller/
│   │   ├── PromotionAdminController.java
│   │   ├── CouponAdminController.java
│   │   └── CouponUserController.java
│   └── dto/
│       ├── request/
│       │   ├── PromotionCreateRequest.java
│       │   ├── PromotionUpdateRequest.java
│       │   ├── CouponCreateRequest.java
│       │   └── CouponValidateRequest.java
│       └── response/
│           ├── PromotionResponse.java
│           ├── CouponResponse.java
│           ├── DiscountBreakdownResponse.java
│           └── CouponUsageStatsResponse.java
├── domain/
│   ├── Promotion.java
│   ├── Coupon.java
│   ├── CouponRedemption.java
│   ├── CouponUsageReservation.java
│   ├── OrphanEbDiscount.java
│   └── DiscountReconciliationLog.java
├── service/
│   ├── PromotionService.java                ← CRUD + lifecycle, deactivation cascade
│   ├── CouponEligibilityService.java        ← validation pipeline, soft-hold, idempotency
│   ├── CouponRedemptionService.java         ← record/void redemptions, usage counters
│   └── DiscountSyncOrchestrator.java        ← create/delete+recreate/guarded-delete via EbDiscountSyncService
├── scheduler/
│   ├── DiscountReconciliationJob.java       ← inbound EB sync: list, diff, flag orphans/drift
│   └── CouponExpiryJob.java                ← deactivate expired coupons + guarded EB delete + release reservations
├── repository/
│   ├── PromotionRepository.java
│   ├── CouponRepository.java
│   ├── CouponRedemptionRepository.java
│   ├── CouponUsageReservationRepository.java
│   ├── OrphanEbDiscountRepository.java
│   └── DiscountReconciliationLogRepository.java
├── mapper/
│   ├── PromotionMapper.java
│   ├── CouponMapper.java
│   └── EbDiscountSyncPayloadMapper.java     ← Coupon + Promotion → EbDiscountSyncPayload value object
├── event/
│   └── listener/
│       ├── BookingConfirmedListener.java    ← listens BookingConfirmedEvent → record redemption
│       ├── BookingCancelledListener.java    ← listens BookingCancelledEvent → void redemption
│       └── PaymentFailedListener.java       ← listens PaymentFailedEvent → release reservation
└── exception/
    ├── CouponNotFoundException.java
    ├── CouponExpiredException.java
    ├── CouponNotYetValidException.java
    ├── CouponInactiveException.java
    ├── CouponOrgMismatchException.java
    ├── CouponEventMismatchException.java
    ├── CouponUsageLimitReachedException.java
    ├── CouponPerUserCapReachedException.java
    ├── CouponCodeConflictException.java
    └── EbDiscountSyncException.java
```

---

## Stage 4 — DB Schema

### Pre-FR7 Migration Already Applied

> **V36** (`V36__split_cart_discount_amounts.sql`) was applied as a pre-FR7 gap fix (GAP-4).
> It renames `carts.discount_amount` → `carts.group_discount_amount` and adds `carts.coupon_discount_amount`.
> This migration already exists at `app/src/main/resources/db/migration/V36__split_cart_discount_amounts.sql`.

### FR7 Flyway Migration: `V37__create_promotions_tables.sql`

> Migration numbering: V35 (FR6 refund audit) → **V36 (pre-FR7 cart discount split, already applied)** → **V37 (FR7 promotions tables — use this number)**

#### Table: `promotions`

```sql
CREATE TABLE promotions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            VARCHAR(255) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    discount_type     VARCHAR(20)  NOT NULL,           -- PERCENT_OFF | AMOUNT_OFF
    discount_value    NUMERIC(12,4) NOT NULL,
    scope             VARCHAR(20)  NOT NULL,           -- ORG_WIDE | EVENT_SCOPED
    eb_event_id       VARCHAR(255)  NULL,              -- null for ORG_WIDE
    max_usage_limit   INTEGER       NULL,              -- null = unlimited
    per_user_cap      INTEGER       NULL,              -- null = unlimited
    valid_from        TIMESTAMPTZ   NOT NULL,
    valid_until       TIMESTAMPTZ   NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_promotions_org_id    ON promotions(org_id);
CREATE INDEX idx_promotions_status    ON promotions(status);
CREATE INDEX idx_promotions_eb_event  ON promotions(eb_event_id) WHERE eb_event_id IS NOT NULL;
```

#### Table: `coupons`

```sql
CREATE TABLE coupons (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id                 UUID         NOT NULL REFERENCES promotions(id),
    org_id                       VARCHAR(255) NOT NULL,
    code                         VARCHAR(100) NOT NULL,
    status                       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | INACTIVE | EXHAUSTED
    redemption_count             INTEGER      NOT NULL DEFAULT 0,
    eb_discount_id               VARCHAR(255) NULL,
    eb_sync_status               VARCHAR(30)  NOT NULL DEFAULT 'NOT_SYNCED',
    eb_quantity_sold_at_last_sync INTEGER     NULL,
    last_eb_sync_at              TIMESTAMPTZ  NULL,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- No two active coupons with same code in the same org
CREATE UNIQUE INDEX uq_coupons_code_org
    ON coupons(code, org_id)
    WHERE status != 'INACTIVE';

CREATE INDEX idx_coupons_promotion_id   ON coupons(promotion_id);
CREATE INDEX idx_coupons_eb_discount_id ON coupons(eb_discount_id) WHERE eb_discount_id IS NOT NULL;
CREATE INDEX idx_coupons_eb_sync_status ON coupons(eb_sync_status);
CREATE INDEX idx_coupons_status         ON coupons(status);
```

#### Table: `coupon_redemptions`

```sql
CREATE TABLE coupon_redemptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id       UUID         NOT NULL REFERENCES coupons(id),
    user_id         UUID         NOT NULL,          -- ID-only ref to identity.users
    booking_id      UUID         NOT NULL,          -- ID-only ref to payments-ticketing.bookings
    cart_id         UUID         NOT NULL,          -- ID-only ref to booking-inventory.carts
    discount_amount NUMERIC(12,4) NOT NULL,
    currency        VARCHAR(10)  NOT NULL,
    redeemed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    voided          BOOLEAN      NOT NULL DEFAULT FALSE,
    voided_at       TIMESTAMPTZ  NULL
);

-- At most one non-voided redemption per (coupon, booking)
CREATE UNIQUE INDEX uq_coupon_redemptions_active
    ON coupon_redemptions(coupon_id, booking_id)
    WHERE voided = FALSE;

CREATE INDEX idx_coupon_redemptions_coupon_id  ON coupon_redemptions(coupon_id);
CREATE INDEX idx_coupon_redemptions_user_id    ON coupon_redemptions(user_id);
CREATE INDEX idx_coupon_redemptions_booking_id ON coupon_redemptions(booking_id);
CREATE INDEX idx_coupon_redemptions_cart_id    ON coupon_redemptions(cart_id);
```

#### Table: `coupon_usage_reservations`

```sql
CREATE TABLE coupon_usage_reservations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id   UUID        NOT NULL REFERENCES coupons(id),
    cart_id     UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    released    BOOLEAN     NOT NULL DEFAULT FALSE
);

-- At most one active reservation per (coupon, cart)
CREATE UNIQUE INDEX uq_coupon_reservations_active
    ON coupon_usage_reservations(coupon_id, cart_id)
    WHERE released = FALSE;

CREATE INDEX idx_coupon_reservations_coupon_id  ON coupon_usage_reservations(coupon_id);
CREATE INDEX idx_coupon_reservations_cart_id    ON coupon_usage_reservations(cart_id);
CREATE INDEX idx_coupon_reservations_expires_at ON coupon_usage_reservations(expires_at)
    WHERE released = FALSE;
```

#### Table: `orphan_eb_discounts`

```sql
CREATE TABLE orphan_eb_discounts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    eb_discount_id  VARCHAR(255) NOT NULL,
    org_id          VARCHAR(255) NOT NULL,
    code            VARCHAR(100) NOT NULL,
    detected_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed        BOOLEAN      NOT NULL DEFAULT FALSE,
    notes           TEXT         NULL
);

CREATE INDEX idx_orphan_eb_discounts_org_id ON orphan_eb_discounts(org_id);
CREATE INDEX idx_orphan_eb_discounts_reviewed ON orphan_eb_discounts(reviewed) WHERE reviewed = FALSE;
```

#### Table: `discount_reconciliation_logs`

```sql
CREATE TABLE discount_reconciliation_logs (
    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                    VARCHAR(255) NOT NULL,
    run_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    discounts_checked         INTEGER      NOT NULL DEFAULT 0,
    drifts_found              INTEGER      NOT NULL DEFAULT 0,
    orphans_found             INTEGER      NOT NULL DEFAULT 0,
    externally_deleted_found  INTEGER      NOT NULL DEFAULT 0,
    actions_taken_summary     TEXT         NULL,
    error_summary             TEXT         NULL
);

CREATE INDEX idx_reconciliation_logs_org_id ON discount_reconciliation_logs(org_id);
CREATE INDEX idx_reconciliation_logs_run_at ON discount_reconciliation_logs(run_at);
```

---

## Stage 5 — API

### Admin — Promotion Management (`ROLE_ADMIN` required on all)

#### `POST /api/v1/promotions`
Create a new promotion.

- **Request body:** `PromotionCreateRequest { name, discountType, discountValue, scope, ebEventId?, validFrom, validUntil, maxUsageLimit?, perUserCap? }`
- **Response:** `201 Created` with `PromotionResponse { id, name, discountType, discountValue, scope, ebEventId, validFrom, validUntil, maxUsageLimit, perUserCap, status, createdAt }`
- **Errors:** `400` validation failure | `403` not admin

#### `GET /api/v1/promotions`
List all promotions for the caller's org (paginated).

- **Query params:** `page`, `size`, `status` (filter)
- **Response:** `200 OK` paginated list of `PromotionResponse`

#### `GET /api/v1/promotions/{promotionId}`
Get single promotion detail.

- **Response:** `200 OK` with `PromotionResponse`
- **Errors:** `404 PROMOTION_NOT_FOUND`

#### `PATCH /api/v1/promotions/{promotionId}`
Update promotion validity and usage limits. Triggers guarded EB delete + recreate for all linked synced coupons.

- **Request body:** `PromotionUpdateRequest { validFrom?, validUntil?, maxUsageLimit?, perUserCap? }`
- **Response:** `200 OK` with updated `PromotionResponse`
- **Errors:** `404` | `409 PROMOTION_CANNOT_BE_MODIFIED` if any linked coupon has `CANNOT_RESYNC` — response includes `ebSyncStatus` warning field for each affected coupon

#### `DELETE /api/v1/promotions/{promotionId}`
Deactivate promotion and all linked coupons. Triggers guarded EB delete for all synced coupons.

- **Response:** `204 No Content`
- **Errors:** `404` | `409 PROMOTION_HAS_ACTIVE_REDEMPTIONS` if any non-voided redemptions exist

---

### Admin — Coupon Management (`ROLE_ADMIN` required on all)

#### `POST /api/v1/promotions/{promotionId}/coupons`
Create a coupon code under a promotion. Triggers async EB sync.

- **Request body:** `CouponCreateRequest { code }`
- **Response:** `201 Created` with `CouponResponse { id, code, promotionId, status, redemptionCount, ebSyncStatus, ebDiscountId, createdAt }`
- **Errors:** `404 PROMOTION_NOT_FOUND` | `409 COUPON_CODE_CONFLICT`

#### `GET /api/v1/promotions/{promotionId}/coupons`
List all coupons for a promotion.

- **Response:** `200 OK` paginated list of `CouponResponse`

#### `GET /api/v1/promotions/coupons/{couponCode}/usage`
Get redemption count and reservation stats for a coupon.

- **Response:** `200 OK` with `CouponUsageStatsResponse { couponCode, status, redemptionCount, activeReservations, voidedRedemptions, ebSyncStatus, lastEbSyncAt, ebQuantitySoldAtLastSync }`
- **Errors:** `404 COUPON_NOT_FOUND`

#### `DELETE /api/v1/promotions/coupons/{couponCode}`
Deactivate coupon. Triggers guarded EB delete.

- **Response:** `204 No Content`
- **Errors:** `404` | `409 COUPON_HAS_ACTIVE_REDEMPTIONS` if non-voided redemptions exist

---

### Admin — EB Sync Management (`ROLE_ADMIN` required on all)

#### `POST /api/v1/promotions/coupons/{couponCode}/sync`
Manually trigger EB sync (delete + recreate) for a single coupon.

- **Response:** `200 OK` with `CouponResponse` including updated `ebSyncStatus`
- **Errors:** `404` | `422 COUPON_CANNOT_RESYNC` if `ebQuantitySoldAtLastSync > 0`

#### `GET /api/v1/promotions/reconciliation/latest`
Get the latest reconciliation job log for the caller's org.

- **Response:** `200 OK` with `DiscountReconciliationLogResponse { orgId, runAt, discountsChecked, driftsFound, orphansFound, externallyDeletedFound, actionsTakenSummary, errorSummary }`
- **Errors:** `404` if no run has occurred yet

#### `POST /api/v1/promotions/reconciliation/trigger`
Manually trigger a reconciliation run for the caller's org.

- **Response:** `202 Accepted` — run is async
- **Errors:** `409` if a run is already in-flight for the org (ShedLock held)

---

### User — Coupon Apply (`ROLE_USER` required on all)

#### `POST /api/v1/promotions/validate`
Validate a coupon code against a cart and return the discount breakdown.

- **Request body:** `CouponValidateRequest { couponCode, cartId }`
- **Response:** `200 OK` with `DiscountBreakdownResponse { couponCode, discountType, discountAmount, adjustedCartTotal, currency }`
- **Errors:** `404 COUPON_NOT_FOUND` | `410 COUPON_INACTIVE` | `422 COUPON_EXPIRED` | `422 COUPON_NOT_YET_VALID` | `403 COUPON_ORG_MISMATCH` | `422 COUPON_EVENT_MISMATCH` | `409 COUPON_USAGE_LIMIT_REACHED` | `409 COUPON_PER_USER_CAP_REACHED` | `404 CART_NOT_FOUND` | `422 CART_EXPIRED`

#### `DELETE /api/v1/promotions/cart/{cartId}/coupon`
Remove an applied coupon from a cart and release the usage reservation.

- **Response:** `204 No Content`
- **Errors:** `404` if no coupon is applied to the cart | `403` if cart does not belong to calling user

---

## Stage 6 — Business Logic

### BL-1 — Discount Calculation

Discount computation is performed exclusively in `CouponEligibilityService.calculateDiscount()` and returned as a `DiscountCalculationResult` value object. It is **never stored** until a `CouponRedemption` is created on booking confirmation.

**PERCENT_OFF:**
```
discountAmount = floor(cartTotalInSmallestUnit × percentOff / 100)
adjustedTotal  = cartTotalInSmallestUnit − discountAmount
```

**AMOUNT_OFF:**
```
discountAmount = min(amountOffInSmallestUnit, cartTotalInSmallestUnit)
adjustedTotal  = max(0, cartTotalInSmallestUnit − discountAmount)
```

- Both formulas must produce non-negative `adjustedTotal` — floor to `0` minimum
- All arithmetic uses `long` in smallest currency unit (paise / cents) — never `double` or `float`

---

### BL-2 — Coupon Eligibility Pipeline

`CouponEligibilityService.validate(couponCode, cartId, userId)` runs checks in strict order; short-circuits on first failure:

1. Load `Coupon` by code + `orgId` (from `CartSnapshotReader`) — not found → `CouponNotFoundException`
2. Assert `coupon.status == ACTIVE` — `INACTIVE` or `EXHAUSTED` → `CouponInactiveException`
3. Load parent `Promotion`; assert `promotion.isValid(NOW())` — expired → `CouponExpiredException`; not-yet-valid → `CouponNotYetValidException`
4. Assert `coupon.orgId == cartSnapshot.orgId` — mismatch → `CouponOrgMismatchException`
5. If `promotion.scope == EVENT_SCOPED`: assert `cartSnapshot.slotId` maps to `promotion.ebEventId` — mismatch → `CouponEventMismatchException`
6. Assert `coupon.redemptionCount + activeReservationCount(couponId) < maxUsageLimit` (skip if `maxUsageLimit` is null) — exceeded → `CouponUsageLimitReachedException`
7. Assert `userRedemptionCount(couponId, userId) < perUserCap` (skip if `perUserCap` is null) — exceeded → `CouponPerUserCapReachedException`
8. Idempotency check: if an active `CouponUsageReservation` already exists for `(couponId, cartId)`, return existing `DiscountCalculationResult` — no duplicate reservation created

On success: create `CouponUsageReservation`, calculate discount, publish `CouponValidatedEvent`, return `DiscountCalculationResult`.

---

### BL-3 — EB Outbound Sync State Machine

Managed by `DiscountSyncOrchestrator`. All transitions are non-blocking to the caller — EB sync runs after internal save commits.

#### Create path:
```
Internal coupon saved (eb_sync_status = SYNC_PENDING)
  ↓
Idempotency guard: GET /organizations/{org_id}/discounts/ — search for matching code
  ├── Found: adopt eb_discount_id → SYNCED (no POST)
  └── Not found: POST /organizations/{org_id}/discounts/
        ├── Success: store eb_discount_id → verify GET /discounts/{id}/
        │     ├── 200: → SYNCED
        │     └── 404: → SYNC_FAILED
        └── Error: → SYNC_FAILED (eligible for retry on next CouponExpiryJob run)
```

#### Modify path (triggered by PATCH /promotions/{id}):
```
Fetch current EB quantity_sold: GET /discounts/{eb_discount_id}/
  ├── quantity_sold == 0:
  │     DELETE /discounts/{eb_discount_id}/
  │       ├── 200: POST /organizations/{org_id}/discounts/ (new payload)
  │       │     ├── Success: store new eb_discount_id → SYNCED
  │       │     └── Error: → SYNC_FAILED
  │       └── Error (non-409): → SYNC_FAILED
  └── quantity_sold > 0:
        → CANNOT_RESYNC; internal change persisted; alert ops; no EB call
```

#### Delete/Deactivate path:
```
If eb_discount_id is null → skip (nothing to delete on EB)
Fetch current EB quantity_sold: GET /discounts/{eb_discount_id}/
  ├── quantity_sold == 0:
  │     DELETE /discounts/{eb_discount_id}/
  │       ├── 200: eb_discount_id = null; eb_sync_status = NOT_SYNCED
  │       └── 400 DISCOUNT_CANNOT_BE_DELETED: → DELETE_BLOCKED; log ops alert
  │       └── Other error: → SYNC_FAILED
  └── quantity_sold > 0:
        → DELETE_BLOCKED; log ops alert; internal deactivation still completes
```

---

### BL-4 — Post-Redemption Lifecycle

#### On `BookingConfirmedEvent` (received by `BookingConfirmedListener`):
> `BookingConfirmedEvent` now carries `bookingId` as its **first field** (GAP-1 fix). Use `event.bookingId()` to look up the redemption FK — no cross-module DB query needed.
1. Look up `CartSnapshot` by `event.cartId()` via `CartSnapshotReader.getCartSummary()` — extract `couponCode` (nullable; skip entire block if null)
2. Find active `CouponUsageReservation` by `cartId` — if none, log warning and continue (idempotent)
3. Load `Coupon` by `couponCode` — lock row for update
4. Create `CouponRedemption` — set `redeemedAt = NOW()`
5. Call `coupon.recordRedemption(promotion.maxUsageLimit)` — increments count; transitions to `EXHAUSTED` if limit reached
6. Mark reservation `released = true`
7. If coupon transitioned to `EXHAUSTED`: trigger guarded EB delete via `DiscountSyncOrchestrator`
8. Save all; commit

#### On `BookingCancelledEvent` (received by `BookingCancelledListener`):
1. Find non-voided `CouponRedemption` by `bookingId` — if none, return (no coupon was used)
2. Set `voided = true`, `voidedAt = NOW()`
3. Load `Coupon` — lock row for update
4. Call `coupon.voidRedemption()` — decrements `redemptionCount`; reverts `EXHAUSTED → ACTIVE` if applicable
5. If coupon reverted from `EXHAUSTED` and `eb_sync_status IN (DELETE_BLOCKED, EB_DELETED_EXTERNALLY)`: do NOT attempt EB re-create — log only
6. Save all; commit

#### On `PaymentFailedEvent` (received by `PaymentFailedListener`):
1. Find active `CouponUsageReservation` by `cartId` — if none, return
2. Set `released = true`
3. Save; commit (this decrements the effective usage count for subsequent eligibility checks)

---

### BL-5 — Scheduled Jobs

#### `CouponExpiryJob` (every 1 hour, `@Scheduled(cron = "0 0 * * * *")`)
1. Find all `Coupon` records with `status = ACTIVE` and `promotion.validUntil < NOW()`
2. For each expired coupon:
   a. Set `coupon.status = INACTIVE`
   b. Release all open `CouponUsageReservation` rows for this coupon (`released = true`)
   c. Trigger guarded EB delete via `DiscountSyncOrchestrator` (non-blocking; failures logged and continue)
3. For each `SYNC_FAILED` coupon (retry): re-trigger `DiscountSyncOrchestrator.createSync()`
4. Write summary to log; continue on per-coupon failures — never abort entire run

#### `DiscountReconciliationJob` (every 4 hours, configurable, protected by ShedLock)
1. Acquire distributed lock — skip run if already held
2. For each org that has at least one `SYNCED` coupon:
   a. Call `EbDiscountSyncService.listDiscounts(orgId)` — on failure: log, skip org, write log entry, continue
   b. Build `Map<ebDiscountId, EbDiscountDto>` from EB response
   c. For each internal `SYNCED` coupon:
      - Present in EB map + `quantity_sold` drifted → update `redemption_count`, update `eb_quantity_sold_at_last_sync`
      - Present in EB map + fields drifted → set `DRIFT_DETECTED`; alert ops
      - Not in EB map → set `EB_DELETED_EXTERNALLY`; alert ops
   d. For each EB discount in map with no internal match → create `OrphanEbDiscount`; alert ops
   e. Write `DiscountReconciliationLog` for this org
3. Release lock

---

### BL-6 — Promotion Deactivation Cascade

When `PromotionService.deactivate(promotionId)` is called:

1. Validate no non-voided `CouponRedemption` records exist across all linked coupons — else return `409 PROMOTION_HAS_ACTIVE_REDEMPTIONS`
2. For each linked `Coupon`:
   a. Set `status = INACTIVE`
   b. Release all open `CouponUsageReservation` rows
   c. Trigger guarded EB delete (non-blocking; failure logged and continue)
3. Set `promotion.status = INACTIVE`
4. Commit all in single `@Transactional` boundary

---

## Stage 7 — Error Handling

### Coupon Validation — User-Facing

| Scenario | HTTP Status | Error Code |
|---|---|---|
| Coupon code does not exist in org | `404` | `COUPON_NOT_FOUND` |
| Coupon exists but `status = INACTIVE` | `410` | `COUPON_INACTIVE` |
| Coupon exists but `status = EXHAUSTED` | `410` | `COUPON_INACTIVE` |
| Current time is after `promotion.validUntil` | `422` | `COUPON_EXPIRED` |
| Current time is before `promotion.validFrom` | `422` | `COUPON_NOT_YET_VALID` |
| Cart's `orgId` does not match `coupon.orgId` | `403` | `COUPON_ORG_MISMATCH` |
| EVENT_SCOPED coupon applied to wrong slot | `422` | `COUPON_EVENT_MISMATCH` |
| Global usage limit reached | `409` | `COUPON_USAGE_LIMIT_REACHED` |
| Per-user cap reached for this user | `409` | `COUPON_PER_USER_CAP_REACHED` |
| Same coupon already applied to same cart | `200` | — (idempotent success, return existing breakdown) |
| Cart not found | `404` | `CART_NOT_FOUND` |
| Cart is expired | `422` | `CART_EXPIRED` |

### Admin Operations

| Scenario | HTTP Status | Error Code |
|---|---|---|
| Duplicate coupon code within same org | `409` | `COUPON_CODE_CONFLICT` |
| Deleting promotion with non-voided redemptions | `409` | `PROMOTION_HAS_ACTIVE_REDEMPTIONS` |
| Deleting coupon with non-voided redemptions | `409` | `COUPON_HAS_ACTIVE_REDEMPTIONS` |
| Manual sync attempted with `CANNOT_RESYNC` status | `422` | `COUPON_CANNOT_RESYNC` |
| Promotion not found | `404` | `PROMOTION_NOT_FOUND` |
| Coupon not found | `404` | `COUPON_NOT_FOUND` |

### EB Outbound Sync Failures (non-blocking — internal proceeds regardless)

| Scenario | Internal Action | EB Sync Status Set |
|---|---|---|
| EB create returns non-2xx | Log error; coupon stays `ACTIVE`; eligible for retry | `SYNC_FAILED` |
| EB verify GET after create returns 404 | Log warning; schedule retry | `SYNC_FAILED` |
| EB delete fails (non-409) on deactivation | Log error; internal deactivation still completes | `SYNC_FAILED` |
| EB returns `DISCOUNT_CANNOT_BE_DELETED` (400) | Log ops alert; internal deactivation still completes | `DELETE_BLOCKED` |
| Modify attempted; EB `quantity_sold > 0` | Internal change persists; alert ops; no EB call | `CANNOT_RESYNC` |
| Same code already on EB (idempotency guard) | Adopt existing `eb_discount_id`; log warning | `SYNCED` |
| `EbAuthException` (token unavailable) | Log; defer to next scheduled job | `SYNC_FAILED` |
| `eb_event_id` null on EVENT_SCOPED coupon | Log warning; skip EB sync | `NOT_SYNCED` |

### EB Inbound Sync / Reconciliation Failures

| Scenario | Internal Action | EB Sync Status Set |
|---|---|---|
| EB list call fails for an org | Log error; skip org; write `DiscountReconciliationLog` with error; continue other orgs | — (no status change) |
| EB discount present; no internal match | Create `OrphanEbDiscount`; alert ops | — (orphan, no coupon row) |
| Internal `SYNCED` coupon absent from EB list | Alert ops | `EB_DELETED_EXTERNALLY` |
| EB field drift detected (code/value changed on EB) | Alert ops; do NOT auto-overwrite | `DRIFT_DETECTED` |
| EB `quantity_sold` higher than internal count | Update `redemption_count` to EB value; log drift | `SYNCED` (stays) |

### Scheduled Job Failures

| Scenario | Internal Action |
|---|---|
| `CouponExpiryJob` fails on one coupon mid-run | Log and skip; continue remaining; write partial summary |
| `DiscountReconciliationJob` already running when next trigger fires | Skip trigger (ShedLock held); log |
| `DiscountReconciliationJob` throws unhandled exception | Log; release ShedLock; write error log entry |

---

## Stage 8 — Tests

### Layer 1 — Domain Unit Tests (`promotions/src/test/java/.../domain/`)

#### `PromotionTest`

**D1 — `deactivate_should_cascade_inactive_status_to_all_linked_coupons`**
```
Given:  Promotion with status=ACTIVE and 3 linked Coupons with status=ACTIVE
When:   promotion.deactivate() is called
Then:   promotion.status == INACTIVE
        all 3 coupons.status == INACTIVE
```

**D2 — `isValid_should_return_false_when_validUntil_is_in_past`**
```
Given:  Promotion with validFrom=yesterday, validUntil=1 hour ago
When:   promotion.isValid(NOW()) is called
Then:   returns false
```

**D3 — `isValid_should_return_false_when_validFrom_is_in_future`**
```
Given:  Promotion with validFrom=tomorrow
When:   promotion.isValid(NOW()) is called
Then:   returns false
```

#### `CouponTest`

**D4 — `recordRedemption_should_transition_to_EXHAUSTED_when_count_reaches_max_usage_limit`**
```
Given:  Coupon with redemptionCount=4, maxUsageLimit=5
When:   coupon.recordRedemption(5) is called
Then:   coupon.redemptionCount == 5
        coupon.status == EXHAUSTED
```

**D5 — `voidRedemption_should_revert_EXHAUSTED_to_ACTIVE`**
```
Given:  Coupon with status=EXHAUSTED, redemptionCount=5, maxUsageLimit=5
When:   coupon.voidRedemption() is called
Then:   coupon.redemptionCount == 4
        coupon.status == ACTIVE
```

**D6 — `canEbDelete_should_return_false_when_ebQuantitySoldAtLastSync_is_greater_than_zero`**
```
Given:  Coupon with ebDiscountId="eb_123", ebQuantitySoldAtLastSync=1
When:   coupon.canEbDelete() is called
Then:   returns false
```

#### `DiscountCalculationResultTest`

**D7 — `percentOff_should_compute_discount_correctly_in_smallest_unit`**
```
Given:  cartTotal=10000 (smallest unit), percentOff=10
When:   calculation applied
Then:   discountAmount == 1000
        adjustedTotal == 9000
```

**D8 — `amountOff_should_floor_to_zero_when_discount_exceeds_cart_total`**
```
Given:  cartTotal=500 (smallest unit), amountOff=1000 (smallest unit)
When:   calculation applied
Then:   discountAmount == 500
        adjustedTotal == 0
```

---

### Layer 2 — Service Tests (`promotions/src/test/java/.../service/`, Mockito)

#### `CouponEligibilityServiceTest`

**S1 — `validate_should_return_discount_breakdown_for_valid_active_coupon`**
```
Given:  Active coupon, valid promotion, cart org matches, within window, usage not exceeded
When:   validate(couponCode, cartId, userId) called
Then:   Returns DiscountBreakdownResponse with correct discountAmount
        CouponUsageReservation created
        CouponValidatedEvent published
```

**S2 — `validate_should_throw_CouponExpiredException_when_outside_validity_window`**
```
Given:  Coupon with validUntil in the past
When:   validate called
Then:   CouponExpiredException thrown
        No reservation created
```

**S3 — `validate_should_throw_CouponInactiveException_when_status_is_EXHAUSTED`**
```
Given:  Coupon with status=EXHAUSTED
When:   validate called
Then:   CouponInactiveException thrown
```

**S4 — `validate_should_throw_CouponOrgMismatchException_when_coupon_org_differs_from_cart_org`**
```
Given:  Coupon.orgId="org-1", cartSnapshot.orgId="org-2"
When:   validate called
Then:   CouponOrgMismatchException thrown
```

**S5 — `validate_should_throw_CouponEventMismatchException_for_event_scoped_coupon_on_wrong_slot`**
```
Given:  promotion.scope=EVENT_SCOPED, promotion.ebEventId="eb-event-1"
        cartSnapshot.slotId maps to ebEventId="eb-event-2"
When:   validate called
Then:   CouponEventMismatchException thrown
```

**S6 — `validate_should_throw_CouponUsageLimitReachedException_when_count_plus_reservations_equals_max`**
```
Given:  maxUsageLimit=5, redemptionCount=4, activeReservations=1
When:   validate called
Then:   CouponUsageLimitReachedException thrown
```

**S7 — `validate_should_throw_CouponPerUserCapReachedException_when_user_has_reached_per_user_cap`**
```
Given:  perUserCap=1, user already has 1 non-voided redemption for this coupon
When:   validate called
Then:   CouponPerUserCapReachedException thrown
```

**S8 — `validate_should_return_idempotent_response_when_reservation_already_exists_for_same_cart`**
```
Given:  Active CouponUsageReservation exists for (couponId, cartId)
When:   validate called again with same cartId + couponCode
Then:   Returns same DiscountBreakdownResponse
        No second reservation created
        No second event published
```

**S9 — `validate_should_create_CouponUsageReservation_with_expiresAt_matching_cart_expiry`**
```
Given:  Cart expiresAt = T+30min
When:   validate succeeds
Then:   Reserved CouponUsageReservation.expiresAt == cart.expiresAt
```

#### `CouponRedemptionServiceTest`

**S10 — `onBookingConfirmed_should_create_redemption_and_increment_count`**
```
Given:  BookingConfirmedEvent(bookingId=91, cartId=500, ...) with active reservation for cartId=500
When:   listener fires
Then:   CouponRedemption record created with bookingId=91 and correct amounts
        coupon.redemptionCount incremented by 1
        reservation.released = true
```

**S11 — `onBookingConfirmed_should_mark_coupon_EXHAUSTED_and_trigger_guarded_delete_when_limit_reached`**
```
Given:  Coupon maxUsageLimit=5, redemptionCount=4 before confirm
When:   BookingConfirmedEvent fires
Then:   coupon.status == EXHAUSTED
        DiscountSyncOrchestrator.deleteSync() called
```

**S12 — `onBookingConfirmed_should_be_noop_when_no_coupon_in_cart_snapshot`**
```
Given:  CartSnapshot.couponCode = null
When:   BookingConfirmedEvent fires
Then:   No CouponRedemption created
        No exception thrown
```

**S13 — `onBookingCancelled_should_void_redemption_and_decrement_count`**
```
Given:  Non-voided CouponRedemption exists for bookingId
When:   BookingCancelledEvent fires
Then:   redemption.voided = true
        coupon.redemptionCount decremented by 1
```

**S14 — `onBookingCancelled_should_revert_EXHAUSTED_to_ACTIVE_and_not_recreate_on_EB`**
```
Given:  Coupon was EXHAUSTED, eb_sync_status=DELETE_BLOCKED
When:   BookingCancelledEvent fires
Then:   coupon.status == ACTIVE
        DiscountSyncOrchestrator NOT called (no EB re-create)
```

**S15 — `onPaymentFailed_should_release_open_usage_reservation_for_cart`**
```
Given:  Active CouponUsageReservation for cartId
When:   PaymentFailedEvent fires
Then:   reservation.released = true
```

#### `DiscountSyncOrchestratorTest`

**S16 — `createSync_should_call_EbDiscountSyncService_and_store_eb_discount_id_on_success`**
```
Given:  Coupon with eb_sync_status=SYNC_PENDING
        Idempotency guard returns no existing match
        EbDiscountSyncService.createDiscount() returns "eb_discount_abc"
        EbDiscountSyncService.getDiscount("eb_discount_abc") returns valid response
When:   createSync(coupon) called
Then:   coupon.ebDiscountId == "eb_discount_abc"
        coupon.ebSyncStatus == SYNCED
```

**S17 — `createSync_should_set_SYNC_FAILED_when_EB_create_returns_error_and_not_block_internal`**
```
Given:  EbDiscountSyncService.createDiscount() throws EbDiscountSyncException
When:   createSync(coupon) called
Then:   coupon.ebSyncStatus == SYNC_FAILED
        No exception propagated to caller
```

**S18 — `modifySync_should_delete_then_recreate_on_EB_when_quantity_sold_is_zero`**
```
Given:  Coupon with ebDiscountId="old_id", eb_quantity_sold_at_last_sync=0
        EbDiscountSyncService.getDiscount("old_id").quantitySold = 0
        EbDiscountSyncService.deleteDiscount("old_id") succeeds
        EbDiscountSyncService.createDiscount() returns "new_id"
When:   modifySync(coupon) called
Then:   coupon.ebDiscountId == "new_id"
        coupon.ebSyncStatus == SYNCED
```

**S19 — `modifySync_should_set_CANNOT_RESYNC_when_quantity_sold_is_greater_than_zero`**
```
Given:  EbDiscountSyncService.getDiscount(ebDiscountId).quantitySold = 3
When:   modifySync(coupon) called
Then:   coupon.ebSyncStatus == CANNOT_RESYNC
        No DELETE called on EB
        Internal coupon change persisted
```

**S20 — `deleteSync_should_call_EB_delete_when_quantity_sold_is_zero`**
```
Given:  Coupon with ebDiscountId="eb_123", eb_quantity_sold_at_last_sync=0
        EB GET returns quantitySold=0
When:   deleteSync(coupon) called
Then:   EbDiscountSyncService.deleteDiscount("eb_123") called
        coupon.ebDiscountId = null
        coupon.ebSyncStatus = NOT_SYNCED
```

**S21 — `deleteSync_should_set_DELETE_BLOCKED_when_EB_responds_DISCOUNT_CANNOT_BE_DELETED`**
```
Given:  EB GET returns quantitySold=2
When:   deleteSync(coupon) called
Then:   No DELETE call made
        coupon.ebSyncStatus == DELETE_BLOCKED
        No exception propagated
```

**S22 — `createSync_should_adopt_existing_eb_discount_id_when_idempotency_guard_finds_matching_code`**
```
Given:  EbDiscountSyncService.listDiscounts(orgId) returns existing discount with same code
When:   createSync(coupon) called
Then:   coupon.ebDiscountId = existing eb_discount_id
        coupon.ebSyncStatus == SYNCED
        No POST /organizations/{org_id}/discounts/ called
```

**S23 — `createSync_should_skip_EB_and_log_warning_when_eb_event_id_is_null_on_event_scoped_coupon`**
```
Given:  promotion.scope=EVENT_SCOPED, promotion.ebEventId=null
When:   createSync(coupon) called
Then:   No EB API calls made
        coupon.ebSyncStatus == NOT_SYNCED
```

#### `DiscountReconciliationJobTest`

**S24 — `reconciliation_should_update_internal_redemption_count_when_EB_quantity_sold_is_higher`**
```
Given:  Internal coupon redemptionCount=2
        EB returns same ebDiscountId with quantitySold=3
When:   reconciliation job runs
Then:   coupon.redemptionCount updated to 3
        coupon.ebQuantitySoldAtLastSync = 3
        DiscountReconciliationLog.driftsFound == 1
```

**S25 — `reconciliation_should_create_OrphanEbDiscount_when_no_internal_match_found`**
```
Given:  EB returns discount with ebDiscountId="orphan_id" — no internal coupon with this id
When:   reconciliation job runs
Then:   OrphanEbDiscount record created with eb_discount_id="orphan_id"
        DiscountReconciliationLog.orphansFound == 1
```

**S26 — `reconciliation_should_set_EB_DELETED_EXTERNALLY_when_synced_coupon_missing_from_EB_list`**
```
Given:  Internal coupon with ebSyncStatus=SYNCED, ebDiscountId="eb_gone"
        EB list does not contain "eb_gone"
When:   reconciliation job runs
Then:   coupon.ebSyncStatus == EB_DELETED_EXTERNALLY
        DiscountReconciliationLog.externallyDeletedFound == 1
```

**S27 — `reconciliation_should_set_DRIFT_DETECTED_when_EB_fields_differ_from_internal`**
```
Given:  Internal coupon.code="SAVE10", EB discount returns code="SAVE20" for same ebDiscountId
When:   reconciliation job runs
Then:   coupon.ebSyncStatus == DRIFT_DETECTED
        Internal coupon.code unchanged
```

**S28 — `reconciliation_should_skip_org_and_log_on_EB_list_call_failure_without_aborting_other_orgs`**
```
Given:  2 orgs with SYNCED coupons
        EB list call throws exception for org-1
When:   reconciliation job runs
Then:   org-2 still processed successfully
        DiscountReconciliationLog for org-1 contains errorSummary
        No exception propagated
```

#### `CouponExpiryJobTest`

**S29 — `expiryJob_should_deactivate_expired_coupons_and_release_open_reservations`**
```
Given:  Coupon with status=ACTIVE, promotion.validUntil=1 hour ago
        2 open CouponUsageReservation rows for this coupon
When:   expiryJob runs
Then:   coupon.status == INACTIVE
        Both reservations.released == true
```

**S30 — `expiryJob_should_trigger_guarded_EB_delete_for_expired_coupon_with_zero_quantity_sold`**
```
Given:  Expired coupon, ebDiscountId="eb_exp", ebQuantitySoldAtLastSync=0
When:   expiryJob runs
Then:   DiscountSyncOrchestrator.deleteSync(coupon) called
```

**S31 — `expiryJob_should_continue_processing_remaining_coupons_when_one_fails`**
```
Given:  3 expired coupons; deleteSync throws exception for coupon-2
When:   expiryJob runs
Then:   coupon-1 and coupon-3 deactivated successfully
        Exception logged; no rethrow
```

---

### Layer 3 — API Tests (`promotions/src/test/java/.../api/`, @WebMvcTest)

**A1 — `POST_promotions_should_return_201_with_promotion_id_for_ROLE_ADMIN`**
```
Given:  Valid PromotionCreateRequest, ROLE_ADMIN JWT
When:   POST /api/v1/promotions
Then:   201 Created; body contains promotion.id and status=ACTIVE
```

**A2 — `POST_promotions_should_return_403_when_called_by_ROLE_USER`**
```
Given:  Valid PromotionCreateRequest, ROLE_USER JWT
When:   POST /api/v1/promotions
Then:   403 Forbidden
```

**A3 — `POST_coupons_should_return_201_and_show_SYNC_PENDING_eb_sync_status`**
```
Given:  Valid CouponCreateRequest under existing promotion, ROLE_ADMIN JWT
When:   POST /api/v1/promotions/{promotionId}/coupons
Then:   201 Created; body.ebSyncStatus == "SYNC_PENDING"
```

**A4 — `POST_coupons_should_return_409_on_duplicate_code_within_org`**
```
Given:  Code "SAVE10" already exists for org
When:   POST /api/v1/promotions/{promotionId}/coupons with code="SAVE10"
Then:   409 Conflict; errorCode == "COUPON_CODE_CONFLICT"
```

**A5 — `POST_promotions_validate_should_return_200_with_discount_breakdown_for_valid_coupon`**
```
Given:  Active coupon, valid cart, ROLE_USER JWT
When:   POST /api/v1/promotions/validate
Then:   200 OK; body contains discountAmount, adjustedCartTotal, currency
```

**A6 — `POST_promotions_validate_should_return_410_when_coupon_is_INACTIVE`**
```
Given:  Coupon with status=INACTIVE
When:   POST /api/v1/promotions/validate
Then:   410 Gone; errorCode == "COUPON_INACTIVE"
```

**A7 — `POST_promotions_validate_should_return_409_when_usage_limit_reached`**
```
Given:  Coupon at maxUsageLimit
When:   POST /api/v1/promotions/validate
Then:   409 Conflict; errorCode == "COUPON_USAGE_LIMIT_REACHED"
```

**A8 — `DELETE_promotions_cart_coupon_should_return_204_and_release_reservation`**
```
Given:  Active CouponUsageReservation for cartId; ROLE_USER JWT (owns cart)
When:   DELETE /api/v1/promotions/cart/{cartId}/coupon
Then:   204 No Content; reservation.released == true
```

**A9 — `POST_coupons_sync_should_return_200_with_updated_ebSyncStatus`**
```
Given:  Coupon with ebSyncStatus=SYNC_FAILED; ROLE_ADMIN JWT
        EB call succeeds on retry
When:   POST /api/v1/promotions/coupons/{couponCode}/sync
Then:   200 OK; body.ebSyncStatus == "SYNCED"
```

**A10 — `POST_reconciliation_trigger_should_return_202_accepted`**
```
Given:  No in-flight reconciliation for org; ROLE_ADMIN JWT
When:   POST /api/v1/promotions/reconciliation/trigger
Then:   202 Accepted
```

---

### Layer 4 — ArchUnit Tests (`promotions/src/test/java/.../arch/`)

**AR1 — `promotions_module_should_not_import_any_other_module_service_or_repository`**
```java
noClasses().that().resideInAPackage("..promotions..")
    .should().dependOnClassesThat()
    .resideInAnyPackage(
        "..bookinginventory.service..",
        "..bookinginventory.repository..",
        "..paymentsticketing.service..",
        "..paymentsticketing.repository..",
        "..identity.service..",
        "..scheduling.service.."
    )
```

**AR2 — `promotions_module_should_only_call_eventbrite_via_EbDiscountSyncService_facade`**
```java
noClasses().that().resideInAPackage("..promotions..")
    .should().accessClassesThat()
    .resideInAPackage("..shared.eventbrite..")
    .and().not().haveSimpleName("EbDiscountSyncService")
```

**AR3 — `promotions_event_listeners_should_only_accept_shared_event_types`**
```java
methods().that().areAnnotatedWith(EventListener.class)
    .and().areDeclaredInClassesThat().resideInAPackage("..promotions.event.listener..")
    .should().haveRawParameterTypes(
        BookingConfirmedEvent.class,
        BookingCancelledEvent.class,
        PaymentFailedEvent.class,
        CartAssembledEvent.class
    )
```

**AR4 — `CouponAppliedEvent_and_CouponValidatedEvent_should_reside_in_shared_module_only`**
```java
classes().that().haveSimpleNameIn("CouponAppliedEvent", "CouponValidatedEvent")
    .should().resideInAPackage("..shared.common.event.published..")
```

---

## Open Questions

1. **Currency handling for AMOUNT_OFF:** Should `discount_value` in `promotions` be stored in major currency unit (e.g. ₹50) or smallest unit (e.g. 5000 paise)? Recommendation: store in smallest unit, consistent with FR5 Stripe `amount` convention.

2. **Multi-currency coupons:** Can a coupon be valid across events in different currencies, or is a coupon always scoped to a single currency? Recommendation: bind `Promotion.currency` to a single currency and reject cart if cart currency does not match.

3. **Group vs coupon stacking:** Can a user apply both a coupon AND benefit from group discount (FR4 `groupDiscountPercent`) in the same cart? Recommendation: allow stacking — apply group discount first at pricing tier level, then apply coupon to the final cart total.
   > **Note (GAP-3/GAP-4 resolution):** This stacking is now correctly implemented. `CartPricingService.recompute()` computes and stores `groupDiscountAmount` independently. `CartService.confirm()` then subtracts `couponDiscountAmount` from the post-group-discount total. The two discounts never overwrite each other.

4. **ShedLock dependency:** Is ShedLock already on the classpath (added in FR6 for `CancellationPolicy` jobs)? If not, it must be added to `promotions/pom.xml`.

5. **Admin JWT `orgId` extraction:** `ROLE_ADMIN` JWT must carry an `orgId` claim so that `CouponAdminController` can scope all operations correctly. Confirm this is already issued by `identity` for admin users.

6. **`CouponAppliedListener` cart mutation pattern:** When `CouponAppliedEvent` is received by `booking-inventory.CouponAppliedListener`, the listener must call `cart.setCouponDiscountAmount(discountInMajorUnit)` (converting from `discountAmountInSmallestUnit / 100`). It must **not** call `cartPricingService.recompute()` — that would overwrite `groupDiscountAmount` with a cached stale value. The listener should only do: load cart → set coupon discount → save cart.

7. **`CartSummaryDto` currency field source:** `CartSummaryDto.currency` is sourced from `cart.getGroupDiscountAmount().currency()` (the embedded `Money` field). This is always set at cart creation from the `currency` constructor argument. Confirmed correct.
