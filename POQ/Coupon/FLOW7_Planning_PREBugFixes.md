User: Now lets plant the functional Flow  7 one by one the cupon and discount the flow is everything is done internally first then synced with EB and any changes we sync if any change was done from EB platform and update our internal DB any edge cases needs to be handled. There is not update option so any modification in the the discount we dont have teh PUT/PATCH option so we need to carefully use PUT on the EB sync for discount.

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/agents/CODING_AGENT.md#1-1), lines 1 to 80

Read [](file:///home/dell/Multi-City-Event-Booking/docs/EVENTBRITE_INTEGRATION.md#1-1), lines 1 to 100

Searched for regex `FR7|discount|coupon|EbDiscountSyncService|promotions`, 20 results

Read [](file:///home/dell/Multi-City-Event-Booking/promotions/src/main/java)

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#700-700), lines 700 to 780

Read [](file:///home/dell/Multi-City-Event-Booking/promotions/src)

Read [](file:///home/dell/Multi-City-Event-Booking/promotions)

Searched for regex `discount|coupon`, 40 results

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/models/discount.py)

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/schemas/discount.py)

Searched for text `def `, 20 results

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/api/routes/discounts.py#1-1), lines 1 to 120

Read [](file:///home/dell/Multi-City-Event-Booking/docs/EVENTBRITE_INTEGRATION.md#100-100), lines 100 to 300

Now I have all the context I need. Let me read the FR7 and discount sync sections in detail to produce a complete SPEC.  

Searched for text `FR7`, 20 results

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#170-170), lines 170 to 260

Now I have all the context. Let me produce the complete `SPEC_7.md` for FR7. 

Created 1 todo

Using "Create File"

User: Create stage 1 get verified and then next

GitHub Copilot: Here is the **Stage 1 — Requirements outline** for SPEC_7.md (FR7: Offer & Coupon Engine → Eligibility Check → Discount Application). Review and confirm before I write the full content.

---

## Stage 1 Outline — Requirements

### Area A — Promotion & Coupon Management (Admin, Internal)
- `[MUST]` Admin creates a promotion internally (name, type: PERCENT_OFF / AMOUNT_OFF, value, scope: org-wide or event-scoped, validity window, max usage limit, per-user usage cap)
- `[MUST]` Admin creates a coupon code and links it to a promotion
- `[MUST]` Admin can deactivate a coupon (soft-delete internally; triggers EB delete if synced)
- `[MUST]` Admin can update a promotion's validity/usage limits — triggers EB re-sync via delete+recreate (no PUT/PATCH exists)
- `[MUST]` Duplicate coupon code within the same org is rejected
- `[SHOULD]` Admin can list all promotions/coupons with current redemption stats

### Area B — EB Outbound Sync (Internal → Eventbrite)
- `[MUST]` After internal creation, push discount to Eventbrite via `EbDiscountSyncService.createDiscount(orgId, payload)` → `POST /organizations/{org_id}/discounts/`
- `[MUST]` Store returned `eb_discount_id` on the internal coupon record; mark `eb_sync_status = SYNCED`
- `[MUST]` If EB create call fails, mark `eb_sync_status = SYNC_FAILED`; coupon is still usable internally — EB sync is non-blocking
- `[MUST]` **Modification rule:** Eventbrite has no PUT/PATCH — any edit to an EB-synced discount must: (1) `DELETE /discounts/{eb_discount_id}/` then (2) `POST /organizations/{org_id}/discounts/` with updated fields, then (3) store new `eb_discount_id`. If EB has recorded any `quantity_sold > 0`, skip the EB delete — flag as `CANNOT_RESYNC` and alert ops
- `[MUST]` Deactivation of an internally used (redeemed) coupon: mark internal `status = INACTIVE`; if `eb_discount_id` exists and `eb_quantity_sold = 0`, call `DELETE /discounts/{eb_discount_id}/`; if `eb_quantity_sold > 0`, skip EB delete (EB constraint), record `eb_sync_status = DELETE_BLOCKED`
- `[MUST]` After promotion expiry, scheduled job deactivates expired coupons and triggers EB delete (same guarded delete logic)
- `[SHOULD]` After any EB create, verify the discount with `GET /discounts/{eb_discount_id}/` and reconcile fields

### Area C — EB Inbound Sync (Eventbrite → Internal)
- `[MUST]` Periodic reconciliation job (`DiscountReconciliationJob`) calls `GET /organizations/{org_id}/discounts/` to list all EB discounts
- `[MUST]` For each EB discount record, find the matching internal coupon by `eb_discount_id`; if EB `quantity_sold` > internal `redemption_count`, update internal `redemption_count`
- `[MUST]` If an EB discount is found with no matching internal record (orphan), create a shadow `OrphanEbDiscount` audit record and alert ops (do not auto-import)
- `[MUST]` If an EB discount that was `SYNCED` internally is no longer present on EB (deleted externally), mark internal coupon `eb_sync_status = EB_DELETED_EXTERNALLY` and flag for admin review
- `[SHOULD]` Reconciliation runs on a configurable schedule (default: every 4 hours)
- `[WONT]` Auto-applying external EB discount codes to internal orders without admin approval

### Area D — Coupon Eligibility & Application (User Flow)
- `[MUST]` Expose `POST /api/v1/promotions/validate` accepting `{ couponCode, cartId }` (JWT `ROLE_USER`)
- `[MUST]` Eligibility checks (in order): coupon exists and `status = ACTIVE`, within validity window, org matches cart's org, not redeemed beyond `maxUsageLimit`, not redeemed beyond `perUserCap` by this user
- `[MUST]` Return `DiscountBreakdownResponse` with `discountAmount`, `discountType`, `adjustedCartTotal`
- `[MUST]` If coupon is valid, publish `CouponValidatedEvent(cartId, couponCode, discountAmountInSmallestUnit, userId)` → booking-inventory recomputes cart total
- `[MUST]` Publish `CouponAppliedEvent(cartId, couponCode, discountAmountInSmallestUnit, userId)` after `CartAssembledEvent` confirms the code was included in the cart snapshot
- `[MUST]` Prevent double-apply: if same coupon already applied to same cart, return idempotent response
- `[SHOULD]` Soft hold on usage limit: reserve a slot during cart session; release on `PaymentFailedEvent` or cart expiry

### Area E — Post-Redemption & Lifecycle
- `[MUST]` On `BookingConfirmedEvent(cartId, ...)`, if `couponCode` is present in cart snapshot, record `CouponRedemption(couponCode, userId, bookingId, amount, redeemedAt)`
- `[MUST]` Increment internal `redemption_count`; if `redemption_count >= maxUsageLimit`, mark coupon `EXHAUSTED` and trigger EB delete (guarded)
- `[MUST]` On `BookingCancelledEvent`, void the `CouponRedemption` record and decrement `redemption_count` (release the slot back to the pool)
- `[MUST]` On `PaymentFailedEvent(cartId, userId)`, release any soft-held usage reservation for this cart
- `[SHOULD]` Expose `GET /api/v1/promotions/{couponCode}/usage` for admin to view current redemption stats

### Area F — Error & Edge Cases
- `[MUST]` EB sync failure never blocks internal coupon creation or user-facing eligibility check
- `[MUST]` EB `DISCOUNT_CANNOT_BE_DELETED` (quantity_sold > 0) is handled gracefully — log, set `eb_sync_status = DELETE_BLOCKED`, continue
- `[MUST]` If both internal and EB records exist but `eb_discount_id` is mismatched (drift), flag `eb_sync_status = DRIFT_DETECTED` for manual reconciliation
- `[MUST]` If `eb_event_id` is null on the target event slot, EB sync is skipped but internal coupon is created; log warning
- `[SHOULD]` Idempotent EB create: before creating on EB, check `GET /organizations/{org_id}/discounts/` for existing code with same value; if found, store existing `eb_discount_id` rather than creating a duplicate
- `[WONT]` Per-seat or per-ticket-class coupon scoping in FR7

---

> Does this look complete? Anything to add, remove, or change before I write Stage 1 in full?

User: next stage

GitHub Copilot: Here is the **Stage 2 — Domain Model outline**. Review and confirm before I write the full content.

---

## Stage 2 Outline — Domain Model

### Entities

| Entity | One-line description |
|---|---|
| `Promotion` | Admin-defined offer rule: type, value, scope, validity window, usage limits, EB sync state |
| `Coupon` | A redeemable code linked to one `Promotion`; tracks internal status and `eb_discount_id` |
| `CouponRedemption` | Immutable record of a successful coupon use — links `Coupon → User → Booking` |
| `CouponUsageReservation` | Soft-hold on a usage slot during active cart session; released on failure/expiry |
| `OrphanEbDiscount` | Audit-only record for EB discounts with no matching internal coupon |
| `DiscountReconciliationLog` | Timestamped record of each reconciliation job run: counts, drifts found, actions taken |

### Enums

| Enum | Values |
|---|---|
| `DiscountType` | `PERCENT_OFF`, `AMOUNT_OFF` |
| `PromotionScope` | `ORG_WIDE`, `EVENT_SCOPED` |
| `CouponStatus` | `ACTIVE`, `INACTIVE`, `EXHAUSTED` |
| `EbSyncStatus` | `NOT_SYNCED`, `SYNC_PENDING`, `SYNCED`, `SYNC_FAILED`, `DELETE_BLOCKED`, `EB_DELETED_EXTERNALLY`, `DRIFT_DETECTED`, `CANNOT_RESYNC` |

### Key Relationships

- `Promotion` 1 → N `Coupon` (one promotion may have multiple codes)
- `Coupon` 1 → N `CouponRedemption`
- `Coupon` 1 → N `CouponUsageReservation` (at most one active per `cartId`)
- `CouponRedemption` links to `userId` (identity, ID only) and `bookingId` (payments-ticketing, ID only)

### Non-Persisted Value Objects

| Value Object | Purpose |
|---|---|
| `DiscountCalculationResult(discountType, discountAmountInSmallestUnit, adjustedTotal, couponCode)` | Computed eligibility result returned to caller; never persisted |
| `EbDiscountSyncPayload(code, type, percentOff, amountOff, startDate, endDate, quantityAvailable, eventId, ticketClassIds)` | Mapped from `Coupon` + `Promotion` before EB API call |

### Cross-Module ID References (no entity imports)

| Field | Points To | Module |
|---|---|---|
| `CouponRedemption.userId` | `users.id` | identity |
| `CouponRedemption.bookingId` | `bookings.id` | payments-ticketing |
| `CouponUsageReservation.cartId` | `carts.id` | booking-inventory |
| `Promotion.orgId` | org context | identity/scheduling |
| `Promotion.ebEventId` | `show_slots.eb_event_id` | scheduling (nullable — org-wide coupons have no event scope) |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 2 in full?

User: Next stage

GitHub Copilot: Here is the **Stage 3 — Architecture & File Structure outline**. Review and confirm before I write the full content.

---

## Stage 3 Outline — Architecture & File Structure

### Module Ownership
- **Primary owner:** `promotions`
- **Event consumers (additions to existing modules):** booking-inventory (listens to `CouponAppliedEvent` → recompute cart total)
- **Shared additions:** two new event records in `shared/common/event/published/`
- **Admin module:** read-only delegation to `promotions` REST API (no owned entities)

---

### Inter-Module Communication Plan

| Interaction | Type | Mechanism |
|---|---|---|
| `promotions` reads cart org/slot/ebEventId on validate | In-process read | `CartSnapshotReader` (already in shared, implemented by booking-inventory) |
| `promotions` publishes coupon applied | Async event | `CouponAppliedEvent` → booking-inventory `CouponAppliedListener` recomputes cart total |
| `promotions` listens to booking confirmed | Async event | `BookingConfirmedEvent` (shared) → record redemption, increment usage count |
| `promotions` listens to booking cancelled | Async event | `BookingCancelledEvent` (shared) → void redemption, decrement usage count |
| `promotions` listens to payment failed | Async event | `PaymentFailedEvent` (shared) → release soft-held usage reservation |
| `promotions` → Eventbrite | ACL facade | `EbDiscountSyncService` (shared) only — never direct HTTP |

---

### Feature Files

**shared additions (2 files):**
```
shared/common/event/published/
├── CouponAppliedEvent.java        ← record(cartId, couponCode, discountAmountInSmallestUnit, currency, userId)
└── CouponValidatedEvent.java      ← record(cartId, couponCode, discountAmountInSmallestUnit, currency, userId)
```

**booking-inventory addition (1 file):**
```
event/listener/
└── CouponAppliedListener.java     ← listens CouponAppliedEvent; triggers cart total recompute
```

**`promotions/` primary module:**
```
promotions/src/main/java/com/eventplatform/promotions/
├── api/controller/
│   ├── PromotionAdminController.java
│   ├── CouponAdminController.java
│   └── CouponUserController.java
├── api/dto/request/
│   ├── PromotionCreateRequest.java
│   ├── CouponCreateRequest.java
│   └── CouponValidateRequest.java
├── api/dto/response/
│   ├── PromotionResponse.java
│   ├── CouponResponse.java
│   ├── DiscountBreakdownResponse.java
│   └── CouponUsageStatsResponse.java
├── domain/
│   ├── Promotion.java
│   ├── Coupon.java
│   ├── CouponRedemption.java
│   ├── CouponUsageReservation.java
│   ├── OrphanEbDiscount.java
│   └── DiscountReconciliationLog.java
├── service/
│   ├── PromotionService.java              ← CRUD, lifecycle management
│   ├── CouponEligibilityService.java      ← validate, soft-hold, idempotency
│   ├── CouponRedemptionService.java       ← record/void redemptions, usage counters
│   └── DiscountSyncOrchestrator.java      ← calls EbDiscountSyncService (create/delete+recreate/guarded-delete)
├── scheduler/
│   ├── DiscountReconciliationJob.java     ← inbound EB sync: list, diff, flag orphans/drift
│   └── CouponExpiryJob.java              ← deactivate expired coupons + trigger guarded EB delete
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
│   └── EbDiscountSyncPayloadMapper.java   ← Coupon+Promotion → EbDiscountSyncPayload value object
├── event/listener/
│   ├── BookingConfirmedListener.java
│   ├── BookingCancelledListener.java
│   └── PaymentFailedListener.java
└── exception/
    ├── CouponNotFoundException.java
    ├── CouponExpiredException.java
    ├── CouponUsageLimitReachedException.java
    └── EbDiscountSyncException.java
```

---

> Does this look complete? Anything to add, remove, or change before I write Stage 3 in full?

User: Next stage

GitHub Copilot: Here is the **Stage 4 — DB Schema outline**. Review and confirm before I write the full content.

---

## Stage 4 Outline — DB Schema

### Tables

| Table | Key Fields |
|---|---|
| `promotions` | `id`, `org_id`, `name`, `discount_type`, `discount_value`, `scope`, `eb_event_id` (nullable), `max_usage_limit`, `per_user_cap`, `valid_from`, `valid_until`, `status`, `created_at`, `updated_at` |
| `coupons` | `id`, `promotion_id` (FK), `code` (unique per org), `org_id`, `status`, `redemption_count`, `eb_discount_id` (nullable), `eb_sync_status`, `eb_quantity_sold_at_last_sync`, `last_eb_sync_at`, `created_at`, `updated_at` |
| `coupon_redemptions` | `id`, `coupon_id` (FK), `user_id`, `booking_id`, `cart_id`, `discount_amount`, `currency`, `voided`, `voided_at`, `redeemed_at` |
| `coupon_usage_reservations` | `id`, `coupon_id` (FK), `cart_id`, `user_id`, `reserved_at`, `expires_at`, `released` |
| `orphan_eb_discounts` | `id`, `eb_discount_id`, `org_id`, `code`, `detected_at`, `reviewed`, `notes` |
| `discount_reconciliation_logs` | `id`, `org_id`, `run_at`, `discounts_checked`, `drifts_found`, `orphans_found`, `externally_deleted_found`, `actions_taken_summary` |

### Key Design Notes (outline only)
- `coupons.code` + `coupons.org_id` → partial unique index (no two active coupons with same code per org)
- `coupon_usage_reservations` → one active (non-released) reservation per `(coupon_id, cart_id)`
- `coupon_redemptions` → one non-voided redemption per `(coupon_id, user_id, booking_id)`
- `promotions.eb_event_id` nullable — null means org-wide scope, populated means event-scoped
- `coupons.eb_quantity_sold_at_last_sync` — snapshot used to decide if EB DELETE is safe (only delete if `0`)

---

> Does this look complete? Anything to add, remove, or change before I write Stage 4 in full?

User: next stage 

GitHub Copilot: Here is the **Stage 5 — API outline**. Review and confirm before I write the full content.

---

## Stage 5 Outline — API Endpoints

### Admin — Promotion Management
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/promotions` | Create a new promotion (admin only) |
| `GET` | `/api/v1/promotions` | List all promotions for the org (paginated, admin only) |
| `GET` | `/api/v1/promotions/{promotionId}` | Get single promotion detail |
| `PATCH` | `/api/v1/promotions/{promotionId}` | Update promotion validity/limits — triggers EB delete+recreate sync |
| `DELETE` | `/api/v1/promotions/{promotionId}` | Deactivate promotion + all linked coupons (guarded EB delete) |

### Admin — Coupon Management
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/promotions/{promotionId}/coupons` | Create a coupon code under a promotion — triggers async EB sync |
| `GET` | `/api/v1/promotions/{promotionId}/coupons` | List all coupons for a promotion |
| `GET` | `/api/v1/promotions/coupons/{couponCode}/usage` | Get redemption count and reservation stats for a coupon |
| `DELETE` | `/api/v1/promotions/coupons/{couponCode}` | Deactivate coupon (guarded EB delete) |

### Admin — EB Sync Management
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/promotions/coupons/{couponCode}/sync` | Manually trigger EB sync (delete+recreate) for a coupon |
| `GET` | `/api/v1/promotions/reconciliation/latest` | Get latest reconciliation job log for the org |
| `POST` | `/api/v1/promotions/reconciliation/trigger` | Manually trigger a reconciliation run for the org |

### User — Coupon Apply
| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/promotions/validate` | Validate coupon code against a cart — returns discount breakdown (JWT `ROLE_USER`) |
| `DELETE` | `/api/v1/promotions/cart/{cartId}/coupon` | Remove applied coupon from cart — releases usage reservation (JWT `ROLE_USER`) |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 5 in full?

User: next stage 

GitHub Copilot: Here is the **Stage 6 — Business Logic outline**. Review and confirm before I write the full content.

---

## Stage 6 Outline — Business Logic Rules & State Transitions

### BL-1 — Promotion & Coupon Lifecycle
- Promotion status transitions: `ACTIVE → INACTIVE` (admin deactivate) | `ACTIVE → EXHAUSTED` (all linked coupons exhausted)
- Coupon status transitions: `ACTIVE → INACTIVE` (admin deactivate) | `ACTIVE → EXHAUSTED` (redemption_count >= maxUsageLimit)
- Deactivating a promotion cascades `INACTIVE` to all linked coupons

### BL-2 — EB Outbound Sync Rules (the critical path)
- **Create:** internal save first → async `EbDiscountSyncService.createDiscount()` → store `eb_discount_id` → verify with GET
- **Modify (no PUT/PATCH):** check `eb_quantity_sold_at_last_sync == 0` → if safe: DELETE old `eb_discount_id` then POST new → store new `eb_discount_id` | if not safe: set `CANNOT_RESYNC`, alert ops, internal edit still persists
- **Delete/Deactivate:** fetch current EB `quantity_sold` via `GET /discounts/{eb_discount_id}/` → if `0`: call DELETE | if `> 0`: set `DELETE_BLOCKED`, log, continue
- **Idempotency guard:** before any EB create, scan `GET /organizations/{org_id}/discounts/` for existing code match — if found, adopt existing `eb_discount_id` instead of creating duplicate
- **Retry rule:** `SYNC_FAILED` coupons are retried by `CouponExpiryJob` on next scheduled run (not immediate re-throw)

### BL-3 — EB Inbound Sync Rules (reconciliation)
- For each EB discount returned by list: find internal coupon by `eb_discount_id`
  - Match found, `quantity_sold` drifted up → update internal `redemption_count` to EB value (EB wins on usage count)
  - Match found, fields drifted (code/value changed externally) → set `DRIFT_DETECTED`, alert ops, do NOT auto-overwrite internal
  - No internal match → create `OrphanEbDiscount` audit record, alert ops
  - Internal coupon marked `SYNCED` but EB returns 404 → set `EB_DELETED_EXTERNALLY`, alert ops

### BL-4 — Coupon Eligibility Rules (ordered checks)
1. Coupon exists and `status = ACTIVE`
2. `NOW()` is within `[valid_from, valid_until]`
3. Cart's `orgId` matches `coupon.orgId`
4. If `scope = EVENT_SCOPED`: cart's `slotId` maps to `promotion.ebEventId`
5. `redemption_count + active_reservations_count < maxUsageLimit`
6. `user's prior non-voided redemptions for this coupon < perUserCap`
7. No existing active `CouponUsageReservation` for same `(couponId, cartId)` → idempotent if same cart re-validates

### BL-5 — Soft Hold (Usage Reservation)
- On successful validation: create `CouponUsageReservation(couponId, cartId, userId, expiresAt = cart.expiresAt)`
- Reservation counts toward usage limit during eligibility check
- Released by: `PaymentFailedEvent`, `BookingCancelledEvent` (voided redemption path), or `expiresAt` exceeded (cleaned by `CouponExpiryJob`)
- On `BookingConfirmedEvent`: reservation is converted → `CouponRedemption` created, reservation marked `released = true`

### BL-6 — Discount Calculation
- `PERCENT_OFF`: `discountAmount = floor(cartTotal * percentOff / 100)` in smallest currency unit
- `AMOUNT_OFF`: `discountAmount = min(amountOff, cartTotal)` — never produce negative totals
- `adjustedTotal = cartTotal - discountAmount` (floor to 0 minimum)
- Discount computed in `CouponEligibilityService` and returned as `DiscountCalculationResult` value object — never stored until redemption is confirmed

### BL-7 — Post-Redemption Lifecycle
- `BookingConfirmedEvent` received → look up active reservation by `cartId` → create `CouponRedemption` → increment `redemption_count` → release reservation → if `redemption_count >= maxUsageLimit`: mark coupon `EXHAUSTED` → trigger guarded EB delete
- `BookingCancelledEvent` received → find non-voided `CouponRedemption` by `bookingId` → set `voided = true`, `voided_at = NOW()` → decrement `redemption_count` → if coupon was `EXHAUSTED`: revert to `ACTIVE` (un-exhaust) → if coupon had `DELETE_BLOCKED` or was EB-deleted: no EB re-create (log only)

### BL-8 — Scheduled Jobs
- `DiscountReconciliationJob`: runs every 4 hours; iterates all orgs with `SYNCED` coupons; calls EB list; diffs; writes `DiscountReconciliationLog`
- `CouponExpiryJob`: runs every 1 hour; finds coupons past `valid_until` with `status = ACTIVE`; sets `INACTIVE`; triggers guarded EB delete; releases all open reservations for expired coupons

---

> Does this look complete? Anything to add, remove, or change before I write Stage 6 in full?

User: next stage 

GitHub Copilot: Here is the **Stage 7 — Error Handling outline**. Review and confirm before I write the full content.

---

## Stage 7 Outline — Failure Scenarios

### Coupon Validation / User-Facing
| Scenario | Expected Behaviour |
|---|---|
| Coupon code does not exist | `404 COUPON_NOT_FOUND` |
| Coupon exists but `status = INACTIVE` or `EXHAUSTED` | `410 COUPON_INACTIVE` |
| Outside validity window (`before valid_from` or `after valid_until`) | `422 COUPON_EXPIRED` or `422 COUPON_NOT_YET_VALID` |
| Org mismatch (coupon belongs to different org than cart's org) | `403 COUPON_ORG_MISMATCH` |
| Event-scoped coupon applied to wrong slot/event | `422 COUPON_EVENT_MISMATCH` |
| Global usage limit reached (`redemption_count + reservations >= maxUsageLimit`) | `409 COUPON_USAGE_LIMIT_REACHED` |
| Per-user cap reached for this user | `409 COUPON_PER_USER_CAP_REACHED` |
| Same coupon already applied to same cart (idempotent re-apply) | `200 OK` with existing `DiscountBreakdownResponse` |
| Cart not found or expired when validating | `404 CART_NOT_FOUND` / `422 CART_EXPIRED` |

### EB Outbound Sync Failures (non-blocking, internal proceeds regardless)
| Scenario | Expected Behaviour |
|---|---|
| EB create call returns non-2xx | Set `eb_sync_status = SYNC_FAILED`; log error; coupon still active internally; eligible for retry |
| EB verify GET after create returns 404 (race) | Set `eb_sync_status = SYNC_FAILED`; schedule retry |
| EB delete fails on deactivate/modify (non-409) | Log error; set `eb_sync_status = SYNC_FAILED`; do not block internal deactivation |
| EB delete rejected with `DISCOUNT_CANNOT_BE_DELETED` (quantity_sold > 0) | Set `eb_sync_status = DELETE_BLOCKED`; log ops alert; internal deactivation proceeds |
| Modify attempted but EB `quantity_sold > 0` (cannot safely delete+recreate) | Set `eb_sync_status = CANNOT_RESYNC`; persist internal change only; alert ops |
| Duplicate code detected on EB during idempotency check | Adopt existing `eb_discount_id`; set `SYNCED`; log warning |
| EB token not available / `EbAuthException` | Set `eb_sync_status = SYNC_FAILED`; log; retry deferred to next scheduled job run |

### EB Inbound Sync / Reconciliation Failures
| Scenario | Expected Behaviour |
|---|---|
| EB list call fails entirely for an org | Log error; skip that org in this run; write `DiscountReconciliationLog` with error summary; do not abort other orgs |
| EB discount found with no internal match (orphan) | Create `OrphanEbDiscount` audit record; alert ops; do NOT auto-import |
| Internal `SYNCED` coupon missing from EB list (externally deleted) | Set `eb_sync_status = EB_DELETED_EXTERNALLY`; alert ops; coupon still usable internally |
| EB discount fields drifted from internal (code or value changed on EB side) | Set `eb_sync_status = DRIFT_DETECTED`; alert ops; do NOT auto-overwrite internal record |

### Admin Operations
| Scenario | Expected Behaviour |
|---|---|
| Duplicate coupon code within same org | `409 COUPON_CODE_CONFLICT` |
| Deactivating a promotion that has active reservations in-flight | Cascade deactivate coupons; release all open `CouponUsageReservation` rows; guarded EB delete |
| Deleting a promotion with non-voided redemptions | `409 PROMOTION_HAS_ACTIVE_REDEMPTIONS` — must cancel bookings first |
| Modify promotion when `eb_sync_status = CANNOT_RESYNC` or `DELETE_BLOCKED` | Internal update persists; EB sync skipped; response includes `ebSyncStatus` warning field |

### Scheduled Job Failures
| Scenario | Expected Behaviour |
|---|---|
| `CouponExpiryJob` fails mid-run for one coupon | Log and skip; continue processing remaining coupons; write partial run log |
| `DiscountReconciliationJob` takes longer than next scheduled trigger | Skip next trigger (single-instance lock via `@SchedulerLock` or Shedlock) |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 7 in full?

User: next stage

GitHub Copilot: Here is the **Stage 8 — Tests outline**. Review and confirm before I write the full content.

---

## Stage 8 Outline — Test Cases

### Layer 1 — Domain Unit Tests (`domain/`)

| # | Test Title |
|---|---|
| D1 | `Promotion_should_cascade_inactive_status_to_all_linked_coupons_on_deactivate` |
| D2 | `Coupon_should_transition_to_EXHAUSTED_when_redemption_count_reaches_max_usage_limit` |
| D3 | `Coupon_should_revert_from_EXHAUSTED_to_ACTIVE_when_redemption_is_voided` |
| D4 | `DiscountCalculationResult_should_compute_percent_off_correctly_in_smallest_unit` |
| D5 | `DiscountCalculationResult_should_floor_amount_off_to_zero_when_discount_exceeds_cart_total` |
| D6 | `CouponUsageReservation_should_count_toward_usage_limit_during_eligibility_window` |

### Layer 2 — Service Tests (`service/`, Mockito)

**CouponEligibilityService**
| # | Test Title |
|---|---|
| S1 | `validate_should_return_discount_breakdown_for_valid_active_coupon` |
| S2 | `validate_should_throw_CouponExpiredException_when_outside_validity_window` |
| S3 | `validate_should_throw_CouponInactiveException_when_status_is_EXHAUSTED` |
| S4 | `validate_should_throw_CouponOrgMismatchException_when_coupon_org_differs_from_cart_org` |
| S5 | `validate_should_throw_CouponEventMismatchException_for_event_scoped_coupon_on_wrong_slot` |
| S6 | `validate_should_throw_CouponUsageLimitReachedException_when_count_plus_reservations_equals_max` |
| S7 | `validate_should_throw_CouponPerUserCapReachedException_when_user_has_reached_per_user_cap` |
| S8 | `validate_should_return_idempotent_response_when_same_coupon_already_applied_to_same_cart` |
| S9 | `validate_should_create_CouponUsageReservation_on_successful_validation` |

**CouponRedemptionService**
| # | Test Title |
|---|---|
| S10 | `onBookingConfirmed_should_create_redemption_record_and_increment_count` |
| S11 | `onBookingConfirmed_should_mark_coupon_EXHAUSTED_when_count_reaches_max_usage_limit` |
| S12 | `onBookingCancelled_should_void_redemption_and_decrement_count` |
| S13 | `onBookingCancelled_should_revert_coupon_from_EXHAUSTED_to_ACTIVE_when_redemption_voided` |
| S14 | `onPaymentFailed_should_release_open_usage_reservation_for_cart` |

**DiscountSyncOrchestrator**
| # | Test Title |
|---|---|
| S15 | `createSync_should_call_EbDiscountSyncService_and_store_eb_discount_id_on_success` |
| S16 | `createSync_should_set_SYNC_FAILED_when_EB_create_returns_error_and_not_block_internal_creation` |
| S17 | `modifySync_should_delete_then_recreate_on_EB_when_quantity_sold_is_zero` |
| S18 | `modifySync_should_set_CANNOT_RESYNC_and_skip_EB_when_quantity_sold_is_greater_than_zero` |
| S19 | `deleteSync_should_call_EB_delete_when_quantity_sold_is_zero` |
| S20 | `deleteSync_should_set_DELETE_BLOCKED_and_not_call_EB_delete_when_quantity_sold_greater_than_zero` |
| S21 | `idempotencyGuard_should_adopt_existing_eb_discount_id_when_code_already_exists_on_EB` |
| S22 | `createSync_should_skip_EB_call_and_log_warning_when_eb_event_id_is_null_on_event_scoped_coupon` |

**DiscountReconciliationJob**
| # | Test Title |
|---|---|
| S23 | `reconciliation_should_update_internal_redemption_count_when_EB_quantity_sold_is_higher` |
| S24 | `reconciliation_should_create_OrphanEbDiscount_record_when_no_internal_match_found` |
| S25 | `reconciliation_should_set_EB_DELETED_EXTERNALLY_when_synced_coupon_missing_from_EB_list` |
| S26 | `reconciliation_should_set_DRIFT_DETECTED_when_EB_fields_differ_from_internal_record` |
| S27 | `reconciliation_should_skip_org_and_log_on_EB_list_call_failure_without_aborting_other_orgs` |

**CouponExpiryJob**
| # | Test Title |
|---|---|
| S28 | `expiryJob_should_deactivate_expired_coupons_and_release_open_reservations` |
| S29 | `expiryJob_should_trigger_guarded_EB_delete_for_expired_coupons_with_zero_quantity_sold` |
| S30 | `expiryJob_should_set_DELETE_BLOCKED_and_continue_when_EB_delete_rejected_for_expired_coupon` |

### Layer 3 — API Tests (`api/`, @WebMvcTest)

| # | Test Title |
|---|---|
| A1 | `POST_promotions_should_return_201_with_promotion_id_for_admin` |
| A2 | `POST_promotions_should_return_403_when_called_by_ROLE_USER` |
| A3 | `POST_promotions_coupons_should_return_201_and_trigger_async_eb_sync` |
| A4 | `POST_promotions_coupons_should_return_409_on_duplicate_code_within_org` |
| A5 | `POST_promotions_validate_should_return_200_with_discount_breakdown_for_valid_coupon` |
| A6 | `POST_promotions_validate_should_return_410_when_coupon_is_INACTIVE` |
| A7 | `POST_promotions_validate_should_return_409_when_usage_limit_reached` |
| A8 | `DELETE_promotions_cart_coupon_should_return_204_and_release_reservation` |
| A9 | `POST_promotions_coupons_sync_should_return_200_with_updated_eb_sync_status` |
| A10 | `POST_promotions_reconciliation_trigger_should_return_202_and_run_async_job` |

### Layer 4 — ArchUnit Tests (`arch/`)

| # | Test Title |
|---|---|
| AR1 | `promotions_module_should_not_import_any_other_module_service_or_repository` |
| AR2 | `promotions_module_should_only_call_eventbrite_via_EbDiscountSyncService_facade` |
| AR3 | `promotions_event_listeners_should_only_accept_shared_event_types` |
| AR4 | `CouponAppliedEvent_and_CouponValidatedEvent_should_reside_in_shared_module_only` |

---

> Does this look complete? Anything to add, remove, or change before I write Stage 8 in full?

User: Write all the 8 stages in full into SPEC_7.md file

GitHub Copilot: Created 1 todo

Read [](file:///home/dell/Multi-City-Event-Booking/POQ)

Created [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md)

Completed: *Write full SPEC_7.md all 8 stages* (1/1)

SPEC_7.md written in full. Here's what's included:

**Stage 1 — Requirements** — 6 areas (A–F): admin promotion/coupon CRUD, EB outbound sync with the no-PUT/PATCH delete+recreate rule, EB inbound reconciliation, user eligibility pipeline, post-redemption lifecycle, and all edge cases.

**Stage 2 — Domain Model** — 6 entities (`Promotion`, `Coupon`, `CouponRedemption`, `CouponUsageReservation`, `OrphanEbDiscount`, `DiscountReconciliationLog`), all enums, 2 non-persisted value objects (`DiscountCalculationResult`, `EbDiscountSyncPayload`), cross-module ID reference table.

**Stage 3 — Architecture & File Structure** — full package tree, 2 shared event records, 1 booking-inventory listener addition, complete `promotions` module layout.

**Stage 4 — DB Schema** — 6 Flyway-ready tables with all columns, partial unique indexes, and foreign keys including the key guards: `uq_coupons_code_org` (active only), `uq_coupon_reservations_active`, `uq_coupon_redemptions_active`.

**Stage 5 — API** — 14 endpoints across admin promotion management, admin coupon management, admin EB sync management, and user coupon apply.

**Stage 6 — Business Logic** — 6 BL sections: discount calculation formulas in smallest unit, the 7-step eligibility pipeline, full EB sync state machine (create / modify / delete paths), post-redemption lifecycle handlers, and both scheduled jobs.

**Stage 7 — Error Handling** — 4 tables covering user-facing validation errors, admin operation errors, non-blocking EB outbound sync failures, and reconciliation failures — all with HTTP status and error codes.

**Stage 8 — Tests** — 31 service tests (D1–D8, S1–S31), 10 API tests (A1–A10), 4 ArchUnit rules (AR1–AR4), plus 5 open questions at the end.

Made changes.

User: Are there any bugs or gaps that needs to be addressed before i implement the SPEC 7 the issues by flow 1-6 ??

GitHub Copilot: Let me do a thorough audit across all relevant areas before answering.  

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files)

Read [](file:///home/dell/Multi-City-Event-Booking/test_result_folder)

Read [](file:///home/dell/Multi-City-Event-Booking/test_result_folder/fr6_cancellation_20260305_020556.md)

Read [](file:///home/dell/Multi-City-Event-Booking/test_result_folder/payment_confirmation_flow_20260304_234905.md)

Read [](file:///home/dell/Multi-City-Event-Booking/test_result_folder/seat_selection_fr4_20260304_200857.md)

Read [](file:///home/dell/Multi-City-Event-Booking/test_result_folder/fr3_user_onboarding_20260304_131915.md)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/CartAssembledEvent.java)

Searched for files matching `**/shared/src/main/java/com/eventplatform/shared/common/event/published/*.java`, 12 matches

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/BookingConfirmedEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/BookingCancelledEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/PaymentFailedEvent.java)

Searched for files matching `**/shared/src/main/java/com/eventplatform/shared/common/service/CartSnapshotReader.java`, 1 match

Searched for files matching `**/shared/src/main/java/com/eventplatform/shared/common/dto/CartSnapshot*.java`, no matches

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/service/CartSnapshotReader.java)

Searched for files matching `**/shared/src/main/java/com/eventplatform/shared/common/dto/*.java`, 5 matches

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/dto/CartItemSnapshotDto.java)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/dto/SlotSummaryDto.java)

Searched for files matching `**/booking-inventory/src/main/java/com/eventplatform/bookinginventory/**/*SnapshotReader*.java`, 1 match

Searched for files matching `**/booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Cart.java`, 1 match

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartSnapshotReaderImpl.java)

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Cart.java#1-1), lines 1 to 80

Searched for text `CartAssembledEvent` (`**/payments-ticketing/src/main/java/**/*.java`), 4 results

Searched for text `totalAmountInSmallestUnit` (`**/booking-inventory/src/main/java/**/*.java`), 2 results

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java#255-255), lines 255 to 300

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/service/PaymentService.java#85-85), lines 85 to 115

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/service/PaymentService.java#115-115), lines 115 to 165

Searched for text `bookingId` (`**/payments-ticketing/src/main/java/**/*.java`), 20 results

Searched for text `BookingConfirmedEvent` (`**/payments-ticketing/src/main/java/**/*.java`), 2 results

Searched for text `promotions` (`**/pom.xml`), no results

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/service/PaymentService.java#235-235), lines 235 to 265

Searched for text `promotions` (`**/pom.xml`), no results

Searched for text `module` (`**/pom.xml`), 18 results

Searched for text `discountAmount` (`**/booking-inventory/src/main/java/**/*.java`), 12 results

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartPricingService.java#70-70), lines 70 to 150

Now I have a complete picture. Let me check one more critical area — the existing `shopping-cart` coupon fields and the DB migrations:  

Searched for text `coupon` (`**/booking-inventory/src/main/resources/db/migration/*.sql`), no results

Searched for text `CouponAppliedEvent\|CouponValidatedEvent` (`**/shared/src/main/java/**/*.java`), no results

Searched for files matching `**/booking-inventory/src/main/resources/db/migration/*.sql`, no matches

Searched for text `coupon_code` (`**/booking-inventory/src/main/resources/**/*.sql`), no results

Summarized conversation history

Searched for text `coupon_code` (`**/booking-inventory/src/main/resources/**/*.sql`), no results

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/booking-inventory/src/main/resources -name "*.sql" | sort

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/booking-inventory/src -name "*.sql" | sort && find /home/dell/Multi-City-Event-Booking/booking-inventory/src -name "*.xml" | sort

Ran terminal command:  find /home/dell/Multi-City-Event-Booking -name "*.sql" | sort; echo "---"; find /home/dell/Multi-City-Event-Booking -name "migration" -type d | sort

Read [](file:///home/dell/.config/Code/User/workspaceStorage/d0c32092166abb554a6d3bb3dc965310/GitHub.copilot-chat/chat-session-resources/cd21c934-7e4e-47df-a295-766d67c33823/toolu_bdrk_01C2HeDtaZHR5vjRC3RbPwjY__vscode-1772654368737/content.txt)

Good — all migrations live in migration. Let me check V21 (carts) for coupon fields and also read the Cart entity to confirm the full field state. 

Ran terminal command:  cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/V21__create_carts.sql

The DB confirms V21 has a single `discount_amount` column — no separate coupon discount column. The audit is complete. Here is the full pre-FR7 gap report:

---

## Pre-FR7 Gap Report — Flows 1–6

6 confirmed gaps. All must be fixed before implementing SPEC_7.

---

### GAP-1 — `BookingConfirmedEvent` missing `bookingId` **(CRITICAL)**

| | |
|---|---|
| **File** | BookingConfirmedEvent.java |
| **Current** | `record BookingConfirmedEvent(Long cartId, List<Long> seatIds, String stripePaymentIntentId, Long userId)` |
| **Problem** | `promotions.BookingConfirmedListener` must create a `CouponRedemption` row keyed by `bookingId`. Without it, there is no stable FK to reference. |
| **Fix** | Add `Long bookingId` as first field. Update payments-ticketing/.../service/PaymentService.java publish call to pass `booking.getId()`. |

---

### GAP-2 — `CartSnapshotReader` returns items only, no cart header **(CRITICAL)**

| | |
|---|---|
| **Files** | shared/.../service/CartSnapshotReader.java, shared/.../dto/CartItemSnapshotDto.java |
| **Problem** | `CouponEligibilityService.validate()` needs `orgId`, `slotId`, `couponCode`, `expiresAt`, `currency` from the cart header. Currently only line-item data is exposed. |
| **Fix** | 1. Create `CartSummaryDto(Long cartId, Long orgId, Long slotId, String couponCode, Instant expiresAt, String currency)` in `shared/common/dto/`. 2. Add `CartSummaryDto getCartSummary(Long cartId)` to `CartSnapshotReader` interface. 3. Implement in booking-inventory's `CartSnapshotReaderImpl`. |

---

### GAP-3 — `CartService.confirm()` does not subtract coupon discount from Stripe amount **(CRITICAL)**

| | |
|---|---|
| **File** | booking-inventory/.../service/CartService.java |
| **Problem** | `totalAmountInSmallestUnit = pricing.total().amount() × 100`. `pricing.total()` = `subtotal − groupDiscount` only. Coupon discount is never deducted → Stripe PaymentIntent receives the full pre-coupon amount. User is over-charged. |
| **Fix** | After `recompute()`, subtract `cart.getCouponDiscountAmount()` before building `CartAssembledEvent`: `total -= cart.couponDiscountAmountInSmallestUnit()`. |

---

### GAP-4 — `CartPricingService.recompute()` overwrites coupon discount **(CRITICAL)**

| | |
|---|---|
| **Files** | booking-inventory/.../service/CartPricingService.java, booking-inventory/.../domain/Cart.java, V21__create_carts.sql |
| **Problem** | `recompute()` calls `cart.setDiscountAmount(groupDiscount)`. This single field stores group discount now and will be expected to store coupon discount in FR7 — they clobber each other. DB has one `discount_amount` column. |
| **Fix** | 1. New migration `V36__split_cart_discount_amounts.sql`: rename `discount_amount` → `group_discount_amount`; add `coupon_discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00`. 2. Update Cart.java: rename field, add `couponDiscountAmount`. 3. `recompute()` only writes `groupDiscountAmount` — never touches `couponDiscountAmount`. 4. `CartAssembledEvent` total = `subtotal − groupDiscount − couponDiscount`. |

---

### GAP-5 — `promotions` module not scaffolded **(STRUCTURAL)**

| | |
|---|---|
| **Files** | pom.xml |
| **Problem** | `<module>promotions</module>` missing from root pom.xml. Directory does not exist. Maven build will not include the module. |
| **Fix** | Create pom.xml with full module structure per SPEC_7 Stage 3. Add `<module>promotions</module>` to root pom.xml. |

---

### GAP-6 — `CouponAppliedEvent` and `CouponValidatedEvent` missing from shared **(STRUCTURAL)**

| | |
|---|---|
| **Directory** | published |
| **Problem** | Both events are required for the `promotions` → booking-inventory async communication contract defined in SPEC_7. Neither file exists. |
| **Fix** | Create: `CouponAppliedEvent(Long cartId, String couponCode, long discountAmountInSmallestUnit, String currency, Long userId)` and `CouponValidatedEvent(Long cartId, String couponCode, long discountAmountInSmallestUnit, String currency, Long userId)`. |

---

## Recommended Fix Order

```
GAP-1  →  GAP-6  →  GAP-2  →  GAP-4  →  GAP-3  →  GAP-5
shared     shared     shared     Cart       CartSvc    scaffold
(zero      events    interface  entity +   total      promotions
 risk)                          migration  compute    module
```

GAP-1 and GAP-6 are pure shared additions — zero risk to existing tests. GAP-4 must land before GAP-3 because `CartService` needs the split field to exist. GAP-5 (scaffold) can be done any time before writing the first `promotions` class.

Do you want me to apply all 6 fixes now?

User: yes apply all the six fixes then document the gaps and fixes in detailed with proper explanation

GitHub Copilot: Created 7 todos

Let me read all files needed simultaneously before making any edits.   

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/BookingConfirmedEvent.java)

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/service/CartSnapshotReader.java)

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published -type f | sort

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/service/PaymentService.java#220-220), lines 220 to 300

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java#245-245), lines 245 to 320

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartPricingService.java#60-60), lines 60 to 150

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Cart.java#1-1), lines 1 to 120

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/booking-inventory/src -name "CartSnapshotReaderImpl.java" | xargs cat 2>/dev/null; echo "---"; find /home/dell/Multi-City-Event-Booking/shared/src -name "CartItemSnapshotDto.java" | xargs cat 2>/dev/null

Read [](file:///home/dell/Multi-City-Event-Booking/pom.xml#1-1), lines 1 to 35

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Cart.java#120-120), lines 120 to 155

Read [](file:///home/dell/Multi-City-Event-Booking/pom.xml#36-36), lines 36 to 56

Ran terminal command:  cat /home/dell/Multi-City-Event-Booking/scheduling/pom.xml

Starting: *GAP-1: Add bookingId to BookingConfirmedEvent + PaymentService* (1/7)

**Starting GAP-1 + GAP-6 simultaneously** (pure shared/ changes): 

Replacing 13 lines with 19 lines in [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/BookingConfirmedEvent.java)


```

```
Now update PaymentService to pass `booking.getId()` and simultaneously create both coupon events: 

Replacing 8 lines with 9 lines in [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/service/PaymentService.java)


```

```
Now check all consumers of `BookingConfirmedEvent` to update their call sites: 

Ran terminal command:  grep -rn "BookingConfirmedEvent" /home/dell/Multi-City-Event-Booking --include="*.java" | grep -v "\.class"

Ran terminal command:  grep -n "BookingConfirmedEvent" /home/dell/Multi-City-Event-Booking/payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/PaymentServiceTest.java | head -20

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/event/listener/BookingConfirmedListener.java)

Read [](file:///home/dell/Multi-City-Event-Booking/payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/PaymentServiceTest.java#215-215), lines 215 to 250

Good — the test only captures via `ArgumentCaptor`, no constructor call to fix. Now create GAP-6 events and do GAP-2 in parallel: 

Starting: *GAP-6: Create CouponAppliedEvent & CouponValidatedEvent* (2/7)

Created [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/CouponAppliedEvent.java)

Created [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/event/published/CouponValidatedEvent.java)

Completed: *GAP-6: Create CouponAppliedEvent & CouponValidatedEvent* (2/7)

Now GAP-2 — create `CartSummaryDto`, extend `CartSnapshotReader` interface, and implement in `CartSnapshotReaderImpl`: 

Created [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/dto/CartSummaryDto.java)

Replacing 8 lines with 29 lines in [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/common/service/CartSnapshotReader.java)


```

```
Now check what CartRepository looks like for the query to use in `getCartSummary`:  

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/booking-inventory/src -name "CartRepository.java" | xargs cat

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/shared/src -name "ResourceNotFoundException.java" | xargs cat 2>/dev/null || find /home/dell/Multi-City-Event-Booking/shared/src -name "*NotFoundException*" | head -5

Replacing 34 lines with 62 lines in [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartSnapshotReaderImpl.java)


```

```


Starting: *GAP-4: V36 migration + Cart entity split discount fields* (4/7)

Check all usages of `discountAmount` / `getDiscountAmount` before renaming:   

Ran terminal command:  grep -rn "discountAmount\|getDiscountAmount\|setDiscountAmount\|discount_amount" /home/dell/Multi-City-Event-Booking/booking-inventory/src --include="*.java" --include="*.xml" --include="*.sql"

Ran terminal command:  grep -rn "discountAmount\|getDiscountAmount\|setDiscountAmount\|discount_amount" /home/dell/Multi-City-Event-Booking/payments-ticketing/src /home/dell/Multi-City-Event-Booking/shared/src --include="*.java" 2>/dev/null

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/booking-inventory/src -name "CartResponse.java" | xargs cat; echo "---"; find /home/dell/Multi-City-Event-Booking/booking-inventory/src -name "CartMapper.java" | xargs cat

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/test/java/com/eventplatform/bookinginventory/api/BookingInventoryControllerTest.java#175-175), lines 175 to 210

Ran terminal command:  grep -n "cartResponse()" /home/dell/Multi-City-Event-Booking/booking-inventory/src/test/java/com/eventplatform/bookinginventory/api/BookingInventoryControllerTest.java

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/test/java/com/eventplatform/bookinginventory/api/BookingInventoryControllerTest.java#292-292), lines 292 to 315

Now executing all GAP-4 changes simultaneously — V36 migration, Cart entity field split, CartPricingService, CartSnapshotReaderImpl, CartResponse, CartMapper, and the test:  

Using "Create File"

Continue to iterate?
[object Object]

Made changes.

User: @agent Continue: "Continue to iterate?"

GitHub Copilot: Now update Cart.java — rename the embedded field and add the coupon discount column: 

Replacing 15 lines with 37 lines in [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/domain/Cart.java)


```

```
Now update all callers of the renamed Cart methods and the API response DTO:  

Replacing 46 lines with 49 lines in [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartPricingService.java), [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartSnapshotReaderImpl.java), [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/api/dto/response/CartResponse.java), [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/mapper/CartMapper.java), [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/test/java/com/eventplatform/bookinginventory/api/BookingInventoryControllerTest.java)


```

```

```

```

```

```

```

```

```

```


Starting: *GAP-3: Fix CartService total amount coupon deduction* (5/7)

**GAP-3** — fix `CartService.confirm()` to deduct coupon discount from the Stripe amount:  

Replacing 5 lines with 13 lines in [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/main/java/com/eventplatform/bookinginventory/service/CartService.java)


```

```


Starting: *GAP-5: Scaffold promotions module + add to root pom.xml* (6/7)

**GAP-5** — scaffold `promotions` module:  

Created [](file:///home/dell/Multi-City-Event-Booking/promotions/pom.xml)

Now create all the canonical package stubs and the module root class:   

Created [](file:///home/dell/Multi-City-Event-Booking/promotions/src/main/java/com/eventplatform/promotions/package-info.java)

Ran terminal command:  BASE="/home/dell/Multi-City-Event-Booking/promotions/src/main/java/com/eventplatform/promotions"
for pkg in "api/controller" "api/dto/request" "api/dto/response" "domain" "domain/enums" "event/listener" "event/published" "exception" "mapper" "repository" "service" "statemachine" "scheduler"; do
  dir="$BASE/$pkg"
  mkdir -p "$dir"
  # Only create package-info.java if not already present
  pname=$(echo "$pkg" | tr '/' '.')
  cat > "$dir/package-info.java" <<EOF
/** Promotions module — ${pkg} layer. */
package com.eventplatform.promotions.${pname};
EOF
done
echo "Done"

Ran terminal command:  BASE="/home/dell/Multi-City-Event-Booking/promotions/src/main/java/com/eventplatform/promotions"
mkdir -p "$BASE/api/controller" "$BASE/api/dto/request" "$BASE/api/dto/response" "$BASE/domain/enums" "$BASE/event/listener" "$BASE/event/published" "$BASE/exception" "$BASE/mapper" "$BASE/repository" "$BASE/service" "$BASE/statemachine" "$BASE/scheduler"
TBASE="/home/dell/Multi-City-Event-Booking/promotions/src/test/java/com/eventplatform/promotions"
mkdir -p "$TBASE/domain" "$TBASE/service" "$TBASE/api" "$TBASE/arch"
echo "scaffold done"
find /home/dell/Multi-City-Event-Booking/promotions/src -type d | sort

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/promotions/src -type d | sort

All directories created. Now add promotions to root pom.xml and add a resources placeholder:   

Replacing 10 lines with 11 lines in [](file:///home/dell/Multi-City-Event-Booking/pom.xml)


```

```
Check whether pom.xml needs promotions declared as a dependency:   

Ran terminal command:  cat /home/dell/Multi-City-Event-Booking/app/pom.xml

Replacing 10 lines with 15 lines in [](file:///home/dell/Multi-City-Event-Booking/app/pom.xml)


```

```
Now verify the build compiles cleanly for affected modules:   

Ran terminal command:  mvn compile -pl shared,booking-inventory,payments-ticketing,promotions -am -q 2>&1 | tail -30

Ran terminal command:  mvn compile -pl shared,booking-inventory,payments-ticketing,promotions -am 2>&1 | grep -E "BUILD|ERROR|error:" | tail -20

Clean compile. Now run the test suites for all affected modules:    

Ran terminal command:  mvn test -pl shared,booking-inventory,payments-ticketing -am 2>&1 | grep -E "Tests run|BUILD|ERROR|FAIL" | tail -30

`CartServiceTest.java` also constructs `CartResponse` directly — let me read and fix those three call sites:   

Read [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/test/java/com/eventplatform/bookinginventory/service/CartServiceTest.java#200-200), lines 200 to 300

Fix all three `CartResponse` constructor calls in CartServiceTest.java — add the new `couponDiscountAmount` argument:   

Replacing 6 lines with 6 lines in [](file:///home/dell/Multi-City-Event-Booking/booking-inventory/src/test/java/com/eventplatform/bookinginventory/service/CartServiceTest.java)


```

```
Re-run the tests:    

Ran terminal command:  mvn test -pl shared,booking-inventory,payments-ticketing -am 2>&1 | grep -E "Tests run|BUILD|FAIL|ERROR" | tail -30

All green — **booking-inventory 80/80, payments-ticketing 67/67**. Now write the documentation:    

Starting: *Document all gaps and fixes in BUG_FIXES file* (7/7)

Created [](file:///home/dell/Multi-City-Event-Booking/docs/Files/PRE_FR7_GAP_FIXES.md)

Completed: *Document all gaps and fixes in BUG_FIXES file* (7/7)

All 6 gaps fixed, all tests green. Here's a summary of what was done:

---

## ✅ All 6 Pre-FR7 Gaps Fixed

**Tests after fixes:** booking-inventory 80/80 ✅ · payments-ticketing 67/67 ✅

---

### GAP-1 — `BookingConfirmedEvent` missing `bookingId`
Added `Long bookingId` as the first field. Updated PaymentService.java to pass `booking.getId()`. `promotions.BookingConfirmedListener` can now FK-reference the confirmed booking when recording `CouponRedemption`.

---

### GAP-6 — Missing `CouponAppliedEvent` and `CouponValidatedEvent` in shared
Created both records in `shared/…/event/published/`. Both carry `(cartId, couponCode, discountAmountInSmallestUnit, currency, userId)` — Rule 4 compliant (primitives/IDs/strings only). `CouponValidatedEvent` fires pre-mutation; `CouponAppliedEvent` fires after the discount is committed.

---

### GAP-2 — `CartSnapshotReader` had no cart-header method
Created CartSummaryDto.java in shared (`cartId`, `orgId`, `slotId`, `couponCode`, `expiresAt`, `currency`). Added `getCartSummary(Long cartId)` to CartSnapshotReader.java. Implemented in CartSnapshotReaderImpl.java using `CartRepository` (throws `ResourceNotFoundException` on miss).

---

### GAP-4 — Single `discount_amount` column clobbered by `recompute()`
Created V36__split_cart_discount_amounts.sql: renames `discount_amount` → `group_discount_amount`, adds `coupon_discount_amount DECIMAL(12,2) DEFAULT 0.00`. Split Cart.java into `groupDiscountAmount` (embedded `Money`) + `couponDiscountAmount` (raw `BigDecimal`). `CartPricingService.recompute()` now only writes `groupDiscountAmount` — never touches `couponDiscountAmount`. `CartResponse` DTO and `CartMapper` updated; 5 test call-sites fixed.

---

### GAP-3 — Stripe amount never deducted coupon discount (user over-charged)
Fixed CartService.java `confirm()`: `netTotal = pricing.total() − cart.getCouponDiscountAmount()`, floored at zero with `.max(BigDecimal.ZERO)` guard, then multiplied ×100 for paise. Stripe now receives the correct post-coupon amount.

---

### GAP-5 — promotions module didn't exist
Created pom.xml with full dependency set (shared, Spring Boot Web/JPA/Validation/Security/Quartz, MapStruct, ArchUnit). Scaffolded all canonical directories: `api/controller`, `api/dto/request`, `api/dto/response`, `domain/enums`, `event/listener`, `event/published`, `exception`, `mapper`, `repository`, `service`, `statemachine`, `scheduler`, plus test counterparts. Added `<module>promotions</module>` to root pom.xml and promotions dependency to pom.xml.

---

Full detailed explanation with before/after code for every change is in PRE_FR7_GAP_FIXES.md.

Made changes.

User: Update the SPEC_7 file with Bug fixes nad the migration numbers as well, Update the file with the new changes

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md#1-1), lines 1 to 100

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md#100-100), lines 100 to 250

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md#250-250), lines 250 to 600

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md#600-600), lines 600 to 850

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md#850-850), lines 850 to 1100

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md#1100-1100), lines 1100 to 1271

Now I have the full picture. I need to update: the header dependencies section, Stage 3 shared additions (mark events as created), Stage 3 booking-inventory additions, Stage 4 migration numbering (V36 is taken → V37), and BL-4/Area E `BookingConfirmedEvent` signatures. Let me apply all changes: 

Replacing 66 lines with 157 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md)


```

```
Let me verify the migration number and key sections look correct: 

Ran terminal command:  grep -n "V37\|V36\|V{next}\|Pre-FR7 Migration\|FR7 Flyway\|bookingId\|getCartSummary\|CouponAppliedEvent.*Created\|CouponValidatedEvent.*Created\|GAP-1\|GAP-2\|GAP-3\|GAP-4\|GAP-5\|GAP-6" /home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md | head -60

All changes landed correctly. Let me do a final check to confirm no stale `V{next}` placeholder remains: 

Ran terminal command:  grep -n "V{next}" /home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md
Completed with input:  grep -n "V{next}" /home/dell/Multi-City-Event-Booking/POQ/Coupon/SPEC_7.md

No stale placeholder remains. SPEC_7.md is fully updated with the following changes:

---

### Changes made to SPEC_7.md

**Header (top of file)**
- Added a prominent **Pre-FR7 Gap Fixes Applied** callout table with all 6 gaps, one-line descriptions, and `✅ Applied` status
- Updated the `Depends on` block: `BookingConfirmedEvent` now shows the correct 5-field signature with `bookingId` first; `CartSnapshotReader` dependency now references `getCartSummary()`

**Stage 1 — Area E**
- `BookingConfirmedEvent(cartId, ...)` → `BookingConfirmedEvent(bookingId, cartId, ...)` with inline GAP-1 note

**Stage 3 — Shared additions**
- All 4 shared files marked `✅ Created` or `✅ Updated` with gap references: `CouponAppliedEvent`, `CouponValidatedEvent`, `CartSummaryDto`, `CartSnapshotReader`, `BookingConfirmedEvent`
- Includes the updated `BookingConfirmedEvent` record with `bookingId` field shown inline

**Stage 3 — booking-inventory changes**
- Full section rewritten: pre-applied fixes (Cart entity split, `CartPricingService`, `CartService.confirm()`, `CartSnapshotReaderImpl`, `CartResponse`) are documented as `✅ applied`
- `CouponAppliedListener` clearly marked as **NEW (FR7)** — still to be implemented

**Stage 4 — DB Schema**
- `V{next}__create_promotions_tables.sql` → **`V37__create_promotions_tables.sql`**
- Added "Pre-FR7 Migration Already Applied" note explaining V36 is taken, with chain: V35 → V36 → V37

**BL-4 — Post-Redemption Lifecycle**
- `BookingConfirmedEvent` handler updated: uses `event.bookingId()` directly, reads cart snapshot via `CartSnapshotReader.getCartSummary()`

**Stage 8 — Test S10**
- Updated to show `BookingConfirmedEvent(bookingId=91, cartId=500, ...)` and assert `bookingId=91` on the created redemption

**Open Questions**
- Question 3 (stacking) answered with implementation note (GAP-3/GAP-4 resolution)
- Two new questions added: Q6 (`CouponAppliedListener` correct mutation pattern — load→set→save, no recompute) and Q7 (`CartSummaryDto.currency` source confirmed)

Made changes.

User: There is a promotion file that was never there before how did it come there ?

GitHub Copilot: Ran terminal command:  git log --oneline -10 && echo "---" && git show HEAD --stat | head -20

The promotions directory was created **by me** (GitHub Copilot) in the previous turn of this conversation as **GAP-5** of the pre-FR7 gap fixes.

Here is exactly what happened:

1. **You asked:** *"yes apply all the six fixes"*
2. I identified GAP-5 — the promotions module was missing from the codebase entirely (not in pom.xml, no directory on disk)
3. I created the scaffold:
   - pom.xml — full Maven module definition
   - package-info.java
   - All canonical subdirectories: `api/controller`, `api/dto/request`, `api/dto/response`, `domain/enums`, `event/listener`, `event/published`, `exception`, `mapper`, `repository`, `service`, `statemachine`, `scheduler`
   - Test counterparts: `src/test/…/domain`, `service`, `api`, `arch`
4. Added `<module>promotions</module>` to the root pom.xml
5. Added promotions as a dependency in pom.xml

The terminal also shows `git stash pop` was run — that means the changes were briefly stashed (likely when you switched branches or ran something that required a clean working tree) and then restored. The files were always part of this session's work.

The directory is intentionally scaffolded and empty — it's ready for FR7 implementation. The pom.xml and the package structure are all that's needed until you actually start writing the domain entities and services for SPEC_7.

User: there are few errors one is the xml dependecy and other is the app 

GitHub Copilot: Summarized conversation historyLet me check both pom.xml files for errors.  

Read [](file:///home/dell/Multi-City-Event-Booking/pom.xml)

Checked [](file:///home/dell/Multi-City-Event-Booking/app/pom.xml), [](file:///home/dell/Multi-City-Event-Booking/pom.xml), 1 problem found

The promotions dependency is declared but the module hasn't been installed to the local Maven repo yet. Let me check the promotions pom and then build it. 

Read [](file:///home/dell/Multi-City-Event-Booking/promotions/pom.xml#1-1), lines 1 to 80

The promotions module needs to be compiled and installed. Let me also check if it has the necessary Spring Boot entry point or config class that would allow it to compile. 

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/promotions/src/main/java -name "*.java" | head -20

Only `package-info.java` stubs exist. The module needs to be compiled and installed. Let me run install: 

Ran terminal command:  mvn install -pl promotions -am -DskipTests -q 2>&1 | tail -20

Clean build. Now let me verify the error is resolved:  

Checked [](file:///home/dell/Multi-City-Event-Booking/app/pom.xml), 1 problem found

The JAR was installed but the IDE hasn't refreshed its index. Let me confirm the artifact is physically present and then force a Maven project reload: 

Ran terminal command:  ls ~/.m2/repository/com/eventplatform/promotions/0.0.1-SNAPSHOT/*.jar 2>/dev/null && echo "JAR exists" || echo "JAR missing"

The JAR is installed. The IDE just needs its Maven project refreshed: 

Running command `Update Maven Project Configuration`

Checked [](file:///home/dell/Multi-City-Event-Booking/app/pom.xml), no problems found

Both errors resolved:

- **Root pom.xml** — no errors (was already clean)
- **pom.xml** — `Missing artifact com.eventplatform:promotions:jar:0.0.1-SNAPSHOT` → fixed

**Root cause:** The promotions module was scaffolded (directory + pom created) but had never been compiled and installed to the local Maven repository (`~/.m2`). The app module declared it as a dependency but Maven couldn't resolve it.

**Fix:** `mvn install -pl promotions -am -DskipTests` built the module and placed `promotions-0.0.1-SNAPSHOT.jar` into `~/.m2`, then a project configuration refresh cleared the IDE error.

User: next commit the bug fixes and changes skip the MD files into the current branch with proper prefix

GitHub Copilot: Ran terminal command:  git branch --show-current && git status --short

Ran terminal command:  find promotions POQ -name "*.md" -o -name "*.MD" 2>/dev/null

Good. Now stage everything except all `.md`/`.MD` files and commit: 

Ran terminal command:  git add -A -- ':!**/*.md' ':!**/*.MD' && git status --short

All the right files are staged. Coupon (SPEC_7.md) is correctly left out. Now commit:  

Ran terminal command:  cd /home/dell/Multi-City-Event-Booking && git commit -m "fix(pre-fr7): resolve 6 blocking gaps before coupon/promotions implementation

GAP-1: add bookingId to BookingConfirmedEvent; update PaymentService publish call
GAP-2: add CartSummaryDto + getCartSummary() to CartSnapshotReader/Impl
GAP-3: subtract couponDiscountAmount from Stripe charge total in CartService.confirm()
GAP-4: split cart discount_amount into group_discount_amount + coupon_discount_amount
       - V36 migration, Cart entity, CartPricingService, CartResponse, CartMapper
GAP-5: scaffold promotions module (pom.xml, canonical package dirs, root/app pom wired)
GAP-6: create CouponAppliedEvent + CouponValidatedEvent in shared cross-module events

Tests: booking-inventory 80/80 PASS, payments-ticketing 67/67 PASS"

Committed to `feature/coupon-promotions` as `d0accee` — 32 files, 382 insertions.

**What was included:**
- All 6 GAP fixes (Java source, V36 migration, promotions scaffold)
- Modified test files (BookingInventoryControllerTest, CartServiceTest)
- Root & app pom.xml wiring

**What was excluded:**
- SPEC_7.md and all other `*.md` / `*.MD` files

User: but accoeding to file structure and architecture arent promotions ans coupons a single module reason for it to be a saperate module ?? answer with explanation concise

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#1-1), lines 1 to 80

Yes, **they are one module** — and that's exactly how it's already set up.

`PRODUCT.md §1` defines the promotions module as owning:
> *"Coupons, Promotions, Eligibility rules, EB discount sync"*

Coupons are a sub-domain **inside** promotions, not a separate module. The reasoning:

1. **Tight cohesion** — a `Coupon` only exists within a `Promotion`. Their lifecycle, eligibility rules, redemption tracking, and EB sync are all one bounded context.
2. **No cross-module writes** — no other module creates or mutates coupons. Communication is one-directional: promotions publishes `CouponAppliedEvent`, booking-inventory listens.
3. **Dependency order** fits one slot — promotions sits between payments-ticketing and `engagement` in the dependency graph. Splitting it into two modules would require one to depend on the other, creating a same-level coupling violation (Hard Rule #10).

The module is called promotions because the **aggregate root** is `Promotion` — a coupon is just the redemption mechanism of a promotion. This follows the same pattern as payments-ticketing owning both payments *and* tickets/refunds.

**Nothing needs to change** — the scaffold is correctly named promotions, and coupons live inside it at `promotions/domain/Coupon.java`.