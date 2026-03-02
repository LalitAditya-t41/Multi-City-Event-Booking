# Eventbrite Integration Strategy

**Owner:** Lalit  
**Domain:** EntertainmentTech  
**Last Updated:** March 2026  
**Status:** Partially Implemented — See Constraints Section

---

## Overview

Eventbrite serves as the **ticketing and payment backbone** for the multi-city event booking platform. The architecture follows an **internal-first approach**: your database is the source of truth, and Eventbrite is pushed to after internal validation.

**Core Principle:** Eventbrite does not create orders or users. It only provides ticketing infrastructure, payment processing through its checkout widget, and attendee records post-purchase.

---

## 1. Eventbrite ACL Facades (`shared/eventbrite/service/`)

### Available Facades

| Facade | Responsibilities | Status |
|---|---|---|
| **EbEventSyncService** | Create, update, publish, unpublish, cancel, delete events; pull org event catalog; verify sync integrity | ✅ Designed |
| **EbVenueService** | Create and update venues on Eventbrite; list venues by org | ✅ Designed |
| **EbScheduleService** | Create recurring event schedules and occurrences | ✅ Designed |
| **EbTicketService** | Create/update ticket classes; manage inventory tiers; check availability | ✅ Designed |
| **EbCapacityService** | Retrieve and update event capacity tiers; manage seat maps | ✅ Designed |
| **EbOrderService** | Retrieve order details post-checkout; list orders by event/org | ✅ Designed |
| **EbAttendeeService** | Retrieve attendee records; list attendees by event or org | ✅ Designed |
| **EbDiscountSyncService** | Create, update, delete discount codes on Eventbrite; sync with internal promotions | ✅ Designed |
| **EbRefundService** | Read refund request status on orders; filter orders by refund status | ✅ Designed |
| **EbWebhookService** | Register and manage org-scoped webhooks for event/order state changes | ✅ Designed |

**Hard Rule:** No module ever calls Eventbrite directly. All API calls go through these facades in `shared/`.

---

## 2. The Spine: `eventbrite_event_id`

The `eventbrite_event_id` is the bridge between your internal DB and Eventbrite. Every downstream flow depends on it:

- **FR2 (Scheduling)** — Create show slot → Store `eventbrite_event_id`
- **FR4 (Seats)** — Validate availability against Eventbrite `ticket_classes` for that `event_id`
- **FR5 (Payment)** — Eventbrite Checkout Widget needs valid `event_id` to render
- **FR6 (Cancellation)** — Refund APIs reference `event_id` + `order_id`
- **FR7 (Discounts)** — Discount codes scoped to `event_id`
- **FR8 (Reviews)** — Attendance verification uses `event_id` + attendee email

**Without FR2 sync:** If `eventbrite_event_id` is null or orphaned, the entire chain breaks.

---

## 3. Integration Flows — Planned vs. Constraints

### FR1: City Selection → Venue Discovery → Event Listing

**What Was Supposed to Happen:**
```
User selects city 
  ↓
App queries internal venues by city_id
  ↓
GET /organizations/{org_id}/venues/ [Eventbrite]
  ↓
Merge internal event_catalog with Eventbrite org events
  ↓
User sees unified event list
```

**Eventbrite Calls:**
- `GET /organizations/{org_id}/venues/` — list org venues for cross-reference
- `GET /organizations/{org_id}/events/` — pull org's published events
- `GET /events/{event_id}/` — fetch individual event details for catalog refresh

**Constraints:** ⚠️ Public event search (`GET /events/search/`) is **deprecated** on Eventbrite. Event discovery is limited to your org's events only. No city-wide public search available.

**Status:** ✅ Designed; ⚠️ Limited to org events

---

### FR2: Show Scheduling → Time Slot Configuration → Conflict Validation

**What Was Supposed to Happen:**

#### Phase 1: Venue Registration
```
Admin registers venue in your app
  ↓
POST /organizations/{org_id}/venues/ [Eventbrite]
  ↓
Store eventbrite_venue_id in internal DB
```

#### Phase 2: Show Slot Creation (Core Sync)
```
Admin creates show slot with venue + date + time
  ↓
Internal validation (duplicate check, turnaround gaps)
  ↓
POST /organizations/{org_id}/events/ [Eventbrite]
  ↓
Eventbrite returns event_id
  ↓
Store eventbrite_event_id in show_slots table
  ↓
POST /events/{event_id}/ticket_classes/ [Eventbrite] — create ticket tiers
  ↓
POST /events/{event_id}/publish/ [Eventbrite] — make event live
```

#### Phase 3: Recurring Shows
```
Admin creates recurring series (e.g., every Saturday)
  ↓
Internal DB generates individual show_slot records
  ↓
POST /events/{event_id}/schedules/ [Eventbrite] — create recurring occurrences
```

#### Phase 4: Status Sync
```
Admin updates/deletes slot
  ↓
POST /events/{event_id}/ [Eventbrite] — sync changes
  OR
POST /events/{event_id}/cancel/ [Eventbrite] — cancel event
```

#### Phase 5: Conflict Validation (Background Job)
```
Periodic job queries internal show_slots
  ↓
GET /events/{event_id}/ [Eventbrite] — verify eventbrite_event_id still exists
  ↓
Flags orphaned records for admin review
```

**Eventbrite Calls:**
- `POST /organizations/{org_id}/venues/` — create venue
- `GET /organizations/{org_id}/venues/` — list venues for conflict check
- `POST /organizations/{org_id}/events/` — create event
- `POST /events/{event_id}/ticket_classes/` — create ticket tiers
- `POST /events/{event_id}/publish/` — publish event
- `POST /events/{event_id}/schedules/` — create recurring occurrences
- `POST /events/{event_id}/` — update event details
- `POST /events/{event_id}/cancel/` — cancel event
- `GET /events/{event_id}/` — verify event exists

**Constraints:** ⚠️ **Turnaround policy enforcement** — Eventbrite has no conflict validation API. Your app must enforce time gaps between shows internally. Conflict validation is 100% internal DB logic.

**Status:** ✅ Designed; ⚠️ Turnaround gaps are internal logic only

---

### FR3: User Registration → Profile Setup → Preference Onboarding

**What Was Supposed to Happen:**
```
User registers (email + password + name)
  ↓
Internal users table created
  ↓
[No Eventbrite call — no user creation API exists]
  ↓
User fills profile (phone, city, avatar)
  ↓
[No Eventbrite sync at this stage]
  ↓
User selects preferences (genres, cities, price ranges)
  ↓
Preferences stored in users_preferences table
  ↓
[Post-purchase: user linked to Eventbrite via order email]
```

**Eventbrite Calls:**
- No Eventbrite call for this flow we will use the atendee sync that happens in later flows

**Constraints:** ⚠️ **No user creation API on Eventbrite.** User identity is 100% internal. The link between your app user and Eventbrite is established post-purchase via the order email. No user profile sync is possible.

**Status:** ✅ Designed; ⚠️ Zero Eventbrite integration at registration time

---

### FR4: Seat Selection → Pricing Tier Validation → Cart Assembly

**What Was Supposed to Happen:**
```
User picks seats from internal SeatMap
  ↓
GET /events/{event_id}/ticket_classes/for_sale/ [Eventbrite]
  ↓
Validate: ticket count still > 0?
  ↓
Apply internal pricing tiers (VIP, Premium, General)
  ↓
POST /pricing/calculate_price_for_item/ [Eventbrite] — validate pricing
  ↓
Soft lock seat via Redis (TTL = cart session)
  ↓
REST call to promotions module — validate coupon eligibility
  ↓
CartAssembledEvent published
```

**Eventbrite Calls:**
- `GET /events/{event_id}/ticket_classes/for_sale/` — remaining ticket count
- `GET /events/{event_id}/inventory_tiers/{id}/` — verify tier capacity
- `POST /pricing/calculate_price_for_item/` — validate price against Eventbrite fees
- `GET /organizations/{org_id}/ticket_groups/` — check ticket bundles

**Constraints:** ⚠️ **No seat lock API on Eventbrite.** The entire state machine (AVAILABLE → SOFT_LOCKED → HARD_LOCKED → CONFIRMED) is internal using Redis + Spring State Machine. Eventbrite is consulted only once — for availability count.

**Status:** ✅ Designed; ⚠️ Seat locking is 100% internal

---

### FR5: Payment Gateway → Booking Confirmation → E-Ticket Generation

**What Was Supposed to Happen:**

#### Step 1: Checkout Widget Embed
```
CartAssembledEvent received by payments-ticketing
  ↓
Frontend receives event_id
  ↓
Render Eventbrite Checkout Widget (JS SDK: eb_widgets.js)
  ↓
[No direct REST call — JS SDK handles it]
```

#### Step 2: User Pays via Widget
```
User enters payment information in Eventbrite widget
  ↓
Eventbrite processes payment (NOT Razorpay for this flow)
  ↓
Widget fires onOrderComplete callback
  ↓
Frontend sends order_id to backend
```

#### Step 3: Booking Confirmation
```
Backend receives order_id from frontend
  ↓
GET /orders/{order_id}/ [Eventbrite]
  ↓
Read full order details (costs, buyer info, status)
  ↓
BookingConfirmedEvent published
  ↓
engagement module unlocks review eligibility
  ↓
identity module updates order history
```

#### Step 4: E-Ticket Generation
```
ETicket record created in e_tickets table
  ↓
GET /orders/{order_id}/?expand=attendees [Eventbrite]
  ↓
Get attendee barcodes and seat assignments
  ↓
Generate QR code and send confirmation email
```

**Eventbrite Calls:**
- `window.EBWidgets.createWidget()` — embed checkout widget (JS SDK, not REST)
- `GET /orders/{order_id}/` — read order post-checkout
- `GET /organizations/{org_id}/orders/` — verify order belongs to org
- `GET /orders/{order_id}/?expand=attendees` — get attendee barcodes
- `GET /events/{event_id}/attendees/{attendee_id}/` — get seat assignment

**Also: Webhook Path**
```
Eventbrite webhook fires on order completion
  ↓
EbWebhookController receives payload
  ↓
OrderCompletedEvent published internally
  ↓
Parallel to frontend callback — optional async confirmation
```

**Constraints:** ⚠️ **No order creation API.** Orders are created exclusively by the Eventbrite Checkout Widget (JS SDK). Your backend reads the order AFTER the widget callback. This is NOT a webhook—it is a frontend JS callback.

**Status:** ✅ Designed; ⚠️ No programmatic order creation

---

### FR6: Cancellation Request → Refund Policy Engine → Wallet Credit

**What Was Supposed to Happen:**

#### Step 1: User Requests Cancellation
```
User submits cancellation request in your app
  ↓
Internal refund_policy calculates refund % (time-based)
  ↓
Check if cancellation permitted
```

#### Step 2: Cancel Order on Eventbrite
```
GET /orders/{order_id}/ [Eventbrite] — read current status
  ↓
GET /events/{event_id}/orders?refund_request_statuses=pending [Eventbrite]
  ↓
Check if refund already in flight
  ↓
[Requested API call that doesn't exist should be here]
  ↓
Poll order until status changes to cancelled
```

#### Step 3: Refund Status Tracking
```
BookingCancelledEvent published
  ↓
booking-inventory module releases seats
  ↓
GET /events/{event_id}/orders?refund_request_statuses=completed [Eventbrite]
  ↓
Verify refund processed
```

#### Step 4: Wallet Credit
```
Approved refund amount credited to user wallet
  ↓
wallet_transactions table updated
```

#### Bulk Cancellation (Entire Show Slot)
```
ShowSlotCancelledEvent received
  ↓
All associated bookings cancelled
  ↓
POST /events/{event_id}/cancel/ [Eventbrite] — cancel entire event
  ↓
All refunds triggered on Eventbrite side
```

**Eventbrite Calls:**
- `GET /orders/{order_id}/` — read order status
- `GET /events/{event_id}/orders?refund_request_statuses=pending` — check pending refunds
- `GET /events/{event_id}/orders?refund_request_statuses=completed` — verify refunds processed
- `POST /events/{event_id}/cancel/` — cancel entire event (bulk refund)

**Constraints:** ⚠️ **No single-order cancel or programmatic refund API.** There is no `POST /orders/{order_id}/cancel/` or `POST /orders/{order_id}/refund/` endpoint. Only `POST /events/{event_id}/cancel/` exists, which cancels the ENTIRE event. Per-order cancellation is achieved by acting as the org admin using your org OAuth token to mimic the cancellation action on Eventbrite's side — but there is no standard API for this. Refund status is read via order fields; actual refund initiation is handled by mimicking admin actions via org token.

**Status:** ⚠️ Partially Implemented; **Blocked:** No single-order cancel API. Workaround required.

---

### FR7: Offer & Coupon Engine → Eligibility Check → Discount Application

**What Was Supposed to Happen:**

#### Step 1: Admin Creates Promotion
```
Admin creates promotion in your app
  ↓
POST /organizations/{org_id}/discounts/ [Eventbrite]
  ↓
Eventbrite returns discount_id
  ↓
Store discount_id in coupons table
```

#### Step 2: User Applies Coupon
```
User enters coupon code in app UI
  ↓
Internal validation against coupons table
  ↓
GET /discounts/{discount_id}/ [Eventbrite] — read discount rules
  ↓
Validate: usage count < usage_limit?
  ↓
Coupon passed to Eventbrite Checkout Widget as promoCode parameter
```

#### Step 3: Discount Applied at Checkout
```
User proceeds through Eventbrite Checkout Widget
  ↓
window.EBWidgets.createWidget({ promoCode: 'DISCOUNT_CODE' })
  ↓
Widget applies discount automatically
  ↓
Order placed with discount factored in
```

#### Step 4: Post-Redemption Sync
```
GET /orders/{order_id}/ [Eventbrite] — verify discount applied in costs
  ↓
coupon_redemptions table updated
  ↓
If usage limit reached: DELETE /discounts/{discount_id}/ [Eventbrite]
  ↓
Deactivate discount on both platforms
```

**Eventbrite Calls:**
- `POST /organizations/{org_id}/discounts/` — create discount code
- `GET /organizations/{org_id}/discounts/` — list org discounts
- `GET /discounts/{discount_id}/` — read discount rules and usage
- `POST /discounts/{discount_id}/` — update usage limit
- `DELETE /discounts/{discount_id}/` — remove if exhausted
- `window.EBWidgets.createWidget()` — pass `promoCode` parameter to widget

**Constraints:** ✅ No major constraints.

**Status:** ✅ Designed; Full Eventbrite integration planned

---

### FR8: Review & Rating Submission → Moderation Queue → Public Publish

**What Was Supposed to Happen:**

#### Step 1: Verify Attendance
```
User attempts to submit review
  ↓
GET /events/{event_id}/attendees/ [Eventbrite]
  ↓
Find attendee by email
  ↓
GET /events/{event_id}/attendees/{attendee_id}/ [Eventbrite]
  ↓
Check: cancelled=false AND refunded=false?
  ↓
If yes: attendance verified ✅
```

#### Step 2: Review Submission
```
If attendance verified: Review record created
  ↓
Status: SUBMITTED
  ↓
Added to moderation queue
```

#### Step 3: Moderation
```
Moderation engine runs:
  - Profanity filter
  - Spam detection (OpenAI moderation API)
  ↓
If APPROVED: status → APPROVED
  ↓
If REJECTED: status → REJECTED (terminal)
```

**Review States:** `SUBMITTED → PENDING_MODERATION → APPROVED → PUBLISHED / REJECTED`

**Eventbrite Calls:**
- `GET /events/{event_id}/attendees/` — list attendees
- `GET /events/{event_id}/attendees/{attendee_id}/` — check attendance status

**Constraints:** ✅ No constraints. No review write API exists on Eventbrite — reviews are 100% internal.

**Status:** ✅ Designed; Eventbrite read-only for attendance verification

---

### FR9: Admin Dashboard → Event CRUD → Seat Inventory Management

**What Was Supposed to Happen:**

#### Event CRUD
```
Admin creates/edits/deletes events in dashboard
  ↓
Internal event record updated
  ↓
POST /organizations/{org_id}/events/ [Eventbrite] — create
  ↓
OR
POST /events/{event_id}/ [Eventbrite] — update
  ↓
OR
POST /events/{event_id}/cancel/ [Eventbrite] — cancel (triggers bulk refund)
```

#### Seat Inventory Management
```
Admin manages ticket tiers, capacities, seat maps
  ↓
Internal seat_maps and pricing_tiers updated
  ↓
POST /events/{event_id}/ticket_classes/ [Eventbrite]
  ↓
POST /events/{event_id}/capacity_tier/ [Eventbrite]
```

#### Venue & Media Management
```
Admin uploads event images, banners
  ↓
POST /media/upload/ [Eventbrite]
  ↓
Admin updates venue details
  ↓
PUT /venues/{venue_id}/ [Eventbrite]
```

#### Event Description & Content
```
Admin writes rich HTML description
  ↓
POST /events/{event_id}/structured_content/{version}/ [Eventbrite]
```

#### Reporting & Analytics
```
Admin views sales and attendance reports
  ↓
GET /reports/sales/ [Eventbrite]
  ↓
GET /events/{event_id}/attendees/ [Eventbrite]
  ↓
Merge with internal analytics
```

**Eventbrite Calls:**
- `POST /organizations/{org_id}/events/` — create event
- `POST /events/{event_id}/` — update event
- `POST /events/{event_id}/cancel/` — cancel event
- `POST /events/{event_id}/ticket_classes/` — create ticket tier
- `PUT /events/{event_id}/ticket_classes/{class_id}/` — update tier
- `POST /events/{event_id}/capacity_tier/` — update capacity
- `POST /media/upload/` — upload images
- `PUT /venues/{venue_id}/` — update venue
- `POST /events/{event_id}/structured_content/{version}/` — set description
- `GET /reports/sales/` — sales report
- `GET /events/{event_id}/attendees/` — attendee list

**Constraints:** ✅ No major constraints.

**Status:** ✅ Designed; Full admin CRUD planned

---

### FR10: Seat Lock State Machine with Conflict Resolution

**What Was Supposed to Happen:**

#### State Transitions
```
AVAILABLE
  ↓ [SELECT] + AvailabilityGuard
  ↓ (check internal DB + Eventbrite ticket_classes count)
  ↓
SOFT_LOCKED  (held for cart session TTL via Redis)
  ↓ [CONFIRM] + CartTtlGuard
  ↓ (user proceeds to checkout)
  ↓
HARD_LOCKED  (held during Eventbrite checkout)
  ↓ [PAY]
  ↓
PAYMENT_PENDING
  ↓ [CONFIRM_PAYMENT] (onOrderComplete callback received)
  ↓
CONFIRMED  (eventbrite_order_id stored in DB)

OR any state → [RELEASE] → AVAILABLE
```

**Eventbrite Checks:**
- At AVAILABLE → SOFT_LOCKED transition:
  - `GET /events/{event_id}/ticket_classes/for_sale/` — remaining count > 0?
  - `GET /events/{event_id}/inventory_tiers/{id}/` — tier capacity available?

**Conflict Resolution:**
```
If Redis lock fails (seat already taken):
  ↓
ConflictResolution triggered
  ↓
User informed: "Seat is now unavailable"
  ↓
Seat returned to AVAILABLE state for next user
```

**Rollback (Payment Failure):**
```
User's Eventbrite checkout fails
  ↓
onOrderComplete callback NOT fired
  ↓
CartTtlGuard releases seat lock 
  ↓
Seat → AVAILABLE for next user
```

**Constraints:** ⚠️ **No seat lock API on Eventbrite.** The entire state machine is internal. Seats are locked via Redis, conflict resolution is internal, and rollback is triggered by timer expiration. Eventbrite is consulted only for availability validation.

**Status:** ✅ Designed; ⚠️ Locking is 100% internal

---

## 4. What Eventbrite Cannot Do — Implemented Internally

| Gap | Handled Internally |
|---|---|
| User creation/sync | No user write API. Identity is 100% internal. Users linked post-purchase via order email. |
| Conflict validation | No conflict API. Time slot conflicts and turnaround gaps enforced in internal DB. |
| Seat locking | No seat lock API. State machine (Redis + Spring State Machine) is internal. |
| Cart management | No cart API. Cart assembly and group discount rules are internal. |
| Order creation | No order creation API. Orders created exclusively by Eventbrite Checkout Widget. |
| Single order cancel | No per-order cancel API. Only full event cancel exists. Per-order cancellation not achievable. |
| Refund submission | No programmatic refund API. Refunds triggered by mimicking admin actions. |
| Reviews | No reviews API. Entire system is internal. Eventbrite only verifies attendance. |
| Public event search | Deprecated API. Event discovery limited to org events only. |

---

## 5. Critical Integration Constraints Summary

### ✅ Fully Achievable
- Event creation, update, publish, unpublish, cancel
- Ticket tier creation and management
- Venue creation and management
- User profile linking (post-purchase only)
- Attendance verification for reviews
- Sales reporting and attendee lists
- Discount code creation and tracking
- Recurring event schedules
- Capacity tier management

### ⚠️ Partially Achievable (Workarounds Required)
- **Single-order cancellation** — No API exists. Option: Mimic admin action via org token (not officially supported).
- **Programmatic refunds** — No API exists. Status is read-only. Refunds triggered by mimicking admin actions.
- **Turnaround policy enforcement** — Internal DB logic only. Eventbrite has no conflict check API.

### ❌ Not Achievable
- **Public event search** — Deprecated API. Limited to org events.
- **Direct seat locking** — Must use internal Redis + Spring State Machine.
- **Cart persistence on Eventbrite** — Cart is 100% internal.
- **User creation on Eventbrite** — No write API. Users are internal only.
- **Review storage on Eventbrite** — No reviews API. System is internal.
- **Order creation via API** — Only Checkout Widget can create orders.

---

## 6. Webhook Integration

### Webhook Types Registered

**Event Webhooks:**
- `event.updated` — Admin updates event details
- `event.published` — Event goes live
- `event.unpublished` — Event comes offline
- `event.canceled` — Event cancelled

**Order Webhooks:**
- `order.placed` — Order created (parallel to frontend callback)
- `order.refunded` — Refund processed
- `attendee.checked_in` — Attendee checked in at venue

### Webhook Flow
```
Eventbrite fires webhook event
  ↓
EbWebhookController receives payload
  ↓
EbWebhookDispatcher routes to appropriate listener
  ↓
Internal Spring Event published
  ↓
Modules listen and react asynchronously
```

**Example:**
```
order.placed webhook received
  ↓
OrderCompletedEvent published internally
  ↓
payments-ticketing listens and confirms booking
  ↓
engagement listens and unlocks review eligibility
  ↓
[Happens in parallel to frontend callback]
```

**Note:** Modules NEVER consume raw webhooks directly. All webhooks are translated to internal Spring Events.

---

## 7. Summary Table: Eventbrite Integration Coverage

| FR | Flow | Status | Eventbrite Calls | Constraints |
|---|---|---|---|---|
| FR1 | City → Venue → Events | ✅ Designed | `GET /orgs/events`, `GET /orgs/venues` | Limited to org events (public search deprecated) |
| FR2 | Show Scheduling | ✅ Designed | `POST /events`, `POST /ticket_classes`, `POST /schedules`, `POST /publish` | Turnaround gaps internal-only |
| FR3 | User Registration | ✅ Designed | (none at registration) | Zero Eventbrite integration; post-purchase only |
| FR4 | Seat Selection | ✅ Designed | `GET /ticket_classes`, `GET /inventory_tiers` | No seat lock API; locking is internal |
| FR5 | Payment & Ticketing | ✅ Designed | `GET /orders`, `GET /attendees`, JS SDK checkout widget | No order creation API; widget only |
| FR6 | Cancellation | ⚠️ Partial | `GET /orders`, `POST /events/cancel` | No single-order cancel; no refund API |
| FR7 | Coupons | ✅ Designed | `POST /discounts`, `GET /discounts`, `DELETE /discounts` | (none) |
| FR8 | Reviews | ✅ Designed | `GET /attendees` (verify only) | No reviews API; system is internal |
| FR9 | Admin Dashboard | ✅ Designed | Full CRUD: post/put/delete events, venues, tickets | (none) |
| FR10 | Seat Lock State Machine | ✅ Designed | One-time availability check | Lock/conflict resolution is internal |

---

## 8. Architecture Diagram

```
┌─────────────────────────────────────────┐
│        Your Spring Boot App              │
│  (Internal DB = Source of Truth)         │
└─────────────────────────────────────────┘
           ↓           ↓           ↓
    [Sync]      [Read]      [Write]
      ↓           ↓           ↓
┌──────────────────────────────────────────┐
│     shared/eventbrite/service/           │
│  ACL Facades (Only Integration Point)    │
└──────────────────────────────────────────┘
      ↓                           ↓
  [REST API]               [Webhooks]
      ↓                           ↓
┌──────────────────────────────────────────┐
│         Eventbrite API v3                │
│  Ticketing | Payment | Catalog | Orders  │
└──────────────────────────────────────────┘
```

---

## 9. Recommendations Going Forward

### High Priority (Unblock FR6 Cancellation)
1. **Single-Order Cancel Workaround** — Investigate org-token-based cancellation mimicking admin action. Document the undocumented API endpoint.
2. **Refund Status Polling** — Implement robust polling mechanism to track refund status post-cancellation.

### Medium Priority (Improve Robustness)
1. **Webhook Retry Logic** — Add exponential backoff for failed webhook deliveries.
2. **Orphan Detection Job** — Implement background job to flag and reconcile orphaned `eventbrite_event_id` records.
3. **Rate Limiting** — Add circuit breaker pattern for Eventbrite API calls to handle rate limits.

### Low Priority (Nice-to-Have)
1. **Event Analytics Dashboard** — Pull sales and attendance reports from Eventbrite API.
2. **Multi-Org Support** — Extend ACL facades to support multiple Eventbrite organizations.

---

## 10. References

- Eventbrite API v3 Docs: `docs/eventbriteapiv3public.apib`
- Module ownership: `PRODUCT.md`
- Architecture rules: `docs/MODULE_STRUCTURE_TEMPLATE.md`
- Coding standards: `agents/CODING_AGENT.md`
