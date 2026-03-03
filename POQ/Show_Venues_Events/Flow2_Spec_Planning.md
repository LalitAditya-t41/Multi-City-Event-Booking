User:   Flow 2 — Show Scheduling → Time Slot Configuration → Conflict Validation

  A. Org OAuth Onboarding (one‑time per org)

  1. Org admin authorizes your app via Eventbrite OAuth.
  2. Your backend exchanges code → access_token (+ refresh_token if issued).
  3. Store tokens encrypted per org + organization_id + expires_at.

  ———

  B. Venue Creation & Sync (per org, as needed)

  1. Admin creates venue in your app (name, address, capacity, seating mode, etc.).
  2. Backend calls POST /organizations/{org_id}/venues/ using that org’s token.
  3. Eventbrite returns venue_id.
  4. Store eventbrite_venue_id on internal venue record.

  ———

  C. Create Show Slot (core flow)

  1. Admin fills show slot details:
      - Venue, date, start time, end time
      - Pricing tiers
      - Seating mode (GA vs reserved)
      - Optional recurrence rule
  2. Internal validation first (no EB calls yet):
      - Check overlap against internal show_slots for this venue.
      - Enforce turnaround gap policy.
      - If conflict: return structured alternatives (same venue different time; nearby venue; adjusted time).
  3. If conflict‑free, proceed to sync.

  ———

  D. Eventbrite Sync (draft → publish)

  1. Create Eventbrite event (draft)
     POST /organizations/{org_id}/events/
     → store eventbrite_event_id on show_slots (status = PENDING_SYNC).
  2. Ticket classes
     POST /events/{event_id}/ticket_classes/ (one per pricing tier).
  3. Inventory tiers
     POST /events/{event_id}/inventory_tiers/.
  4. Capacity setup (GA only)
     POST /events/{event_id}/capacity_tier/.
  5. Seat map copy (reserved only)
     POST /events/{event_id}/seatmaps/ with source_seatmap_id.
  6. Publish
     POST /events/{event_id}/publish/.
  7. On success: set internal slot status = ACTIVE.

  ———

  E. Recurring schedules (optional)

  1. If recurrence requested:
     POST /events/{event_id}/schedules/.
  2. Store EB series + occurrences and link to internal slots.

  ———

  F. Error Handling & Retry

  - If any EB call fails:
      - Keep slot in PENDING_SYNC (not ACTIVE).
      - Return safe error to admin.
      - Retry with backoff (rate limit aware).
  - If creation succeeded but publish failed:
      - Keep draft in EB, retry publish.

  ———

  G. Background Reconciliation (periodic)

  - For each show slot:
      - GET /events/{event_id}/ to ensure EB record exists.
  - For each venue:
      - GET /venues/{venue_id}/ to detect external edits.
  - Flag mismatches for admin review.

  Now this is the Flow 2 what are your thoughts on this 

GitHub Copilot: # Flow 2 — Show Scheduling Analysis

Good overall structure. Here are my thoughts mapped against your codebase architecture:

---

## ✅ What's Correct

**Module ownership is clean:**
- discovery-catalog owns Venue creation/sync → uses `EbVenueService`
- `scheduling` owns Show Slots, conflict validation, recurrence → uses `EbEventSyncService`, `EbScheduleService`
- `booking-inventory` owns pricing tiers/ticket classes → uses `EbTicketService`, `EbCapacityService`

**Internal-first validation (Step C.2)** is correct — conflict check hits your DB before any EB call. This is the right pattern.

---

## ⚠️ Issues & Gaps

### 1. Module Boundary Violation Risk
Step D combines slot creation + ticket classes + capacity + seat maps in one flow. These span **two modules**:

| Step | Belongs To |
|---|---|
| D.1 Create EB event | `scheduling` via `EbEventSyncService` |
| D.2 Ticket classes | `booking-inventory` via `EbTicketService` |
| D.3 Inventory tiers | `booking-inventory` via `EbTicketService` |
| D.4 Capacity setup | `booking-inventory` via `EbCapacityService` |
| D.5 Seat map copy | `booking-inventory` via `EbTicketService` |
| D.6 Publish | `scheduling` via `EbEventSyncService` |

**Fix:** `scheduling` publishes a `ShowSlotCreatedEvent` (Spring Event) → `booking-inventory` listener handles D.2–D.5, then fires back a `TicketSyncCompletedEvent` → `scheduling` listener triggers D.6 publish.

---

### 2. OAuth Token Storage (Step A.3)
Per your architecture, this belongs in `shared/eventbrite/` or identity. Clarify:
- Token store → `shared/eventbrite/config/` (org-scoped token resolver)
- identity module stores `organization_id` on the org admin user profile

---

### 3. Venue Sync is discovery-catalog, Not `scheduling`
Step B creates a venue — that's discovery-catalog's domain. `scheduling` only **reads** venue data via REST call to discovery-catalog. Never direct entity import.

---

### 4. `PENDING_SYNC` State Machine
Your `Seat Lock State Machine` is in `booking-inventory`. The **slot status state machine** (`PENDING_SYNC → ACTIVE → CANCELLED`) belongs in:
```
scheduling/statemachine/ShowSlotStateMachine.java
```
This is missing from your flow description — needs to be explicit.

---

### 5. Retry/Backoff (Step F)
No retry infrastructure is mentioned in your current codebase. You'll need:
```
shared/common/retry/EventbriteRetryPolicy.java
```
Using Spring's `@Retryable` or a dedicated retry queue. This is a **shared concern**, not module-specific.

---

### 6. Reconciliation (Step G) — Who Owns It?
This is a background job. Per your rules:
- `admin/` can **read** from modules but writes go via REST
- A `@Scheduled` reconciliation task fits in `scheduling/service/` for slots and `discovery-catalog/service/` for venues
- `admin/` can **aggregate** mismatches for the dashboard view only

---

## 🔄 Corrected Event Flow

```
Admin → POST /api/scheduling/slots
          └─ scheduling: internal conflict check
          └─ scheduling: EbEventSyncService.createDraft()
          └─ scheduling: publish ShowSlotCreatedEvent
                          └─ booking-inventory listener:
                              EbTicketService.createTicketClasses()
                              EbTicketService.createInventoryTiers()
                              EbCapacityService.setupCapacity() [GA only]
                              EbTicketService.copySeatMap() [Reserved only]
                              publish TicketSyncCompletedEvent
                                └─ scheduling listener:
                                    EbEventSyncService.publish()
                                    slot.status = ACTIVE
```

---

## 📋 Recommended Next Step

Run this through **SPEC_GENERATOR.md** — specifically Stage 3 (Architecture & File Structure) and Stage 5 (API) — to lock down the exact file locations before writing any code. The cross-module event choreography in Step D is the highest-risk area and needs the spec to be explicit about event payloads (primitives/IDs only — Hard Rule #4).

User: Can u rewrite the correct flow and also explain me how ill be syncing the user with OAuth will i not loose the user from my app, or iam just using the OAuth of eventbrite for login/signup ?

GitHub Copilot: # Flow 2 — Corrected & OAuth Clarification

---

## First — Critical OAuth Clarification

**You are NOT using Eventbrite OAuth for user login/signup.**

```
Your App Users  ←→  Your own JWT (identity module)
Eventbrite OAuth  ←→  Your ORG ADMIN only (one-time per org, backend-to-backend)
```

These are **two completely separate auth systems.**

| Concern | Auth Used | Who |
|---|---|---|
| User registers/logs in to your app | Your JWT (identity module) | End users |
| Org admin authorizes your app to call EB API | Eventbrite OAuth | Org admin (one-time setup) |
| Your backend calls EB on behalf of org | Stored `access_token` per org | Your backend service |

**You will never lose your users.** Your users never touch Eventbrite OAuth. They don't even know Eventbrite exists.

---

## What Eventbrite OAuth Actually Does Here

```
Org Admin (in your admin panel)
    │
    │  clicks "Connect to Eventbrite"
    ▼
Eventbrite Authorization Page
    │
    │  admin approves → EB returns code to your callback URL
    ▼
Your Backend (admin/ or shared/eventbrite/)
    │
    │  exchanges code → { access_token, organization_id }
    ▼
Stored encrypted in your DB (per org)
    │
    │  All future EB API calls use this stored token
    ▼
EbEventSyncService, EbVenueService, etc. (shared/ facades)
```

**End users are completely outside this loop.**

---

## Corrected Full Flow 2

---

### A. Org OAuth Onboarding *(one-time, admin panel only)*

```
admin/ controller → POST /admin/orgs/{orgId}/eventbrite/connect
    │
    ├─ Redirect admin browser to EB OAuth URL
    ├─ EB returns ?code= to your callback
    ├─ shared/eventbrite/ exchanges code → access_token
    ├─ Store encrypted: { org_id, access_token, eb_organization_id, expires_at }
    └─ This token is now used by ALL facades for this org
```

**Module ownership:** `shared/eventbrite/config/EbTokenStore.java`
**Admin panel:** just initiates redirect + receives callback — zero business logic.

---

### B. Venue Creation & Sync *(discovery-catalog owns this)*

```
Admin → POST /api/discovery-catalog/venues
    │
    ├─ discovery-catalog: persist venue internally (name, address, capacity, seating_mode)
    ├─ discovery-catalog: EbVenueService.createVenue(orgToken, venueData)
    │       └─ POST /organizations/{eb_org_id}/venues/
    │              └─ returns eb_venue_id
    └─ discovery-catalog: store eb_venue_id on internal Venue entity
```

**scheduling module NEVER creates venues — it only reads venue data via REST GET to discovery-catalog.**

---

### C. Create Show Slot *(scheduling owns this)*

```
Admin → POST /api/scheduling/slots
    │
    ├─ scheduling: validate request (venue exists via REST → discovery-catalog)
    ├─ scheduling: INTERNAL conflict check (DB only, zero EB calls)
    │       ├─ Overlap check: same venue, overlapping time window
    │       ├─ Turnaround gap policy enforcement
    │       └─ If conflict → return structured alternatives:
    │               { same_venue_different_time: [...], nearby_venues: [...], adjusted_times: [...] }
    │
    └─ If conflict-free → set slot status = PENDING_SYNC
                        → publish ShowSlotCreatedEvent (carries only: slotId, venueId, orgId, pricingTierIds)
```

---

### D. Eventbrite Sync *(cross-module via Spring Events)*

```
ShowSlotCreatedEvent
    │
    ├─ scheduling listener: EbEventSyncService.createDraft(orgToken, slotDetails)
    │       └─ POST /organizations/{eb_org_id}/events/
    │              └─ returns eb_event_id
    │       └─ store eb_event_id on ShowSlot (status stays PENDING_SYNC)
    │       └─ publish SlotDraftCreatedEvent (carries: slotId, ebEventId, pricingTierIds, seatingMode)
    │
    ├─ booking-inventory listener (on SlotDraftCreatedEvent):
    │       ├─ EbTicketService.createTicketClasses()
    │       │       └─ POST /events/{eb_event_id}/ticket_classes/ (one per pricing tier)
    │       ├─ EbTicketService.createInventoryTiers()
    │       │       └─ POST /events/{eb_event_id}/inventory_tiers/
    │       ├─ [GA only]  EbCapacityService.setupCapacity()
    │       │       └─ POST /events/{eb_event_id}/capacity_tier/
    │       ├─ [Reserved] EbTicketService.copySeatMap()
    │       │       └─ POST /events/{eb_event_id}/seatmaps/ with source_seatmap_id
    │       └─ publish TicketSyncCompletedEvent (carries: slotId, ebEventId)
    │
    └─ scheduling listener (on TicketSyncCompletedEvent):
            ├─ EbEventSyncService.publishEvent()
            │       └─ POST /events/{eb_event_id}/publish/
            └─ slot.status = ACTIVE
```

---

### E. Recurring Schedules *(optional, scheduling owns)*

```
If recurrence rule present on ShowSlot:
    │
    └─ scheduling: EbScheduleService.createSchedule(ebEventId, recurrenceRule)
            └─ POST /events/{eb_event_id}/schedules/
            └─ Store EB series_id + occurrence_ids → link to internal ShowSlot children
```

---

### F. Error Handling & Retry

```
Any EB call fails at D:
    ├─ Slot stays PENDING_SYNC (never set to ACTIVE)
    ├─ Safe error returned to admin (no raw EB error exposed)
    └─ shared/common/retry/EbRetryPolicy.java (@Retryable + backoff, rate-limit aware)

Draft created but publish fails (D.6 only):
    ├─ Slot stays PENDING_SYNC
    ├─ EB draft preserved (don't delete it)
    └─ Retry only POST /events/{eb_event_id}/publish/ — don't recreate event

Partial ticket sync failure:
    ├─ Log which tier failed with eb_event_id
    └─ Admin can trigger manual re-sync via POST /api/scheduling/slots/{id}/retry-sync
```

---

### G. Background Reconciliation *(periodic, @Scheduled)*

```
scheduling/service/SlotReconciliationJob.java (@Scheduled)
    └─ For each ACTIVE slot with eb_event_id:
            GET /events/{eb_event_id}/
            └─ If EB status != LIVE → flag slot for admin review → SlotMismatchEvent

discovery-catalog/service/VenueReconciliationJob.java (@Scheduled)
    └─ For each venue with eb_venue_id:
            GET /venues/{eb_venue_id}/
            └─ If address/capacity differs → flag venue for admin review

admin/ dashboard:
    └─ Reads flagged mismatches via REST GET (no direct DB, no @Entity)
```

---

## ShowSlot State Machine

````java
// States
DRAFT → PENDING_SYNC → ACTIVE → CANCELLED

// Transitions
DRAFT       + SUBMIT          → PENDING_SYNC
PENDING_SYNC + EB_PUBLISHED   → ACTIVE
PENDING_SYNC + EB_FAILED      → PENDING_SYNC  (stays, retry eligible)
ACTIVE       + ADMIN_CANCEL   → CANCELLED
````

---

## Summary — What Your Users Experience

```
User opens your app
    → Registers/logs in via YOUR JWT (identity module)
    → Browses events from discovery-catalog
    → Books seats via booking-inventory + payments-ticketing
    → Receives e-ticket from YOUR system

Behind the scenes (invisible to user):
    → Your backend talks to Eventbrite using org's stored OAuth token
    → User never sees Eventbrite, never authenticates with Eventbrite
    → If Eventbrite is down, your UX degrades gracefully — user data is safe in YOUR DB
```

**Your users are 100% in your system. Eventbrite is a backend sync target, not an auth provider for end users.**

User: The identity Folder is empty why is that can u look into it 

GitHub Copilot: Optimizing tool selection...

Read [](file:///home/dell/Multi-City-Event-Booking/identity)

Read [](file:///home/dell/Multi-City-Event-Booking/identity/src)

Read [](file:///home/dell/Multi-City-Event-Booking/identity/target)

Read [](file:///home/dell/Multi-City-Event-Booking/identity/src/main)

Read [](file:///home/dell/Multi-City-Event-Booking/identity/src/test)

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/identity/src -type f | sort

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/identity/target/classes -type f | sort

Ran terminal command:  cat /home/dell/Multi-City-Event-Booking/identity/pom.xml

Ran terminal command:  git log --oneline --all -- identity/src/ 2>/dev/null | head -20

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/discovery-catalog/src -type f | sort | head -30 && echo "---" && find /home/dell/Multi-City-Event-Booking/shared/src -type f | sort | head -30

Read [](file:///home/dell/.config/Code/User/workspaceStorage/d0c32092166abb554a6d3bb3dc965310/GitHub.copilot-chat/chat-session-resources/0f59d1d5-313f-4d75-a970-51891e9e3a49/toolu_bdrk_01C3SqhUZov6kqnkV8wnKnTV__vscode-1772525234477/content.txt#1-1), lines 1 to 60

The identity module was **never implemented** — it's the scaffold only. Here's the full picture:

| What exists | Status |
|---|---|
| pom.xml | ✅ Configured, depends on shared |
| java | ✅ Directory exists |
| java | ✅ Directory exists |
| Any `.java` source files | ❌ Zero files, never written |
| Git history for src | ❌ Nothing ever committed there |
| `target/classes/` | ❌ Empty — nothing to compile |

Compare that to discovery-catalog which has 30+ fully implemented files (controllers, domain, events, exceptions, etc.) and shared which has all its facades and config.

**This is not a bug — it's simply the next module to build.** The module was scaffolded (folder + pom.xml registered in the parent build) but no code has been written yet.

Per your dependency order from AGENTS.md:
```
shared/ ✅ implemented
discovery-catalog/ ✅ implemented  
identity/ ← you are here (empty, needs implementation)
scheduling/ (not started)
...
```

**What identity needs to own** (per PRODUCT.md):
- Users, JWT, profiles, preferences, wallet
- Uses no EB facades directly (only shared for JWT infra)

If you want to start on it, the right next step is running it through SPEC_GENERATOR.md to produce a `SPEC_identity.md` before writing any code.

User: Stage 1 — Requirements Outline

  Admin OAuth Onboarding

  - [MUST] Admin must initiate Eventbrite OAuth connection for an org.
  - [MUST] System must exchange code for access_token (and refresh_token if issued) and store encrypted per org.
  - [MUST] System must store eb_organization_id and token expiry per org.
  - [MUST] All Eventbrite calls must be scoped to the org’s stored token.
  - [SHOULD] Token refresh must be supported when access token expires.

  Venue Management (discovery‑catalog)

  - [MUST] Admin must create venues in the app and push to Eventbrite via API.
  - [MUST] System must store eventbrite_venue_id returned by Eventbrite.
  - [MUST] Scheduling must read venue data via REST from discovery‑catalog only.
  - [SHOULD] Venue reconciliation job must detect EB‑side edits and flag mismatches.
  - [COULD] Admin can manually trigger venue re‑sync.

  Show Slot Creation & Validation (scheduling)

  - [MUST] Admin must create show slots with venue, date, start/end time, pricing tiers, seating mode, recurrence.
  - [MUST] System must enforce conflict detection against internal slots.
  - [MUST] System must enforce turnaround gap policy.
  - [MUST] If conflict, return structured alternatives (same venue different time, nearby venue, adjusted time).
  - [MUST] Only conflict‑free slots can proceed to Eventbrite sync.

  Eventbrite Sync Orchestration

  - [MUST] System must create EB event in draft, store eventbrite_event_id, and keep slot PENDING_SYNC.
  - [MUST] System must create ticket classes, inventory tiers, and capacity tier (GA) or seat map copy (reserved).
  - [MUST] System must publish EB event and mark slot ACTIVE.
  - [MUST] Sync steps must be driven by Spring Events between modules.
  - [SHOULD] Partial sync failures must be retryable without duplicate EB events.

  Recurring Schedules

  - [MUST] If recurrence is provided, system must create EB schedule and link occurrences to internal slots.
  - [SHOULD] Recurrence validation errors must be surfaced clearly to admin.

  Error Handling & Retry

  - [MUST] Any EB failure must keep slot non‑ACTIVE and return safe error to admin.
  - [MUST] Errors must use shared exception types and ErrorResponse.
  - [SHOULD] Rate‑limit and transient EB failures must be retried with backoff.
  - [COULD] Admin can trigger manual re‑sync.

  Background Reconciliation

  - [MUST] Periodic job must verify EB event existence for ACTIVE slots.
  - [MUST] Mismatches must be flagged for admin review.

  State Management

  - [MUST] Slot states: DRAFT → PENDING_SYNC → ACTIVE → CANCELLED.
  - [MUST] Invalid transitions must raise BusinessRuleException.

  check for any changes or missed ones and give a review for it 

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#1-1), lines 1 to 80

Read [](file:///home/dell/Multi-City-Event-Booking/agents/SPEC_GENERATOR.md#1-1), lines 1 to 80

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#80-80), lines 80 to 200

Read [](file:///home/dell/Multi-City-Event-Booking/docs/EVENTBRITE_INTEGRATION.md#1-1), lines 1 to 120

Read [](file:///home/dell/Multi-City-Event-Booking/docs/EVENTBRITE_INTEGRATION.md#120-120), lines 120 to 280

Good comprehensive requirements. Here's the review against PRODUCT.md, EVENTBRITE_INTEGRATION.md, and the Hard Rules:

---

## ✅ Correct & Complete

- Internal-first validation before any EB call
- Spring Events for cross-module sync (Hard Rule #2)
- Shared exception types + single `GlobalExceptionHandler` (Hard Rule #5)
- Conflict alternatives structure
- `PENDING_SYNC` retention on any failure
- State machine placement in `scheduling`
- Reconciliation ownership in `scheduling/` and discovery-catalog (not `admin/`)

---

## ❌ Critical Issues

### 1. Venue Creation via EB API — Contradicted in Your Own Docs
PRODUCT.md Section 4 explicitly states:
> `EbVenueService` — **Read-only.** Venue creation/updates not available via API — Dashboard only.

But EVENTBRITE_INTEGRATION.md FR2 lists `POST /organizations/{org_id}/venues/` as a live call. **These contradict each other.** You need to resolve this before speccing Stage 2. The Venue Management requirement assumes the API exists — if it doesn't, the entire "push venue to EB" requirement is invalid and venues must be created manually on the Eventbrite Dashboard, with `eb_venue_id` entered by admin.

**Action required:** Verify against the actual EB API (eventbriteapiv3public.apib) and pick one answer.

### 2. Slot CANCELLATION Sync — Completely Missing
You have `CANCELLED` as a state but zero requirement for syncing it to Eventbrite. Per EVENTBRITE_INTEGRATION.md Phase 4:
```
POST /events/{event_id}/cancel/   ← missing from requirements
```
**Add:**
- `[MUST]` When a slot transitions to CANCELLED, system must call `EbEventSyncService.cancelEvent()`.
- `[MUST]` Cancellation sync failure must not block internal cancellation — log and flag.

### 3. Slot UPDATE Sync — Missing
No requirement covers admin editing an existing ACTIVE slot (change time, capacity). This needs:
- `[MUST]` When slot details change post-ACTIVE, system must call `EbEventSyncService.updateEvent()`.

---

## ⚠️ Gaps & Clarifications Needed

### 4. OAuth Token Storage — Module Not Assigned
You say "store encrypted per org" but never specify **where**. This must be explicit:
- Storage lives in `shared/eventbrite/config/EbTokenStore` (or a dedicated DB table scoped to shared)
- identity stores the `organization_id` on the org admin's profile
- All facades accept an `orgId`, resolve token internally from `EbTokenStore`

**Add:** `[MUST]` Token store must be in shared layer. No module caches or stores EB tokens directly.

### 5. Token Refresh — Mechanism Unspecified
`[SHOULD] Token refresh must be supported` is too vague to implement. Specify the trigger:
- Is it a pre-call interceptor in `EbTokenStore` that auto-refreshes if `expires_at < now + buffer`?
- Or a `@Scheduled` job?
- What happens if refresh fails mid-request?

**Add:** `[SHOULD]` Token refresh must be handled transparently as a pre-call interceptor in shared. Facades must never receive an expired token.

### 6. "Nearby Venue" in Conflict Alternatives — Undefined
`[MUST] return structured alternatives (... nearby venue ...)` — "nearby" is unspecified:
- Same city? Within X km radius? Same venue category?
- Who computes this — `scheduling` or discovery-catalog?

**Add:** Define criteria. Suggested: `[MUST]` nearby = same city_id, sorted by distance. `scheduling` calls discovery-catalog REST to fetch candidates.

### 7. SYNC_FAILED vs PENDING_SYNC — State Ambiguity
Currently `PENDING_SYNC + EB_FAILED → PENDING_SYNC` (stays). This means an admin dashboard cannot distinguish between:
- Slot waiting for first sync attempt (never attempted)
- Slot that has failed N times (retried, still broken)

**Add:** Consider a distinct `SYNC_FAILED` state — or at minimum a `sync_attempt_count` + `last_sync_error` field on the entity to surface this to admin.

### 8. Scheduling Reads Venue via REST — Event Not Specified
You state `[MUST] Scheduling must read venue data via REST from discovery-catalog only` (correct), but there's no requirement specifying what REST operation this uses or what venue fields scheduling needs. This becomes important for Stage 4 (DB Schema) — scheduling should NOT duplicate venue data beyond `venue_id`.

---

## ➕ Missing Requirements to Add

| # | Requirement | Priority |
|---|---|---|
| M1 | When slot transitions to CANCELLED, call `EbEventSyncService.cancelEvent()` | MUST |
| M2 | When ACTIVE slot details change, call `EbEventSyncService.updateEvent()` | MUST |
| M3 | EB token store owned by shared; no module holds tokens directly | MUST |
| M4 | Token refresh handled as pre-call interceptor in shared | SHOULD |
| M5 | Invalid state transitions (e.g., ACTIVE → PENDING_SYNC) must raise `BusinessRuleException` | MUST |
| M6 | `sync_attempt_count` and `last_sync_error` tracked on ShowSlot for admin visibility | SHOULD |
| M7 | Conflict alternative "nearby" must use `city_id` from discovery-catalog REST call | MUST |
| M8 | Manual re-sync endpoint: `POST /api/scheduling/slots/{id}/retry-sync` | COULD |

---

## Summary

| Category | Count |
|---|---|
| Critical blockers (resolve before Stage 2) | 3 |
| Gaps to fill in Stage 1 | 5 |
| Missing requirements to add | 8 |

The venue API contradiction (#1) is the highest priority — it affects whether Step B exists at all. Resolve that against eventbriteapiv3public.apib before moving to Stage 2.

User: We are working on FLOW 2 only so write a review accordingly 

GitHub Copilot: Ran terminal command:  grep -n "venues" /home/dell/Multi-City-Event-Booking/docs/Files/eventbriteapiv3public.apib | head -40

Read [](file:///home/dell/Multi-City-Event-Booking/docs/Files/eventbriteapiv3public.apib#4277-4277), lines 4277 to 4360

Ran terminal command:  grep -n "capacity_tier\|inventory_tier\|seatmap\|seat_map\|schedules\|publish" /home/dell/Multi-City-Event-Booking/docs/Files/eventbriteapiv3public.apib | head -40

Ran terminal command:  grep -n "inventory_tier\|ticket_class" /home/dell/Multi-City-Event-Booking/docs/Files/eventbriteapiv3public.apib | grep -i "create\|POST\|Create" | head -20

Ran terminal command:  grep -n "capacity_tier" /home/dell/Multi-City-Event-Booking/docs/Files/eventbriteapiv3public.apib | head -20 && echo "---" && grep -n "seatmap\|seat_map" /home/dell/Multi-City-Event-Booking/docs/Files/eventbriteapiv3public.apib | grep -i "POST\|create\|copy" | head -10

Now I have everything needed for a precise review. Here it is:

---

# Flow 2 — Stage 1 Requirements Review

---

## ❌ Critical Blockers (Fix Before Stage 2)

### 1. PRODUCT.md Declares `EbVenueService` Read-Only — API Proves Otherwise

PRODUCT.md Section 4 currently says:
> `EbVenueService` — Read-only. Venue creation/updates not available via API (Dashboard only).

**The actual API blueprint contradicts this directly:**
```
POST /organizations/{organization_id}/venues/   ← Create a Venue ✅
POST /venues/{venue_id}/                        ← Update a Venue ✅
GET  /organizations/{organization_id}/venues/   ← List Venues ✅
```

Both create and update ARE available. PRODUCT.md has an error here.

**Action required before Stage 2:** PRODUCT.md Section 4 `EbVenueService` description must be corrected to include create and update. Otherwise your Requirement B.1 ("push to Eventbrite via API") references an endpoint your architecture document says doesn't exist — that's a contradiction that will block the coding agent.

---

### 2. Capacity Tier — No CREATE Endpoint Exists

Your Requirement D.4 says:
> `[MUST]` System must create ticket classes, inventory tiers, and **capacity tier (GA)**

The API has **no create** for capacity tier:
```
GET  /events/{event_id}/capacity_tier/   ← Retrieve only
POST /events/{event_id}/capacity_tier/   ← UPDATE only (not create)
```

Capacity is set as part of the **event creation payload** itself (the `capacity` field on `POST /organizations/{org_id}/events/`), then updated via the capacity_tier endpoint if changed later.

**Fix the requirement to:**
> `[MUST]` For GA events, capacity must be set on EB event creation, and updated via `EbCapacityService.updateCapacityTier()` when changed — there is no separate create step.

---

## ⚠️ Gaps Within Flow 2

### 3. Slot CANCELLATION Sync — Missing

You define `CANCELLED` as a valid state but zero requirements cover syncing it to Eventbrite. The API supports:
```
POST /events/{event_id}/cancel/
```

**Add:**
- `[MUST]` When a slot transitions to `CANCELLED`, system must call `EbEventSyncService.cancelEvent()`.
- `[MUST]` EB cancellation failure must NOT block internal cancellation — log, flag, retry separately.

---

### 4. Slot UPDATE Sync — Missing

No requirement covers what happens when an admin edits an `ACTIVE` slot (time change, capacity update, pricing change). The API supports:
```
POST /events/{event_id}/   ← update event details
```

**Add:**
- `[MUST]` When an ACTIVE slot's details change, `EbEventSyncService.updateEvent()` must be called.
- `[MUST]` Update failures must not silently succeed internally — slot must be flagged `SYNC_FAILED` or equivalent.

---

### 5. `PENDING_SYNC` Cannot Distinguish "Never Tried" from "Failed N Times"

Currently `PENDING_SYNC + EB_FAILED → PENDING_SYNC`. Admin dashboard sees one state for both:
- A brand new slot waiting its first sync attempt
- A slot that has failed 5 retries

This makes observability impossible and retry logic ambiguous.

**Options (pick one before Stage 4 DB Schema):**
- Add a `SYNC_FAILED` state as a distinct terminal-until-retry state
- OR keep one state but add `sync_attempt_count` + `last_sync_error` + `last_attempted_at` fields

---

### 6. "Nearby Venue" Undefined

`[MUST] return structured alternatives (... nearby venue ...)` — "nearby" has no definition:
- Same `city_id`? Within X km? Same category?
- **Who computes this?** `scheduling` must call discovery-catalog REST to get venue candidates — but by what filter criteria?

**Add:** `[MUST]` Nearby venue alternatives are queried from discovery-catalog via REST using `city_id` from the conflicting slot's venue. Sorted by capacity match, not distance (distance data not stored).

---

### 7. OAuth Token Store — Module Not Assigned in Requirements

You say `[MUST] store encrypted per org` but never state **where**:
- identity module handles human users/profiles — it must NOT own EB tokens
- Token storage belongs in `shared/eventbrite/` (a DB table or encrypted config scoped to org)

**Add:** `[MUST]` EB org tokens (`access_token`, `refresh_token`, `eb_organization_id`, `expires_at`) stored in shared layer only. No module owns or caches tokens directly.

---

### 8. Token Refresh Has No Trigger Defined

`[SHOULD] Token refresh must be supported` is too vague:
- Pre-call interceptor inside each facade? (`if expires_at < now + 5min → refresh`)
- Scheduled background job?
- What if refresh fails mid-request (admin in the middle of a sync)?

**Add:** `[SHOULD]` Token refresh triggered as a pre-call check inside `shared/eventbrite/` facade base before any API call. If refresh fails, throw `EbAuthException` — caller gets safe error, slot stays `PENDING_SYNC`.

---

### 9. Recurrence — Series Parent vs. Occurrence Relationship Not Specified

`[MUST] link occurrences to internal slots` — but how? Eventbrite requires:
1. Create a series parent event with `is_series: true`
2. Add schedules → EB generates occurrence event IDs

Each occurrence gets its own `eventbrite_event_id`. Your requirement doesn't address:
- Does each recurrence get its own `ShowSlot` row internally?
- Or one `ShowSlot` with a `series_id` + separate occurrences table?

This decision **directly affects Stage 4 DB Schema** and must be resolved in Stage 1.

**Add:** `[MUST]` For recurring shows, a parent `ShowSlot` (is_series=true) links to child `ShowSlotOccurrence` records. Each child stores its own `eb_event_id` (occurrence ID). The parent stores `eb_series_id`.

---

## ➕ Missing Requirements Summary

| # | Add to Requirements | Priority |
|---|---|---|
| M1 | Slot CANCELLED → `EbEventSyncService.cancelEvent()` | MUST |
| M2 | ACTIVE slot edit → `EbEventSyncService.updateEvent()` | MUST |
| M3 | Capacity set at event creation, not separate create step | MUST |
| M4 | EB token store owned exclusively by shared layer | MUST |
| M5 | `SYNC_FAILED` state OR `sync_attempt_count`/`last_sync_error` fields | SHOULD |
| M6 | Nearby venue = same `city_id` from discovery-catalog REST call | MUST |
| M7 | Token refresh = pre-call interceptor in facade, throws `EbAuthException` on failure | SHOULD |
| M8 | Recurring: parent `ShowSlot` + child `ShowSlotOccurrence` with own `eb_event_id` | MUST |

---

## ✅ What Is Correct

- Internal conflict check before any EB call — correct
- Spring Events for cross-module sync (D steps) — correct, Hard Rule #2
- `PENDING_SYNC` retention on any failure — correct
- Manual retry endpoint as `[COULD]` — correctly prioritized
- Mismatches flagged (not auto-fixed) by reconciliation job — correct
- `scheduling` reads venue via REST from discovery-catalog only — correct
- `BusinessRuleException` on invalid state transitions — correct

---

**Bottom line:** Fix M3 (capacity create) and resolve the `EbVenueService` read-only contradiction in PRODUCT.md before moving to Stage 2 — those two affect the architecture directly. The other items can be incorporated into Stage 1 before finalizing.

User: Ok update the cahnges with all the fixes adn also rewrite the stage 1

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_1.md#1-1), lines 1 to 50

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#80-80), lines 80 to 130

Searched for files matching `**/SPEC_2*`, no matches

Good. No `SPEC_2.md` exists yet. I'll fix PRODUCT.md and create `SPEC_2.md` with the corrected Stage 1. 

Created 2 todos

Starting: *Fix PRODUCT.md EbVenueService* (1/2)

Replacing 3 lines with 3 lines in [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md)


```

```


Starting: *Create SPEC_2.md Stage 1* (2/2)

Created [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)

Completed: *Create SPEC_2.md Stage 1* (2/2)

Done. Here's what changed:

---

### PRODUCT.md — Fix Applied
`EbVenueService` description corrected from "Read-only / Dashboard only" to accurately reflect the API:
- `POST /organizations/{org_id}/venues/` — create
- `POST /venues/{venue_id}/` — update
- `GET /organizations/{org_id}/venues/` — list
- The incorrect bottom note was also replaced.

---

### SPEC_2.md — Created with Full Corrected Stage 1

10 sections covering the complete flow with all review fixes incorporated:

| Section | Fix Applied |
|---|---|
| A — OAuth Onboarding | Token store owned by shared only; refresh as pre-call interceptor; `EbAuthException` on failure |
| B — Venue Management | `EbVenueService.createVenue()` and `updateVenue()` now explicit (no longer contradicted) |
| C — Slot Validation | "Nearby" defined as same `city_id` via discovery-catalog REST |
| D — EB Sync | Capacity is **not a create step** — set in event creation payload, updated via `updateCapacityTier()` |
| E — Slot Update Sync | New section: ACTIVE slot edits → `EbEventSyncService.updateEvent()` |
| F — Slot Cancellation | New section: CANCELLED → `EbEventSyncService.cancelEvent()`; failure doesn't block internal cancel |
| G — Recurring | Parent `ShowSlot` + child `ShowSlotOccurrence`, each with own `eb_event_id` |
| H — State Machine | `SYNC_FAILED` path clarified; `sync_attempt_count`, `last_sync_error`, `last_attempted_at` fields added |
| I — Error Handling | Rate-limit aware retry via shared `EbRetryPolicy`; manual retry endpoint |
| J — Reconciliation | Module ownership explicit; `admin/` reads only via REST, owns no logic |

Made changes.

User: • Stage 2 — Domain Modeling (Outline) for SPEC_2.md

  Entities

  1. OrganizationAuth
      - Stores EB OAuth tokens per organizer (org_id, eb_organization_id, access_token, refresh_token, expires_at,
        status).
  2. Venue
      - Internal venue owned by discovery-catalog, linked to eb_venue_id, has seating mode (GA/Reserved).
  3. ShowSlot
      - Scheduling aggregate; owns time window, venue linkage, status, EB event ID, sync metadata.
  4. ShowSlotOccurrence
      - Child of a recurring ShowSlot (occurrence‑level eb_event_id).
  5. PricingTier
      - Ticket tier definition referenced by show slots.
  6. SeatMapReference
      - References EB seatmap (source_seatmap_id) for reserved seating.
  7. SyncFailureRecord (or embedded on ShowSlot)
      - Captures sync_attempt_count, last_sync_error, last_attempted_at.

  Relationships

  - OrganizationAuth 1 → N Venues
  - OrganizationAuth 1 → N ShowSlots
  - Venue 1 → N ShowSlots
  - ShowSlot 1 → N ShowSlotOccurrences (only if recurring)
  - ShowSlot N → N PricingTiers (via join table or mapping)
  - ShowSlot 0..1 → 1 SeatMapReference (reserved only)

  Lifecycle/States

  - ShowSlot: DRAFT → PENDING_SYNC → ACTIVE → CANCELLED
  - ShowSlotOccurrence: mirrors parent’s lifecycle, but has its own eb_event_id
  - OrganizationAuth: CONNECTED / TOKEN_EXPIRED / REVOKED

  Does this look complete? Anything to add, remove, or change before I write Stage 2 in full?
  List what is needed 

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#1-1), lines 1 to 50

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#1-1), lines 1 to 60

Here's the full review with everything that needs to change, be added, or be removed:

---

## ✅ Correct

- `ShowSlot` as the scheduling aggregate with sync metadata — correct
- `ShowSlotOccurrence` as a child with its own `eb_event_id` — correct
- `OrganizationAuth` storing token fields — correct
- `Venue` in discovery-catalog with `eb_venue_id` — correct
- `SyncFailureRecord` considered as embedded (good default) — see below

---

## ❌ Critical Issues

### 1. `PricingTier` — Wrong Module Owner

`booking-inventory` owns "Pricing" in PRODUCT.md, but the dependency order is:
```
shared → discovery-catalog → scheduling → identity → booking-inventory
```

`scheduling` sits **above** `booking-inventory`. It cannot import or depend on `booking-inventory`'s `@Entity`.

`PricingTier` as referenced at show slot creation time (ticket name, price, quota) must be owned by **`scheduling`** — rename it `ShowSlotPricingTier`. It's the input that drives EB ticket class creation via the `SlotDraftCreatedEvent`.

`booking-inventory`'s "Pricing" refers to cart-level pricing and seat lock pricing — different concern.

---

### 2. Cross-Module FK Relationships Are Not JPA

Your relationships show:
```
OrganizationAuth 1 → N Venues
OrganizationAuth 1 → N ShowSlots
```

`OrganizationAuth` lives in shared. `Venue` lives in discovery-catalog. `ShowSlot` lives in `scheduling`. **JPA `@ManyToOne` / `@OneToMany` across module boundaries are forbidden** (Hard Rule #1).

These are **logical relationships only**, represented as plain `org_id` (Long) scalar fields on each entity:
- `Venue` stores `org_id` (Long) — not `@ManyToOne OrganizationAuth`
- `ShowSlot` stores `org_id` (Long) — not `@ManyToOne OrganizationAuth`

Remove cross-module JPA relationships from the diagram entirely. Replace with: "linked by `org_id` scalar field."

---

### 3. `ShowSlotOccurrence` State Is Independent, Not Mirrored

"mirrors parent's lifecycle" is incorrect. Consider:
- Parent `ShowSlot` = `ACTIVE`
- Occurrence 3 of 5 fails EB sync → its own `eb_event_id` is null, sync failed

Each `ShowSlotOccurrence` needs **its own** `status`, `sync_attempt_count`, `last_sync_error`, `last_attempted_at` — same fields as the parent. It has an independent sync lifecycle.

---

## ⚠️ Gaps to Fill

### 4. `SeatMapReference` — Should Be a Value Object, Not Entity

A `SeatMapReference` is just `source_seatmap_id` (a String) pinned to a `ShowSlot`. There's no lifecycle, no audit, no relationships. Making it a separate `@Entity` adds a join for zero benefit.

**Decision to make before Stage 2:** Embed `source_seatmap_id` (nullable) directly on `ShowSlot`. Only promote to separate entity if you anticipated multi-map or floor-plan versioning — which is out of scope for Flow 2.

---

### 5. `SyncFailureRecord` — Embed or Table? Decide Now

Stage 1 already resolved this as fields on `ShowSlot`. Separate entity only makes sense if you need full per-attempt audit history (every retry logged as a row). For Flow 2:

| Approach | When to use |
|---|---|
| Embedded fields (`sync_attempt_count`, `last_sync_error`, `last_attempted_at`) | Only current failure state matters — simpler, no extra table |
| Separate `SyncAttemptLog` table | Full retry audit trail needed by admin dashboard |

Pick one and lock it. Suggested default: **embedded fields on `ShowSlot`** (and on `ShowSlotOccurrence`). A separate `SyncAttemptLog` is a `[COULD]` enhancement.

---

### 6. Missing: `eb_series_id` on `ShowSlot`

For recurring shows, the parent `ShowSlot` stores two EB IDs:
- `eb_event_id` — the series parent event ID
- `eb_series_id` — the EB schedule/series ID returned by `POST /events/{id}/schedules/`

Both are distinct fields. `eb_series_id` is not in your entity outline.

---

### 7. Missing: `ConflictAlternative` Value Object

Stage 1 C requires returning structured alternatives on conflict. This is a **non-persisted value object / response DTO**:

```
ConflictAlternativeResponse {
    List<TimeWindowOption> sameVenueAlternatives
    List<VenueOption>      nearbyVenueAlternatives
    List<TimeWindowOption> adjustedTimeOptions
}
```

Add it as a value object (no `@Entity`, not persisted). It belongs in `scheduling/domain/value/`.

---

### 8. Missing: `is_recurring` Flag + `recurrence_rule` on `ShowSlot`

`ShowSlot` needs to carry:
- `is_recurring` (boolean) — drives whether G-path is taken
- `recurrence_rule` (String/JSON/embedded value object) — the rule passed to `EbScheduleService`

Without this, the domain model has no way to distinguish a one-time slot from the head of a recurring series.

---

### 9. `OrganizationAuth` Status Values Are Missing One

Your statuses: `CONNECTED / TOKEN_EXPIRED / REVOKED`

Missing: **`PENDING`** — the state between OAuth initiation and code exchange. If the admin starts but doesn't complete the OAuth flow, the record exists but is not connected yet.

Full set: `PENDING → CONNECTED → TOKEN_EXPIRED → REVOKED`

---

## Corrected Summary

| Entity / Concept | Owner Module | Type | Change Needed |
|---|---|---|---|
| `OrganizationAuth` | `shared/eventbrite/` | `@Entity` | Add `PENDING` status; no JPA FK to other modules |
| `Venue` | discovery-catalog | `@Entity` | Stores `org_id` as Long scalar, not FK |
| `ShowSlot` | `scheduling` | `@Entity` | Add `eb_series_id`, `is_recurring`, `recurrence_rule`, `source_seatmap_id` (embedded) |
| `ShowSlotOccurrence` | `scheduling` | `@Entity` | Own independent status + sync fields, not mirrored |
| `ShowSlotPricingTier` | `scheduling` | `@Entity` | Renamed from PricingTier; owned by `scheduling`, not `booking-inventory` |
| `SyncFailureRecord` | ~~separate~~ | ~~entity~~ | Drop as entity; embed fields on `ShowSlot` + `ShowSlotOccurrence` |
| `SeatMapReference` | ~~separate~~ | ~~entity~~ | Drop as entity; embed `source_seatmap_id` on `ShowSlot` |
| `ConflictAlternativeResponse` | `scheduling` | Value Object | Add — no persistence, lives in `domain/value/` |

---

**In short:** Fix the `PricingTier` owner (biggest risk), remove cross-module JPA FKs, give `ShowSlotOccurrence` its own status fields, drop `SeatMapReference` to embedded field, and add the three missing fields (`eb_series_id`, `is_recurring`, `recurrence_rule`). With those resolved you're ready to write Stage 2 in full.

User: Now update these changes in SPEC_2.MD file as the stage 2

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#150-150), lines 150 to 159

Replacing 10 lines with 262 lines in [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)


```

```
Stage 2 is now written in full in SPEC_2.md. Here's what was included and every fix from the review applied:

| Entity | Key Fixes Applied |
|---|---|
| `OrganizationAuth` | Added `PENDING` status; full lifecycle documented; lives in `shared/eventbrite/domain/` |
| `Venue` | `org_id` as scalar Long (no cross-module JPA FK); `VenueSyncStatus` enum with `DRIFT_FLAGGED` |
| `ShowSlot` | Added `eb_series_id`, `is_recurring`, `recurrence_rule`; `source_seatmap_id` embedded directly (no separate entity); sync fields embedded |
| `ShowSlotOccurrence` | **Own independent** `status` + `sync_attempt_count` + `last_sync_error` + `last_attempted_at` — not mirrored from parent |
| `ShowSlotPricingTier` | Renamed from `PricingTier`; explicitly owned by `scheduling` with clear note separating it from `booking-inventory`'s pricing concern |
| `SeatMapReference` | **Dropped as entity** — `source_seatmap_id` is a nullable String field on `ShowSlot` |
| `SyncFailureRecord` | **Dropped as entity** — fields embedded on `ShowSlot` and `ShowSlotOccurrence` |
| `ConflictAlternativeResponse` | Added as non-persisted value object in `scheduling/domain/value/` with full record structure |
| Enums | All 5 enums defined with values and owning module |
| Relationships | Cross-module links shown as scalar IDs only — JPA `@ManyToOne` allowed only within the same module |

Made changes.

User: Next is the Stage 3, Create a list of the Schemas for the given Domain Modeling ill verify it 

GitHub Copilot: Per SPEC_GENERATOR.md, Stage 3 is Architecture & File Structure — but you're asking for DB Schema (Stage 4 in the generator). I'll follow your numbering and present the **outline only** (table names + key fields, no types or constraints yet) for you to verify before writing the full schema.

---

## Stage 3 (DB Schema) — Outline for Verification

---

### Table: `organization_auth` *(shared/)*
- `id`
- `org_id`
- `eb_organization_id`
- `access_token` *(encrypted)*
- `refresh_token` *(encrypted, nullable)*
- `expires_at`
- `status` *(PENDING / CONNECTED / TOKEN_EXPIRED / REVOKED)*
- `created_at`, `updated_at`

---

### Table: `venues` *(discovery-catalog)*
- `id`
- `org_id`
- `name`
- `address_line1`, `address_line2`
- `city`, `city_id`, `country`
- `latitude`, `longitude` *(nullable)*
- `capacity`
- `seating_mode` *(GA / RESERVED)*
- `eb_venue_id` *(nullable)*
- `sync_status` *(PENDING_SYNC / SYNCED / DRIFT_FLAGGED)*
- `created_at`, `updated_at`

---

### Table: `show_slots` *(scheduling)*
- `id`
- `org_id`
- `venue_id`
- `title`
- `start_time`, `end_time`
- `seating_mode` *(GA / RESERVED)*
- `capacity`
- `source_seatmap_id` *(nullable — Reserved only)*
- `is_recurring`
- `recurrence_rule` *(nullable)*
- `status` *(DRAFT / PENDING_SYNC / ACTIVE / CANCELLED)*
- `eb_event_id` *(nullable)*
- `eb_series_id` *(nullable — recurring only)*
- `sync_attempt_count`
- `last_sync_error` *(nullable)*
- `last_attempted_at` *(nullable)*
- `created_at`, `updated_at`

---

### Table: `show_slot_occurrences` *(scheduling)*
- `id`
- `parent_slot_id` *(FK → show_slots)*
- `occurrence_index`
- `start_time`, `end_time`
- `status` *(DRAFT / PENDING_SYNC / ACTIVE / CANCELLED)*
- `eb_event_id` *(nullable)*
- `sync_attempt_count`
- `last_sync_error` *(nullable)*
- `last_attempted_at` *(nullable)*
- `created_at`

---

### Table: `show_slot_pricing_tiers` *(scheduling)*
- `id`
- `slot_id` *(FK → show_slots)*
- `name`
- `price_amount`
- `currency`
- `quota`
- `tier_type` *(FREE / PAID / DONATION)*
- `eb_ticket_class_id` *(nullable — populated after D.2 sync)*
- `created_at`

---

Does this look complete? Anything to add, remove, or rename before I write the full Stage 3 schema with types, constraints, and indexes?

User: Give stage 3 the architecture and file strucutre, list the changes ill verify it 

GitHub Copilot: Per SPEC_GENERATOR.md Stage 3, here is the **outline only** — modules, cross-module interactions, and feature files. Verify before I write it in full.

---

## Stage 3 — Architecture & File Structure Outline

---

### Owning Modules (per PRODUCT.md)

| Concern | Module |
|---|---|
| OAuth token storage | `shared/eventbrite/` |
| Venue create/update/sync | discovery-catalog |
| Show slot, conflict, state machine, sync orchestration | `scheduling` (primary) |
| Ticket classes, inventory tiers, seat map copy | `booking-inventory` (listener) |
| OAuth onboarding endpoint | `admin/` |

---

### Cross-Module Interactions

| From | To | Mechanism | Reason |
|---|---|---|---|
| `scheduling` | discovery-catalog | REST GET | Read venue details before validation |
| `scheduling` | `booking-inventory` | Spring Event (`SlotDraftCreatedEvent`) | Trigger ticket/capacity/seatmap sync |
| `booking-inventory` | `scheduling` | Spring Event (`TicketSyncCompletedEvent` / `TicketSyncFailedEvent`) | Signal publish or failure |
| `scheduling` | `shared/eventbrite/` | Direct call (ACL facade) | `EbEventSyncService`, `EbScheduleService` |
| discovery-catalog | `shared/eventbrite/` | Direct call (ACL facade) | `EbVenueService` |
| `booking-inventory` | `shared/eventbrite/` | Direct call (ACL facade) | `EbTicketService`, `EbCapacityService` |
| `admin/` | `shared/eventbrite/` | Direct call (ACL facade) | Token exchange, store OAuth token |

---

### New / Modified Files Per Module

---

#### shared
```
shared/eventbrite/
├── domain/
│   └── OrganizationAuth.java                    ← NEW @Entity
├── repository/
│   └── OrganizationAuthRepository.java          ← NEW
├── service/
│   ├── EbTokenStore.java                        ← NEW — resolves + refreshes token per org
│   ├── EbVenueService.java                      ← MODIFY — add createVenue(), updateVenue()
│   ├── DefaultEbVenueService.java               ← MODIFY — implement new methods
│   └── EbRetryPolicy.java                       ← NEW — @Retryable config, backoff, rate-limit aware
├── exception/
│   └── EbAuthException.java                     ← NEW (if not already present)
└── dto/
    ├── EbVenueCreateRequest.java                ← NEW
    └── EbVenueResponse.java                     ← NEW
```

---

#### discovery-catalog
```
discovery-catalog/
├── domain/
│   └── Venue.java                               ← MODIFY — add eb_venue_id, sync_status, VenueSyncStatus enum
├── api/
│   ├── controller/
│   │   └── VenueCatalogController.java          ← MODIFY — add POST /venues, PUT /venues/{id}, POST /venues/{id}/sync
│   └── dto/
│       ├── request/
│       │   └── CreateVenueRequest.java          ← NEW
│       └── response/
│           └── VenueResponse.java               ← MODIFY — add eb_venue_id, sync_status
├── service/
│   ├── VenueService.java                        ← NEW (or MODIFY) — createVenue, updateVenue, syncVenue
│   └── VenueReconciliationJob.java              ← NEW — @Scheduled, GET /venues/{eb_venue_id}/
├── repository/
│   └── VenueRepository.java                     ← MODIFY — add findByOrgIdAndSyncStatus()
├── mapper/
│   └── VenueMapper.java                         ← NEW
└── event/
    └── published/
        └── VenueDriftDetectedEvent.java         ← NEW
```

---

#### `scheduling/`
```
scheduling/
├── domain/
│   ├── ShowSlot.java                            ← NEW @Entity (aggregate root)
│   ├── ShowSlotOccurrence.java                  ← NEW @Entity
│   ├── ShowSlotPricingTier.java                 ← NEW @Entity
│   ├── enums/
│   │   ├── ShowSlotStatus.java                  ← NEW enum
│   │   ├── SeatingMode.java                     ← NEW enum (or move to shared/common)
│   │   └── TierType.java                        ← NEW enum
│   └── value/
│       └── ConflictAlternativeResponse.java     ← NEW record (non-persisted)
├── api/
│   ├── controller/
│   │   └── ShowSlotController.java              ← NEW
│   └── dto/
│       ├── request/
│       │   └── CreateShowSlotRequest.java       ← NEW
│       └── response/
│           ├── ShowSlotResponse.java            ← NEW
│           └── ConflictAlternativeDto.java      ← NEW
├── service/
│   ├── ShowSlotService.java                     ← NEW — create, submit, update, cancel
│   ├── ConflictDetectionService.java            ← NEW — overlap check, gap policy, alternatives
│   └── SlotReconciliationJob.java               ← NEW — @Scheduled
├── repository/
│   ├── ShowSlotRepository.java                  ← NEW
│   ├── ShowSlotOccurrenceRepository.java        ← NEW
│   └── ShowSlotPricingTierRepository.java       ← NEW
├── mapper/
│   └── ShowSlotMapper.java                      ← NEW
├── statemachine/
│   ├── ShowSlotStateMachineConfig.java          ← NEW
│   ├── ShowSlotState.java                       ← NEW enum
│   ├── ShowSlotEvent.java                       ← NEW enum (trigger events)
│   ├── ShowSlotStateMachineService.java         ← NEW
│   ├── guard/
│   │   └── SlotTransitionGuard.java             ← NEW
│   └── action/
│       └── SlotStatusUpdateAction.java          ← NEW
├── event/
│   ├── published/
│   │   ├── SlotDraftCreatedEvent.java           ← NEW (slotId, ebEventId, pricingTierIds, seatingMode, orgId)
│   │   └── SlotSyncFailedEvent.java             ← NEW (slotId, reason)
│   └── listener/
│       ├── TicketSyncCompletedListener.java     ← NEW — triggers D.6 publish
│       └── TicketSyncFailedListener.java        ← NEW — records error, keeps PENDING_SYNC
└── exception/
    └── SlotConflictException.java              ← NEW
```

---

#### `booking-inventory/`
```
booking-inventory/
├── event/
│   ├── listener/
│   │   └── SlotDraftCreatedListener.java        ← NEW — handles D.2–D.5 (ticket/capacity/seatmap)
│   └── published/
│       ├── TicketSyncCompletedEvent.java        ← NEW (slotId, ebEventId)
│       └── TicketSyncFailedEvent.java           ← NEW (slotId, ebEventId, reason)
└── service/
    └── SlotTicketSyncService.java               ← NEW — orchestrates EbTicketService + EbCapacityService calls
```

---

#### `admin/`
```
admin/
├── api/controller/
│   └── OrgEventbriteController.java             ← NEW — POST /admin/orgs/{orgId}/eventbrite/connect, callback
└── service/
    └── OrgOAuthService.java                     ← NEW — code exchange, delegate token store to shared/
```

---

Does this look complete? Anything to add, rename, remove, or move between modules before I write Stage 3 in full?

User: Why are we putting the Venue create/update/sync	 in the discovery_catalog ? Give mereason with explanation no code 

GitHub Copilot: Three layered reasons:

---

## 1. Module Ownership — PRODUCT.md is Explicit

PRODUCT.md Section 1 states:

> discovery-catalog **owns:** Events, Venues, Cities, Eventbrite catalog sync

> `scheduling` **must not own:** Bookings, Users — and implicitly, it does not own Venues

A `Venue` is not a time slot. It is a **physical location** — it exists independently of any show. A venue can have zero shows scheduled today and still be a valid entity in the system. Ownership follows the entity's natural domain, not who uses it most.

---

## 2. Dependency Direction Would Be Violated Otherwise

The module order is:
```
discovery-catalog → scheduling → booking-inventory → ...
```

`scheduling` sits **downstream** of discovery-catalog. It reads venue data via REST from discovery-catalog.

If you put Venue ownership in `scheduling`, then discovery-catalog would need to call `scheduling` to sync venue data for the catalog — that's an **upstream module calling a downstream module**, which directly violates Hard Rule #10 (dependency direction is strictly downward, no circular imports).

---

## 3. Venue Has a Lifecycle Independent of Slots

A venue can be:
- Created before any show is ever scheduled
- Updated (capacity change, address correction) while shows are active
- Drift-flagged by the reconciliation job independently of any specific slot

If `scheduling` owned venues, it would need to handle venue lifecycle events (drift detection, EB sync for venue metadata) that have **nothing to do with time slots**. That bloats `scheduling` with concerns it doesn't own and makes the module responsible for two unrelated aggregates.

---

## The Clean Boundary

| What | Owned By | Why |
|---|---|---|
| Venue entity, EB venue sync, reconciliation | discovery-catalog | Venue is a place — catalog concern |
| Show slot at that venue | `scheduling` | A slot is a time window — scheduling concern |
| `scheduling` reading venue data | via REST GET to discovery-catalog | Clean read across boundary, no ownership transfer |

`scheduling` only ever needs a `VenueDto` snapshot to validate a slot (capacity, seating mode, city). It does not own, persist, or mutate the venue. That's the correct relationship.

User: Ok great now update the SPEC_2.md file with the stage 3 file structure

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#280-280), lines 280 to 350

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#350-350), lines 350 to 411

Replacing 3 lines with 223 lines in [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#403-403), lines 403 to 411

Replacing 7 lines with 227 lines in [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)

Let me check the exact raw content at the end of the file: 

Ran terminal command:  tail -20 /home/dell/Multi-City-Event-Booking/SPEC_2.md | cat -A

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && tail -3 /home/dell/Multi-City-Event-Booking/SPEC_2.md

The file has no trailing newline and the Enums table is the last content. I'll append Stage 3 directly: 

Ran terminal command:  cat >> /home/dell/Multi-City-Event-Booking/SPEC_2.md << 'STAGE3_EOF'

---

## Stage 3 — Architecture & File Structure

---

### Module Ownership

| Concern | Owning Module |
|---|---|
| OAuth token store, EB ACL facades, retry policy | `shared/eventbrite/` |
| Venue create / update / EB sync / reconciliation | `discovery-catalog` |
| Show slot lifecycle, conflict detection, state machine, sync orchestration | `scheduling` *(primary)* |
| Ticket class / inventory tier / seat map sync | `booking-inventory` *(listener)* |
| OAuth onboarding endpoint (admin panel) | `admin/` |

---

### Cross-Module Interactions

| From | To | Mechanism | What |
|---|---|---|---|
| `scheduling` | `discovery-catalog` | REST GET | Read `VenueDto` (capacity, seating mode, city) before conflict validation |
| `scheduling` | `shared/eventbrite/` | ACL facade call | `EbEventSyncService` — create draft, publish, update, cancel; `EbScheduleService` — recurring |
| `scheduling` | `booking-inventory` | Spring Event → `SlotDraftCreatedEvent` | Trigger D.2–D.5 ticket / capacity / seatmap setup |
| `booking-inventory` | `scheduling` | Spring Event → `TicketSyncCompletedEvent` / `TicketSyncFailedEvent` | Signal D.6 publish or failure |
| `booking-inventory` | `shared/eventbrite/` | ACL facade call | `EbTicketService` — ticket classes, inventory tiers, seat map copy; `EbCapacityService` — capacity update |
| `discovery-catalog` | `shared/eventbrite/` | ACL facade call | `EbVenueService` — create venue, update venue, reconcile |
| `admin/` | `shared/eventbrite/` | ACL facade call | `EbTokenStore` — OAuth code exchange, store token |
| `admin/` | `scheduling` | REST GET | Read slot mismatch flags for dashboard |
| `admin/` | `discovery-catalog` | REST GET | Read venue drift flags for dashboard |

> **Hard Rules applied:**
> - No module imports another module's `@Service`, `@Entity`, or `@Repository` (Rule #1).
> - Spring Events carry only primitives, IDs, enums, value objects — never `@Entity` (Rule #4).
> - All EB API calls go through `shared/` ACL facades only (Rule #2).

---

### Feature File Structure

---

#### `shared/`

```
shared/src/main/java/com/eventplatform/shared/
│
├── eventbrite/
│   ├── domain/
│   │   └── OrganizationAuth.java                  ← NEW  @Entity — OAuth tokens per org
│   ├── repository/
│   │   └── OrganizationAuthRepository.java        ← NEW  Spring Data JPA
│   ├── service/
│   │   ├── EbTokenStore.java                      ← NEW  Resolves + pre-call refreshes token per org
│   │   ├── EbVenueService.java                    ← MODIFY  Add createVenue(), updateVenue()
│   │   ├── DefaultEbVenueService.java             ← MODIFY  Implement new methods
│   │   └── EbRetryPolicy.java                     ← NEW  @Retryable config, exponential backoff, Retry-After aware
│   ├── exception/
│   │   └── EbAuthException.java                   ← NEW  Thrown when token missing / refresh fails
│   └── dto/
│       ├── EbVenueCreateRequest.java              ← NEW
│       └── EbVenueResponse.java                   ← NEW
│
└── common/
    └── enums/
        └── SeatingMode.java                       ← NEW  GA / RESERVED — shared by discovery-catalog + scheduling
```

---

#### `discovery-catalog/`

```
discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/
│
├── domain/
│   ├── Venue.java                                 ← MODIFY  Add eb_venue_id, sync_status
│   └── enums/
│       └── VenueSyncStatus.java                   ← NEW  PENDING_SYNC / SYNCED / DRIFT_FLAGGED
│
├── api/
│   ├── controller/
│   │   └── VenueCatalogController.java            ← MODIFY  Add POST /venues, PUT /venues/{id}, POST /venues/{id}/sync
│   └── dto/
│       ├── request/
│       │   ├── CreateVenueRequest.java            ← NEW
│       │   └── UpdateVenueRequest.java            ← NEW
│       └── response/
│           └── VenueResponse.java                 ← MODIFY  Add eb_venue_id, sync_status
│
├── service/
│   ├── VenueService.java                          ← NEW  createVenue(), updateVenue(), syncVenue()
│   └── VenueReconciliationJob.java                ← NEW  @Scheduled — GET /venues/{eb_venue_id}/, flag drift
│
├── repository/
│   └── VenueRepository.java                       ← MODIFY  Add findByOrgId(), findByCityId(), findBySyncStatus()
│
├── mapper/
│   └── VenueMapper.java                           ← NEW  MapStruct — Venue ↔ VenueResponse / CreateVenueRequest
│
└── event/
    └── published/
        └── VenueDriftDetectedEvent.java           ← NEW  (venueId, ebVenueId, driftDescription)
```

---

#### `scheduling/` *(primary owner)*

```
scheduling/src/main/java/com/eventplatform/scheduling/
│
├── domain/
│   ├── ShowSlot.java                              ← NEW  @Entity — aggregate root
│   ├── ShowSlotOccurrence.java                    ← NEW  @Entity — child of recurring slot
│   ├── ShowSlotPricingTier.java                   ← NEW  @Entity — ticket tier definition
│   ├── enums/
│   │   ├── ShowSlotStatus.java                    ← NEW  DRAFT / PENDING_SYNC / ACTIVE / CANCELLED
│   │   └── TierType.java                          ← NEW  FREE / PAID / DONATION
│   └── value/
│       ├── ConflictAlternativeResponse.java       ← NEW  record — non-persisted, returned on conflict
│       ├── TimeWindowOption.java                  ← NEW  record — proposed start/end
│       └── VenueOption.java                       ← NEW  record — nearby venue candidate
│
├── api/
│   ├── controller/
│   │   └── ShowSlotController.java                ← NEW
│   └── dto/
│       ├── request/
│       │   ├── CreateShowSlotRequest.java         ← NEW
│       │   └── UpdateShowSlotRequest.java         ← NEW
│       └── response/
│           ├── ShowSlotResponse.java              ← NEW
│           ├── ShowSlotOccurrenceResponse.java    ← NEW
│           └── ConflictAlternativeDto.java        ← NEW
│
├── service/
│   ├── ShowSlotService.java                       ← NEW  create, submit, update, cancel — use-case methods
│   ├── ConflictDetectionService.java              ← NEW  overlap check, gap policy, build alternatives
│   └── SlotReconciliationJob.java                 ← NEW  @Scheduled — GET /events/{eb_event_id}/, flag mismatches
│
├── repository/
│   ├── ShowSlotRepository.java                    ← NEW
│   ├── ShowSlotOccurrenceRepository.java          ← NEW
│   └── ShowSlotPricingTierRepository.java         ← NEW
│
├── mapper/
│   └── ShowSlotMapper.java                        ← NEW  MapStruct — ShowSlot ↔ response/request DTOs
│
├── statemachine/
│   ├── ShowSlotStateMachineConfig.java            ← NEW
│   ├── ShowSlotState.java                         ← NEW  enum — mirrors ShowSlotStatus
│   ├── ShowSlotEvent.java                         ← NEW  enum — SUBMIT / EB_PUBLISHED / EB_FAILED / CANCEL
│   ├── ShowSlotStateMachineService.java           ← NEW  sendEvent(), throws BusinessRuleException on invalid transition
│   ├── guard/
│   │   └── SlotTransitionGuard.java               ← NEW
│   └── action/
│       └── SlotStatusUpdateAction.java            ← NEW  persists new status on transition
│
├── event/
│   ├── published/
│   │   ├── SlotDraftCreatedEvent.java             ← NEW  (slotId, ebEventId, pricingTierIds, seatingMode, orgId, sourceSeatMapId)
│   │   └── SlotSyncFailedEvent.java               ← NEW  (slotId, reason)
│   └── listener/
│       ├── TicketSyncCompletedListener.java       ← NEW  → ShowSlotService.onTicketSyncComplete() → D.6 publish
│       └── TicketSyncFailedListener.java          ← NEW  → ShowSlotService.onTicketSyncFailed() → record error
│
└── exception/
    └── SlotConflictException.java                 ← NEW  carries ConflictAlternativeResponse in payload
```

---

#### `booking-inventory/` *(listener only for this flow)*

```
booking-inventory/src/main/java/com/eventplatform/bookinginventory/
│
├── event/
│   ├── listener/
│   │   └── SlotDraftCreatedListener.java          ← NEW  listens SlotDraftCreatedEvent → SlotTicketSyncService
│   └── published/
│       ├── TicketSyncCompletedEvent.java          ← NEW  (slotId, ebEventId)
│       └── TicketSyncFailedEvent.java             ← NEW  (slotId, ebEventId, reason)
│
└── service/
    └── SlotTicketSyncService.java                 ← NEW  orchestrates D.2-D.5:
                                                          EbTicketService.createTicketClasses()
                                                          EbTicketService.createInventoryTiers()
                                                          EbCapacityService.updateCapacityTier() [GA]
                                                          EbTicketService.copySeatMap() [Reserved]
```

---

#### `admin/`

```
admin/src/main/java/com/eventplatform/admin/
│
├── api/controller/
│   └── OrgEventbriteController.java               ← NEW  POST /admin/orgs/{orgId}/eventbrite/connect
│                                                          GET  /admin/orgs/{orgId}/eventbrite/callback
│
└── service/
    └── OrgOAuthService.java                       ← NEW  code exchange → delegate to EbTokenStore in shared/
```

---

### Spring Event Payload Reference

| Event | Publisher | Listener(s) | Fields (primitives / IDs / enums only) |
|---|---|---|---|
| `SlotDraftCreatedEvent` | `scheduling` | `booking-inventory` | `slotId`, `ebEventId`, `pricingTierIds` (List\<Long\>), `seatingMode`, `orgId`, `sourceSeatMapId` (nullable) |
| `TicketSyncCompletedEvent` | `booking-inventory` | `scheduling` | `slotId`, `ebEventId` |
| `TicketSyncFailedEvent` | `booking-inventory` | `scheduling` | `slotId`, `ebEventId`, `reason` |
| `SlotSyncFailedEvent` | `scheduling` | *(admin notification — future)* | `slotId`, `reason` |
| `VenueDriftDetectedEvent` | `discovery-catalog` | *(admin notification — future)* | `venueId`, `ebVenueId`, `driftDescription` |
STAGE3_EOF

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && tail -5 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Stage 3 is now in SPEC_2.md — file grew from 410 → 632 lines. Here's a summary of what was written:

**Module Ownership table** — 5 modules with their concerns clearly assigned.

**Cross-Module Interactions table** — 9 interactions, each specifying From / To / mechanism (REST GET or Spring Event) / purpose. Hard Rules #1, #2, #4 explicitly noted.

**Feature File Structure** — 5 modules covered:

| Module | New Files | Modified Files |
|---|---|---|
| shared | `OrganizationAuth`, `OrganizationAuthRepository`, `EbTokenStore`, `EbRetryPolicy`, `EbAuthException`, 2 DTOs, `SeatingMode` enum | `EbVenueService`, `DefaultEbVenueService` |
| discovery-catalog | `VenueService`, `VenueReconciliationJob`, `VenueMapper`, `CreateVenueRequest`, `UpdateVenueRequest`, `VenueSyncStatus`, `VenueDriftDetectedEvent` | `Venue`, `VenueCatalogController`, `VenueResponse`, `VenueRepository` |
| `scheduling` | 25 new files across domain, api, service, repository, mapper, statemachine, event, exception | — |
| `booking-inventory` | `SlotDraftCreatedListener`, `TicketSyncCompletedEvent`, `TicketSyncFailedEvent`, `SlotTicketSyncService` | — |
| `admin/` | `OrgEventbriteController`, `OrgOAuthService` | — |

**Spring Event Payload Reference table** — all 5 events with publisher, listener, and exact fields.

User: Now lets move to the stage 4 try to map the domains to DB schema also check the existing DB for any reference keys, There is Org table already built look into in the DB. List the outline ill verify it

GitHub Copilot: Summarized conversation historyLet me check the existing DB migrations and the current SPEC_2.md state in parallel. 

Ran terminal command:  find /home/dell/Multi-City-Event-Booking -path "*/db/migration/*.sql" | sort && echo "---" && find /home/dell/Multi-City-Event-Booking -name "*.sql" | sort

Ran terminal command:  cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/V0__create_organization_table.sql && echo "===" && cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/V1__create_cities_table.sql && echo "===" && cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/V2__create_venues_table.sql && echo "===" && cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/V3__create_event_catalog_table.sql && echo "===" && cat /home/dell/Multi-City-Event-Booking/app/src/main/resources/db/migration/V4__create_webhook_config_table.sql

Good, I have the full picture. Now let me check the current end of SPEC_2.md: 

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && tail -10 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Here's what's already in the DB, cross-referenced with the Stage 2 domain model:

---

**Existing tables (V0–V4) — FK anchors for Flow 2:**

| Table | Key FK columns available |
|---|---|
| `organization` | `id` PK, `eventbrite_org_id` UNIQUE |
| `city` | `id` PK, `organization_id` → organization |
| `venue` | `id` PK, `organization_id` → organization, `city_id` → city, `eventbrite_venue_id` |
| `event_catalog` | `id` PK, `organization_id`, `city_id`, `venue_id` |
| `webhook_config` | `id` PK, `organization_id` UNIQUE |

---

## Stage 4 — DB Schema Outline

**5 operations across 5 migrations (V5–V9):**

---

### V5 — ALTER venue *(add scheduling fields — new migration, never touch V2)*
**Table: `venue`** — adds missing fields for Flow 2
- `capacity`
- `seating_mode` — GA / RESERVED
- `sync_status` — SYNCED / PENDING_SYNC / SYNC_FAILED
- `last_sync_error` (nullable)
- `last_attempted_at` (nullable)

---

### V6 — CREATE `organization_auth` *(owned by `shared/eventbrite/`)*
- `id` PK
- `organization_id` → FK → `organization.id` (UNIQUE — one token per org)
- `eb_organization_id` (the EB org ID string)
- `access_token_encrypted`
- `refresh_token_encrypted` (nullable)
- `expires_at` (nullable)
- `status` — CONNECTED / EXPIRED / REVOKED / PENDING
- `created_at`, `updated_at`

---

### V7 — CREATE `show_slot` *(owned by `scheduling`)*
- `id` PK
- `organization_id` → `organization.id`
- `venue_id` → `venue.id`
- `title`
- `description` (nullable)
- `start_time`, `end_time`
- `seating_mode` — GA / RESERVED
- `capacity`
- `source_seatmap_id` (nullable — EB seatmap ID for Reserved copy)
- `is_recurring`
- `recurrence_rule` (nullable — RFC 5545 RRULE string)
- `status` — DRAFT / PENDING_SYNC / ACTIVE / CANCELLED
- `eb_event_id` (nullable, UNIQUE)
- `eb_series_id` (nullable — only set when `is_recurring = true`)
- `sync_attempt_count`
- `last_sync_error` (nullable)
- `last_attempted_at` (nullable)
- `created_at`, `updated_at`

---

### V8 — CREATE `show_slot_occurrence` *(owned by `scheduling`)*
- `id` PK
- `parent_slot_id` → FK → `show_slot.id`
- `occurrence_index` — position in the series (1, 2, 3…)
- `start_time`, `end_time`
- `status` — ACTIVE / CANCELLED
- `eb_event_id` (nullable, UNIQUE — EB-assigned occurrence event ID)
- `sync_attempt_count`
- `last_sync_error` (nullable)
- `last_attempted_at` (nullable)
- `created_at`

---

### V9 — CREATE `show_slot_pricing_tier` *(owned by `scheduling`)*
- `id` PK
- `slot_id` → FK → `show_slot.id`
- `name`
- `price_amount` (DECIMAL — stored with currency, maps to `Money` value object internally)
- `currency`
- `quota` — max tickets for this tier
- `tier_type` — FREE / PAID / DONATION
- `eb_ticket_class_id` (nullable — returned by D.2)
- `eb_inventory_tier_id` (nullable — returned by D.3)
- `created_at`, `updated_at`

---

**Reference key map — what each new table anchors to:**

| New Table | FK to existing | Notes |
|---|---|---|
| `organization_auth` | `organization.id` | One row per org — UNIQUE constraint |
| `show_slot` | `organization.id`, `venue.id` | `venue_id` is scalar ID — no cross-module JPA import |
| `show_slot_occurrence` | `show_slot.id` | Child records — cascade delete on slot delete |
| `show_slot_pricing_tier` | `show_slot.id` | Cascade delete on slot delete |

---

Does this look complete? Anything to add, remove, or change before I write this section in full?

User: Looks good update the outline with full section in the SPEC_2.md

GitHub Copilot: Ran terminal command:  cat >> /home/dell/Multi-City-Event-Booking/SPEC_2.md << 'STAGE4_EOF'

---

## Stage 4 — DB Schema

> **Flyway convention:** Never modify existing migrations. New fields on existing tables go in a new `ALTER TABLE` migration. Next version in sequence is `V5`.

---

### Existing Tables Referenced (V0–V4)

| Table | Owner | Key columns used as FK anchors |
|---|---|---|
| `organization` | `identity` | `id` PK, `eventbrite_org_id` VARCHAR UNIQUE |
| `city` | `discovery-catalog` | `id` PK, `organization_id` → organization |
| `venue` | `discovery-catalog` | `id` PK, `organization_id`, `city_id`, `eventbrite_venue_id` |
| `event_catalog` | `discovery-catalog` | `id` PK, `organization_id`, `city_id`, `venue_id` |
| `webhook_config` | `discovery-catalog` | `id` PK, `organization_id` UNIQUE |

---

### V5 — `ALTER TABLE venue` *(discovery-catalog — add scheduling fields)*

> `venue` exists from V2. These columns are missing and required for Flow 2.

```sql
-- owner: discovery-catalog module
ALTER TABLE venue
    ADD COLUMN capacity      INTEGER,
    ADD COLUMN seating_mode  VARCHAR(20) DEFAULT 'GA'
                             CONSTRAINT chk_venue_seating_mode CHECK (seating_mode IN ('GA', 'RESERVED')),
    ADD COLUMN sync_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING_SYNC'
                             CONSTRAINT chk_venue_sync_status CHECK (sync_status IN ('PENDING_SYNC', 'SYNCED', 'DRIFT_FLAGGED')),
    ADD COLUMN last_sync_error     TEXT,
    ADD COLUMN last_attempted_at   TIMESTAMP;

CREATE INDEX idx_venue_sync_status ON venue(sync_status);
```

---

### V6 — `CREATE TABLE organization_auth` *(shared/eventbrite/ — OAuth token store)*

```sql
-- owner: shared/eventbrite/ — never accessed directly by any application module
CREATE TYPE org_auth_status AS ENUM ('PENDING', 'CONNECTED', 'TOKEN_EXPIRED', 'REVOKED');

CREATE TABLE organization_auth (
    id                        BIGSERIAL PRIMARY KEY,
    organization_id           BIGINT       NOT NULL UNIQUE,
    eb_organization_id        VARCHAR(255) NOT NULL,
    access_token_encrypted    TEXT         NOT NULL,
    refresh_token_encrypted   TEXT,
    expires_at                TIMESTAMP,
    status                    org_auth_status NOT NULL DEFAULT 'PENDING',
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_org_auth_org FOREIGN KEY (organization_id)
        REFERENCES organization(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_org_auth_org      ON organization_auth(organization_id);
CREATE INDEX        idx_org_auth_status  ON organization_auth(status);
CREATE INDEX        idx_org_auth_eb_org  ON organization_auth(eb_organization_id);
```

**Notes:**
- One row per org — enforced by `UNIQUE` on `organization_id`.
- `access_token_encrypted` / `refresh_token_encrypted` — stored encrypted at application layer (AES-256). Columns are `TEXT` because ciphertext length is unpredictable.
- `refresh_token_encrypted` is nullable — not all OAuth grants issue a refresh token.
- `expires_at` is nullable — some tokens are long-lived without expiry.
- No module outside `shared/eventbrite/` may read or write this table.

---

### V7 — `CREATE TABLE show_slot` *(scheduling — primary aggregate)*

```sql
-- owner: scheduling module
CREATE TYPE show_slot_status AS ENUM ('DRAFT', 'PENDING_SYNC', 'ACTIVE', 'CANCELLED');
CREATE TYPE seating_mode     AS ENUM ('GA', 'RESERVED');

CREATE TABLE show_slot (
    id                    BIGSERIAL    PRIMARY KEY,
    organization_id       BIGINT       NOT NULL,
    venue_id              BIGINT       NOT NULL,
    title                 VARCHAR(500) NOT NULL,
    description           TEXT,
    start_time            TIMESTAMPTZ  NOT NULL,
    end_time              TIMESTAMPTZ  NOT NULL,
    seating_mode          seating_mode NOT NULL,
    capacity              INTEGER      NOT NULL,
    source_seatmap_id     VARCHAR(255),
    is_recurring          BOOLEAN      NOT NULL DEFAULT FALSE,
    recurrence_rule       TEXT,
    status                show_slot_status NOT NULL DEFAULT 'DRAFT',
    eb_event_id           VARCHAR(255) UNIQUE,
    eb_series_id          VARCHAR(255),
    sync_attempt_count    INTEGER      NOT NULL DEFAULT 0,
    last_sync_error       TEXT,
    last_attempted_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_show_slot_org   FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_show_slot_venue FOREIGN KEY (venue_id)        REFERENCES venue(id)        ON DELETE RESTRICT,
    CONSTRAINT chk_slot_times     CHECK (end_time > start_time),
    CONSTRAINT chk_slot_capacity  CHECK (capacity > 0),
    CONSTRAINT chk_recurring_rule CHECK (
        (is_recurring = FALSE AND recurrence_rule IS NULL) OR
        (is_recurring = TRUE)
    )
);

CREATE INDEX idx_show_slot_org_id     ON show_slot(organization_id);
CREATE INDEX idx_show_slot_venue_id   ON show_slot(venue_id);
CREATE INDEX idx_show_slot_status     ON show_slot(status);
CREATE INDEX idx_show_slot_eb_event   ON show_slot(eb_event_id);
-- Conflict detection query index: venue + time range overlap
CREATE INDEX idx_show_slot_venue_time ON show_slot(venue_id, start_time, end_time);
-- Reconciliation job index: ACTIVE slots with an eb_event_id
CREATE INDEX idx_show_slot_active_eb  ON show_slot(status, eb_event_id) WHERE status = 'ACTIVE';
```

**Notes:**
- `venue_id` is a scalar FK to `venue.id` within the same DB — allowed because both tables exist. No `@ManyToOne` **JPA import** across module packages.
- `TIMESTAMPTZ` (timestamp with time zone) used for all time fields — venues span cities and timezones.
- `ON DELETE RESTRICT` on `venue_id` — a venue with active slots cannot be deleted without first cancelling slots.
- `source_seatmap_id` stores the Eventbrite seatmap template ID for `RESERVED` slots — nullable, string.
- `eb_series_id` nullable — only set when `is_recurring = TRUE` and EB schedule creation succeeds.
- `sync_attempt_count` reset to 0 when slot reaches `ACTIVE`.

---

### V8 — `CREATE TABLE show_slot_occurrence` *(scheduling — recurring child records)*

```sql
-- owner: scheduling module
CREATE TABLE show_slot_occurrence (
    id                    BIGSERIAL   PRIMARY KEY,
    parent_slot_id        BIGINT      NOT NULL,
    occurrence_index      INTEGER     NOT NULL,
    start_time            TIMESTAMPTZ NOT NULL,
    end_time              TIMESTAMPTZ NOT NULL,
    status                show_slot_status NOT NULL DEFAULT 'ACTIVE',
    eb_event_id           VARCHAR(255) UNIQUE,
    sync_attempt_count    INTEGER     NOT NULL DEFAULT 0,
    last_sync_error       TEXT,
    last_attempted_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_occurrence_slot CHECK (end_time > start_time),
    CONSTRAINT fk_slot_occurrence FOREIGN KEY (parent_slot_id)
        REFERENCES show_slot(id) ON DELETE CASCADE,
    CONSTRAINT uk_occurrence_slot_index UNIQUE (parent_slot_id, occurrence_index)
);

CREATE INDEX idx_occurrence_parent   ON show_slot_occurrence(parent_slot_id);
CREATE INDEX idx_occurrence_status   ON show_slot_occurrence(status);
CREATE INDEX idx_occurrence_eb_event ON show_slot_occurrence(eb_event_id);
```

**Notes:**
- `ON DELETE CASCADE` — if the parent `show_slot` is deleted, all occurrences are deleted.
- `UNIQUE (parent_slot_id, occurrence_index)` — prevents duplicate occurrence positions.
- `status` is independent per occurrence — not mirrored from parent slot.
- `eb_event_id` UNIQUE — each occurrence has its own EB-assigned event ID.

---

### V9 — `CREATE TABLE show_slot_pricing_tier` *(scheduling — ticket tier definitions)*

```sql
-- owner: scheduling module
CREATE TYPE tier_type AS ENUM ('FREE', 'PAID', 'DONATION');

CREATE TABLE show_slot_pricing_tier (
    id                    BIGSERIAL    PRIMARY KEY,
    slot_id               BIGINT       NOT NULL,
    name                  VARCHAR(255) NOT NULL,
    price_amount          DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    currency              VARCHAR(3)   NOT NULL DEFAULT 'INR',
    quota                 INTEGER      NOT NULL,
    tier_type             tier_type    NOT NULL DEFAULT 'PAID',
    eb_ticket_class_id    VARCHAR(255),
    eb_inventory_tier_id  VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_pricing_tier_slot FOREIGN KEY (slot_id)
        REFERENCES show_slot(id) ON DELETE CASCADE,
    CONSTRAINT chk_quota_positive   CHECK (quota > 0),
    CONSTRAINT chk_price_free       CHECK (
        (tier_type = 'FREE' AND price_amount = 0.00) OR
        (tier_type != 'FREE')
    )
);

CREATE INDEX idx_pricing_tier_slot   ON show_slot_pricing_tier(slot_id);
CREATE INDEX idx_pricing_tier_eb     ON show_slot_pricing_tier(eb_ticket_class_id);
```

**Notes:**
- `ON DELETE CASCADE` — deleting a `show_slot` removes all its pricing tiers.
- `eb_ticket_class_id` — populated in Step D.2 when `EbTicketService.createTicketClasses()` returns.
- `eb_inventory_tier_id` — populated in Step D.3 when `EbTicketService.createInventoryTiers()` returns.
- `DECIMAL(12,2)` — supports up to ₹9,999,999,999.99; adequate for INR event pricing.
- `chk_price_free` — enforces that `FREE` tier must have `price_amount = 0`. `PAID` and `DONATION` tiers can have any non-negative amount.
- `currency` — ISO 4217 3-char code; NOT a FK — kept as string for flexibility.

---

### Migration Sequence Summary

| Version | Operation | Table | Owner |
|---|---|---|---|
| V0 | CREATE | `organization` | `identity` |
| V1 | CREATE | `city` | `discovery-catalog` |
| V2 | CREATE | `venue` | `discovery-catalog` |
| V3 | CREATE | `event_catalog` | `discovery-catalog` |
| V4 | CREATE | `webhook_config` | `discovery-catalog` |
| **V5** | **ALTER** | **`venue`** | **`discovery-catalog`** |
| **V6** | **CREATE** | **`organization_auth`** | **`shared/eventbrite/`** |
| **V7** | **CREATE** | **`show_slot`** | **`scheduling`** |
| **V8** | **CREATE** | **`show_slot_occurrence`** | **`scheduling`** |
| **V9** | **CREATE** | **`show_slot_pricing_tier`** | **`scheduling`** |

---

### Entity → Table Mapping

| Domain Entity | DB Table | Module |
|---|---|---|
| `OrganizationAuth` | `organization_auth` | `shared/eventbrite/` |
| `Venue` (extended) | `venue` | `discovery-catalog` |
| `ShowSlot` | `show_slot` | `scheduling` |
| `ShowSlotOccurrence` | `show_slot_occurrence` | `scheduling` |
| `ShowSlotPricingTier` | `show_slot_pricing_tier` | `scheduling` |

STAGE4_EOF
echo "Exit: $?"

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && tail -8 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Stage 4 written. File grew from 632 → 872 lines (240 lines added).

**What was written (V5–V9):**
- **V5** — `ALTER TABLE venue` — adds `capacity`, `seating_mode`, `sync_status`, `last_sync_error`, `last_attempted_at`
- **V6** — `CREATE TABLE organization_auth` — OAuth token store with encrypted fields, `org_auth_status` enum, UNIQUE on `organization_id`
- **V7** — `CREATE TABLE show_slot` — full aggregate with `TIMESTAMPTZ`, conflict detection index on `(venue_id, start_time, end_time)`, partial index for reconciliation job, all 5 CHECK constraints
- **V8** — `CREATE TABLE show_slot_occurrence` — child records, CASCADE delete, UNIQUE `(parent_slot_id, occurrence_index)`
- **V9** — `CREATE TABLE show_slot_pricing_tier` — `DECIMAL(12,2)`, `FREE` tier price enforcement constraint, both `eb_ticket_class_id` and `eb_inventory_tier_id` columns

Ready for Stage 5 — API Endpoints outline whenever you are.

User: Next stage 5 give an Outline of API Endpoints 

GitHub Copilot: ## Stage 5 — API Endpoints Outline

Per SPEC_GENERATOR.md format: `METHOD /path` — one-line description.

---

### `admin/` — OAuth Onboarding

| Method | Path | Description |
|---|---|---|
| `POST` | `/admin/orgs/{orgId}/eventbrite/connect` | Initiate OAuth — returns EB authorization redirect URL |
| `GET` | `/admin/orgs/{orgId}/eventbrite/callback` | OAuth callback — exchange `code` for token, store in `organization_auth` |
| `GET` | `/admin/orgs/{orgId}/eventbrite/status` | Get current token status (`PENDING` / `CONNECTED` / `TOKEN_EXPIRED` / `REVOKED`) |
| `DELETE` | `/admin/orgs/{orgId}/eventbrite/disconnect` | Revoke token, mark status `REVOKED` |

---

### discovery-catalog — Venue Management

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/discovery-catalog/venues` | Create venue — persists internally, calls `EbVenueService.createVenue()` |
| `PUT` | `/api/discovery-catalog/venues/{id}` | Update venue — persists internally, calls `EbVenueService.updateVenue()` |
| `GET` | `/api/discovery-catalog/venues/{id}` | Get venue by ID — used by `scheduling` REST call before conflict check |
| `GET` | `/api/discovery-catalog/venues?cityId={cityId}&orgId={orgId}` | List venues by city — used by conflict alternative suggestion |
| `POST` | `/api/discovery-catalog/venues/{id}/sync` | *(COULD)* Manually re-trigger EB venue sync |
| `GET` | `/api/discovery-catalog/venues/drift` | List venues flagged `DRIFT_FLAGGED` — read by `admin/` dashboard |

---

### `scheduling/` — Show Slot Lifecycle

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/scheduling/slots` | Create slot — conflict check, persist as `DRAFT` |
| `POST` | `/api/scheduling/slots/{id}/submit` | Submit slot — transition `DRAFT` → `PENDING_SYNC`, trigger EB sync chain |
| `GET` | `/api/scheduling/slots/{id}` | Get slot details including sync status and error |
| `GET` | `/api/scheduling/slots?orgId={orgId}&status={status}&venueId={venueId}` | List slots with filters |
| `PUT` | `/api/scheduling/slots/{id}` | Update slot details — triggers EB update if `ACTIVE` |
| `POST` | `/api/scheduling/slots/{id}/cancel` | Cancel slot — transitions to `CANCELLED`, calls EB cancel |
| `POST` | `/api/scheduling/slots/{id}/retry-sync` | *(COULD)* Manually retry EB sync for `PENDING_SYNC` slot |
| `GET` | `/api/scheduling/slots/{id}/occurrences` | List occurrences for a recurring slot |
| `GET` | `/api/scheduling/slots/mismatches` | List `ACTIVE` slots with EB drift detected — read by `admin/` dashboard |

---

### `admin/` — Dashboard Aggregation Reads

| Method | Path | Description |
|---|---|---|
| `GET` | `/admin/scheduling/slots/flagged` | Aggregate: slots in `PENDING_SYNC` with `sync_attempt_count > 0` or EB mismatches |
| `GET` | `/admin/discovery-catalog/venues/flagged` | Aggregate: venues with `sync_status = DRIFT_FLAGGED` |

---

**Total: 16 endpoints across 3 surfaces** (admin OAuth × 4, venues × 6, slots × 9 — 3 COULD, admin reads × 2)

**Notes:**
- No separate `POST /api/scheduling/slots/{id}/occurrences` endpoint — occurrences are created automatically by `EbScheduleService` callback, not by admin directly.
- Conflict alternatives are returned inline in the `POST /api/scheduling/slots` `409 Conflict` response body — no separate check endpoint needed.
- Internal REST calls (`scheduling` → discovery-catalog) reuse the existing `GET /venues/{id}` and `GET /venues?cityId=` endpoints — no separate internal API surface.

---

Does this look complete? Anything to add, remove, or change before I write this section in full?

User: Before we go to API few fixes first the Flow 1 had few API endpoints and the Domain MAPping nad buisness logic of caching and syncing how will they affect the current migrations ?

GitHub Copilot: Good catch — let me analyze the interactions before we touch Stage 5. Let me read the Flow 1 domain entities and existing venue code to map the exact overlaps. 

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/discovery-catalog/src -name "*.java" | sort && echo "===" && find /home/dell/Multi-City-Event-Booking/shared/src -name "*.java" | sort

Ran terminal command:  cat /home/dell/.config/Code/User/workspaceStorage/d0c32092166abb554a6d3bb3dc965310/GitHub.copilot-chat/chat-session-resources/0f59d1d5-313f-4d75-a970-51891e9e3a49/toolu_bdrk_017EvtSaXNbhxj1SJfmspkAw__vscode-1772525234518/content.txt

Ran terminal command:  find /home/dell/Multi-City-Event-Booking/discovery-catalog/src -name "*.java" | head -60

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java)

Searched for files matching `**/discovery-catalog/src/**/*.java`, 20 matches

Searched codebase for "Venue entity domain class fields JPA discovery-catalog", 18 results

Now let me check the existing VenueCatalogController to see what endpoints are already there: 

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/api/controller/VenueCatalogController.java)

Good picture now. Here's the full impact analysis:

---

## Flow 1 → Flow 2 Migration Impact Analysis

---

### Issue 1 — V5 Missing Data Backfill (Migration Correctness)

V5 adds `sync_status NOT NULL DEFAULT 'PENDING_SYNC'`. That means every **existing venue row** (including ones that already have `eventbrite_venue_id` set) silently becomes `PENDING_SYNC`.

**Fix needed in V5:**
```sql
-- After the ALTER TABLE, add:
UPDATE venue SET sync_status = 'SYNCED' WHERE eventbrite_venue_id IS NOT NULL;
```
Without this, the reconciliation job would try to re-sync already-synced venues on first run.

---

### Issue 2 — Naming Inconsistency: `eb_venue_id` vs `eventbrite_venue_id`

The Stage 2 domain model uses the shorthand `eb_venue_id`. But **V2 migration, Venue.java, and VenueResponse.java all use `eventbrite_venue_id` / `eventbriteVenueId`**. No rename is needed — Stage 2 docs just used a shorthand. The real field stays `eventbriteVenueId` to match V2.

---

### Issue 3 — Venue.java Entity / DTO / Mapper are Incomplete for Flow 2

Current Venue.java has only 7 fields. V5 adds 5 new columns — but the entity, response DTO, and mapper don't map them yet. This means `scheduling`'s REST call to `GET /api/v1/catalog/venues/{id}` would return a `VenueResponse` **missing `capacity` and `seatingMode`** — both are required for conflict detection.

**3 code changes required (not migrations):**
| File | Change |
|---|---|
| Venue.java | Add `capacity`, `seatingMode`, `syncStatus`, `lastSyncError`, `lastAttemptedAt` fields + `VenueSyncStatus` enum |
| VenueResponse.java | Add `capacity`, `seatingMode`, `syncStatus` — scheduling needs capacity + seatingMode |
| VenueMapper.java | Add mapping for new fields |

---

### Issue 4 — `VenueCatalogController` Base Path Mismatch

Flow 1's controller uses `@RequestMapping("/api/v1/catalog")`. Stage 3 of this spec wrote paths like `/api/discovery-catalog/venues`. These are different prefixes.

**Decision needed before Stage 5:**
- Read endpoints (existing) → `/api/v1/catalog/venues` ✅
- New write endpoints (Flow 2 creates/updates) → should also be `/api/v1/catalog/venues` to stay consistent, or split into a separate admin-scoped prefix like `/api/v1/admin/catalog/venues`

My recommendation: **admin write operations go under `/api/v1/admin/catalog/venues`** (protected by `ROLE_ADMIN`), read operations stay under `/api/v1/catalog/venues`. This keeps public reads clean and admin writes gated.

---

### Issue 5 — `VenueCatalogService` Missing Methods

Current `VenueCatalogService` only has `listVenues()`. Missing for Flow 2:
- `getVenueById(Long id)` — needed for `scheduling`'s REST call pre-validation
- `getVenuesByCityId(Long cityId, Long orgId)` — needed for conflict alternative suggestions
- `createVenue()` / `updateVenue()` — admin write operations

Also, `listVenues()` currently uses a `defaultOrgId` from Spring config. The write endpoints and scheduling REST calls will need actual `orgId` — either from JWT claim or passed as request param.

---

### Issue 6 — Cache Chain (No Issue, Natural Flow)

When a `show_slot` becomes `ACTIVE` the chain is:
```
ShowSlot ACTIVE → EbEventSyncService.publishEvent() → EB publishes
  → EB fires event.published webhook
    → EbWebhookController → EbWebhookDispatcher
      → EventbriteWebhookEventListener (Flow 1)
        → event_catalog upserted (show_slot.eb_event_id = event_catalog.eventbrite_event_id)
          → EventCatalogUpdatedEvent published
            → Redis cache (org_id, city_id) invalidated ✅
```
**No changes needed.** Flow 1's webhook pipeline handles this automatically.

---

### Issue 7 — Stage 5 API Paths Need Correction

The Stage 5 outline I gave used `/api/discovery-catalog/venues` — this must be corrected to match the actual base path `/api/v1/catalog/venues` (reads) and `/api/v1/admin/catalog/venues` (writes) before writing the full section.

---

### Summary of Fixes Before Stage 5

| # | Fix | Where |
|---|---|---|
| 1 | Add `UPDATE venue SET sync_status = 'SYNCED' WHERE eventbrite_venue_id IS NOT NULL` | V5 migration |
| 2 | `eb_venue_id` → use `eventbriteVenueId` in all Stage 2/3 references | SPEC_2.md note |
| 3 | Add 5 new fields to Venue.java + VenueResponse.java + VenueMapper.java | Code (Flow 2 implementation) |
| 4 | Decide path prefix: `/api/v1/catalog/venues` reads vs `/api/v1/admin/catalog/venues` writes | Stage 5 outline |
| 5 | Add `getVenueById()` + admin-scoped write methods to `VenueCatalogService` | Code (Flow 2 implementation) |

---

Shall I apply fix #1 to the V5 SQL in SPEC_2.md now, and then proceed with Stage 5 using the corrected paths?

User: yes apply the fix#1

GitHub Copilot: Searched for text `` V5 — `ALTER TABLE venue` `` (`**/SPEC_2.md`), 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#654-654), lines 654 to 680

Replacing 13 lines with 20 lines in [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)


```

```
Done. Let me verify: 

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#657-657), lines 657 to 682

Fix applied. V5 now reads correctly — `ALTER TABLE` adds the column with `DEFAULT 'PENDING_SYNC'`, the backfill immediately updates any existing row that already has an `eventbrite_venue_id` to `SYNCED`, then the index is created.

**Remaining issues to keep in mind for Stage 5 (no file changes needed now):**

| # | What | When it applies |
|---|---|---|
| 2 | Use `eventbriteVenueId` (not `eb_venue_id`) in all code | Implementation |
| 3 | Venue.java + VenueResponse.java + VenueMapper.java need 5 new fields | Implementation |
| 4 | Paths: reads `/api/v1/catalog/venues`, admin writes `/api/v1/admin/catalog/venues` | Stage 5 — I'll use correct paths |
| 5 | `getVenueById()` + write methods added to `VenueCatalogService` | Implementation |

Ready to proceed with the corrected Stage 5 API outline whenever you are.

Made changes.

User: Also there are logic of syncing nad cahing of the data how will that impact the Flow -2, like redis cahcing, we also need to implement how we will update the internal update of the events and the venues when it is updated internally and the through EB sync as well 

GitHub Copilot: Let me read the existing sync and cache services before answering.  

Searched for files matching `**/discovery-catalog/src/main/java/**/*Cache*.java`, 1 match

Searched for files matching `**/discovery-catalog/src/main/java/**/*Sync*.java`, 4 matches

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/service/cache/EventCatalogSnapshotCache.java)

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/service/EventCatalogSyncService.java#1-1), lines 1 to 80

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/service/EventCatalogSyncService.java#80-80), lines 80 to 154

Searched for files matching `**/discovery-catalog/src/main/java/**/*Listener*.java`, 3 matches

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/event/listener/EventbriteWebhookEventListener.java#1-1), lines 1 to 80

Read [](file:///home/dell/Multi-City-Event-Booking/discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/event/listener/EventCatalogUpdatedEventListener.java)

Now I have everything needed. Here's the full interaction map:

---

## Flow 1 Cache & Sync vs Flow 2 — Full Impact Analysis

---

### What Flow 1 Built (the live pipeline)

```
EB Webhook arrives
  → EbWebhookController (shared/)
    → EbWebhookDispatcher → EbWebhookReceivedEvent (Spring Event)
      → EventbriteWebhookEventListener (discovery-catalog)
          → finds event_catalog row by eb_event_id
          → invalidates Redis cache key: catalog:snapshot:{orgId}:{cityId}
          → publishes EventCatalogUpdatedEvent

Redis cache key: "catalog:snapshot:{orgId}:{cityId}" — TTL 1 hour
EventCatalogSyncService.sync(orgId, cityId, venueId) — pulls all events from EB, upserts event_catalog
```

**Cache scope:** `(org_id, city_id)` only. Venue list is NOT cached. Only `event_catalog` is cached.

---

### Problem 1 — Slot becomes ACTIVE but `event_catalog` is empty

**What happens today:**
1. Flow 2: `show_slot` → ACTIVE → `EbEventSyncService.publishEvent()` → EB event is LIVE
2. EB fires `event.published` webhook → `EventbriteWebhookEventListener.onWebhook()`
3. Listener calls `eventCatalogRepository.findByEventbriteEventId(eb_event_id)` → **returns empty**
4. Listener logs `"Webhook event not found in catalog"` and exits
5. Redis cache is **NOT invalidated**, `event_catalog` has **no entry** for this event
6. Users querying the catalog via Flow 1 see nothing — until the next scheduled sync (up to 4 hours later)

**Root cause:** `EventbriteWebhookEventListener` only invalidates existing entries — it never creates new ones. `EventCatalogSyncService.sync()` is the only thing that creates `event_catalog` rows.

**Fix required:**
`scheduling` must publish a new `ShowSlotActivatedEvent` when a slot reaches `ACTIVE`. discovery-catalog listens and calls `EventCatalogSyncService.sync(orgId, cityId, venueId)` to immediately pull the new event into `event_catalog` and invalidate the cache.

```
ShowSlot → ACTIVE
  → scheduling publishes ShowSlotActivatedEvent(slotId, ebEventId, orgId, venueId, cityId)
    → discovery-catalog SlotActivatedListener
        → EventCatalogSyncService.sync(orgId, cityId, venueId)
            → event_catalog row created
            → cache invalidated for (orgId, cityId) ✅
```

**New event needed in Stage 3 file structure:**
- `scheduling/event/published/ShowSlotActivatedEvent.java` — add to spec
- `discovery-catalog/event/listener/SlotActivatedListener.java` — add to spec

---

### Problem 2 — Slot CANCELLED but `event_catalog` shows stale PUBLISHED

**What happens today:**
1. Flow 2: `show_slot` → CANCELLED → `EbEventSyncService.cancelEvent()` → EB event cancelled
2. EB fires `event.cancelled` webhook → `EventbriteWebhookEventListener`
3. Listener finds the `event_catalog` row (it exists now) → **invalidates cache only**
4. **Does NOT update `event_catalog.state` to `CANCELLED`**
5. Next read hits DB → still sees `PUBLISHED` — until next scheduled sync

**Fix required:**
`EventbriteWebhookEventListener` must also handle the `action` field from the webhook payload. When `action = event.cancelled`, it should update `event_catalog.state = CANCELLED` directly (not just invalidate cache). Same for `event.unpublished`.

OR — simpler, consistent with internal-first principle — `scheduling` publishes `ShowSlotCancelledEvent(slotId, ebEventId, orgId, venueId, cityId)`, and discovery-catalog listens to update the `event_catalog` row and invalidate cache immediately, without waiting for the EB webhook roundtrip.

```
ShowSlot → CANCELLED (internal)
  → scheduling publishes ShowSlotCancelledEvent(slotId, ebEventId, orgId, venueId, cityId)
    → discovery-catalog SlotCancelledListener
        → event_catalog.state = CANCELLED (if row exists)
        → cache invalidated for (orgId, cityId) ✅
  → (separately) EbEventSyncService.cancelEvent() called — EB side cancelled
```

**New event needed:**
- `scheduling/event/published/ShowSlotCancelledEvent.java`
- `discovery-catalog/event/listener/SlotCancelledListener.java`

---

### Problem 3 — `cityId` is not on `ShowSlot`

`ShowSlot` stores `venue_id` but NOT `city_id`. The cache key is `(org_id, city_id)`. To invalidate the right cache bucket from a `ShowSlotActivatedEvent`, the listener needs `city_id`.

**Two options:**
- **Option A:** Add `city_id` to `ShowSlot` table (denormalized scalar — acceptable since `scheduling` reads venue from discovery-catalog at creation time and knows the `city_id` at that point)
- **Option B:** discovery-catalog listener resolves `city_id` from `venue_id` using its own `VenueRepository`

**Recommendation: Option A** — add `city_id` to `show_slot`. It's just a scalar Long, not a JPA FK import. Venues don't change cities. It eliminates a DB lookup in the listener hot path.

**Migration fix:** Add `city_id BIGINT NOT NULL` to V7 (`show_slot`). References `city.id`.

---

### Problem 4 — Venue webhooks are silently ignored

EB fires `venue.updated` webhooks when a venue changes externally. The current `EventbriteWebhookEventListener` looks for `event_id` in the payload — a `venue.updated` payload has no `event_id`, so it logs a warning and exits.

**Impact:** External venue drift detected only by `VenueReconciliationJob` (periodic). No immediate cache or DB update happens on EB-side venue change.

**This is acceptable for Flow 2** — venue data is not cached, and drift is flagged by the reconciliation job. No fix needed now. Document as known behaviour.

---

### Problem 5 — Venue UPDATE (internal) — no cache impact, but `event_catalog` denormalized data

`event_catalog` stores `venue_id` as a scalar. It does NOT store venue name or address. The catalog reads join through `venue` table at query time. So an internal venue update automatically reflects in catalog reads. **No cache fix needed** — but the cache **must be invalidated** when a venue update changes something users see in event listings (e.g. venue name shown in event card).

**Fix:** When `VenueService.updateVenue()` runs in discovery-catalog, after persisting + calling EB, it should invalidate all cache buckets for that venue's `city_id`:
```java
snapshotCache.invalidate(orgId, venue.getCityId());
```
This is internal to discovery-catalog — no new Spring Event needed.

---

### Summary — Changes Required to SPEC_2.md

| # | Change | Where |
|---|---|---|
| 1 | Add `city_id` column to `show_slot` (V7) | SPEC_2.md Stage 4 — V7 migration |
| 2 | Add `ShowSlotActivatedEvent` to event list | SPEC_2.md Stage 3 — Spring Event table |
| 3 | Add `ShowSlotCancelledEvent` to event list | SPEC_2.md Stage 3 — Spring Event table |
| 4 | Add `SlotActivatedListener` to discovery-catalog file structure | SPEC_2.md Stage 3 |
| 5 | Add `SlotCancelledListener` to discovery-catalog file structure | SPEC_2.md Stage 3 |
| 6 | Add cache invalidation rule to `VenueService.updateVenue()` in business logic | SPEC_2.md Stage 6 |

---

Shall I apply all 6 changes to SPEC_2.md now before we move to Stage 5?

User: Apply all the changes to SPEC_2.md 

GitHub Copilot: Let me read the exact text for all 4 target locations simultaneously.   

Searched for text `Spring Event Payload Reference` (`**/SPEC_2.md`), 1 result

Searched for text `booking-inventory.*listener only for this flow` (`**/SPEC_2.md`), 1 result

Searched for text `V7 — .CREATE TABLE show_slot` (`**/SPEC_2.md`), 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#623-623), lines 623 to 645

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#556-556), lines 556 to 610

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#716-716), lines 716 to 770

Now I have all exact text. Let me find the discovery-catalog listener section too: 

Searched for text `EventbriteWebhookEventListener.java` (`**/SPEC_2.md`), no results

Searched for text `OrgCreatedEventListener` (`**/SPEC_2.md`), no results

Searched for text `VenueDriftDetectedEvent.java` (`**/SPEC_2.md`), 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#495-495), lines 495 to 555

Now I have all the exact text. Applying all 6 changes simultaneously: 

Replacing 57 lines with 79 lines in [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)


```

```
Let me verify all 5 replacements landed correctly: 

Ran terminal command:  grep -n "city_id" /home/dell/Multi-City-Event-Booking/SPEC_2.md | head -20 && echo "===" && grep -n "ShowSlotActivatedEvent\|ShowSlotCancelledEvent" /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "===" && grep -n "SlotActivatedListener\|SlotCancelledListener" /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "===" && grep -n "snapshotCache.invalidate" /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "===" && wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md

All 6 changes confirmed in SPEC_2.md (880 → 901 lines). Here's what landed:

| Change | Verified at line(s) |
|---|---|
| `city_id BIGINT NOT NULL` added to V7 `show_slot` | 745, 766, 777, 784 |
| `ShowSlotActivatedEvent.java` in `scheduling/event/published/` | 586 |
| `ShowSlotCancelledEvent.java` in `scheduling/event/published/` | 589 |
| `SlotActivatedListener.java` + `SlotCancelledListener.java` in `discovery-catalog/event/listener/` | 519, 523 |
| `snapshotCache.invalidate()` note on `VenueService.updateVenue()` | 505 |
| `ShowSlotActivatedEvent` + `ShowSlotCancelledEvent` in Spring Event Payload Reference table | 646, 647 |

**The full cache/sync chain is now spec'd correctly:**

```
ShowSlot → ACTIVE
  → ShowSlotActivatedEvent → SlotActivatedListener
      → EventCatalogSyncService.sync(orgId, cityId, venueId)
          → event_catalog row created immediately ✅
          → Redis cache (orgId, cityId) invalidated ✅

ShowSlot → CANCELLED
  → ShowSlotCancelledEvent → SlotCancelledListener
      → event_catalog.state = CANCELLED ✅
      → Redis cache invalidated ✅

Venue updated internally
  → VenueService.updateVenue() → snapshotCache.invalidate(orgId, cityId) ✅
```

Ready to proceed with Stage 5 API endpoints using corrected paths now.

Made changes.

User: yes stage 5 write the full section of the outline with the updated changes we discussed till now 

GitHub Copilot: Ran terminal command:  tail -5 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Ran terminal command:  cat >> /home/dell/Multi-City-Event-Booking/SPEC_2.md << 'STAGE5_EOF'

---

## Stage 5 — API Endpoints

> **Path conventions (corrected from outline, based on existing Flow 1 code):**
> - Public catalog reads (Flow 1 existing): `GET /api/v1/catalog/...`
> - Admin writes for catalog/venues (Flow 2 new): `POST|PUT /api/v1/admin/catalog/...` — `ROLE_ADMIN` only
> - Show slot operations: `/api/v1/scheduling/slots/...` — `ROLE_ADMIN` for writes, `ROLE_ADMIN` for reads
> - Org OAuth onboarding: `/api/v1/admin/orgs/.../eventbrite/...` — `ROLE_ADMIN` only
> - Admin dashboard aggregations: `/api/v1/admin/...` — `ROLE_ADMIN` only

---

### 1. Org OAuth Onboarding — `admin/` module

---

#### `POST /api/v1/admin/orgs/{orgId}/eventbrite/connect`

Initiates the Eventbrite OAuth flow for an org. Returns the EB authorization URL for the admin to redirect to.

**Auth:** `ROLE_ADMIN`  
**Path params:** `orgId` (Long)  
**Request body:** none  
**Response `200`:**
```json
{
  "authorizationUrl": "https://www.eventbrite.com/oauth/authorize?response_type=code&client_id=...&redirect_uri=...&state=..."
}
```
**Response `409`:** org already has a `CONNECTED` token — return current status  
**Notes:**
- Generates a CSRF `state` parameter stored in session or short-lived Redis key.
- Does NOT create an `organization_auth` row yet — that happens on callback.

---

#### `GET /api/v1/admin/orgs/{orgId}/eventbrite/callback`

OAuth callback endpoint. Receives `code` + `state` from Eventbrite, exchanges for token, persists `organization_auth`.

**Auth:** none (public callback URL — `state` param is the CSRF guard)  
**Query params:** `code` (String), `state` (String)  
**Response `302`:** redirect to admin dashboard on success  
**Response `400`:** invalid or expired `state`  
**Response `502`:** EB token exchange failed  
**Notes:**
- Validates `state` against stored value before proceeding.
- Stores `access_token_encrypted`, `refresh_token_encrypted`, `eb_organization_id`, `expires_at` in `organization_auth`.
- Sets `status = CONNECTED`.

---

#### `GET /api/v1/admin/orgs/{orgId}/eventbrite/status`

Returns the current OAuth token status for an org.

**Auth:** `ROLE_ADMIN`  
**Path params:** `orgId` (Long)  
**Response `200`:**
```json
{
  "orgId": 1,
  "ebOrganizationId": "12345678",
  "status": "CONNECTED",
  "expiresAt": "2026-06-01T00:00:00Z",
  "connectedAt": "2026-03-01T10:00:00Z"
}
```
**Response `404`:** no `organization_auth` record for this org  

---

#### `DELETE /api/v1/admin/orgs/{orgId}/eventbrite/disconnect`

Revokes the Eventbrite token for an org. Sets `status = REVOKED`. All future EB calls for this org will fail with `EbAuthException`.

**Auth:** `ROLE_ADMIN`  
**Path params:** `orgId` (Long)  
**Response `204`:** disconnected  
**Response `404`:** no connected token found  
**Notes:** Does NOT call Eventbrite revoke API (not available in v3). Revocation is internal-only — token record marked `REVOKED`, facades refuse to use it.

---

### 2. Venue Management — `discovery-catalog` module

> **Existing Flow 1 endpoints (unchanged):**
> - `GET /api/v1/catalog/venues?cityId={cityId}&page={page}&size={size}` — public listing
>
> **New Flow 2 endpoints below.** Read endpoints used by `scheduling` internally reuse the existing `GET /api/v1/catalog/venues/{id}`.

---

#### `POST /api/v1/admin/catalog/venues`

Create a new venue. Persists internally first, then calls `EbVenueService.createVenue()`.

**Auth:** `ROLE_ADMIN`  
**Request body:**
```json
{
  "name": "Grand Theatre",
  "addressLine1": "12 MG Road",
  "addressLine2": "Indiranagar",
  "cityId": 3,
  "country": "IN",
  "zipCode": "560001",
  "latitude": "12.9716",
  "longitude": "77.5946",
  "capacity": 1200,
  "seatingMode": "RESERVED"
}
```
**Response `201`:**
```json
{
  "id": 42,
  "name": "Grand Theatre",
  "cityId": 3,
  "capacity": 1200,
  "seatingMode": "RESERVED",
  "eventbriteVenueId": "eb-venue-99887",
  "syncStatus": "SYNCED",
  "createdAt": "2026-03-03T10:00:00Z"
}
```
**Response `400`:** validation failure (missing required fields)  
**Response `409`:** venue with same name + city already exists  
**Response `502`:** EB venue creation failed — venue still persisted internally with `syncStatus = PENDING_SYNC`  
**Notes:**
- If EB call fails, the venue is saved internally with `syncStatus = PENDING_SYNC` and a safe `502` is returned. Admin can retry via the sync endpoint.
- `eventbriteVenueId` is null in the `502` response body.

---

#### `PUT /api/v1/admin/catalog/venues/{id}`

Update an existing venue. Persists internally, calls `EbVenueService.updateVenue()`, invalidates Redis cache for `(orgId, cityId)`.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Request body:** same shape as POST (all fields optional — partial update)  
**Response `200`:** updated `VenueResponse`  
**Response `404`:** venue not found  
**Response `502`:** EB update failed — internal update still persisted, `syncStatus` flagged  
**Notes:**
- Even on EB failure, the internal record is updated (internal DB is source of truth).
- Cache invalidated regardless of EB outcome: `snapshotCache.invalidate(orgId, venue.getCityId())`.

---

#### `GET /api/v1/catalog/venues/{id}`

Get a single venue by ID. Used by `scheduling` via internal REST call before conflict validation.

**Auth:** none (public)  
**Path params:** `id` (Long)  
**Response `200`:**
```json
{
  "id": 42,
  "cityId": 3,
  "name": "Grand Theatre",
  "address": "12 MG Road, Indiranagar",
  "zipCode": "560001",
  "latitude": "12.9716",
  "longitude": "77.5946",
  "capacity": 1200,
  "seatingMode": "RESERVED",
  "eventbriteVenueId": "eb-venue-99887",
  "syncStatus": "SYNCED"
}
```
**Response `404`:** venue not found  
**Notes:** This is the endpoint `scheduling` calls internally (via `RestTemplate` / `WebClient`) before conflict detection. It must return `capacity` and `seatingMode` — both were missing in the Flow 1 `VenueResponse`, fixed by the V5 migration + entity/DTO update.

---

#### `POST /api/v1/admin/catalog/venues/{id}/sync`

*(COULD)* Manually re-trigger EB venue sync for a venue stuck in `PENDING_SYNC` or `DRIFT_FLAGGED`.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Response `200`:** updated `VenueResponse` with new `syncStatus`  
**Response `404`:** venue not found  
**Response `502`:** EB sync still failing  

---

#### `GET /api/v1/admin/catalog/venues/flagged`

List venues with `syncStatus = DRIFT_FLAGGED`. Read by admin dashboard.

**Auth:** `ROLE_ADMIN`  
**Query params:** `orgId` (Long, optional), `page`, `size`  
**Response `200`:**
```json
{
  "venues": [
    {
      "id": 42,
      "name": "Grand Theatre",
      "cityId": 3,
      "syncStatus": "DRIFT_FLAGGED",
      "lastSyncError": "EB capacity changed from 1200 to 800",
      "lastAttemptedAt": "2026-03-03T06:00:00Z"
    }
  ],
  "pagination": { "page": 0, "size": 20, "total": 1 }
}
```

---

### 3. Show Slot Lifecycle — `scheduling` module

---

#### `POST /api/v1/scheduling/slots`

Create a new show slot. Runs conflict detection, gap policy check, persists as `DRAFT`.

**Auth:** `ROLE_ADMIN`  
**Request body:**
```json
{
  "venueId": 42,
  "title": "Rock Night 2026",
  "description": "Annual rock festival",
  "startTime": "2026-04-15T19:00:00+05:30",
  "endTime": "2026-04-15T23:00:00+05:30",
  "seatingMode": "GA",
  "capacity": 1000,
  "pricingTiers": [
    { "name": "Early Bird", "priceAmount": 499.00, "currency": "INR", "quota": 200, "tierType": "PAID" },
    { "name": "General",    "priceAmount": 799.00, "currency": "INR", "quota": 800, "tierType": "PAID" }
  ],
  "isRecurring": false,
  "recurrenceRule": null,
  "sourceSeatMapId": null
}
```
**Response `201`:**
```json
{
  "id": 101,
  "venueId": 42,
  "cityId": 3,
  "title": "Rock Night 2026",
  "startTime": "2026-04-15T19:00:00+05:30",
  "endTime": "2026-04-15T23:00:00+05:30",
  "seatingMode": "GA",
  "capacity": 1000,
  "status": "DRAFT",
  "isRecurring": false,
  "ebEventId": null,
  "syncAttemptCount": 0,
  "pricingTiers": [ ... ],
  "createdAt": "2026-03-03T10:00:00Z"
}
```
**Response `409` — conflict detected:**
```json
{
  "errorCode": "SLOT_CONFLICT",
  "message": "Venue has an overlapping slot or turnaround gap violation",
  "alternatives": {
    "sameVenueAlternatives": [
      { "proposedStart": "2026-04-16T19:00:00+05:30", "proposedEnd": "2026-04-16T23:00:00+05:30" }
    ],
    "nearbyVenueAlternatives": [
      { "venueId": 43, "venueName": "City Hall", "city": "Bengaluru", "capacity": 800 }
    ],
    "adjustedTimeOptions": [
      { "proposedStart": "2026-04-15T20:30:00+05:30", "proposedEnd": "2026-04-16T00:30:00+05:30" }
    ]
  }
}
```
**Response `400`:** validation failure (end before start, no pricing tiers, unknown venueId)  
**Response `502`:** venue fetch from `discovery-catalog` failed  

---

#### `POST /api/v1/scheduling/slots/{id}/submit`

Transition slot `DRAFT → PENDING_SYNC`. Triggers the full EB sync chain (D.1 → D.2–D.5 → D.6).

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Request body:** none  
**Response `200`:**
```json
{
  "id": 101,
  "status": "PENDING_SYNC",
  "ebEventId": null,
  "syncAttemptCount": 0,
  "message": "EB sync initiated"
}
```
**Response `404`:** slot not found  
**Response `422`:** slot not in `DRAFT` status — `BusinessRuleException`  
**Response `502`:** EB draft creation failed — slot stays `PENDING_SYNC`, `syncAttemptCount` incremented, `lastSyncError` recorded  
**Notes:** Response is returned immediately after transitioning to `PENDING_SYNC`. EB sync proceeds asynchronously (Spring Events). `ebEventId` is populated once D.1 succeeds.

---

#### `GET /api/v1/scheduling/slots/{id}`

Get full slot details including current sync state.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Response `200`:**
```json
{
  "id": 101,
  "venueId": 42,
  "cityId": 3,
  "title": "Rock Night 2026",
  "startTime": "2026-04-15T19:00:00+05:30",
  "endTime": "2026-04-15T23:00:00+05:30",
  "seatingMode": "GA",
  "capacity": 1000,
  "status": "ACTIVE",
  "isRecurring": false,
  "ebEventId": "eb-event-556677",
  "ebSeriesId": null,
  "syncAttemptCount": 0,
  "lastSyncError": null,
  "lastAttemptedAt": null,
  "pricingTiers": [
    { "id": 1, "name": "Early Bird", "priceAmount": 499.00, "currency": "INR", "quota": 200, "tierType": "PAID", "ebTicketClassId": "tc-001" },
    { "id": 2, "name": "General",    "priceAmount": 799.00, "currency": "INR", "quota": 800, "tierType": "PAID", "ebTicketClassId": "tc-002" }
  ],
  "createdAt": "2026-03-03T10:00:00Z",
  "updatedAt": "2026-03-03T10:05:00Z"
}
```
**Response `404`:** slot not found  

---

#### `GET /api/v1/scheduling/slots`

List slots with filters.

**Auth:** `ROLE_ADMIN`  
**Query params:** `orgId` (Long, required), `status` (ShowSlotStatus, optional), `venueId` (Long, optional), `cityId` (Long, optional), `startAfter` (ISO timestamp, optional), `page`, `size`  
**Response `200`:** paginated list of `ShowSlotResponse`  

---

#### `PUT /api/v1/scheduling/slots/{id}`

Update a slot's details. If status is `ACTIVE`, triggers `EbEventSyncService.updateEvent()`. Internal update always persists regardless of EB outcome.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Request body:** partial — any subset of `title`, `description`, `startTime`, `endTime`, `capacity`, `pricingTiers`  
**Response `200`:** updated `ShowSlotResponse`  
**Response `404`:** slot not found  
**Response `422`:** slot is `CANCELLED` — cannot update a terminal slot  
**Response `502`:** EB update failed — internal update still persisted, `lastSyncError` recorded  

---

#### `POST /api/v1/scheduling/slots/{id}/cancel`

Cancel a slot. Transitions to `CANCELLED`, calls `EbEventSyncService.cancelEvent()`. EB failure does NOT block internal cancellation.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Request body:** `{ "reason": "Venue unavailable" }` (optional)  
**Response `200`:**
```json
{
  "id": 101,
  "status": "CANCELLED",
  "ebCancelSynced": true,
  "message": "Slot cancelled"
}
```
**Response `404`:** slot not found  
**Response `422`:** slot already `CANCELLED`  
**Notes:** If EB cancel fails, `ebCancelSynced: false` is returned but HTTP status is still `200` — internal cancellation succeeded. `lastSyncError` recorded for admin visibility.

---

#### `POST /api/v1/scheduling/slots/{id}/retry-sync`

*(COULD)* Manually retry EB sync for a slot stuck in `PENDING_SYNC`.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long)  
**Response `200`:** `{ "status": "PENDING_SYNC", "message": "Retry initiated" }`  
**Response `422`:** slot not in `PENDING_SYNC`  

---

#### `GET /api/v1/scheduling/slots/{id}/occurrences`

List all occurrences for a recurring slot.

**Auth:** `ROLE_ADMIN`  
**Path params:** `id` (Long — parent slot ID)  
**Response `200`:**
```json
{
  "slotId": 101,
  "occurrences": [
    {
      "id": 201,
      "occurrenceIndex": 1,
      "startTime": "2026-04-15T19:00:00+05:30",
      "endTime": "2026-04-15T23:00:00+05:30",
      "status": "ACTIVE",
      "ebEventId": "eb-occ-001",
      "syncAttemptCount": 0
    }
  ]
}
```
**Response `404`:** slot not found  
**Response `422`:** slot is not recurring (`isRecurring = false`)  

---

#### `GET /api/v1/scheduling/slots/mismatches`

List `ACTIVE` slots where EB event is missing or in unexpected state — flagged by `SlotReconciliationJob`.

**Auth:** `ROLE_ADMIN`  
**Query params:** `orgId` (Long, required), `page`, `size`  
**Response `200`:** paginated list of slots with `lastSyncError` populated  

---

### 4. Admin Dashboard Aggregations — `admin/` module

> `admin/` owns no `@Entity`. These endpoints call `scheduling` and `discovery-catalog` REST APIs and aggregate the results. No business logic in `admin/`.

---

#### `GET /api/v1/admin/scheduling/slots/flagged`

Aggregates slots in `PENDING_SYNC` with `syncAttemptCount > 0`, or `ACTIVE` slots flagged by reconciliation.

**Auth:** `ROLE_ADMIN`  
**Query params:** `orgId` (Long, required)  
**Response `200`:**
```json
{
  "pendingSyncFailed": [
    { "id": 101, "title": "Rock Night", "status": "PENDING_SYNC", "syncAttemptCount": 3, "lastSyncError": "EB publish timeout", "lastAttemptedAt": "..." }
  ],
  "activeWithEbMismatch": [
    { "id": 105, "title": "Jazz Evening", "status": "ACTIVE", "ebEventId": "eb-999", "lastSyncError": "EB event not found on reconciliation" }
  ]
}
```
**Notes:** Delegates to `GET /api/v1/scheduling/slots?status=PENDING_SYNC&orgId=...` and `GET /api/v1/scheduling/slots/mismatches`. Aggregated in `OrgDashboardService` in `admin/`.

---

#### `GET /api/v1/admin/catalog/venues/flagged`

Aggregates venues with `syncStatus = DRIFT_FLAGGED`.

**Auth:** `ROLE_ADMIN`  
**Query params:** `orgId` (Long, required)  
**Response `200`:** paginated list of flagged `VenueResponse` with `lastSyncError`  
**Notes:** Delegates to `GET /api/v1/admin/catalog/venues/flagged` in `discovery-catalog`.

---

### Internal REST Calls (module-to-module — not public API)

These are intra-service calls that reuse existing public endpoints — no new endpoints needed.

| Caller | Callee | Endpoint reused | Purpose |
|---|---|---|---|
| `scheduling` | `discovery-catalog` | `GET /api/v1/catalog/venues/{id}` | Fetch venue capacity + seatingMode before conflict check |
| `scheduling` | `discovery-catalog` | `GET /api/v1/catalog/venues?cityId={id}&orgId={id}` | Fetch nearby venues for conflict alternative suggestions |
| `admin/` | `scheduling` | `GET /api/v1/scheduling/slots?status=PENDING_SYNC` | Aggregate flagged slot data for dashboard |
| `admin/` | `scheduling` | `GET /api/v1/scheduling/slots/mismatches` | Aggregate EB mismatch data for dashboard |
| `admin/` | `discovery-catalog` | `GET /api/v1/admin/catalog/venues/flagged` | Aggregate drift-flagged venues for dashboard |

---

### Endpoint Summary

| # | Method | Path | Module | Auth |
|---|---|---|---|---|
| 1 | POST | `/api/v1/admin/orgs/{orgId}/eventbrite/connect` | admin | ROLE_ADMIN |
| 2 | GET | `/api/v1/admin/orgs/{orgId}/eventbrite/callback` | admin | none (state guard) |
| 3 | GET | `/api/v1/admin/orgs/{orgId}/eventbrite/status` | admin | ROLE_ADMIN |
| 4 | DELETE | `/api/v1/admin/orgs/{orgId}/eventbrite/disconnect` | admin | ROLE_ADMIN |
| 5 | POST | `/api/v1/admin/catalog/venues` | discovery-catalog | ROLE_ADMIN |
| 6 | PUT | `/api/v1/admin/catalog/venues/{id}` | discovery-catalog | ROLE_ADMIN |
| 7 | GET | `/api/v1/catalog/venues/{id}` | discovery-catalog | none |
| 8 | POST | `/api/v1/admin/catalog/venues/{id}/sync` *(COULD)* | discovery-catalog | ROLE_ADMIN |
| 9 | GET | `/api/v1/admin/catalog/venues/flagged` | discovery-catalog | ROLE_ADMIN |
| 10 | POST | `/api/v1/scheduling/slots` | scheduling | ROLE_ADMIN |
| 11 | POST | `/api/v1/scheduling/slots/{id}/submit` | scheduling | ROLE_ADMIN |
| 12 | GET | `/api/v1/scheduling/slots/{id}` | scheduling | ROLE_ADMIN |
| 13 | GET | `/api/v1/scheduling/slots` | scheduling | ROLE_ADMIN |
| 14 | PUT | `/api/v1/scheduling/slots/{id}` | scheduling | ROLE_ADMIN |
| 15 | POST | `/api/v1/scheduling/slots/{id}/cancel` | scheduling | ROLE_ADMIN |
| 16 | POST | `/api/v1/scheduling/slots/{id}/retry-sync` *(COULD)* | scheduling | ROLE_ADMIN |
| 17 | GET | `/api/v1/scheduling/slots/{id}/occurrences` | scheduling | ROLE_ADMIN |
| 18 | GET | `/api/v1/scheduling/slots/mismatches` | scheduling | ROLE_ADMIN |
| 19 | GET | `/api/v1/admin/scheduling/slots/flagged` | admin | ROLE_ADMIN |
| 20 | GET | `/api/v1/admin/catalog/venues/flagged` *(agg.)* | admin | ROLE_ADMIN |

STAGE5_EOF
echo "Exit: $?"

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && tail -8 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Stage 5 written. File grew from 901 → 1420 lines (519 lines added).

**What was written:**

| Surface | Endpoints | Notes |
|---|---|---|
| Admin OAuth | 4 | connect, callback, status, disconnect |
| Venue management | 5 | POST create, PUT update, GET by id, POST sync, GET flagged |
| Show slot lifecycle | 9 | create, submit, get, list, update, cancel, retry-sync, occurrences, mismatches |
| Admin dashboard | 2 | aggregated flagged slots + venues |

**Key decisions reflected from our discussions:**
- `GET /api/v1/catalog/venues/{id}` — public, no auth — used by `scheduling` internal REST call, returns `capacity` + `seatingMode` (fixed by V5)
- Admin writes on separate prefix `/api/v1/admin/catalog/...` — `ROLE_ADMIN` gated
- `POST /slots` returns `409` with full `ConflictAlternativeResponse` inline — no separate endpoint
- `POST /slots/{id}/cancel` returns `200` even if EB cancel fails — `ebCancelSynced: false` in body
- Internal module-to-module REST calls documented in a table — no new endpoints needed

Ready for Stage 6 — Business Logic whenever you are.

User: Lets go with stage 6 next the buisness logic, when building this keep in mind of the buisness logic of the SPEC_1.md flow 1. 

GitHub Copilot: Summarized conversation historyLet me read both spec files to understand the current state before writing Stage 6. 

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && wc -l /home/dell/Multi-City-Event-Booking/SPEC_1.md

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_1.md#1-1), lines 1 to 672

Now let me read the last part of SPEC_2.md to see where Stage 5 ends: 

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#1300-1300), lines 1300 to 1420

Good. Now let me also read the key parts of SPEC_2.md from the beginning to understand the domain model, entities and the architecture sections: 

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#1-1), lines 1 to 640

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#640-640), lines 640 to 901

Good - I have everything I need. Now I'll write Stage 6 in full: 

Created 1 todo

Ran terminal command:  cat >> /home/dell/Multi-City-Event-Booking/SPEC_2.md << 'STAGE6_EOF'

---

## Stage 6 — Business Logic

> Rules presented in the order that code executes. Where a rule parallels Flow 1 (SPEC_1.md Stage 6), the relationship is called out explicitly.

---

### 1. Org Context Resolution

All Flow 2 endpoints are admin-only (`ROLE_ADMIN`). `org_id` is resolved from the authenticated principal:

- **Admin reads / writes** → `org_id` extracted from JWT claim `orgId`.
- Never imported from `identity` `@Service` (Hard Rule #1). The JWT itself carries the claim.
- Every ACL facade call (`EbVenueService`, `EbEventSyncService`, etc.) receives `orgId` as a parameter and resolves the token via `EbTokenStore.getToken(orgId)`.
- If no `OrganizationAuth` row exists for `orgId`, or status is `REVOKED`, `EbTokenStore` throws `EbAuthException` immediately — the EB call never starts.

> Contrast with Flow 1 (SPEC_1 §6.1): public catalog reads resolve `org_id` from `app.default-org-id` config. Flow 2 admin endpoints always resolve from JWT.

---

### 2. OAuth Onboarding Flow

**Trigger:** Admin clicks "Connect Eventbrite" in the admin panel.

**Step 1 — Initiate (`POST /api/v1/admin/orgs/{orgId}/eventbrite/connect`):**
1. `OrgOAuthService.initiateOAuth(orgId)` generates the Eventbrite authorization URL:
   - `https://www.eventbrite.com/oauth/authorize?response_type=code&client_id={CLIENT_ID}&redirect_uri={CALLBACK_URL}&state={csrfState}`
2. Generate a CSRF state token (UUID) and store in Redis with key `oauth:state:{orgId}` TTL 10 minutes.
3. Return the authorization URL to the frontend. Frontend redirects the admin browser there.
4. No EB API call made yet; no `OrganizationAuth` record created yet.

**Step 2 — Callback (`GET /api/v1/admin/orgs/{orgId}/eventbrite/callback?code=…&state=…`):**
1. Validate `state` parameter against Redis key `oauth:state:{orgId}`. If mismatch or expired → return `400 Bad Request`. Delete the Redis key regardless (one-time use).
2. `OrgOAuthService.exchangeCode(orgId, code)` calls `EbTokenStore.exchangeAndStore(orgId, code)`:
   - `POST https://www.eventbrite.com/oauth/token` with `grant_type=authorization_code`, `client_id`, `client_secret`, `code`, `redirect_uri`.
   - On success: store `access_token` (encrypted), `refresh_token` (encrypted, nullable), `eb_organization_id`, `expires_at` (Instant or null if missing from response) in `organization_auth` as a new row (or upsert if reconnecting).
   - Set `status = CONNECTED`.
3. If EB returns an error → throw `EbAuthException("OAuth code exchange failed: {eb_error}")` → return `502`.
4. Respond `200 { "status": "CONNECTED", "ebOrgId": "…" }`.

**Re-connection (upsert rule):**
- If an `organization_auth` row already exists for `orgId`, overwrite `access_token_encrypted`, `refresh_token_encrypted`, `expires_at`, `eb_organization_id`, `status`. Do NOT insert a new row.
- This allows admin to reconnect after token revocation without leaving orphan rows.

**Disconnect (`DELETE /api/v1/admin/orgs/{orgId}/eventbrite/disconnect`):**
1. Set `OrganizationAuth.status = REVOKED`.
2. Clear `access_token_encrypted` and `refresh_token_encrypted` (zero-fill).
3. No EB API call — EB token invalidation is advisory only (no required server-side revoke call).
4. Respond `200 { "status": "REVOKED" }`.

---

### 3. Token Refresh Interceptor

Runs **before every outbound EB API call** inside each `shared/` facade. No application module handles token refresh — it is entirely within `EbTokenStore`.

**Algorithm (`EbTokenStore.getValidToken(orgId)`):**
```
token = load OrganizationAuth by orgId
if token == null OR status == REVOKED:
    throw EbAuthException("No valid EB token for org " + orgId)
if expires_at != null AND now > expires_at - 5 minutes:
    attempt refresh:
        POST https://www.eventbrite.com/oauth/token
        grant_type=refresh_token, refresh_token=decrypt(refresh_token_encrypted)
        on success:
            update access_token_encrypted, expires_at, status = CONNECTED
            return new access_token
        on failure (4xx / network error):
            set status = TOKEN_EXPIRED
            throw EbAuthException("Token refresh failed for org " + orgId)
return decrypt(access_token_encrypted)
```

**Propagation rule:** If `EbAuthException` propagates out of a facade call during slot sync, the `ShowSlotService` catches it, records `last_sync_error = "EB token expired or revoked"`, increments `sync_attempt_count`, and returns a `502` to the admin caller. The slot remains `PENDING_SYNC` — it is retry-eligible once the admin reconnects.

---

### 4. Venue Creation Flow

**Owner:** `discovery-catalog` → `VenueService.createVenue(orgId, request)`

**Internal-first rule (same as all flows — PRODUCT.md §5.1):**

```
1. Validate request: name, address, capacity > 0, cityId exists in city table.
2. Persist Venue to DB with sync_status = PENDING_SYNC, eb_venue_id = null.
3. Retrieve valid token via EbTokenStore.getValidToken(orgId).
4. Call EbVenueService.createVenue(token, orgId, venueData):
       POST /organizations/{eb_org_id}/venues/
       payload: { name, address: { address_1, city, country, postal_code }, capacity }
5. Store returned eb_venue_id on the Venue entity.
6. Set sync_status = SYNCED.
7. Save Venue again (eb_venue_id + sync_status update).
8. Return VenueResponse (including eb_venue_id, sync_status = SYNCED).
```

**EB call failure on creation:**
- Venue record remains in DB with `sync_status = PENDING_SYNC`, `eb_venue_id = null`.
- `last_sync_error` and `last_attempted_at` recorded on the `Venue`.
- Return `202 Accepted` with `sync_status = PENDING_SYNC` (venue created internally; EB sync pending).
- Admin can trigger manual re-sync via `POST /api/v1/admin/catalog/venues/{id}/sync` (COULD — optional).

**Cache impact:** Venue creation adds a new venue row but does NOT touch `event_catalog`. No cache invalidation is needed at venue creation time. Cache is only invalidated when a `ShowSlot` at this venue goes `ACTIVE` (Section 10 below).

---

### 5. Venue Update Flow

**Owner:** `discovery-catalog` → `VenueService.updateVenue(orgId, venueId, request)`

```
1. Load Venue by venueId. Verify orgId matches (ownership check).
2. Apply field updates internally (name, address, capacity, seatingMode).
3. Save to DB.
4. If eb_venue_id is NOT null (venue is synced):
       Call EbVenueService.updateVenue(token, eb_venue_id, updatedData):
           POST /venues/{venue_id}/
       On success: set sync_status = SYNCED. Save.
       On failure: record last_sync_error. Set sync_status = DRIFT_FLAGGED. Save.
            → VenueDriftDetectedEvent published (venueId, eb_venue_id, driftDescription)
5. If eb_venue_id IS null (never synced):
       Attempt EB create (same as creation flow step 3–7).
6. ALWAYS after persisting update — invalidate snapshot cache:
       snapshotCache.invalidate(orgId, venue.getCityId())
       Reason: venue name + address appear in event cards cached in the snapshot.
```

**Why cache invalidation on venue update:** Flow 1's `EventCatalogSnapshotCache` stores the full `EventCatalogItemResponse` which includes venue name and address. An updated venue name would serve stale venue info to users until cache expires. Immediate invalidation ensures the next read repopulates from DB.

---

### 6. Venue Reconciliation Job (`VenueReconciliationJob`)

**Cadence:** `@Scheduled(cron = "0 0 * * * *")` — every hour.

**Algorithm:**
```
for each Venue where sync_status IN (SYNCED, DRIFT_FLAGGED) and eb_venue_id IS NOT NULL:
    GET /venues/{eb_venue_id}/
    compare response.capacity and response.address with internal record
    if drift detected (capacity changed > 10% OR address line differs):
        set sync_status = DRIFT_FLAGGED
        record last_sync_error = "EB drift: capacity {eb} vs internal {internal}"
        set last_attempted_at = now
        publish VenueDriftDetectedEvent(venueId, ebVenueId, driftDescription)
    else:
        // no change — nothing to update; leave sync_status as-is
    if EB returns 404:
        set sync_status = DRIFT_FLAGGED
        record last_sync_error = "EB venue not found — may have been deleted from EB Dashboard"
```

**Flag-not-fix policy (Hard Rule from Req J):** The job flags mismatches — it never auto-corrects them. Admin resolves via admin panel.

**Scheduler failure handling:** Wrapped in `try-catch(Exception)` per record loop iteration. One EB failure does NOT stop iteration over remaining venues. Log `WARN` per failure. Scheduler thread never dies from a record-level error.

---

### 7. Show Slot Creation — `DRAFT` State

**Owner:** `scheduling` → `ShowSlotService.createSlot(orgId, request)`

This method performs **internal validation only**. No EB call is made. The slot is persisted as `DRAFT`.

```
1. Fetch venue details via REST GET to discovery-catalog:
       GET /api/v1/catalog/venues/{request.venueId}
       Response must include: capacity, seatingMode, cityId.
       If 404 → throw ResourceNotFoundException("Venue not found").
       If venue.sync_status == PENDING_SYNC (eb_venue_id is null) → throw BusinessRuleException(
           "Cannot create slot for venue not yet synced to Eventbrite. Sync venue first.")
2. ConflictDetectionService.checkConflicts(venueId, proposedStart, proposedEnd, orgId):
       → see Section 8 (Conflict Detection Rules).
       If conflict found → throw SlotConflictException(conflictAlternativeResponse).
3. Validate pricing tiers:
       Must have at least 1 tier.
       FREE tiers must have price_amount = 0.
       Sum of all tier quotas should not exceed venue.capacity (WARN if exceeded, do not block).
4. If seatingMode == RESERVED:
       request.sourceSeatMapId must be present (non-null).
       If null → throw BusinessRuleException("sourceSeatMapId required for RESERVED seating").
5. Persist ShowSlot with status = DRAFT, eb_event_id = null, sync_attempt_count = 0.
6. Persist all ShowSlotPricingTier records linked to the slot.
7. Return ShowSlotResponse (status = DRAFT, ebEventId = null).
```

**Note on venue REST call:** `scheduling` calls `discovery-catalog` via internal HTTP (same JVM, same process — Spring MVC dispatch-to-self or explicit `RestTemplate`/`WebClient` call depending on config). It receives a `VenueDto` value object. It NEVER holds a reference to `Venue.java` from `discovery-catalog`.

---

### 8. Conflict Detection Rules

**Owner:** `scheduling` → `ConflictDetectionService`

**Overlap query (DB-level):**
```sql
SELECT COUNT(*) FROM show_slot
WHERE venue_id = :venueId
  AND status NOT IN ('CANCELLED')
  AND start_time < :proposedEnd
  AND end_time   > :proposedStart
  AND id != :excludeSlotId  -- null-safe: exclude self on update
```
If `COUNT > 0` → conflict detected.

**Turnaround gap policy:**
- Configurable property: `scheduling.turnaround-gap-minutes=60` (default 60).
- Additional query: find the latest `end_time` of any non-CANCELLED slot at this venue where `end_time > proposedStart - gap`:
```sql
SELECT MAX(end_time) FROM show_slot
WHERE venue_id = :venueId
  AND status NOT IN ('CANCELLED')
  AND end_time > :proposedStart - interval ':gapMinutes minutes'
  AND end_time <= :proposedStart
```
If any row found → gap policy violated → conflict detected.

**Both checks run.** The slot is only saved if BOTH pass.

**Idempotency on update:** When admin updates an `ACTIVE` slot's times, pass `excludeSlotId = slot.id` so the slot does not conflict with itself.

---

### 9. Alternatives Builder

**Owner:** `scheduling` → `ConflictDetectionService.buildAlternatives(...)`, returns `ConflictAlternativeResponse`

**Rule 1 — Same venue, next available windows:**
```
Find next 3 non-conflicting windows at the same venue after proposedEnd:
  For each candidate start T = proposedEnd + gapMinutes, T + slotDuration, T + 2*slotDuration…
  Check conflict query for each candidate → include in sameVenueAlternatives if no conflict.
  Return up to 3 options.
```

**Rule 2 — Adjusted times satisfying gap policy:**
```
adjustedStart = max end_time of existing slots at venue + gapMinutes + 1 minute buffer
adjustedEnd   = adjustedStart + (proposedEnd - proposedStart)  // same duration
Return [ TimeWindowOption(adjustedStart, adjustedEnd) ]
```

**Rule 3 — Alternative venues in same city:**
```
REST GET /api/v1/catalog/venues?cityId={cityId}&orgId={orgId}
Filter: exclude current venue, include only where sync_status = SYNCED (has eb_venue_id)
Sort by |venue.capacity - slot.capacity| ascending (closest capacity match first)
Return up to 5 VenueOption records: { venueId, venueName, city, capacity }
```

> This REST call to `discovery-catalog` is the only cross-module call in conflict detection. It follows Hard Rule #1 — `scheduling` does NOT import `Venue.java` or `VenueRepository`.

**Combined into `SlotConflictException` payload** (HTTP 422):
```json
{
  "errorCode": "SLOT_CONFLICT",
  "message": "A conflicting slot exists at this venue for the requested time window.",
  "alternatives": {
    "sameVenueAlternatives": [...],
    "nearbyVenueAlternatives": [...],
    "adjustedTimeOptions": [...]
  }
}
```

---

### 10. Slot Submission — `DRAFT → PENDING_SYNC` (D.1)

**Trigger:** `POST /api/v1/scheduling/slots/{id}/submit`

**Owner:** `scheduling` → `ShowSlotService.submitSlot(orgId, slotId)`

```
1. Load ShowSlot by slotId. Verify orgId ownership.
2. Validate current status is DRAFT — else throw BusinessRuleException("Only DRAFT slots can be submitted").
3. Validate at least 1 ShowSlotPricingTier exists for this slot.
4. Transition status: DRAFT → PENDING_SYNC via ShowSlotStateMachineService.sendEvent(SUBMIT).
5. Persist (status = PENDING_SYNC, sync_attempt_count remains 0).
6. Build EB event creation payload:
       {
         "name": { "text": slot.title },
         "start": { "utc": slot.startTime (UTC), "timezone": inferTimezone(slot.venueId) },
         "end":   { "utc": slot.endTime (UTC),   "timezone": inferTimezone(slot.venueId) },
         "venue_id": slot.ebVenueId (from venue lookup),
         "capacity": slot.capacity,
         "is_series": slot.isRecurring,
         "currency": primaryPricingTier.currency,
         "online_event": false
       }
7. Call EbEventSyncService.createDraft(token, ebOrgId, payload):
       POST /organizations/{eb_org_id}/events/
8. On EB success:
       slot.ebEventId = returned event id
       slot.syncAttemptCount = 0 (never incremented on first success)
       slot.lastSyncError = null
       slot.lastAttemptedAt = now
       Persist slot.
       Publish SlotDraftCreatedEvent(slotId, ebEventId, pricingTierIds, seatingMode, orgId, sourceSeatMapId)
9. On EB failure (any exception from facade):
       slot.syncAttemptCount += 1
       slot.lastSyncError = exception.message (truncated to 1000 chars)
       slot.lastAttemptedAt = now
       Persist slot. (Status stays PENDING_SYNC)
       Return error response to admin (502 or 422 depending on EB error type).
       Do NOT publish SlotDraftCreatedEvent.
```

**Retry semantics:** Slot stays `PENDING_SYNC`. Admin retries via `POST /api/v1/scheduling/slots/{id}/retry-sync`. The same `submitSlot` logic runs from step 5 onward (skip status transition guard since already `PENDING_SYNC`). `syncAttemptCount` increments on each failure; resets to 0 on first success.

---

### 11. Ticket / Inventory / SeatMap Setup — D.2–D.5

**Owner:** `booking-inventory` → `SlotDraftCreatedListener` → `SlotTicketSyncService.syncTickets(event)`

**Triggered by:** `SlotDraftCreatedEvent`

**Execution — strict sequential order:**

```
Step D.2 — Create Ticket Classes:
  for each pricingTierId in event.pricingTierIds:
      fetch ShowSlotPricingTier by id (via REST GET to scheduling:
          GET /api/v1/scheduling/slots/{slotId}/pricing-tiers or as payload data)
      call EbTicketService.createTicketClasses(token, ebEventId, tier):
          POST /events/{event_id}/ticket_classes/
          payload: { name, cost: { currency, value: priceInCents }, quantity_total: tier.quota,
                     sales_start: now, free: (tier_type==FREE) }
      store returned eb_ticket_class_id on the ShowSlotPricingTier record
        (via REST PATCH to scheduling: PATCH /api/v1/scheduling/slots/{slotId}/pricing-tiers/{tierId}/eb-id)
      — OR — embed eb_ticket_class_id in the event payload and let scheduling store it on completion.

Step D.3 — Create Inventory Tiers:
  for each pricingTier with eb_ticket_class_id now known:
      call EbTicketService.createInventoryTiers(token, ebEventId, tier):
          POST /events/{event_id}/inventory_tiers/
          payload: { ticket_class_id: eb_ticket_class_id, quantity_total: tier.quota }
      store returned eb_inventory_tier_id on the ShowSlotPricingTier record.

Step D.4 — Capacity Update (GA only):
  if event.seatingMode == GA:
      (Capacity was already set in D.1 event creation payload via 'capacity' field)
      Only call EbCapacityService.updateCapacityTier() if capacity needs correction after D.2/D.3.
      Standard flow: no call needed here — D.1 already set it.

Step D.5 — Copy Seat Map (RESERVED only):
  if event.seatingMode == RESERVED AND event.sourceSeatMapId != null:
      call EbTicketService.copySeatMap(token, ebEventId, sourceSeatMapId):
          POST /events/{event_id}/seatmaps/
          payload: { source_seatmap_id: sourceSeatMapId }
```

**On all steps succeeded:**
```
publish TicketSyncCompletedEvent(slotId, ebEventId)
```

**On any step failure:**
```
publish TicketSyncFailedEvent(slotId, ebEventId, reason="D.{step}: {exception message}")
```

**Failure atomicity:** There is NO rollback of prior EB steps on failure. If D.3 fails after D.2 succeeded, the ticket classes remain on EB. On admin retry (via `retry-sync`), the sync restarts from D.1 only if `eb_event_id` is null; otherwise it skips D.1 and re-attempts D.2 onward. Duplicate ticket class creation is avoided by checking `eb_ticket_class_id != null` on each pricing tier before calling D.2 again.

---

### 12. Publish — D.6

**Owner:** `scheduling` → `TicketSyncCompletedListener` → `ShowSlotService.onTicketSyncComplete(event)`

**Triggered by:** `TicketSyncCompletedEvent`

```
1. Load ShowSlot by slotId. Verify status is PENDING_SYNC (guard).
2. Call EbEventSyncService.publishEvent(token, ebEventId):
       POST /events/{event_id}/publish/
3. On EB success:
       Transition via state machine: PENDING_SYNC → ACTIVE
       slot.syncAttemptCount = 0     ← RESET on reaching ACTIVE
       slot.lastSyncError = null
       slot.lastAttemptedAt = now
       Persist slot.
       Publish ShowSlotActivatedEvent(slotId, ebEventId, orgId, venueId, cityId)
       [Recurring step: see Section 15]
4. On EB failure:
       slot.syncAttemptCount += 1
       slot.lastSyncError = "D.6 publish failed: " + exception.message
       slot.lastAttemptedAt = now
       Persist slot. (Status stays PENDING_SYNC — the EB draft event is preserved, do NOT recreate)
       Publish SlotSyncFailedEvent(slotId, reason)
       Log at WARN level: "Publish failed for slot={} eb_event={}: {}"
```

**Retry on publish failure:** Admin calls `retry-sync` on the slot. `ShowSlotService.retrySync()` detects `eb_event_id != null` and `status == PENDING_SYNC` → skips D.1, skips D.2–D.5 (pricing tier `eb_ticket_class_id` already populated) → re-attempts D.6 only, via `EbEventSyncService.publishEvent()` directly.

---

### 13. Slot Activated — Cache & Catalog Chain

**Triggered by:** `ShowSlotActivatedEvent` (from D.6 success)

**Owner:** `discovery-catalog` → `SlotActivatedListener.onSlotActivated(event)`

```
1. Call EventCatalogSyncService.sync(orgId, cityId, venueId):
       GET /organizations/{eb_org_id}/events/ (or /venues/{eb_venue_id}/events/)
       Upsert event_catalog row for this eb_event_id.
       Ensures the new ACTIVE slot appears in catalog immediately — 
       does NOT wait for next 4-hour scheduled sync.
2. Invalidate snapshot cache:
       snapshotCache.invalidate(orgId, cityId)
       Next public catalog read (GET /api/v1/catalog/events?cityId=…) repopulates from DB.
```

> This directly addresses the Flow 1 integration problem identified in Stage 3: `EventbriteWebhookEventListener` only updates EXISTING rows. `SlotActivatedListener` creates the row when the slot first goes live. See SPEC_1 Stage 6 §Async Refresh Flow for the upsert behaviour inside `EventCatalogSyncService`.

---

### 14. Slot Update Rules (ACTIVE Slots)

**Owner:** `scheduling` → `ShowSlotService.updateSlot(orgId, slotId, request)`

**Allowed field edits per status:**

| Field | DRAFT | PENDING_SYNC | ACTIVE | CANCELLED |
|---|---|---|---|---|
| `title` | ✓ | ✗ blocked | ✓ (EB update) | ✗ terminal |
| `startTime` / `endTime` | ✓ | ✗ blocked | ✓ (EB update + conflict recheck) | ✗ terminal |
| `capacity` | ✓ | ✗ blocked | ✓ (EB capacity_tier update) | ✗ terminal |
| `pricingTiers` (add/edit) | ✓ | ✗ blocked | ✓ (EB ticket_class update) | ✗ terminal |
| `seatingMode` | ✓ | ✗ blocked | ✗ locked | ✗ terminal |
| `isRecurring` / `recurrenceRule` | ✓ | ✗ blocked | ✗ locked | ✗ terminal |

**`PENDING_SYNC` is blocked for all edits** — slot is mid-sync. Admin must wait for `ACTIVE` or cancel to retry.

**Update flow for ACTIVE slots:**
```
1. Re-run conflict detection with excludeSlotId = slotId (skip self-conflict check).
   If new times cause conflict → throw SlotConflictException with alternatives.
2. Persist updated fields to ShowSlot.
3. Call EbEventSyncService.updateEvent(token, ebEventId, changedFields):
       POST /events/{event_id}/
4. If capacity changed → also call EbCapacityService.updateCapacityTier(token, ebEventId, newCapacity):
       POST /events/{event_id}/capacity_tier/
5. On EB call failure:
       Internal update is already persisted (source of truth = internal DB).
       Record last_sync_error = "EB update failed: " + reason.
       Return 207 Multi-Status: { "internalUpdate": "OK", "ebSyncStatus": "FAILED", "error": "…" }
       Slot remains ACTIVE — no state regression.
```

---

### 15. Slot Cancellation Rules

**Owner:** `scheduling` → `ShowSlotService.cancelSlot(orgId, slotId)`

**CANCELLED is a terminal state.** Once cancelled:
- No further EB sync attempts.
- No status regression possible.
- `SlotConflictException` will never reference a CANCELLED slot (conflict query excludes `CANCELLED`).

**Cancellation flow:**
```
1. Load ShowSlot. Verify orgId ownership.
2. Verify status is ACTIVE or PENDING_SYNC. If DRAFT → throw BusinessRuleException("DRAFT slots must be deleted, not cancelled").
3. Transition via state machine: * → CANCELLED.
4. Persist (status = CANCELLED).
5. If eb_event_id is NOT null:
       Call EbEventSyncService.cancelEvent(token, ebEventId):
           POST /events/{event_id}/cancel/
       On success: log INFO.
       On failure: log WARN. Record last_sync_error = "EB cancel failed: " + reason.
           Do NOT block cancellation — internal cancellation already committed.
6. Publish ShowSlotCancelledEvent(slotId, ebEventId, orgId, venueId, cityId)
```

**EB cancel failure is non-blocking** (PRODUCT.md FR2 Phase 4 + Req F). The slot is `CANCELLED` internally regardless. Admin can re-attempt EB cancel from the admin panel. The EB event will also eventually be cancelled by the EB-side event if it is not sold out and admin manually cancels via EB Dashboard.

---

### 16. Slot Cancelled — Catalog & Cache Chain

**Triggered by:** `ShowSlotCancelledEvent` (from cancellation flow)

**Owner:** `discovery-catalog` → `SlotCancelledListener.onSlotCancelled(event)`

```
1. Look up event_catalog row where eventbrite_event_id = event.ebEventId AND deleted_at IS NULL.
2. If found:
       set event_catalog.state = 'CANCELLED'
       set event_catalog.updated_at = now
       Persist. (Do NOT set deleted_at — the record is kept for historical reference)
3. Invalidate snapshot cache:
       snapshotCache.invalidate(orgId, cityId)
       Next public catalog read no longer returns this event (state filter in query excludes CANCELLED).
4. If event_catalog row NOT found (slot was never ACTIVE / no catalog row ever created):
       No-op. Log INFO: "No catalog row to cancel for eb_event_id={}"
```

> Flow 1 `EventbriteWebhookEventListener` handles `event.unpublished` / `event.cancelled` webhooks from EB but only updates existing rows and invalidates cache — it does NOT set `state = CANCELLED`. `SlotCancelledListener` bridges this gap for internally-initiated cancellations.

---

### 17. Recurring Schedule Rules

**Trigger:** Slot reaches ACTIVE (D.6 success) AND `slot.isRecurring = true`.

**Called immediately after D.6 publish, before publishing `ShowSlotActivatedEvent`:**

```
1. EbScheduleService.createSchedule(token, ebEventId, recurrenceRule):
       POST /events/{event_id}/schedules/
       payload: { recurrence: parseRuleToEbFormat(recurrenceRule) }
       (EB expects 'schedules' array with 'frequency', 'interval', 'count'/'end_date', etc.)
2. On success:
       store returned eb_series_id on ShowSlot.
       Retrieve occurrence list: GET /events/{event_id}/series/
       for each occurrence returned:
           create ShowSlotOccurrence(parentSlotId, occurrenceIndex, startTime, endTime,
                                    status=ACTIVE, ebEventId=occurrence.id)
       Persist all occurrences.
3. On failure (EB schedule creation error):
       Log WARN: "Recurring schedule creation failed for slot={}: {ebError}"
       Surface EB error code + human-readable message to admin (e.g., ERROR_CANNOT_PUBLISH_SERIES_WITH_NO_DATES).
       Slot stays ACTIVE (the series head event is published on EB — only the schedule failed).
       Set lastSyncError = "Recurring schedule failed: " + ebErrorCode.
       ShowSlotActivatedEvent is still published (the primary ACTIVE event exists and must reach catalog).
4. After step 2 or 3's resolution → publish ShowSlotActivatedEvent(slotId, ebEventId, orgId, venueId, cityId).
```

**rrule format rule:** `recurrenceRule` stored as string in `show_slot.recurrence_rule`. EB expects a JSON schedule object, not iCal RRULE directly. `EbScheduleService` is responsible for translation — it encapsulates the format details. `ShowSlotService` passes the raw RRULE string; `EbScheduleService.createSchedule()` converts it before the HTTP call.

---

### 18. State Machine Transitions

**Owner:** `scheduling/statemachine/` — Spring State Machine

**States (enum `ShowSlotState`):** `DRAFT`, `PENDING_SYNC`, `ACTIVE`, `CANCELLED`

**Events (enum `ShowSlotEvent`):** `SUBMIT`, `EB_PUBLISHED`, `EB_FAILED`, `CANCEL`

**Transition table:**

| Current State | Event | New State | Guard | Action |
|---|---|---|---|---|
| `DRAFT` | `SUBMIT` | `PENDING_SYNC` | Has ≥1 pricing tier | `SlotStatusUpdateAction` |
| `PENDING_SYNC` | `EB_PUBLISHED` | `ACTIVE` | `eb_event_id` not null | `SlotStatusUpdateAction` + reset `syncAttemptCount` |
| `PENDING_SYNC` | `EB_FAILED` | `PENDING_SYNC` *(self)* | — | increment `syncAttemptCount`, record `lastSyncError` |
| `PENDING_SYNC` | `CANCEL` | `CANCELLED` | — | `SlotStatusUpdateAction` |
| `ACTIVE` | `CANCEL` | `CANCELLED` | — | `SlotStatusUpdateAction` |

**Any other transition (e.g., `ACTIVE → PENDING_SYNC`, `CANCELLED → ACTIVE`, `CANCELLED → DRAFT`):**
→ `ShowSlotStateMachineService.sendEvent()` throws `BusinessRuleException("Invalid slot transition: {from} → {to}")`.

**`SlotStatusUpdateAction`:** Writes the new `ShowSlotStatus` to the `ShowSlot` entity and calls `showSlotRepository.save()` within the action. This ensures the status update is persisted atomically with the transition trigger.

**`SlotTransitionGuard`:** Checks preconditions before allowing a transition. If the guard returns `false`, the state machine rejects the event and `sendEvent()` throws `BusinessRuleException`.

---

### 19. `sync_attempt_count` Lifecycle

| Event | Effect on `sync_attempt_count` |
|---|---|
| Slot created as DRAFT | Set to `0` |
| Slot submitted (D.1 succeeds) | Stays `0` (no failure) |
| D.1 / D.2–D.5 / D.6 failure | Increment by 1 |
| D.6 succeeds (slot → ACTIVE) | **Reset to `0`** |
| Slot updated while ACTIVE (EB call fails) | Stays `0` — update failure uses `lastSyncError` only |
| Slot cancelled | No change (terminal state) |

**Admin visibility threshold:** If `syncAttemptCount >= 3` and status == `PENDING_SYNC`, the slot appears in `GET /api/v1/scheduling/slots/mismatches` and the `GET /api/v1/admin/scheduling/slots/flagged` aggregation.

---

### 20. Slot Reconciliation Job (`SlotReconciliationJob`)

**Owner:** `scheduling` → `@Scheduled`

**Cadence:** `@Scheduled(fixedDelay = "PT1H")` — every hour, after last run ends.

**Algorithm:**
```
Load all ShowSlot where status = ACTIVE AND eb_event_id IS NOT NULL:
for each slot:
    try:
        GET /events/{eb_event_id}/
        if response not found (404) OR status in [CANCELLED, DELETED]:
            slot.lastSyncError = "EB mismatch: EB event status=" + ebStatus + " but internal=ACTIVE"
            slot.lastAttemptedAt = now
            (do NOT auto-change internal status — flag only)
            persist slot
            log WARN: "Slot {} eb_event {} mismatch: internal=ACTIVE, EB={}"
        else if response.status == LIVE:
            // all good — no action
    catch EbAuthException:
        log ERROR: "Reconciliation aborted for slot {}: EB auth failure"
        break inner loop — no point retrying all slots if token is expired
    catch Exception:
        log WARN: "Reconciliation check failed for slot {}: {}"
        continue to next slot
```

**Flag-not-fix policy:** Mismatches are flagged via `lastSyncError`. The reconciliation job NEVER auto-transitions an ACTIVE slot to CANCELLED. Admin sees it in `GET /api/v1/scheduling/slots/mismatches`.

**Scheduler failure handling:** Outer `try-catch(Throwable)` at job level catches anything not caught by the inner loop. Logs error. Scheduler thread continues — subsequent scheduled runs execute normally.

---

### 21. Flow 1 × Flow 2 Cache Integration Summary

This section consolidates how Flow 2 state changes interact with the Flow 1 snapshot cache (defined in SPEC_1 Stage 6).

| Flow 2 Event | Who handles it | Cache effect |
|---|---|---|
| Venue CREATED | `VenueService.createVenue()` | None — no event_catalog row yet |
| Venue UPDATED | `VenueService.updateVenue()` | `snapshotCache.invalidate(orgId, cityId)` — venue name/address in event cards |
| Slot → ACTIVE | `SlotActivatedListener` (from `ShowSlotActivatedEvent`) | `EventCatalogSyncService.sync()` creates catalog row. Then `snapshotCache.invalidate(orgId, cityId)`. |
| Slot → CANCELLED | `SlotCancelledListener` (from `ShowSlotCancelledEvent`) | Sets `event_catalog.state = CANCELLED`. Then `snapshotCache.invalidate(orgId, cityId)`. |
| EB webhook (event.updated) | `EventbriteWebhookEventListener` (Flow 1, existing) | Invalidates cache if existing row found. Does NOT create new rows. |
| VenueReconciliation flags drift | `VenueDriftDetectedEvent` published | No direct cache effect — admin resolves, then venue update triggers invalidation |

**Read path (from Flow 1, unchanged):** `GET /api/v1/catalog/events?cityId=…` → check Redis snapshot (`catalog:snapshot:{orgId}:{cityId}`) → on miss, query DB → return with `stale` flag and `source` indicator. ACTIVE EB-synced slots now appear in catalog within seconds of `SlotActivatedListener` running (not up to 4 hours).

STAGE6_EOF
echo "Done. Line count:"
wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "---" && tail -12 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Good — 2050 lines (630 lines added). Let me do a quick sanity check on the section headings to confirm structure: 

Ran terminal command:  grep -n "^### [0-9]" /home/dell/Multi-City-Event-Booking/SPEC_2.md | tail -30

All 21 sections confirmed. Let me also verify the `## Stage 6` heading is correct: 

Ran terminal command:  grep -n "^## Stage" /home/dell/Multi-City-Event-Booking/SPEC_2.md

Completed: *Write Stage 6 Business Logic* (1/1)

Stage 6 — Business Logic is written. SPEC_2.md is now **2050 lines** (Stages 1–6 complete).

---

**Stage 6 covers 21 sections:**

| # | Section | Key content |
|---|---|---|
| 1 | Org Context Resolution | JWT claim resolution; `EbAuthException` if no valid token |
| 2 | OAuth Onboarding Flow | CSRF state in Redis, code exchange, upsert on reconnect, disconnect |
| 3 | Token Refresh Interceptor | Pre-call `expires_at < now + 5min` check inside `EbTokenStore`; failure keeps slot `PENDING_SYNC` |
| 4 | Venue Creation Flow | Internal-first; EB push; `PENDING_SYNC` on failure; no cache effect at creation |
| 5 | Venue Update Flow | EB sync after persist; always invalidate `snapshotCache(orgId, cityId)` after update |
| 6 | VenueReconciliationJob | Hourly; flag-not-fix; no scheduler thread death on per-record failure |
| 7 | Slot Creation (DRAFT) | Venue REST fetch; conflict guard; seating mode + tier validation; no EB call |
| 8 | Conflict Detection Rules | Overlap SQL query + turnaround gap property; both must pass |
| 9 | Alternatives Builder | Same-venue next windows, adjusted times, REST-fetched nearby venues |
| 10 | Slot Submission D.1 | `DRAFT → PENDING_SYNC`; EB draft create; `SlotDraftCreatedEvent` on success |
| 11 | D.2–D.5 (booking-inventory) | Sequential: ticket classes → inventory tiers → capacity (GA) → seatmap (Reserved); failure atomicity |
| 12 | D.6 Publish | `TicketSyncCompletedEvent` → EB publish → `ACTIVE`; retry skips D.1–D.5 if EB draft exists |
| 13 | Slot Activated Cache Chain | `SlotActivatedListener` → `EventCatalogSyncService.sync()` + `snapshotCache.invalidate()` |
| 14 | Slot Update Rules (ACTIVE) | Field edit whitelist per status; 207 Multi-Status on EB failure (DB is source of truth) |
| 15 | Slot Cancellation Rules | Internal-first; EB failure non-blocking; terminal state |
| 16 | Slot Cancelled Cache Chain | `SlotCancelledListener` → `state = CANCELLED` on catalog row + cache invalidation |
| 17 | Recurring Schedule Rules | `is_series: true` in D.1; schedule after D.6; occurrence records; failure non-blocking for `ShowSlotActivatedEvent` |
| 18 | State Machine Transitions | Full guard/action table; `BusinessRuleException` on invalid transitions |
| 19 | `sync_attempt_count` Lifecycle | Reset to 0 on ACTIVE; increment on each failure; threshold ≥3 triggers `mismatches` flag |
| 20 | SlotReconciliationJob | Hourly; flag-not-fix; `EbAuthException` breaks inner loop; scheduler survives per-record errors |
| 21 | Flow 1 × Flow 2 Cache Integration | Complete impact table mapping every Flow 2 event to its cache effect |

Next: **Stage 7 — Error Handling**. Ready when you are.

User: Yes now stage 7 error handling get the outline

GitHub Copilot: Here's the Stage 7 outline. Confirm and I'll write the full section.

---

**Stage 7 — Error Handling: Outline (12 categories)**

1. **Request Validation Errors (400)** — missing required fields, invalid date ranges (`end_time ≤ start_time`), negative capacity, invalid enum values, `sourceSeatMapId` missing for RESERVED seating. Handled by `@Valid` + `GlobalExceptionHandler` → `ErrorResponse`.

2. **Resource Not Found (404)** — venue not found, slot not found, org `OrganizationAuth` not found, occurrence not found. Module-level `CatalogNotFoundException` / `SchedulingNotFoundException` extend `ResourceNotFoundException` from shared.

3. **Slot Conflict (422)** — `SlotConflictException` carries `ConflictAlternativeResponse` in `ErrorResponse.details`. Includes `errorCode = SLOT_CONFLICT`. GlobalExceptionHandler extracts `alternatives` from exception and serializes into response body.

4. **State Machine Violation (422)** — `BusinessRuleException` on invalid transitions (`ACTIVE → DRAFT`, `CANCELLED → ACTIVE`, editing a `PENDING_SYNC` slot, submitting a slot with no pricing tiers). All throw `BusinessRuleException` → 422 via GlobalExceptionHandler.

5. **OAuth Errors** — CSRF state mismatch (400), EB code exchange failure (502 `EbAuthException`), token missing/revoked (502), disconnect response (200). `OrgAuthStatus.REVOKED` check in `EbTokenStore` → immediate `EbAuthException` before any EB call.

6. **Token Refresh Failure (502)** — `EbTokenStore` refresh fails → `EbAuthException` → propagates from facade → `ShowSlotService` catches, records `last_sync_error`, keeps slot `PENDING_SYNC`, returns 502 to admin.

7. **EB API Transient Failures — D.1, D.6 (5xx / timeout / 429)** — `@Retryable` via `EbRetryPolicy` (exponential backoff, `Retry-After`-aware). After max retries: slot stays `PENDING_SYNC`, `syncAttemptCount++`, `lastSyncError` recorded, 502 to admin. EB draft event not re-created on D.6 retry.

8. **EB API Transient Failures — D.2–D.5 (booking-inventory, async)** — No HTTP response to admin (async path). `TicketSyncFailedEvent` published with `reason="D.{step}: {error}"`. `scheduling` listener records error on slot. Idempotency: skip D.2 per tier if `eb_ticket_class_id` already populated.

9. **Slot Update EB Failure (207)** — internal update already persisted (source of truth). Return `207 Multi-Status: { internalUpdate: OK, ebSyncStatus: FAILED, error: "…" }`. No `BusinessRuleException` — slot stays `ACTIVE`.

10. **Slot Cancellation EB Failure** — non-blocking. Internal cancellation already committed. Log `WARN`. Record `lastSyncError`. No HTTP error to admin — cancellation response is `200 OK` with a `syncWarning` field.

11. **Reconciliation Job Failures** — per-record `try/catch`, logs `WARN`, continues to next record. `EbAuthException` breaks the inner loop (no point retrying all slots). Outer `try/catch(Throwable)` prevents scheduler thread death. Micrometer counter `reconciliation.errors`.

12. **Venue EB Sync Failures** — creation: `202 Accepted` with `sync_status = PENDING_SYNC`. Update: `200 OK` with `sync_status = DRIFT_FLAGGED` + `lastSyncError`. Venue reconciliation drift: `VenueDriftDetectedEvent` (no HTTP error).

---

Confirm to write the full Stage 7 section.

User: Yes write the full section but again check the flow 1 for conflicts or alredy existing ones like the 400 already been implemneted for error recheck and then update it

GitHub Copilot: Let me re-read SPEC_1.md Stage 7 fully before writing, then cross-reference carefully. 

Ran terminal command:  grep -n "^## Stage 7\|^## Stage 8\|^## Resolved" /home/dell/Multi-City-Event-Booking/SPEC_1.md

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_1.md#528-528), lines 528 to 582

Good — I have the full picture. Now I'll write Stage 7 with precise cross-references to Flow 1, distinguishing shared patterns from new ones. 

Ran terminal command:  cat >> /home/dell/Multi-City-Event-Booking/SPEC_2.md << 'STAGE7_EOF'

---

## Stage 7 — Error Handling

> **GlobalExceptionHandler contract (Hard Rule #5):** One `@ControllerAdvice` lives in
> `shared/common/exception/GlobalExceptionHandler`. No module declares its own. All HTTP error
> shapes conform to `ErrorResponse` from `shared/common/dto/`.
>
> **Flow 1 baseline:** SPEC_1 Stage 7 defines rules 1–9 for the catalog/webhook path. Those rules
> remain unchanged and are not repeated here. Flow 2 adds or refines rules for the scheduling
> path. Cross-references are noted per section.

---

### Key Distinction — Two Failure Counters

Flow 1 and Flow 2 both track consecutive failures, but on **different entities** for **different purposes**:

| Counter | Entity | Purpose |
|---|---|---|
| `WebhookConfig.consecutiveFailures` | `discovery-catalog` (Flow 1) | Tracks EB catalog sync / webhook delivery failures. Drives `cooldown_until` on the org. |
| `ShowSlot.syncAttemptCount` | `scheduling` (Flow 2) | Tracks EB event sync failures for a specific slot. Drives admin visibility (`mismatches` list). No cooldown — slot is retry-eligible after every failure. |

They are independent. A slot failing EB sync does NOT increment `WebhookConfig.consecutiveFailures`. A webhook delivery failure does NOT affect `ShowSlot.syncAttemptCount`.

---

### Exception Hierarchy — New Types Added by Flow 2

All new exceptions extend existing `shared/` base classes. No new base classes are introduced.

```
shared/common/exception/
├── ResourceNotFoundException (404)       ← from Flow 1
│   └── SchedulingNotFoundException       ← NEW  slot / occurrence / org-auth not found
│   └── CatalogNotFoundException          ← from Flow 1 (venue not found in discovery-catalog)
├── BusinessRuleException (422)           ← from shared/ — used in Flow 1; now also used in Flow 2
│   └── SlotConflictException (422)       ← NEW  carries ConflictAlternativeResponse in payload
├── ValidationException (400)             ← from Flow 1
│   └── SlotValidationException           ← NEW  slot/venue request validation failures
└── SystemIntegrationException (502)      ← from shared/
    └── EbAuthException                   ← from Flow 1; reused in Flow 2 token refresh path
    └── EbIntegrationException            ← from Flow 1; reused in Flow 2 EB call failures
```

**GlobalExceptionHandler additions for Flow 2:**

| Exception | HTTP Status | `errorCode` |
|---|---|---|
| `SlotConflictException` | 422 | `SLOT_CONFLICT` |
| `BusinessRuleException` | 422 | `BUSINESS_RULE_VIOLATION` |
| `SlotValidationException` | 400 | `SLOT_VALIDATION_ERROR` |
| `SchedulingNotFoundException` | 404 | `SLOT_NOT_FOUND` / `VENUE_NOT_FOUND` |
| `EbAuthException` (token path) | 502 | `EB_AUTH_FAILURE` |
| `EbIntegrationException` (sync path) | 502 | `EB_INTEGRATION_ERROR` |

> `EbAuthException` and `EbIntegrationException` are reused from Flow 1 (`shared/eventbrite/exception/`). They are already mapped in `GlobalExceptionHandler` from Flow 1. No duplicate mapping needed — confirm the handler maps these to 502 with the `errorCode` fields above.

---

### 1. Request Validation Errors (400)

**Applies to:** `POST /api/v1/admin/catalog/venues`, `PUT /api/v1/admin/catalog/venues/{id}`, `POST /api/v1/scheduling/slots`, `PUT /api/v1/scheduling/slots/{id}`, `POST /api/v1/scheduling/slots/{id}/submit`

**Validation failures handled by `@Valid` on request DTOs + `GlobalExceptionHandler`:**

| Field | Condition | Message |
|---|---|---|
| `name` (venue/slot) | blank or null | `"name is required"` |
| `cityId` (venue) | null | `"cityId is required"` |
| `venueId` (slot) | null | `"venueId is required"` |
| `startTime` / `endTime` | null | `"startTime and endTime are required"` |
| `endTime ≤ startTime` | custom `@ValidTimeRange` | `"endTime must be after startTime"` |
| `capacity` | ≤ 0 | `"capacity must be greater than 0"` |
| `seatingMode` | invalid enum value | `"seatingMode must be GA or RESERVED"` |
| `sourceSeatMapId` | null when `seatingMode = RESERVED` | `"sourceSeatMapId is required for RESERVED seating"` |
| `pricingTiers` | empty list on submit | `"At least one pricing tier is required"` |
| `pricingTier.priceAmount` | < 0 | `"priceAmount must be non-negative"` |
| `pricingTier.quota` | ≤ 0 | `"quota must be greater than 0"` |
| `pricingTier.tierType = FREE` AND `priceAmount > 0` | custom validator | `"FREE tier must have priceAmount = 0"` |
| `currency` | not 3-char ISO 4217 | `"currency must be a valid ISO 4217 code"` |

**Error shape:**
```json
{
  "errorCode": "SLOT_VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [
    { "field": "endTime", "message": "endTime must be after startTime" },
    { "field": "sourceSeatMapId", "message": "sourceSeatMapId is required for RESERVED seating" }
  ]
}
```

> **Flow 1 alignment:** Flow 1 (SPEC_1 Stage 7) does not define 400 validation handling explicitly — it defines `InvalidCatalogSearchException` for bad search params. The same `GlobalExceptionHandler` handles both. No conflict: `@MethodArgumentNotValidException` (Spring's bean validation) is already handled by `GlobalExceptionHandler` returning a 400 with field-level `details` array. Flow 2 leverages this same handler — no change needed to the handler itself, only new validator annotations on the new DTOs.

---

### 2. Resource Not Found (404)

**Trigger locations:**
- `ShowSlotService`: slot not found by `slotId` → `SchedulingNotFoundException("Slot not found: " + slotId)`
- `ShowSlotService`: occurrence not found → `SchedulingNotFoundException("Occurrence not found: " + occurrenceId)`
- `VenueService` (discovery-catalog): venue not found by `venueId` → `CatalogNotFoundException("Venue not found: " + venueId)` *(reused from Flow 1)*
- `OrgOAuthService` / `EbTokenStore`: no `OrganizationAuth` row for `orgId` → `SchedulingNotFoundException("No EB auth configured for org: " + orgId)` *(before attempting EB connection)*
- `scheduling` REST call to `discovery-catalog` returns 404 → propagated as `SchedulingNotFoundException("Venue not found via catalog: " + venueId)`

**Error shape (standard):**
```json
{
  "errorCode": "SLOT_NOT_FOUND",
  "message": "Slot not found: 9999"
}
```

> **Flow 1 alignment:** `CatalogNotFoundException` is already defined and mapped in `GlobalExceptionHandler`. `SchedulingNotFoundException` is a new peer class extending the same `ResourceNotFoundException`. No change to the handler dispatch logic — just a new subclass registered.

---

### 3. Slot Conflict (422)

**Trigger:** `ConflictDetectionService.checkConflicts()` detects overlap or turnaround gap violation.

**Exception:** `SlotConflictException(ConflictAlternativeResponse alternatives)` extends `BusinessRuleException`.

**Error shape:**
```json
{
  "errorCode": "SLOT_CONFLICT",
  "message": "A conflicting slot exists at this venue for the requested time window.",
  "alternatives": {
    "sameVenueAlternatives": [
      { "proposedStart": "2026-04-15T21:00:00+05:30", "proposedEnd": "2026-04-16T01:00:00+05:30" }
    ],
    "nearbyVenueAlternatives": [
      { "venueId": 12, "venueName": "Jio World Garden", "city": "Mumbai", "capacity": 5000 }
    ],
    "adjustedTimeOptions": [
      { "proposedStart": "2026-04-15T22:00:00+05:30", "proposedEnd": "2026-04-16T02:00:00+05:30" }
    ]
  }
}
```

**GlobalExceptionHandler rule:** Catch `SlotConflictException` → 422 → serialize `exception.getAlternatives()` into `alternatives` field on the `ErrorResponse`.

**Empty alternatives handling:** If `nearbyVenueAlternatives` list is empty (no other synced venues in city), the list is `[]` — not omitted. Frontend renders a "no alternatives available" message.

---

### 4. State Machine Violations (422)

**Trigger:** `ShowSlotStateMachineService.sendEvent()` rejects a transition because:
- Current state does not allow the requested event (e.g., `ACTIVE → DRAFT` via submission)
- Guard fails (e.g., no pricing tier on SUBMIT, `eb_event_id` null on EB_PUBLISHED guard)
- Admin attempts to edit a `PENDING_SYNC` or `CANCELLED` slot field that is locked

**Exception:** `BusinessRuleException("Invalid slot transition: {from} → {to}")` or `BusinessRuleException("{specific rule message}")`

**Common messages:**

| Trigger | Message |
|---|---|
| Submit non-DRAFT slot | `"Only DRAFT slots can be submitted"` |
| Submit slot with no pricing tiers | `"Slot must have at least one pricing tier before submission"` |
| Edit PENDING_SYNC slot | `"Slot cannot be edited while sync is in progress (PENDING_SYNC)"` |
| Edit CANCELLED slot | `"Slot is in a terminal state and cannot be modified"` |
| Cancel DRAFT slot | `"DRAFT slots must be deleted, not cancelled"` |
| Change seatingMode on ACTIVE slot | `"seatingMode cannot be changed after slot is ACTIVE"` |
| Change isRecurring / recurrenceRule on ACTIVE slot | `"Recurrence settings cannot be changed after slot is ACTIVE"` |
| ACTIVE → PENDING_SYNC transition | `"Invalid slot transition: ACTIVE → PENDING_SYNC"` |
| CANCELLED → any active state | `"Invalid slot transition: CANCELLED → {target}"` |

**Error shape:**
```json
{
  "errorCode": "BUSINESS_RULE_VIOLATION",
  "message": "Only DRAFT slots can be submitted"
}
```

> **Flow 1 alignment:** `BusinessRuleException` → 422 is already defined in `shared/` and mapped in `GlobalExceptionHandler`. No handler change needed.

---

### 5. OAuth Errors

#### 5a. CSRF State Mismatch (400)
- `state` param in callback does not match Redis key `oauth:state:{orgId}`, or key expired.
- Throw `ValidationException("OAuth state mismatch or expired. Restart the connection flow.")` → 400.
- Delete the Redis key regardless (one-time use, prevent replay).
- Log WARNING: `"OAuth CSRF state mismatch for org={}: expected={}, got={}"`.

#### 5b. EB Code Exchange Failure (502)
- Eventbrite returns non-200 on `POST /oauth/token` (e.g., `invalid_grant`, `invalid_client`).
- Throw `EbAuthException("OAuth code exchange failed: {eb_error_description}")` → 502.
- Do NOT persist any `OrganizationAuth` row.
- Log ERROR: `"EB OAuth code exchange failed for org={}: {}"`.

#### 5c. Token Missing or Revoked (502)
- `EbTokenStore.getValidToken(orgId)` finds no row, or `status = REVOKED`.
- Throw `EbAuthException("No valid EB token for org " + orgId)` → 502.
- Admin must re-initiate OAuth flow.
- Response: `{ "errorCode": "EB_AUTH_FAILURE", "message": "Eventbrite not connected for this org. Please reconnect via /admin/orgs/{orgId}/eventbrite/connect." }`

#### 5d. Disconnect (200)
- Always succeeds internally. No EB API call.
- Even if `OrganizationAuth` row does not exist → return `200 { "status": "REVOKED" }` (idempotent).
- No exception thrown.

> **Flow 1 alignment:** `EbAuthException` is defined in `shared/eventbrite/exception/` for Flow 1's async refresh path (immediate cooldown on `WebhookConfig`). Flow 2 **reuses the same exception class** but the calling context differs: in Flow 2, the exception surfaces synchronously to the admin API caller rather than being handled inside an async listener. `GlobalExceptionHandler` maps it to 502 in both cases — no handler change needed. The `WebhookConfig.consecutiveFailures` cooldown logic is NOT triggered by Flow 2 token failures (different entity, different concern).

---

### 6. Token Refresh Failure in Sync Path (502)

**Path:** `EbTokenStore.refreshToken()` fails during `EbTokenStore.getValidToken()` called from any facade during slot sync (D.1, D.6, or venue EB call).

```
EbTokenStore.getValidToken() throws EbAuthException("Token refresh failed for org " + orgId)
    ↓ propagates through facade (EbEventSyncService, EbVenueService, etc.)
    ↓ caught in ShowSlotService.submitSlot() / onTicketSyncComplete() / cancelSlot()
    ↓ ShowSlotService records:
         slot.syncAttemptCount += 1
         slot.lastSyncError = "EB token refresh failed. Reconnect Eventbrite."
         slot.lastAttemptedAt = now
         persist slot (status stays PENDING_SYNC)
    ↓ throw EbAuthException upstream → GlobalExceptionHandler → 502 to admin
```

**Key distinction from Flow 1:** Flow 1 (SPEC_1 §7.2) handles auth failure in the **async catalog refresh path** — it enters `WebhookConfig` cooldown and does not surface an HTTP error to any user. Flow 2 auth failure surfaces as a **synchronous 502** to the admin who triggered slot submission. The two paths are fully independent.

**OrganizationAuth state update:** `EbTokenStore` sets `OrganizationAuth.status = TOKEN_EXPIRED` on refresh failure. Admin reconnects via `POST /api/v1/admin/orgs/{orgId}/eventbrite/connect`.

---

### 7. EB Transient Failures — D.1 Draft Creation (502 synchronous)

**Path:** `EbEventSyncService.createDraft()` fails (5xx, timeout, 429).

**Retry policy (via `@Retryable` on `EbEventSyncService.createDraft()`):**
```
max attempts: ${eb.sync.retry.max-attempts:3}
backoff: exponential, initial ${eb.sync.retry.initial-interval:1s}, multiplier 2
max backoff: ${eb.sync.retry.max-interval:30s}
retry on: EbIntegrationException (5xx, timeout)
Retry-After header: if EB returns 429, back off for Retry-After seconds before next attempt
no retry on: EbAuthException (auth failures are not transient)
```

**After all retries exhausted:**
```
slot.syncAttemptCount += 1
slot.lastSyncError = "D.1 draft creation failed after {n} attempts: {lastException.message}"
slot.lastAttemptedAt = now
persist slot (status stays PENDING_SYNC, eb_event_id remains null)
return 502 to admin:
    { "errorCode": "EB_INTEGRATION_ERROR",
      "message": "Eventbrite event draft creation failed. Slot is queued for retry.",
      "slotId": 101, "syncAttemptCount": 1 }
```

**Admin action:** Slot is visible in `GET /api/v1/scheduling/slots?status=PENDING_SYNC`. Admin retries via `POST /api/v1/scheduling/slots/{id}/retry-sync`.

---

### 8. EB Transient Failures — D.2–D.5 Ticket/Inventory/SeatMap (async, no HTTP)

**Path:** `SlotDraftCreatedListener` → `SlotTicketSyncService` fails on any D.2–D.5 step.

**No HTTP response to admin** (this runs async after `SlotDraftCreatedEvent`). The admin's original `/submit` call already returned `200`.

**Retry policy:** `@Retryable` on each `EbTicketService` / `EbCapacityService` method call — same config as §7. Retry within `SlotTicketSyncService` before publishing failure event.

**After all retries on a step exhausted:**
```
publish TicketSyncFailedEvent(slotId, ebEventId, reason="D.{step}: {lastException.message}")
    ↓
TicketSyncFailedListener (scheduling):
    slot.syncAttemptCount += 1
    slot.lastSyncError = event.reason
    slot.lastAttemptedAt = now
    persist slot (status stays PENDING_SYNC)
    publish SlotSyncFailedEvent(slotId, reason) ← for future admin notification hook
    log WARN: "Ticket sync failed for slot={} eb_event={}: {}"
```

**Idempotency on retry:** When admin retries via `retry-sync`, `SlotTicketSyncService` checks each `ShowSlotPricingTier.ebTicketClassId` before calling D.2. If already populated → skip that tier's D.2 call. Same for `ebInventoryTierId` before D.3. This prevents duplicate ticket class creation on Eventbrite on partial retries.

**Partial failure atomicity:** There is no EB rollback of completed steps. If D.3 fails after D.2 succeeded, the ticket classes remain on EB. Partial state is visible in `ShowSlotPricingTier` rows.

> **Flow 1 alignment:** Flow 1 (SPEC_1 §7.1) handles EB 5xx in the async catalog refresh path and increments `WebhookConfig.consecutiveFailures`. Flow 2's async failure path increments `ShowSlot.syncAttemptCount` instead. Same pattern, different counters, different entities — no conflict.

---

### 9. EB Transient Failures — D.6 Publish (502 synchronous)

**Path:** `TicketSyncCompletedListener` → `ShowSlotService.onTicketSyncComplete()` → `EbEventSyncService.publishEvent()` fails.

> This runs asynchronously from the admin's `/submit` call but synchronously from the event listener's perspective. The admin sees the slot in `PENDING_SYNC` and checks status.

**Retry policy:** `@Retryable` on `EbEventSyncService.publishEvent()` — same config as §7.

**After all retries exhausted:**
```
slot.syncAttemptCount += 1
slot.lastSyncError = "D.6 publish failed: " + lastException.message
slot.lastAttemptedAt = now
persist slot (status stays PENDING_SYNC)
log WARN: "Publish failed for slot={} eb_event={}"
publish SlotSyncFailedEvent(slotId, reason)
```

**Critical rule:** The EB draft event (`eb_event_id` is populated) is **NOT re-created** on D.6 failure. On admin retry, `ShowSlotService.retrySync()` sees `eb_event_id != null` and `status = PENDING_SYNC` → calls `publishEvent()` directly, skipping D.1 through D.5.

**When both D.5 and D.6 fail in sequence:** `syncAttemptCount` is incremented twice (once by D.5 failure path, once by D.6). This is correct — two distinct failures occurred.

---

### 10. Slot Update EB Failure (207 Multi-Status)

**Path:** `ShowSlotService.updateSlot()` → `EbEventSyncService.updateEvent()` or `EbCapacityService.updateCapacityTier()` fails after the internal DB update was already committed.

**Rule:** The internal DB update is NOT rolled back. Internal DB is source of truth (PRODUCT.md §5.1).

**Response: `207 Multi-Status`**
```json
{
  "internalUpdate": "SUCCESS",
  "ebSyncStatus": "FAILED",
  "slotId": 101,
  "error": "Eventbrite update failed: connection timeout. Internal record updated successfully.",
  "lastSyncError": "EB update failed: connect timeout after 10s"
}
```

**State effect:** Slot remains `ACTIVE`. `lastSyncError` is recorded. `syncAttemptCount` is NOT incremented (this is a post-ACTIVE update failure, not a sync failure on the way to ACTIVE).

**Admin visibility:** The `lastSyncError` field appears in `GET /api/v1/scheduling/slots/{id}`. Admin can re-trigger update manually.

> There is no equivalent to 207 in Flow 1 — this response code is new to Flow 2.

---

### 11. Slot Cancellation EB Failure (200 with syncWarning)

**Path:** `ShowSlotService.cancelSlot()` → `EbEventSyncService.cancelEvent()` fails after internal status is already `CANCELLED`.

**Rule:** EB cancel failure is non-blocking. Internal cancellation is already committed (PRODUCT.md FR2 Phase 4 + Req F).

**Response: `200 OK` with `syncWarning` field**
```json
{
  "slotId": 101,
  "status": "CANCELLED",
  "syncWarning": "Slot cancelled internally. Eventbrite cancel call failed: connection timeout. EB event may still appear live until manually cancelled on Eventbrite Dashboard.",
  "lastSyncError": "EB cancel failed: connect timeout after 10s"
}
```

**No exception thrown to admin.** `ShowSlotCancelledEvent` is still published — `SlotCancelledListener` in `discovery-catalog` still updates `event_catalog.state` and invalidates cache regardless of EB cancel outcome.

**Admin action if needed:** Admin cancels the EB event manually via Eventbrite Dashboard using the `eb_event_id` visible in the admin slot detail view.

---

### 12. Reconciliation Job Failures

**Applies to:** `SlotReconciliationJob` (scheduling) and `VenueReconciliationJob` (discovery-catalog).

#### Pattern — same as Flow 1 SPEC_1 §7.8 (scheduler Throwable)

> **Flow 1 rule (SPEC_1 Stage 7 §8):** "Catch `Throwable` at scheduler level. Log error. Do not rethrow. Increment `scheduler.error` Micrometer counter." Flow 2 applies the **identical pattern** to both reconciliation jobs. No new rule needed — just confirming application.

**Slot reconciliation job specifics:**

| Failure type | Handling |
|---|---|
| EB 404 for a slot's `eb_event_id` | Record `lastSyncError` on slot. Log WARN. Continue to next slot. |
| EB 5xx / timeout per slot | Record `lastSyncError` on slot. Increment `reconciliation.slot.errors` Micrometer counter. Continue. |
| `EbAuthException` | Log ERROR: `"Reconciliation aborted: EB auth failure for org={}"`. Break inner record loop. Return from job method. Check `OrganizationAuth.status` — tokens need renewal. |
| Any `Exception` per slot | Log WARN. Continue to next slot. Never rethrow per-record. |
| Outer `Throwable` (unexpected) | Catch at job entry. Log ERROR. Increment `scheduler.error` Micrometer counter. Do not rethrow. |

**Venue reconciliation job specifics:**

| Failure type | Handling |
|---|---|
| EB 404 for a venue's `eb_venue_id` | Set `sync_status = DRIFT_FLAGGED`. Record `lastSyncError = "EB venue not found"`. Publish `VenueDriftDetectedEvent`. |
| EB 5xx / timeout per venue | Log WARN. Record `lastSyncError`. DO NOT change `sync_status`. Continue. |
| `EbAuthException` | Same break-loop pattern as slot reconciliation. |
| Any `Exception` per venue | Log WARN. Continue to next venue. |

---

### 13. Venue EB Sync Failures

#### 13a. Venue Creation — EB call fails

**Response: `202 Accepted`** (venue created internally, EB sync pending)
```json
{
  "id": 42,
  "name": "Jio World Garden",
  "syncStatus": "PENDING_SYNC",
  "ebVenueId": null,
  "message": "Venue created internally. Eventbrite sync pending."
}
```
- `lastSyncError` and `lastAttemptedAt` recorded on `Venue`.
- No retry is automatic — admin may trigger `POST /api/v1/admin/catalog/venues/{id}/sync` (COULD).

#### 13b. Venue Update — EB call fails

**Response: `200 OK`** with `syncStatus = DRIFT_FLAGGED`
```json
{
  "id": 42,
  "name": "Jio World Garden (Renamed)",
  "syncStatus": "DRIFT_FLAGGED",
  "lastSyncError": "EB venue update failed: 503 Service Unavailable",
  "message": "Venue updated internally. Eventbrite sync failed — venue is now DRIFT_FLAGGED."
}
```
- Internal update persisted. DB is source of truth.
- `VenueDriftDetectedEvent(venueId, ebVenueId, "Update sync failed: " + reason)` published.
- No exception thrown to admin.

#### 13c. Ordering: Venue EB Failure + Active Slots

If `eb_venue_id` is null (`PENDING_SYNC`) and admin tries to create a slot at that venue:
- Flow 2 Req C rule: `scheduling` checks venue `sync_status == PENDING_SYNC` → throws `BusinessRuleException("Cannot create slot for venue not yet synced to Eventbrite. Sync venue first.")` → 422.
- This prevents `eb_venue_id` null from flowing into D.1 payload (which would fail on EB side with invalid venue).

---

### 14. Summary — HTTP Status Code Map for Flow 2

| Scenario | HTTP Status | Exception / Shape |
|---|---|---|
| Bean validation failure | 400 | `SlotValidationException` → `ErrorResponse` with `details` array |
| OAuth CSRF mismatch | 400 | `ValidationException` |
| Slot / venue / org not found | 404 | `SchedulingNotFoundException` / `CatalogNotFoundException` |
| Slot conflict detected | 422 | `SlotConflictException` → `ErrorResponse` with `alternatives` |
| State machine violation | 422 | `BusinessRuleException` |
| Venue blocked (not synced) | 422 | `BusinessRuleException` |
| EB token missing / revoked | 502 | `EbAuthException` |
| OAuth code exchange failure | 502 | `EbAuthException` |
| D.1 draft creation failure (after retries) | 502 | `EbIntegrationException` |
| D.6 publish failure (after retries) | 502 | `EbIntegrationException` |
| Slot update — EB call failed (DB OK) | 207 | Non-exception response shape |
| Slot cancel — EB call failed (DB CANCELLED) | 200 | `syncWarning` field in response |
| Venue creation — EB call failed | 202 | Non-exception response shape |
| Venue update — EB call failed | 200 | `syncStatus = DRIFT_FLAGGED` in response |
| D.2–D.5 async failure | *(no HTTP)* | `TicketSyncFailedEvent` → slot `lastSyncError` |
| Reconciliation job per-record failure | *(no HTTP)* | Log WARN + `lastSyncError` on entity |

STAGE7_EOF
echo "Done."
wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "---" && grep -n "^## Stage" /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "---" && tail -6 /home/dell/Multi-City-Event-Booking/SPEC_2.md

Stage 7 written. **2508 lines** total (458 lines added). Stages 1–7 complete.

---

**What Stage 7 covers and how it relates to Flow 1:**

| Section | Flow 1 relationship |
|---|---|
| **Exception hierarchy** | Extends Flow 1's base classes (`ResourceNotFoundException`, `BusinessRuleException`, `EbAuthException`, etc.) — no new base types. New subclasses: `SlotConflictException`, `SchedulingNotFoundException`, `SlotValidationException`. |
| **§1 — 400 Validation** | Flow 1 has `InvalidCatalogSearchException`. Flow 2 uses the same `GlobalExceptionHandler`'s `@MethodArgumentNotValidException` mapping already in place — no handler change needed, only new DTO validators. |
| **§2 — 404** | `CatalogNotFoundException` reused from Flow 1. New peer: `SchedulingNotFoundException`. |
| **§3 — SlotConflictException (422)** | New to Flow 2. `GlobalExceptionHandler` needs one new `@ExceptionHandler` to serialize `ConflictAlternativeResponse`. |
| **§4 — State Machine (422)** | `BusinessRuleException` already mapped in handler from Flow 1. Zero handler changes. |
| **§5/6 — OAuth / Token errors** | Reuses `EbAuthException` from Flow 1. Key distinction called out: Flow 1 uses it in async catalog refresh (enters `WebhookConfig` cooldown); Flow 2 uses it synchronously in slot sync (surfaces as 502 to admin, sets `OrganizationAuth.status = TOKEN_EXPIRED`). **No conflict — different callers, same exception class.** |
| **§7/8/9 — D.1/D.2–D.5/D.6 EB failures** | Retry pattern uses same `EbRetryPolicy` / `@Retryable` from Flow 1. `syncAttemptCount` on `ShowSlot` ≠ `consecutiveFailures` on `WebhookConfig` — explicitly distinguished. |
| **§10 — 207 Update** | New to Flow 2 (no Flow 1 equivalent). |
| **§11 — Cancel (200 + syncWarning)** | New to Flow 2. |
| **§12 — Reconciliation** | Identical `catch(Throwable)` pattern as Flow 1 SPEC_1 §7.8. Confirmed applied to both `SlotReconciliationJob` and `VenueReconciliationJob`. |
| **§13 — Venue EB failures** | 202 on creation, 200 + `DRIFT_FLAGGED` on update. Venue blocked for slot creation if `PENDING_SYNC`. |
| **§14 — Status code map** | Complete reference table for all 14 scenarios. |

Ready for **Stage 8 — Tests** whenever you are.

User: Yes stage 8

GitHub Copilot: Let me quickly check the testing guide before writing, to match patterns exactly. 

Read [](file:///home/dell/Multi-City-Event-Booking/docs/TESTING_GUIDE.md#1-1), lines 1 to 120

Read [](file:///home/dell/Multi-City-Event-Booking/docs/TESTING_GUIDE.md#120-120), lines 120 to 400

Read [](file:///home/dell/Multi-City-Event-Booking/docs/TESTING_GUIDE.md#400-400), lines 400 to 600

Good — I have all the context I need. Writing Stage 8 now.  

Ran terminal command:  cat >> /home/dell/Multi-City-Event-Booking/SPEC_2.md << 'STAGE8_EOF'

---

## Stage 8 — Tests

> **Test structure follows TESTING_GUIDE.md four-layer rule** — `domain/` (plain JUnit 5), `service/` (Mockito only), `api/` (`@WebMvcTest`), `arch/` (ArchUnit).
> **Test naming convention:** `should_[expected_behaviour]_when_[condition]` — mandatory.
> **No Spring context in domain/ or service/ layers.**
> **Flow 1 tests (SPEC_1 Stage 8) remain unchanged** — only additive new tests are listed here.

---

### Layer 1 — Domain Tests (`domain/`)

Tests live in: `scheduling/src/test/java/.../scheduling/domain/`

---

#### `ShowSlotTest`

**Happy path — valid transitions:**

```
should_transition_to_PENDING_SYNC_when_slot_is_DRAFT_and_has_pricing_tiers
should_transition_to_ACTIVE_when_slot_is_PENDING_SYNC
should_transition_to_CANCELLED_when_slot_is_ACTIVE
should_transition_to_CANCELLED_when_slot_is_PENDING_SYNC
```

**Negative path — invalid transitions:**

```
should_throw_BusinessRuleException_when_submitting_non_DRAFT_slot
should_throw_BusinessRuleException_when_transitioning_ACTIVE_to_PENDING_SYNC
should_throw_BusinessRuleException_when_transitioning_CANCELLED_slot_to_any_state
should_throw_BusinessRuleException_when_transitioning_ACTIVE_to_DRAFT
```

**Sync metadata behaviour:**

```
should_reset_syncAttemptCount_to_zero_when_slot_transitions_to_ACTIVE
should_increment_syncAttemptCount_when_recordSyncFailure_is_called
should_record_lastSyncError_and_lastAttemptedAt_when_recordSyncFailure_is_called
should_not_increment_syncAttemptCount_when_slot_reaches_ACTIVE
should_clear_lastSyncError_when_slot_transitions_to_ACTIVE
```

**Guard conditions:**

```
should_throw_BusinessRuleException_when_seatingMode_is_RESERVED_and_sourceSeatMapId_is_null
should_throw_BusinessRuleException_when_capacity_is_zero_or_negative
should_throw_IllegalArgumentException_when_endTime_is_not_after_startTime
```

**Recurring rules:**

```
should_allow_null_recurrenceRule_when_isRecurring_is_false
should_throw_BusinessRuleException_when_isRecurring_is_true_and_recurrenceRule_is_null
```

**Terminal state enforcement:**

```
should_throw_BusinessRuleException_when_editing_time_fields_on_CANCELLED_slot
should_throw_BusinessRuleException_when_changing_seatingMode_on_ACTIVE_slot
```

---

#### `ShowSlotPricingTierTest`

```
should_throw_BusinessRuleException_when_FREE_tier_has_non_zero_priceAmount
should_allow_zero_priceAmount_for_FREE_tier
should_throw_BusinessRuleException_when_quota_is_zero_or_negative
should_allow_PAID_tier_with_positive_priceAmount
```

---

#### `ConflictAlternativeResponseTest`

```
should_return_empty_lists_for_all_alternatives_when_no_options_found
should_construct_record_with_all_three_alternative_lists
```

---

### Layer 2 — Service Tests (`service/`)

Tests live in: `scheduling/src/test/java/.../scheduling/service/`, `discovery-catalog/src/test/java/.../discoverycatalog/service/`

Annotation: `@ExtendWith(MockitoExtension.class)`

---

#### `ShowSlotServiceTest` — `scheduling`

**Slot creation (DRAFT):**

```
should_create_slot_in_DRAFT_status_when_venue_is_synced_and_no_conflict
should_throw_SchedulingNotFoundException_when_venue_not_found_via_rest_call
should_throw_BusinessRuleException_when_venue_sync_status_is_PENDING_SYNC
should_throw_SlotConflictException_with_alternatives_when_overlap_detected
should_throw_SlotConflictException_with_alternatives_when_gap_policy_violated
should_throw_BusinessRuleException_when_RESERVED_slot_has_no_sourceSeatMapId
should_persist_all_pricing_tiers_when_slot_created_successfully
```

**Slot submission (D.1):**

```
should_transition_to_PENDING_SYNC_and_call_createDraft_when_slot_is_DRAFT
should_publish_SlotDraftCreatedEvent_with_correct_payload_when_EB_draft_succeeds
should_increment_syncAttemptCount_and_not_publish_event_when_createDraft_throws_EbIntegrationException
should_increment_syncAttemptCount_when_createDraft_throws_EbAuthException
should_throw_BusinessRuleException_when_submitting_PENDING_SYNC_slot
should_throw_BusinessRuleException_when_slot_has_no_pricing_tiers
should_store_ebEventId_on_slot_immediately_after_createDraft_succeeds
```

**Slot submission — SlotDraftCreatedEvent payload correctness:**

```
should_include_all_pricingTierIds_in_SlotDraftCreatedEvent
should_include_seatingMode_in_SlotDraftCreatedEvent
should_include_sourceSeatMapId_in_SlotDraftCreatedEvent_when_RESERVED
should_set_sourceSeatMapId_null_in_SlotDraftCreatedEvent_when_GA
should_NOT_include_any_Entity_object_in_SlotDraftCreatedEvent
```

**D.6 Publish (TicketSyncCompleted listener path):**

```
should_transition_to_ACTIVE_and_reset_syncAttemptCount_when_publishEvent_succeeds
should_publish_ShowSlotActivatedEvent_with_correct_fields_when_slot_transitions_to_ACTIVE
should_increment_syncAttemptCount_and_stay_PENDING_SYNC_when_publishEvent_fails
should_not_recreate_EB_draft_when_publish_fails_and_eb_event_id_already_set
should_throw_BusinessRuleException_when_TicketSyncCompleted_arrives_for_non_PENDING_SYNC_slot
```

**Slot update:**

```
should_persist_update_and_call_updateEvent_when_slot_is_ACTIVE
should_persist_update_and_call_updateCapacityTier_when_capacity_changed_on_ACTIVE_slot
should_persist_internal_update_and_record_lastSyncError_when_updateEvent_throws_EbIntegrationException
should_return_207_response_when_internal_update_succeeds_but_EB_update_fails
should_throw_BusinessRuleException_when_updating_PENDING_SYNC_slot
should_throw_BusinessRuleException_when_changing_seatingMode_on_ACTIVE_slot
should_rerun_conflict_check_with_excludeSlotId_when_time_fields_updated
```

**Slot cancellation:**

```
should_transition_to_CANCELLED_and_call_cancelEvent_when_slot_is_ACTIVE
should_transition_to_CANCELLED_even_when_cancelEvent_throws_EbIntegrationException
should_publish_ShowSlotCancelledEvent_regardless_of_EB_cancel_outcome
should_record_lastSyncError_when_EB_cancel_fails_but_internal_cancellation_succeeds
should_throw_BusinessRuleException_when_cancelling_DRAFT_slot
```

**Retry sync:**

```
should_skip_D1_and_attempt_publish_directly_when_eb_event_id_already_set
should_re_attempt_D1_when_eb_event_id_is_null_on_retry
should_throw_BusinessRuleException_when_retry_called_on_ACTIVE_slot
```

---

#### `ConflictDetectionServiceTest` — `scheduling`

```
should_detect_overlap_when_proposed_window_overlaps_existing_slot
should_detect_overlap_when_proposed_window_is_fully_contained_by_existing_slot
should_not_detect_conflict_when_slots_are_adjacent_with_no_gap
should_detect_gap_policy_violation_when_gap_between_slots_less_than_configured_minimum
should_not_detect_conflict_when_gap_is_exactly_the_minimum_required
should_exclude_self_from_conflict_check_when_updating_existing_slot
should_ignore_CANCELLED_slots_in_conflict_check
should_build_sameVenueAlternatives_with_up_to_3_non_conflicting_windows
should_build_nearbyVenueAlternatives_sorted_by_capacity_proximity
should_return_empty_nearbyVenueAlternatives_when_no_synced_venues_in_city
should_build_adjustedTimeOptions_using_max_end_time_plus_gap
```

---

#### `SlotTicketSyncServiceTest` — `booking-inventory`

```
should_call_createTicketClasses_for_each_pricing_tier_and_store_eb_ticket_class_id
should_call_createInventoryTiers_after_createTicketClasses_succeeds
should_not_call_copySeatMap_when_seatingMode_is_GA
should_call_copySeatMap_with_sourceSeatMapId_when_seatingMode_is_RESERVED
should_publish_TicketSyncCompletedEvent_when_all_D2_to_D5_steps_succeed
should_publish_TicketSyncFailedEvent_with_step_identifier_when_D2_fails
should_publish_TicketSyncFailedEvent_with_step_identifier_when_D3_fails
should_publish_TicketSyncFailedEvent_with_step_identifier_when_D5_fails
should_skip_D2_for_tier_when_eb_ticket_class_id_already_populated_idempotency
should_skip_D3_for_tier_when_eb_inventory_tier_id_already_populated_idempotency
should_not_call_EbCapacityService_updateCapacityTier_during_initial_sync
```

---

#### `VenueServiceTest` — `discovery-catalog`

```
should_persist_venue_with_PENDING_SYNC_status_then_call_createVenue_on_EbVenueService
should_store_eb_venue_id_and_set_SYNCED_after_successful_EB_creation
should_persist_venue_and_return_202_when_EbVenueService_createVenue_throws
should_record_lastSyncError_when_EB_venue_creation_fails
should_update_venue_fields_then_call_updateVenue_on_EbVenueService_when_eb_venue_id_present
should_set_DRIFT_FLAGGED_and_publish_VenueDriftDetectedEvent_when_updateVenue_fails
should_always_invalidate_snapshot_cache_after_venue_update_regardless_of_EB_outcome
should_not_call_EbVenueService_on_update_when_eb_venue_id_is_null
should_throw_CatalogNotFoundException_when_venue_not_found_by_id
```

---

#### `SlotActivatedListenerTest` — `discovery-catalog`

```
should_call_EventCatalogSyncService_sync_when_ShowSlotActivatedEvent_received
should_invalidate_snapshot_cache_for_orgId_and_cityId_when_slot_activated
should_use_venueId_from_event_when_calling_sync
should_not_throw_when_EventCatalogSyncService_sync_throws_internally
```

---

#### `SlotCancelledListenerTest` — `discovery-catalog`

```
should_set_event_catalog_state_to_CANCELLED_when_ShowSlotCancelledEvent_received
should_invalidate_snapshot_cache_for_orgId_and_cityId_when_slot_cancelled
should_do_nothing_when_no_event_catalog_row_exists_for_eb_event_id
should_not_set_deleted_at_only_state_CANCELLED_on_catalog_row
```

---

#### `SlotReconciliationJobTest` — `scheduling`

```
should_flag_slot_with_lastSyncError_when_EB_event_returns_404
should_flag_slot_with_lastSyncError_when_EB_event_status_is_CANCELLED
should_not_auto_cancel_internal_slot_when_EB_mismatch_detected
should_continue_to_next_slot_when_individual_slot_EB_check_throws
should_break_inner_loop_when_EbAuthException_encountered
should_not_rethrow_from_outer_catch_block_ensuring_scheduler_survives
should_not_flag_slot_when_EB_event_is_LIVE_and_matches_internal_ACTIVE_status
```

---

#### `VenueReconciliationJobTest` — `discovery-catalog`

```
should_set_DRIFT_FLAGGED_and_publish_VenueDriftDetectedEvent_when_capacity_drift_detected
should_set_DRIFT_FLAGGED_when_EB_venue_returns_404
should_not_change_sync_status_when_EB_venue_matches_internal_record
should_continue_to_next_venue_when_individual_venue_EB_check_throws
should_break_inner_loop_when_EbAuthException_encountered
```

---

#### `OrgOAuthServiceTest` — `admin/`

```
should_generate_authorization_url_and_store_csrf_state_in_redis
should_call_exchangeAndStore_and_return_CONNECTED_when_code_and_state_are_valid
should_throw_ValidationException_when_csrf_state_does_not_match_redis_value
should_throw_ValidationException_when_csrf_state_has_expired_in_redis
should_delete_redis_state_key_regardless_of_match_outcome
should_set_status_to_REVOKED_and_clear_tokens_on_disconnect
should_return_REVOKED_without_error_when_no_OrganizationAuth_row_exists_on_disconnect
```

---

#### `EbTokenStoreTest` — `shared/`

```
should_return_decrypted_access_token_when_token_is_valid_and_not_near_expiry
should_attempt_token_refresh_when_expires_at_is_within_5_minutes_of_now
should_update_token_and_return_new_access_token_after_successful_refresh
should_set_status_TOKEN_EXPIRED_and_throw_EbAuthException_when_refresh_fails
should_throw_EbAuthException_immediately_when_status_is_REVOKED
should_throw_EbAuthException_immediately_when_no_OrganizationAuth_row_exists
should_not_attempt_refresh_when_expires_at_is_null
```

---

### Layer 3 — API Tests (`api/`)

Annotation: `@WebMvcTest`. Service and mapper mocked with `@MockBean`. All requests include `Authorization: Bearer test-token` for secured endpoints.

---

#### `ShowSlotControllerTest` — `scheduling`

**`POST /api/v1/scheduling/slots` — Create slot:**

```
should_return_201_and_slot_response_when_slot_created_successfully
should_return_400_when_venueId_is_missing
should_return_400_when_startTime_is_missing
should_return_400_when_endTime_is_not_after_startTime
should_return_400_when_capacity_is_zero
should_return_400_when_seatingMode_is_RESERVED_and_sourceSeatMapId_is_null
should_return_422_with_SLOT_CONFLICT_errorCode_and_alternatives_body_when_conflict_detected
should_return_422_when_venue_is_not_synced_to_EB
should_return_404_when_venue_not_found
should_return_401_when_no_auth_token_provided
should_return_403_when_user_does_not_have_ROLE_ADMIN
```

**`POST /api/v1/scheduling/slots/{id}/submit` — Submit slot:**

```
should_return_200_when_slot_submitted_successfully
should_return_422_when_submitting_non_DRAFT_slot
should_return_422_when_slot_has_no_pricing_tiers
should_return_502_with_EB_INTEGRATION_ERROR_when_createDraft_fails
should_return_502_with_EB_AUTH_FAILURE_when_token_missing_or_revoked
should_return_404_when_slot_not_found
```

**`PUT /api/v1/scheduling/slots/{id}` — Update slot:**

```
should_return_200_when_ACTIVE_slot_updated_successfully
should_return_207_when_internal_update_succeeds_but_EB_sync_fails
should_return_422_when_updating_PENDING_SYNC_slot
should_return_422_when_changing_seatingMode_on_ACTIVE_slot
should_return_422_with_SLOT_CONFLICT_errorCode_when_new_times_cause_conflict
should_return_400_when_endTime_not_after_startTime_on_update
```

**`POST /api/v1/scheduling/slots/{id}/cancel` — Cancel slot:**

```
should_return_200_when_slot_cancelled_successfully
should_return_200_with_syncWarning_when_EB_cancel_fails
should_return_422_when_cancelling_DRAFT_slot
should_return_404_when_slot_not_found
```

**`POST /api/v1/scheduling/slots/{id}/retry-sync` — Retry sync:**

```
should_return_200_when_retry_initiated_on_PENDING_SYNC_slot
should_return_422_when_slot_is_not_in_PENDING_SYNC_state
```

**`GET /api/v1/scheduling/slots` — List slots:**

```
should_return_200_with_paginated_list_when_slots_exist
should_return_200_with_empty_list_when_no_slots_match_filter
should_return_400_when_orgId_is_missing
```

**`GET /api/v1/scheduling/slots/{id}` — Get slot:**

```
should_return_200_with_slot_details_including_pricingTiers_when_slot_found
should_return_404_when_slot_not_found
```

**`GET /api/v1/scheduling/slots/{id}/occurrences` — List occurrences:**

```
should_return_200_with_occurrence_list_when_slot_is_recurring
should_return_422_when_slot_is_not_recurring
should_return_404_when_slot_not_found
```

**`GET /api/v1/scheduling/slots/mismatches` — Mismatch list:**

```
should_return_200_with_flagged_slots_when_mismatches_exist
should_return_200_with_empty_list_when_no_mismatches
```

---

#### `VenueCatalogControllerTest` — `discovery-catalog`

**`POST /api/v1/admin/catalog/venues` — Create venue:**

```
should_return_201_when_venue_created_and_EB_synced_successfully
should_return_202_when_venue_created_internally_but_EB_sync_failed
should_return_400_when_name_is_blank
should_return_400_when_cityId_is_missing
should_return_400_when_capacity_is_zero_or_negative
should_return_401_when_no_auth_token_provided
should_return_403_when_user_lacks_ROLE_ADMIN
```

**`PUT /api/v1/admin/catalog/venues/{id}` — Update venue:**

```
should_return_200_with_SYNCED_status_when_EB_update_succeeds
should_return_200_with_DRIFT_FLAGGED_status_when_EB_update_fails
should_return_404_when_venue_not_found
should_return_400_when_capacity_is_negative_on_update
```

**`GET /api/v1/catalog/venues/{id}` — Get venue by ID:**

```
should_return_200_with_venue_including_capacity_seatingMode_syncStatus
should_return_404_when_venue_not_found
should_return_200_without_auth_token_public_endpoint
```

**`GET /api/v1/admin/catalog/venues/flagged` — Flagged venues:**

```
should_return_200_with_drift_flagged_venues
should_return_200_with_empty_list_when_no_flagged_venues
```

---

#### `OrgEventbriteControllerTest` — `admin/`

**`POST /api/v1/admin/orgs/{orgId}/eventbrite/connect`:**

```
should_return_200_with_authorization_url_when_connect_initiated
should_return_401_when_no_auth_token_provided
should_return_403_when_user_lacks_ROLE_ADMIN
```

**`GET /api/v1/admin/orgs/{orgId}/eventbrite/callback`:**

```
should_return_200_with_CONNECTED_status_when_code_exchange_succeeds
should_return_400_when_oauth_state_does_not_match
should_return_502_when_EB_code_exchange_fails
```

**`GET /api/v1/admin/orgs/{orgId}/eventbrite/status`:**

```
should_return_200_with_CONNECTED_status_when_token_is_valid
should_return_200_with_TOKEN_EXPIRED_status_when_token_expired
should_return_200_with_NOT_CONNECTED_when_no_OrganizationAuth_row_exists
```

**`DELETE /api/v1/admin/orgs/{orgId}/eventbrite/disconnect`:**

```
should_return_200_with_REVOKED_status_when_disconnected
should_return_200_idempotently_when_no_OrganizationAuth_row_exists
```

---

### Layer 4 — ArchUnit Tests (`arch/`)

Tests live in each module's `arch/` folder. These run at build time and are **never weakened or deleted**.

> **Flow 1 rules (SPEC_1 Stage 8 ArchUnit) remain in force.** Rules 1, 2, 5, 6, 10 are already tested in `discovery-catalog/arch/`. The tests below are additive — new rules and new modules.

---

#### `SchedulingArchTest` — `scheduling/src/test/java/.../scheduling/arch/`

```java
// Hard Rule #1 — No cross-module @Service / @Entity / @Repository imports
@Test
void should_not_import_discovery_catalog_service_or_entity_or_repository()
// scheduling must not import from com.eventplatform.discoverycatalog.domain
// scheduling must not import from com.eventplatform.discoverycatalog.repository
// scheduling must not import from com.eventplatform.discoverycatalog.service

// Hard Rule #2 — EB calls only via shared/ ACL facades
@Test
void should_not_call_EB_http_client_directly()
// No class in scheduling may import an HTTP client targeting Eventbrite
// Only shared/eventbrite/service/ facades may be imported

// Hard Rule #4 — Spring Events carry no @Entity objects
@Test
void should_not_include_entity_types_in_spring_event_payloads()
// SlotDraftCreatedEvent, ShowSlotActivatedEvent, ShowSlotCancelledEvent, SlotSyncFailedEvent
// must not have fields of type annotated with @Entity

// Hard Rule #5 — No @ControllerAdvice in scheduling
@Test
void should_not_declare_ControllerAdvice_in_scheduling_module()

// Hard Rule #6 — No field mapping in services or controllers
@Test
void should_not_map_fields_in_service_or_controller_classes()
// Field mapping must only occur in classes under mapper/ package

// Hard Rule #10 — Dependency direction: scheduling must not import from downstream modules
@Test
void should_not_depend_on_booking_inventory_or_payments_ticketing_or_downstream_modules()
```

---

#### `BookingInventoryArchTest` (additions) — `booking-inventory/src/test/java/.../bookinginventory/arch/`

```java
// Hard Rule #1 — No cross-module imports from scheduling domain/repo/service
@Test
void should_not_import_scheduling_entity_or_repository_or_service()
// booking-inventory may listen to scheduling's Spring Events (primitives/IDs only)
// but must NOT import ShowSlot.java, ShowSlotRepository.java, or ShowSlotService.java

// Hard Rule #4 — Spring Events in booking-inventory carry no @Entity
@Test
void should_not_include_entity_types_in_TicketSyncCompletedEvent_or_TicketSyncFailedEvent()

// Hard Rule #2 — EB calls only via shared/ ACL facades
@Test
void should_only_call_EbTicketService_and_EbCapacityService_from_shared_facades()
```

---

#### `DiscoveryCatalogArchTest` (additions) — `discovery-catalog/src/test/java/.../discoverycatalog/arch/`

> Additive to Flow 1 arch tests already defined in SPEC_1 Stage 8.

```java
// New listener classes must not import scheduling @Entity or @Repository
@Test
void should_not_import_scheduling_entity_in_SlotActivatedListener_or_SlotCancelledListener()
// SlotActivatedListener and SlotCancelledListener receive Spring Events with primitive fields only
// They must not reference ShowSlot.java or any scheduling @Entity

// VenueService must always invalidate cache via EventCatalogSnapshotCache — no direct Redis calls
@Test
void should_only_access_Redis_through_EventCatalogSnapshotCache_in_VenueService()
```

---

#### `SharedArchTest` (additions) — `shared/src/test/java/.../shared/arch/`

> Additive to any existing shared arch tests.

```java
// Hard Rule #8 — shared/ has no dependency on any module
@Test
void should_not_import_any_module_package_from_shared()
// shared/ (including EbTokenStore, OrganizationAuth, all facades)
// must not import com.eventplatform.scheduling.*
// must not import com.eventplatform.discoverycatalog.*
// must not import com.eventplatform.bookinginventory.*
// etc.
```

---

### Mapper Tests (`mapper/`)

Plain JUnit 5 — no Spring, no mocks.

#### `ShowSlotMapperTest` — `scheduling`

```
should_map_ShowSlot_to_ShowSlotResponse_with_all_fields_including_syncAttemptCount
should_map_status_enum_correctly_for_all_four_ShowSlotStatus_values
should_map_null_ebEventId_to_null_in_response
should_map_ShowSlotOccurrence_to_ShowSlotOccurrenceResponse_with_correct_fields
should_not_expose_raw_token_fields_or_auth_fields_in_ShowSlotResponse
```

#### `VenueMapperTest` (additions) — `discovery-catalog`

```
should_map_new_capacity_seatingMode_syncStatus_fields_to_VenueResponse
should_map_null_ebVenueId_to_null_in_response
should_map_DRIFT_FLAGGED_syncStatus_correctly
```

---

### Test Coverage Targets

| Module | Domain | Service | API | Arch |
|---|---|---|---|---|
| `scheduling` | ≥ 90% | ≥ 85% | ≥ 80% | 100% (build breaks) |
| `discovery-catalog` (additions) | ≥ 90% | ≥ 85% | ≥ 80% | 100% (build breaks) |
| `booking-inventory` (additions) | ≥ 85% | ≥ 85% | n/a (no new controller) | 100% (build breaks) |
| `shared/` (additions) | n/a | ≥ 85% | n/a | 100% (build breaks) |
| `admin/` | n/a | ≥ 80% | ≥ 80% | inherit from global arch |

---

### Key Test Invariants — Never Violate

1. **No event published when use case fails** — every test that verifies an exception also verifies `verify(eventPublisher, never()).publishEvent(any())`.
2. **No EB call made when internal validation fails** — conflict detection, state machine violation, and missing pricing tiers all throw before any `EbTokenStore.getValidToken()` call. Verify via `verify(ebTokenStore, never()).getValidToken(any())`.
3. **No entity object in Spring Event payloads** — every event payload test uses `assertThat(event).isNotInstanceOf(...)` for each `@Entity` class in the payload path.
4. **Slot stays PENDING_SYNC on any EB failure** — all D.1–D.6 failure tests verify `slot.getStatus() == PENDING_SYNC` after the failure, never `ACTIVE` or `DRAFT`.
5. **Internal cancellation always commits** — cancellation tests verify the slot is `CANCELLED` in DB even when `EbIntegrationException` is thrown from `cancelEvent()`.
6. **Cache invalidated on venue update regardless of EB outcome** — `VenueServiceTest` verifies `snapshotCache.invalidate(orgId, cityId)` is called in both EB success and EB failure paths of `updateVenue()`.

STAGE8_EOF
echo "Done."
wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "---" && grep -n "^## Stage" /home/dell/Multi-City-Event-Booking/SPEC_2.md && echo "---" && tail -8 /home/dell/Multi-City-Event-Booking/SPEC_2.md

All 8 stages complete. **SPEC_2.md is 3129 lines.**

---

**Stage 8 breakdown — what's covered and Flow 1 alignment:**

**Layer 1 — Domain (`domain/`):** 3 test classes
- `ShowSlotTest` — 20 cases: all state machine transitions (valid + invalid), sync metadata lifecycle (`syncAttemptCount` reset, `lastSyncError` clear), guard conditions, recurring rules, terminal state enforcement
- `ShowSlotPricingTierTest` — FREE tier price constraint, quota validation
- `ConflictAlternativeResponseTest` — value object construction

**Layer 2 — Service (`service/`):** 8 test classes across 4 modules
- `ShowSlotServiceTest` — 35+ cases covering DRAFT creation, D.1 submission, `SlotDraftCreatedEvent` payload correctness (including "no `@Entity` in event" assertion), D.6 publish, update (incl. 207), cancel, retry-sync
- `ConflictDetectionServiceTest` — overlap, gap policy, self-exclusion on update, CANCELLED exclusion, all 3 alternative builder paths
- `SlotTicketSyncServiceTest` — D.2–D.5 happy path, per-step failure events, idempotency skip on retry
- `VenueServiceTest` — creation, update, EB failure handling, **always invalidates cache** on update
- `SlotActivatedListenerTest` / `SlotCancelledListenerTest` — Flow 1 cache integration chain
- `SlotReconciliationJobTest` / `VenueReconciliationJobTest` — flag-not-fix, `EbAuthException` breaks loop, scheduler survives `Throwable`
- `OrgOAuthServiceTest` / `EbTokenStoreTest` — OAuth flow, CSRF, token refresh, revoke

**Layer 3 — API (`api/`):** 3 controller test classes
- `ShowSlotController` — full CRUD + all error codes (400/401/403/404/422/502/207)
- `VenueCatalogController` — create (201/202), update (200/DRIFT_FLAGGED), get, flagged list
- `OrgEventbriteController` — connect, callback, status, disconnect (idempotent)

**Layer 4 — ArchUnit (`arch/`):** 4 modules — additive to Flow 1 rules
- `scheduling` — Hard Rules #1, #2, #4, #5, #6, #10
- `booking-inventory` additions — no `ShowSlot` entity import, no entity in events
- discovery-catalog additions — new listeners don't import scheduling entities; cache only via `EventCatalogSnapshotCache`
- shared additions — zero dependency on any module

**6 invariants** that every service test must enforce (published at the bottom of Stage 8).

**SPEC_2.md is now complete — all 8 stages written.**

User: can u explain why are we having the three tables for show_slot why are we normalizing so much what is the reason ?

GitHub Copilot: Three tables because each has a fundamentally different cardinality and lifecycle:

---

**`show_slot`** — the booking window
One row = one show at one venue on one date. It owns the EB event ID, the sync state machine, and the capacity. This is the aggregate root everything else hangs off.

---

**`show_slot_pricing_tier`** — 1..N per slot
A single slot has multiple ticket tiers: VIP at ₹5000, General at ₹1500, Early Bird at ₹1000. Each tier maps to **one EB ticket class** — `eb_ticket_class_id` is per-tier, not per-slot. If you put tiers in `show_slot` you'd need an array column or a JSON blob, which kills:
- The ability to query "how many slots have a FREE tier"
- Per-tier `eb_ticket_class_id` and `eb_inventory_tier_id` storage (D.2/D.3 sync)
- Individual tier quota tracking

---

**`show_slot_occurrence`** — 0..N, only when `is_recurring = true`
Recurring shows (every Saturday) produce **separate EB event IDs per occurrence** — Eventbrite treats each date as its own event. Each occurrence has its own `eb_event_id`, its own `status`, its own `sync_attempt_count`. If occurrence 3 fails EB sync, occurrences 1, 2, 4 are unaffected. Embedding this in `show_slot` is impossible — you'd need unlimited columns or a JSON array, and you'd lose per-occurrence sync failure tracking entirely.

---

**What a single-table design breaks:**

| Concern | Single table problem |
|---|---|
| Multiple ticket tiers | Array/JSON — not queryable, not FK-able to EB IDs |
| Per-tier EB sync state | Can't store `eb_ticket_class_id` × N tiers in one row |
| Per-occurrence EB event ID | Unbounded columns or JSON — can't index, can't FK |
| Occurrence-level cancellation | Can't cancel occurrence 3 without affecting the parent |
| "List all slots with a FREE tier" query | Impossible without full scan of a JSON blob |

---

**The short answer:** `show_slot_pricing_tier` and `show_slot_occurrence` are not over-normalization — they have genuinely independent identity, independent EB IDs, and independent lifecycle states. Collapsing them into `show_slot` would require JSON columns or dynamic column counts, neither of which works with JPA, Flyway migrations, or EB ID storage per entity.

User:   Notable deviations from SPEC_2:

  - Published cross-module events under shared/common/event/published to avoid circular module dependencies.
  - Admin venue aggregation exposed at /api/v1/admin/dashboard/venues/flagged to avoid path collision with discovery-
    catalog’s /api/v1/admin/catalog/venues/flagged.
  - Org context is currently taken from orgId query params on admin/scheduling endpoints (JWT claim resolution not
    implemented).

    is it good ?

GitHub Copilot: Mixed. Two are fine, one is a problem. Let me go through each:

---

**1. Cross-module events under `shared/common/event/published/` — Fine with a caveat**

The goal is sound — avoid circular dependencies where both scheduling and booking-inventory need to reference the same event class. Putting them in shared breaks the cycle cleanly.

The caveat: shared must remain dependency-direction-agnostic. Events placed in `shared/common/event/published/` must carry **only primitives, IDs, enums, and value objects** — never anything that imports a module-specific type. If that rule holds, this is a legitimate structural decision and worth documenting as an explicit resolved decision in the spec.

Worth updating SPEC_2.md Stage 3 to note the actual location.

---

**2. Admin venue path `/api/v1/admin/dashboard/venues/flagged` — Fine**

Path collision avoidance is valid. `/api/v1/admin/catalog/venues/flagged` (discovery-catalog owned) vs `/api/v1/admin/dashboard/venues/flagged` (admin aggregation) is a cleaner separation anyway — it makes the ownership boundary obvious in the URL itself. No architectural rule is violated.

Worth updating the Stage 5 endpoint summary table row 20 to reflect the actual path.

---

**3. `orgId` from query params instead of JWT claim — Problem, but acceptable as a phase gate**

The spec requires `org_id` from JWT claim for all admin endpoints (Stage 6 §1). Taking it as a query param means:
- **Any caller can pass any `orgId`** — there is zero ownership enforcement. Admin A can query or mutate org B's slots just by changing the param.
- This is fine in a single-org dev environment but is a **security gap** the moment you have more than one org.

It's acceptable as a temporary implementation shortcut **only if**:
- It's behind `ROLE_ADMIN` (the role check is in place)
- There is exactly one org in the system right now
- It is tracked as a known debt item to fix before multi-org goes live

Do **not** ship this to production multi-org without replacing it with JWT claim extraction.

---

**Recommendation:**

Update SPEC_2.md to record all three as resolved decisions at the bottom (like SPEC_1 has a `## Resolved Decisions` section), so the coding agent implementing future flows knows the actual state. Want me to append that section?

User: yes update these 3 changes 

GitHub Copilot: Let me read the relevant sections first before making all changes. 

Ran terminal command:  grep -n "Spring Event Payload Reference\|Endpoint Summary\|admin/dashboard\|admin/catalog/venues/flagged\|shared/common/event\|SlotDraftCreatedEvent\|TicketSyncCompletedEvent\|ShowSlotActivatedEvent\|ShowSlotCancelledEvent\|Row 20\|| 20 |## Resolved" /home/dell/Multi-City-Event-Booking/SPEC_2.md | head -40

Now let me read the exact text at the specific sections I need to modify: 

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#639-639), lines 639 to 652

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#1360-1360), lines 1360 to 1422

Summarized conversation history

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#575-575), lines 575 to 625

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#635-635), lines 635 to 655

Now I have all the context. Executing all 7 inline edits in one pass, then appending the Resolved Decisions section. 

Replacing 23 lines with 35 lines in [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md)


```

```
All 7 inline edits applied. Now appending the Resolved Decisions section. 

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#3135-3135), lines 3135 to 3142

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#3142-3142), lines 3142 to 3142

Made changes.

User: • Brief follow-ups

  1. JWT org resolution (remove orgId params)

  - Goal: Derive orgId from authenticated admin JWT claim everywhere.
  - Work: Add JWT claim parsing helper (likely in shared/security), update controllers/services in admin/, scheduling/,
    discovery-catalog/ to stop accepting orgId query param.
  - Risk: Requires JWT structure to be confirmed (claim name, type).

  2. Eventbrite HTTP clients

  - Goal: Replace stub ACLs with real REST clients for OAuth, venue, event, ticket, capacity, schedule.
  - Work: Add WebClient/RestClient in shared/eventbrite/client/, implement EbOAuthClient, EbVenueService,
    EbEventSyncService, EbTicketService, EbCapacityService, EbScheduleService. Use EbTokenStore to inject bearer
    tokens; apply retry policy and Retry-After handling.
  - Risk: Contract accuracy with EB API v3; needs API credentials and base URL config.

  3. Admin venue aggregator path alignment

  - Goal: Match SPEC_2 endpoint exactly (GET /api/v1/admin/catalog/venues/flagged for admin aggregation).
  - Work: Either move admin aggregator to /api/v1/admin/catalog/venues/flagged and call discovery-catalog at a
    different internal path, or keep discovery-catalog path and add /api/v1/admin/venues/flagged as internal-only.
  - Risk: Endpoint collision if both modules expose the same path in a single app context.

  these where the changes/Follow up mentioned can u verify it why and for what reason these follow ups where mentioned

GitHub Copilot: Let me read the relevant sections to give you an accurate, evidence-based answer.  

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#1-1), lines 1 to 60

Searched for regex `orgId.*query|query.*orgId|JwtClaimHelper|orgId.*claim|claim.*orgId|Deviation.*orgId`, 20 results

Searched for text `EbVenueService|EbEventSyncService|EbTicketService|EbCapacityService|EbScheduleService|EbOAuthClient` (`**/shared/src/**/*.java`), 20 results

Searched for text `admin/catalog/venues/flagged|admin/dashboard/venues/flagged` (`**/*.java`), 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/shared/src/main/java/com/eventplatform/shared/eventbrite/service/DefaultEbOAuthClient.java)

Read [](file:///home/dell/Multi-City-Event-Booking/admin/src/main/java/com/eventplatform/admin/service/client/CatalogAdminClient.java)

Read [](file:///home/dell/Multi-City-Event-Booking/SPEC_2.md#1440-1440), lines 1440 to 1450

Here's the exact origin of each follow-up, traced to the actual code and spec:

---

## Follow-up 1 — JWT org resolution

**Why it was raised:** SPEC_2.md SPEC_2.md explicitly specifies:
> `org_id extracted from JWT claim orgId`

But the actual implementation diverges from that. In CatalogAdminClient.java (line 23–26) and SchedulingAdminClient.java, `orgId` is passed as a query param received from the caller — meaning **any caller can pass any org ID**. The spec says the principal performs this — the JWT is the source of truth. This is exactly **Deviation #3** from the last session: it was flagged as a security gap (caller-supplied `orgId` is unverified) and recorded as known debt because the project is single-org today.

---

## Follow-up 2 — Eventbrite HTTP clients

**Why it was raised:** Every EB facade implementation in shared is currently a stub. For example, DefaultEbOAuthClient.java simply throws `EbIntegrationException("Eventbrite OAuth client not configured")`. The same is true for `DefaultEbVenueService`, `DefaultEbCapacityService`, `DefaultEbScheduleService` — they all just log a warning and return nothing. The entire Flow 2 sync chain (SPEC_2 sections D.1–D.6) depends on these facades making real HTTP calls. Without them, no slot can ever reach `ACTIVE` — the whole EB sync path is inoperative.

---

## Follow-up 3 — Admin venue aggregator path alignment

**Why it was raised:** This is a Spring Boot monolith path collision. Both discovery-catalog and admin are modules in the **same application context** — they are NOT separate services. If both register a `@RequestMapping` for `GET /api/v1/admin/catalog/venues/flagged`, Spring throws an ambiguous handler mapping exception at startup.

- discovery-catalog legitimately owns `GET /api/v1/admin/catalog/venues/flagged` (row 9, SPEC_2 endpoint summary) — this is its real endpoint for listing drift-flagged venues.
- admin was originally spec'd to expose the same path as its aggregation endpoint (row 20) — that's the collision.

The resolution was **Deviation #2** agreed last session: rename the admin aggregation to `GET /api/v1/admin/dashboard/venues/flagged`. The internal call from admin to discovery-catalog still uses `/api/v1/admin/catalog/venues/flagged` (which is correct — that's the discovery-catalog-owned path). Only the admin-facing public endpoint changes to `dashboard`. This is already reflected in the updated SPEC_2.md. The follow-up is noting that CatalogAdminClient.java still calls the discovery-catalog path (correct — that's the internal delegate call), but the **admin controller** that publicly exposes the aggregation result needs its path updated.

---

**In short:**
| Follow-up | Root cause type | Current state |
|---|---|---|
| JWT org resolution | Security gap — spec says JWT, code says query param | Working but unverified, single-org only |
| EB HTTP clients | Stubs only — every `Default*` facade throws immediately | No EB integration works at all |
| Admin path alignment | URL collision within the same Spring app context | Spec fixed (Deviation #2); admin controller path not yet updated in code |

User: Follow-up 1 — JWT org resolution
ill be making it multi tenant org then fixing this would be no use ? i can skip it and fix it when i try to merge this with the PR 

Follow-up 2 — Eventbrite HTTP clients 
i built a fake-mock service for the eventbrite using fastapi and sqlite, need to u se that for development



GitHub Copilot: Both are sensible calls. Let me check the mock service to understand what it already exposes before confirming anything.  

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/api)

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/main.py)

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/api/routes)

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/README.md#1-1), lines 1 to 50

Searched for regex `@router\.(get|post|put|patch|delete)` (`**/mock-eventbrite-api/app/api/routes/*.py`), 20 results

Searched for text `DefaultEbOAuthClient\|DefaultEbVenueService\|DefaultEbEventSyncService\|DefaultEbTicketService\|DefaultEbCapacityService\|DefaultEbScheduleService` (`**/shared/src/**/*.java`), no results

Searched for files matching `**/shared/src/main/java/com/eventplatform/shared/eventbrite/service/Default*.java`, 7 matches

Read [](file:///home/dell/Multi-City-Event-Booking/mock-eventbrite-api/app/api/routes/venues.py)

Both decisions are correct. Here's the precise picture:

---

**Follow-up 1 — Skip JWT org resolution: Yes, correct call.**

The current `orgId`-as-query-param approach works fine while you're single-org. The fix belongs at the multi-tenancy boundary — at that point you'll implement it properly with Spring Security `Authentication` principal extraction, not as a half-measure now. Nothing in the current codebase breaks due to this gap.

---

**Follow-up 2 — Use your mock for development: Yes, this is the right approach.**

The mock already covers every EB API path the `Default*` stubs need to call:

| What the stub does | Mock endpoint |
|---|---|
| `EbEventSyncService.createDraftEvent()` | `POST /v3/organizations/{org_id}/events/` |
| `EbEventSyncService.updateEvent()` | `POST /v3/events/{event_id}/` |
| `EbEventSyncService.publishEvent()` | `POST /v3/events/{event_id}/publish/` |
| `EbEventSyncService.cancelEvent()` | `POST /v3/events/{event_id}/cancel/` |
| `EbVenueService.getVenue()` | `GET /v3/venues/{venue_id}/` |
| `EbTicketService.*` | `GET/POST /v3/events/{event_id}/ticket_classes/` |
| `EbCapacityService.*` | `GET/POST /v3/events/{event_id}/capacity_tier/` |
| `EbScheduleService.*` | `POST /v3/events/{event_id}/schedules/` |

**One gap:** The mock has no `POST /v3/organizations/{org_id}/venues/` — venue creation is missing. You'll need to add that route to the mock, or `EbVenueService.createVenue()` will have nowhere to POST.

**The implementation plan for wiring the stubs:**
1. Add `eventbrite.base-url=http://localhost:8888` to `application-dev.properties`
2. Replace each `Default*` stub's throw with a `RestClient` call using that base URL + `Authorization: Bearer <token>` (token comes from `EbTokenStore.getToken(orgId)`)
3. For `EbOAuthClient` specifically — the mock has no OAuth token endpoint, so use a fixed dev token seeded via `application-dev.properties` (`eventbrite.dev-token=...`) and bypass the exchange flow in dev profile

Do you want to start with wiring one specific facade (e.g., `DefaultEbEventSyncService`) as the pattern, or add the missing venue create route to the mock first?