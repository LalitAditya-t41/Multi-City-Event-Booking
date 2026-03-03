# SPEC_2.md — FR2: Show Scheduling → Time Slot Configuration → Conflict Validation

**Owner:** Lalit
**Module(s):** `scheduling` (primary), `discovery-catalog` (venue), `booking-inventory` (ticket sync), `shared` (EB facades, token store)
**Flow:** Flow 2 — Org OAuth Onboarding → Venue Creation → Show Slot Creation → EB Sync → Recurring → Error Handling → Reconciliation
**Last Updated:** March 2026
**Status:** Stage 1 — Requirements

---

## Stage 1 — Requirements

---

### A. Org OAuth Onboarding *(one-time per org, admin panel only)*

- **[MUST]** Admin must initiate Eventbrite OAuth connection for an org from the admin panel.
- **[MUST]** System must exchange the OAuth `code` for an `access_token` (and `refresh_token` if issued) via the EB token endpoint.
- **[MUST]** The following fields must be stored encrypted per org: `access_token`, `refresh_token` (if present), `eb_organization_id`, `expires_at`.
- **[MUST]** All Eventbrite API calls must be scoped to the org's stored token — no global/shared token allowed.
- **[MUST]** EB org token storage is owned exclusively by `shared/eventbrite/` layer. No application module (`scheduling`, `discovery-catalog`, `booking-inventory`, etc.) stores or caches EB tokens directly.
- **[SHOULD]** Token refresh must be handled transparently as a pre-call interceptor inside each `shared/` EB facade: if `expires_at < now + 5 minutes`, attempt refresh before the outbound call.
- **[SHOULD]** If token refresh fails, throw `EbAuthException`. The calling facade propagates it as a safe error. The triggered slot sync stays in `PENDING_SYNC` (not `ACTIVE`).
- **[SHOULD]** `identity` module stores `organization_id` on the org admin user record. Token details stored in `shared/`, not in `identity`.

---

### B. Venue Management *(discovery-catalog owns this)*

- **[MUST]** Admin must create venues in the app with: name, address, city, capacity, seating mode (GA / Reserved), and optional floor plan reference.
- **[MUST]** On venue creation, `discovery-catalog` must call `EbVenueService.createVenue(orgToken, venueData)` → `POST /organizations/{eb_org_id}/venues/`.
- **[MUST]** Eventbrite-returned `venue_id` must be stored as `eb_venue_id` on the internal `Venue` entity.
- **[MUST]** `scheduling` must read venue data via REST call to `discovery-catalog` only — never direct DB import, never shared entity.
- **[MUST]** When admin updates a venue, `discovery-catalog` must call `EbVenueService.updateVenue(orgToken, ebVenueId, venueData)` → `POST /venues/{venue_id}/`.
- **[SHOULD]** A periodic `VenueReconciliationJob` in `discovery-catalog` must call `GET /venues/{eb_venue_id}/` for each venue and flag external drift (capacity/address change on EB side) for admin review.
- **[COULD]** Admin must be able to manually trigger venue re-sync via `POST /api/discovery-catalog/venues/{id}/sync`.

---

### C. Show Slot Creation & Internal Validation *(scheduling owns this)*

- **[MUST]** Admin must create a show slot with: venue (by `venue_id`), date, start time, end time, seating mode (GA / Reserved), pricing tiers (one or more), and optional recurrence rule.
- **[MUST]** `scheduling` must retrieve venue details (capacity, seating mode, city) via REST GET to `discovery-catalog` before validation. It must not store a copy of the venue entity.
- **[MUST]** System must enforce **overlap conflict detection** against existing internal `show_slots` for the same `venue_id` and overlapping time window — no EB call involved.
- **[MUST]** System must enforce **turnaround gap policy** — a configurable minimum gap (e.g., 60 minutes) between consecutive slots at the same venue — entirely in internal DB logic. Eventbrite has no conflict validation API.
- **[MUST]** If a conflict is detected, the system must return a structured alternatives response:
  - Same venue, non-conflicting time options (next available windows at that venue)
  - Alternative venues in the same city (query `discovery-catalog` REST by `city_id`, sorted by capacity match)
  - Adjusted start/end times that satisfy the gap policy
- **[MUST]** No Eventbrite call is made until all internal validation passes.
- **[MUST]** A conflict-free slot is created internally with status `DRAFT` before any EB sync begins.

---

### D. Eventbrite Sync Orchestration *(cross-module via Spring Events)*

> **Constraint:** Spring Events carry only primitives, IDs, enums, value objects — never `@Entity` objects (Hard Rule #4).

#### D.1 — Draft Event Creation (`scheduling`)
- **[MUST]** On slot submission, `scheduling` transitions slot from `DRAFT` → `PENDING_SYNC`.
- **[MUST]** `scheduling` calls `EbEventSyncService.createDraft(orgToken, slotData)` → `POST /organizations/{eb_org_id}/events/`.
- **[MUST]** Returned `eb_event_id` must be stored on the `ShowSlot` entity immediately.
- **[MUST]** On success, `scheduling` publishes `SlotDraftCreatedEvent(slotId, ebEventId, pricingTierIds, seatingMode, orgId)`.
- **[MUST]** On failure, slot stays `PENDING_SYNC`, `sync_attempt_count` increments, `last_sync_error` is recorded, safe error returned to admin.

#### D.2–D.5 — Ticket / Capacity / Seat Map Setup (`booking-inventory` listener)
- **[MUST]** `booking-inventory` listens to `SlotDraftCreatedEvent` and executes the following in order:
  1. `EbTicketService.createTicketClasses(ebEventId, pricingTiers)` → `POST /events/{event_id}/ticket_classes/` (one per pricing tier).
  2. `EbTicketService.createInventoryTiers(ebEventId, tiers)` → `POST /events/{event_id}/inventory_tiers/`.
  3. **(GA only)** Capacity is **not a separate create call** — it is set in the event creation payload in D.1 via the `capacity` field. Subsequent capacity changes use `EbCapacityService.updateCapacityTier()` → `POST /events/{event_id}/capacity_tier/`.
  4. **(Reserved only)** `EbTicketService.copySeatMap(ebEventId, sourceSeatMapId)` → `POST /events/{event_id}/seatmaps/` with `source_seatmap_id`.
- **[MUST]** On success, `booking-inventory` publishes `TicketSyncCompletedEvent(slotId, ebEventId)`.
- **[MUST]** On any step failure, `booking-inventory` publishes `TicketSyncFailedEvent(slotId, ebEventId, reason)`. Slot remains `PENDING_SYNC`, `sync_attempt_count` increments, `last_sync_error` recorded.

#### D.6 — Publish (`scheduling` listener)
- **[MUST]** `scheduling` listens to `TicketSyncCompletedEvent` and calls `EbEventSyncService.publishEvent(orgToken, ebEventId)` → `POST /events/{event_id}/publish/`.
- **[MUST]** On success, slot transitions `PENDING_SYNC` → `ACTIVE`.
- **[MUST]** On failure, slot stays `PENDING_SYNC`, `sync_attempt_count` increments, `last_sync_error` recorded saying publish failed. The EB draft event is preserved — do NOT attempt to recreate it; retry publish only.

---

### E. Slot Update Sync *(post-ACTIVE edits)*

- **[MUST]** When an admin edits an `ACTIVE` slot's details (time, capacity, pricing), `scheduling` must call `EbEventSyncService.updateEvent(orgToken, ebEventId, changes)` → `POST /events/{event_id}/`.
- **[MUST]** If the EB update call fails, the internal slot update must still be persisted, but slot is flagged `SYNC_FAILED` (or `last_sync_error` recorded) for admin visibility. The internal record is the source of truth.
- **[MUST]** Capacity updates on an existing ACTIVE slot use `EbCapacityService.updateCapacityTier()` — not a new create call.

---

### F. Slot Cancellation Sync

- **[MUST]** When a slot transitions to `CANCELLED`, `scheduling` must call `EbEventSyncService.cancelEvent(orgToken, ebEventId)` → `POST /events/{event_id}/cancel/`.
- **[MUST]** EB cancellation failure must NOT block the internal cancellation — the slot becomes `CANCELLED` internally regardless. Log the failure, increment `sync_attempt_count`, record `last_sync_error`, and flag for admin review.
- **[MUST]** `CANCELLED` is a terminal state — no further EB sync is attempted unless admin explicitly intervenes.

---

### G. Recurring Schedules *(optional)*

- **[MUST]** If a recurrence rule is supplied, the initial EB event must be created with `is_series: true` in the D.1 payload.
- **[MUST]** After D.6 publish, `scheduling` calls `EbScheduleService.createSchedule(orgToken, ebEventId, recurrenceRule)` → `POST /events/{event_id}/schedules/`.
- **[MUST]** Eventbrite generates occurrence event IDs. Each occurrence must be stored as a child `ShowSlotOccurrence` record linked to the parent `ShowSlot`.
- **[MUST]** The parent `ShowSlot` stores `eb_series_id`. Each `ShowSlotOccurrence` stores its own `eb_event_id` (occurrence ID).
- **[MUST]** Publishing the series parent (D.6) publishes all valid occurrences — no per-occurrence publish call.
- **[SHOULD]** Recurrence validation errors from EB (e.g., `ERROR_CANNOT_PUBLISH_SERIES_WITH_NO_DATES`) must be surfaced clearly to admin with the EB error code and a human-readable message.

---

### H. State Management

- **[MUST]** ShowSlot states: `DRAFT` → `PENDING_SYNC` → `ACTIVE` → `CANCELLED`.
- **[MUST]** The state machine lives in `scheduling/statemachine/ShowSlotStateMachine.java`.
- **[MUST]** Valid transitions:

  | From | Event | To |
  |---|---|---|
  | `DRAFT` | Admin submits slot | `PENDING_SYNC` |
  | `PENDING_SYNC` | EB publish succeeds | `ACTIVE` |
  | `PENDING_SYNC` | EB call fails | `PENDING_SYNC` (stays, retry eligible) |
  | `ACTIVE` | Admin cancels | `CANCELLED` |
  | `ACTIVE` | EB cancel fails | `CANCELLED` (internal, flagged) |

- **[MUST]** Any invalid transition (e.g., `ACTIVE` → `PENDING_SYNC`, `CANCELLED` → `ACTIVE`) must throw `BusinessRuleException`.
- **[SHOULD]** `ShowSlot` entity must track `sync_attempt_count` (int), `last_sync_error` (string, nullable), and `last_attempted_at` (timestamp, nullable) to distinguish a never-tried slot from a repeatedly failing one.

---

### I. Error Handling & Retry

- **[MUST]** Any Eventbrite call failure must keep the slot non-`ACTIVE` and return a safe, non-raw error to admin.
- **[MUST]** All errors must use shared exception types from `shared/common/exception/` and conform to `ErrorResponse` format via the single `GlobalExceptionHandler` in `shared/common/exception/GlobalExceptionHandler`.
- **[MUST]** No module may declare its own `@ControllerAdvice` (Hard Rule #5).
- **[SHOULD]** Rate-limit errors (HTTP 429) and transient EB failures (5xx) must be retried with exponential backoff via `shared/common/retry/EbRetryPolicy` using Spring `@Retryable`.
- **[SHOULD]** Retry logic must be rate-limit-aware — respect `Retry-After` header from EB responses.
- **[SHOULD]** Maximum retry attempts and backoff duration must be externally configurable (application properties).
- **[COULD]** Admin must be able to manually trigger a re-sync via `POST /api/scheduling/slots/{id}/retry-sync`.

---

### J. Background Reconciliation *(periodic jobs)*

- **[MUST]** `scheduling/service/SlotReconciliationJob` (`@Scheduled`) must, for each `ACTIVE` slot with an `eb_event_id`, call `GET /events/{event_id}/` and verify the EB event is still live (`LIVE` status).
- **[MUST]** If a mismatch is detected (EB event missing, cancelled, or status unexpected), the slot must be flagged for admin review — not auto-fixed.
- **[MUST]** `discovery-catalog/service/VenueReconciliationJob` (`@Scheduled`) must call `GET /venues/{eb_venue_id}/` for each venue and flag external edits (address, capacity drift) for admin review.
- **[MUST]** `admin/` reads flagged mismatches for dashboard display via REST GET calls to `scheduling` and `discovery-catalog`. `admin/` owns no `@Entity` and no reconciliation logic.
- **[SHOULD]** Reconciliation job failure (e.g., EB API unavailable) must not crash the scheduler — log at WARN level and continue to next record.

---

### Out of Scope (Flow 2)

- User-facing seat selection (FR4 — `booking-inventory`)
- Payment processing (FR5 — `payments-ticketing`)
- Coupon / discount application (FR7 — `promotions`)
- Review eligibility unlocking (FR8 — `engagement`)
- Identity / JWT for end users (FR3 — `identity`)
- Admin dashboard UI implementation
- Eventbrite Checkout Widget integration

---

## Stage 2 — Domain Model

---

### Module Ownership Map

| Entity / Concept | Owned By | Type | Persisted |
|---|---|---|---|
| `OrganizationAuth` | `shared/eventbrite/` | `@Entity` |  Yes |
| `Venue` | `discovery-catalog` | `@Entity` |  Yes |
| `ShowSlot` | `scheduling` | `@Entity` |  Yes |
| `ShowSlotOccurrence` | `scheduling` | `@Entity` |  Yes |
| `ShowSlotPricingTier` | `scheduling` | `@Entity` |  Yes |
| `ConflictAlternativeResponse` | `scheduling` | Value Object |  No (response only) |

> **Hard Rule:** No cross-module JPA `@ManyToOne` / `@OneToMany` relationships. Modules are linked by scalar `org_id` / `venue_id` / `slot_id` fields only. All navigation across module boundaries goes via REST.

---

### 1. `OrganizationAuth` — `shared/eventbrite/domain/`

Stores the Eventbrite OAuth credentials per organisation. One record per org.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `org_id` | `Long` | FK to internal organisation (identity module — scalar, not JPA FK) |
| `eb_organization_id` | `String` | Returned by Eventbrite OAuth |
| `access_token` | `String` | Encrypted at rest |
| `refresh_token` | `String` | Encrypted at rest; nullable (not all grants issue it) |
| `expires_at` | `Instant` | Token expiry timestamp |
| `status` | `OrgAuthStatus` (enum) | `PENDING` / `CONNECTED` / `TOKEN_EXPIRED` / `REVOKED` |
| `created_at` | `Instant` | Audit |
| `updated_at` | `Instant` | Audit |

**Status Lifecycle:**
```
PENDING → CONNECTED → TOKEN_EXPIRED → CONNECTED (after refresh)
                    ↘ REVOKED (admin disconnects or EB revokes)
```

- `PENDING` — OAuth flow initiated, code exchange not yet complete.
- `CONNECTED` — Valid token in place; all EB calls can proceed.
- `TOKEN_EXPIRED` — `expires_at < now`; refresh required before next EB call.
- `REVOKED` — Admin disconnected or EB revoked access; no EB calls permitted.

**Rules:**
- Token refresh is handled as a pre-call interceptor in `shared/` facades. If `expires_at < now + 5 minutes` → attempt refresh → update record → proceed.
- If refresh fails → throw `EbAuthException` → status set to `TOKEN_EXPIRED`.
- No module outside `shared/` reads or writes this entity.

---

### 2. `Venue` — `discovery-catalog/domain/`

Represents a physical performance venue. Owned entirely by `discovery-catalog`.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `org_id` | `Long` | Scalar reference to org (no JPA FK to `OrganizationAuth`) |
| `name` | `String` | |
| `address_line1` | `String` | |
| `address_line2` | `String` | Nullable |
| `city` | `String` | |
| `city_id` | `Long` | FK to internal `City` entity (within `discovery-catalog`) |
| `country` | `String` | |
| `latitude` | `Double` | Nullable |
| `longitude` | `Double` | Nullable |
| `capacity` | `Integer` | Total venue capacity |
| `seating_mode` | `SeatingMode` (enum) | `GA` / `RESERVED` |
| `eb_venue_id` | `String` | Returned by `EbVenueService.createVenue()`; nullable until synced |
| `sync_status` | `VenueSyncStatus` (enum) | `PENDING_SYNC` / `SYNCED` / `DRIFT_FLAGGED` |
| `created_at` | `Instant` | Audit |
| `updated_at` | `Instant` | Audit |

**Rules:**
- `scheduling` never imports this entity. It receives a `VenueDto` via REST GET `/api/discovery-catalog/venues/{id}`.
- `eb_venue_id` is null until the EB venue creation call succeeds.
- `DRIFT_FLAGGED` is set by `VenueReconciliationJob` when EB-side data differs from internal record.

---

### 3. `ShowSlot` — `scheduling/domain/`

The primary scheduling aggregate. Represents one bookable time window at a venue.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `org_id` | `Long` | Scalar org reference |
| `venue_id` | `Long` | Scalar venue reference (no JPA FK to `Venue`) |
| `title` | `String` | Show/event name |
| `start_time` | `ZonedDateTime` | |
| `end_time` | `ZonedDateTime` | |
| `seating_mode` | `SeatingMode` (enum) | `GA` / `RESERVED` |
| `capacity` | `Integer` | Total seats; set on EB event creation payload |
| `source_seatmap_id` | `String` | Nullable; only for `RESERVED` seating — EB source seatmap to copy |
| `is_recurring` | `Boolean` | `true` if this slot is a recurring series head |
| `recurrence_rule` | `String` | Nullable; iCal RRULE string or JSON representation |
| `status` | `ShowSlotStatus` (enum) | `DRAFT` / `PENDING_SYNC` / `ACTIVE` / `CANCELLED` |
| `eb_event_id` | `String` | Nullable; returned by `EbEventSyncService.createDraft()` |
| `eb_series_id` | `String` | Nullable; returned by `EbScheduleService.createSchedule()`; only for recurring |
| `sync_attempt_count` | `Integer` | Incremented on each failed EB call; reset on `ACTIVE` |
| `last_sync_error` | `String` | Nullable; last EB error message |
| `last_attempted_at` | `Instant` | Nullable; timestamp of last sync attempt |
| `created_at` | `Instant` | Audit |
| `updated_at` | `Instant` | Audit |

**Status Lifecycle:**
```
DRAFT → PENDING_SYNC → ACTIVE → CANCELLED
                ↑ (stays on EB failure, retry eligible)
```

**Rules:**
- Invalid transitions throw `BusinessRuleException` (e.g., `ACTIVE → DRAFT`, `CANCELLED → ACTIVE`).
- `eb_event_id` is stored immediately after `EbEventSyncService.createDraft()` succeeds — before ticket sync.
- `capacity` is passed in the EB event creation body (not a separate `capacity_tier` create call).
- `source_seatmap_id` is embedded directly (no separate `SeatMapReference` entity).
- `sync_attempt_count` + `last_sync_error` + `last_attempted_at` are embedded (no separate sync log entity for Flow 2).

---

### 4. `ShowSlotOccurrence` — `scheduling/domain/`

Represents a single occurrence of a recurring `ShowSlot`. Each has its own EB event ID.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `parent_slot_id` | `Long` | Scalar FK to parent `ShowSlot` (within same module — JPA `@ManyToOne` is allowed here) |
| `occurrence_index` | `Integer` | 1-based position in the series |
| `start_time` | `ZonedDateTime` | Specific date/time for this occurrence |
| `end_time` | `ZonedDateTime` | |
| `status` | `ShowSlotStatus` (enum) | Independent — not mirrored from parent |
| `eb_event_id` | `String` | Nullable; this occurrence's EB event ID |
| `sync_attempt_count` | `Integer` | Independent sync failure tracking |
| `last_sync_error` | `String` | Nullable |
| `last_attempted_at` | `Instant` | Nullable |
| `created_at` | `Instant` | Audit |

**Rules:**
- `ShowSlotOccurrence` has its own independent `status` and sync fields — it does NOT mirror the parent's state.
- A parent `ShowSlot` with `is_recurring = true` may have 0..N occurrences.
- Publishing the series parent (EB side) publishes all valid occurrences — no per-occurrence publish call required.
- If a single occurrence fails EB sync, only that occurrence stays `PENDING_SYNC`; the parent and other occurrences are unaffected.

---

### 5. `ShowSlotPricingTier` — `scheduling/domain/`

Defines one ticket tier for a `ShowSlot`. Drives ticket class creation in Eventbrite.

> **Note:** This entity is owned by `scheduling`, not `booking-inventory`. It defines the input to EB ticket class creation at scheduling time. `booking-inventory`'s "Pricing" is a separate concern (cart-level pricing, seat assignment pricing).

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | PK |
| `slot_id` | `Long` | Scalar FK to `ShowSlot` (within same module — JPA `@ManyToOne` allowed) |
| `name` | `String` | e.g., "VIP", "General Admission", "Early Bird" |
| `price_amount` | `BigDecimal` | Base price |
| `currency` | `String` | ISO 4217, e.g., "INR" |
| `quota` | `Integer` | Max tickets available for this tier |
| `tier_type` | `TierType` (enum) | `FREE` / `PAID` / `DONATION` |
| `eb_ticket_class_id` | `String` | Nullable; returned by `EbTicketService.createTicketClasses()` |
| `created_at` | `Instant` | Audit |

**Rules:**
- A `ShowSlot` must have at least one `ShowSlotPricingTier` before EB sync can begin.
- `eb_ticket_class_id` is populated during the D.2 sync step.
- Pricing tiers are passed inside `SlotDraftCreatedEvent` as a list of `PricingTierDto` (IDs + values — no `@Entity` in events).

---

### 6. `ConflictAlternativeResponse` — `scheduling/domain/value/`

Non-persisted value object returned when a conflict is detected during slot creation.

**Structure:**

```java
public record ConflictAlternativeResponse(
    List<TimeWindowOption>  sameVenueAlternatives,   // next available windows at same venue
    List<VenueOption>       nearbyVenueAlternatives, // same city_id, sorted by capacity match
    List<TimeWindowOption>  adjustedTimeOptions      // times satisfying the gap policy
) {}

public record TimeWindowOption(
    ZonedDateTime proposedStart,
    ZonedDateTime proposedEnd
) {}

public record VenueOption(
    Long   venueId,
    String venueName,
    String city,
    int    capacity
) {}
```

**Rules:**
- Never persisted; returned directly in the API error response body.
- `nearbyVenueAlternatives` populated via REST GET to `discovery-catalog` using `city_id` of the conflicting venue.
- `sameVenueAlternatives` and `adjustedTimeOptions` computed from internal `show_slots` DB query.

---

### Relationships Summary

```
OrganizationAuth (shared/)
    org_id ──────────────────────────────────────────────────────→ (scalar ref)
                                  │                                │
                         Venue (discovery-catalog)         ShowSlot (scheduling)
                         org_id: Long ✓                    org_id: Long ✓
                         city_id: Long → City              venue_id: Long ✓
                                                           │
                                          ┌────────────────┴──────────────────┐
                                          │                                   │
                              ShowSlotPricingTier              ShowSlotOccurrence
                              (1..N per slot)                  (0..N, only if recurring)
                              @ManyToOne ShowSlot ✓           @ManyToOne ShowSlot ✓
                              eb_ticket_class_id              own status + sync fields
```

**Cross-module links use scalar IDs only — no JPA `@ManyToOne` across module boundaries.**

---

### Enums

| Enum | Values | Owner |
|---|---|---|
| `OrgAuthStatus` | `PENDING`, `CONNECTED`, `TOKEN_EXPIRED`, `REVOKED` | `shared/` |
| `SeatingMode` | `GA`, `RESERVED` | `shared/common/` (reused by both modules) |
| `ShowSlotStatus` | `DRAFT`, `PENDING_SYNC`, `ACTIVE`, `CANCELLED` | `scheduling/` |
| `VenueSyncStatus` | `PENDING_SYNC`, `SYNCED`, `DRIFT_FLAGGED` | `discovery-catalog/` |
| `TierType` | `FREE`, `PAID`, `DONATION` | `scheduling/` |

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
│   │                                                      updateVenue() MUST call snapshotCache.invalidate(orgId, venue.getCityId())
│   │                                                      after persisting — venue name/address shown in event cards
│   └── VenueReconciliationJob.java                ← NEW  @Scheduled — GET /venues/{eb_venue_id}/, flag drift
│
├── repository/
│   └── VenueRepository.java                       ← MODIFY  Add findByOrgId(), findByCityId(), findBySyncStatus()
│
├── mapper/
│   └── VenueMapper.java                           ← NEW  MapStruct — Venue ↔ VenueResponse / CreateVenueRequest
│
└── event/
    ├── published/
    │   └── VenueDriftDetectedEvent.java           ← NEW  (venueId, ebVenueId, driftDescription)
    └── listener/
        ├── SlotActivatedListener.java             ← NEW  listens ShowSlotActivatedEvent
        │                                                  → EventCatalogSyncService.sync(orgId, cityId, venueId)
        │                                                  → ensures event_catalog row created immediately
        │                                                  → cache invalidated for (orgId, cityId)
        └── SlotCancelledListener.java             ← NEW  listens ShowSlotCancelledEvent
                                                          → sets event_catalog.state = CANCELLED (if row exists)
                                                          → cache invalidated for (orgId, cityId)
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
│   │   │   # ──────────────────────────────────────────────────────────────────────────
│   │   │   # Resolved Decision — Deviation #1:
│   │   │   # These event classes are NOT in this folder at runtime.
│   │   │   # Actual location: shared/common/event/published/
│   │   │   # Reason: avoids circular dependency; both scheduling and booking-inventory
│   │   │   #         need references to the same event types.
│   │   │   # ──────────────────────────────────────────────────────────────────────────
│   │   ├── SlotDraftCreatedEvent.java             ← shared/common/event/published/
│   │   ├── SlotSyncFailedEvent.java               ← shared/common/event/published/
│   │   ├── ShowSlotActivatedEvent.java            ← shared/common/event/published/
│   │   │                                                  fired when slot transitions PENDING_SYNC → ACTIVE
│   │   │                                                  consumed by discovery-catalog to populate event_catalog
│   │   └── ShowSlotCancelledEvent.java            ← shared/common/event/published/
│   │                                                      fired when slot transitions to CANCELLED
│   │                                                      consumed by discovery-catalog to mark event_catalog CANCELLED
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
│       │   # Resolved Decision — Deviation #1: actual location shared/common/event/published/
│       ├── TicketSyncCompletedEvent.java          ← shared/common/event/published/  (slotId, ebEventId)
│       └── TicketSyncFailedEvent.java             ← shared/common/event/published/  (slotId, ebEventId, reason)
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

> **Resolved Decision — Deviation #1:** All cross-module event classes below are physically located in `shared/common/event/published/` (not in per-module `event/published/` folders). This avoids a circular dependency where both `scheduling` and `booking-inventory` would otherwise need to own the same event type. Event payloads carry only primitives, IDs, enums, and value objects — no `@Entity` objects (Hard Rule #4). See [Resolved Decisions](#resolved-decisions) at end of file.

| Event | Publisher | Listener(s) | Fields (primitives / IDs / enums only) |
|---|---|---|---|
| `SlotDraftCreatedEvent` | `scheduling` | `booking-inventory` | `slotId`, `ebEventId`, `pricingTierIds` (List\<Long\>), `seatingMode`, `orgId`, `sourceSeatMapId` (nullable) |
| `TicketSyncCompletedEvent` | `booking-inventory` | `scheduling` | `slotId`, `ebEventId` |
| `TicketSyncFailedEvent` | `booking-inventory` | `scheduling` | `slotId`, `ebEventId`, `reason` |
| `ShowSlotActivatedEvent` | `scheduling` | `discovery-catalog` | `slotId`, `ebEventId`, `orgId`, `venueId`, `cityId` — triggers event_catalog sync + cache invalidation |
| `ShowSlotCancelledEvent` | `scheduling` | `discovery-catalog` | `slotId`, `ebEventId`, `orgId`, `venueId`, `cityId` — marks event_catalog CANCELLED + cache invalidation |
| `SlotSyncFailedEvent` | `scheduling` | *(admin notification — future)* | `slotId`, `reason` |
| `VenueDriftDetectedEvent` | `discovery-catalog` | *(admin notification — future)* | `venueId`, `ebVenueId`, `driftDescription` |


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

-- Backfill: venues that already have an eb_venue_id are already synced.
-- Without this, the VenueReconciliationJob would treat all pre-existing venues
-- as un-synced on its first run and attempt unnecessary EB re-syncs.
UPDATE venue
    SET sync_status = 'SYNCED'
    WHERE eventbrite_venue_id IS NOT NULL;

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
    city_id               BIGINT       NOT NULL,
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
    CONSTRAINT fk_show_slot_city  FOREIGN KEY (city_id)         REFERENCES city(id)         ON DELETE RESTRICT,
    CONSTRAINT chk_slot_times     CHECK (end_time > start_time),
    CONSTRAINT chk_slot_capacity  CHECK (capacity > 0),
    CONSTRAINT chk_recurring_rule CHECK (
        (is_recurring = FALSE AND recurrence_rule IS NULL) OR
        (is_recurring = TRUE)
    )
);

CREATE INDEX idx_show_slot_org_id     ON show_slot(organization_id);
CREATE INDEX idx_show_slot_venue_id   ON show_slot(venue_id);
CREATE INDEX idx_show_slot_city_id    ON show_slot(city_id);
CREATE INDEX idx_show_slot_status     ON show_slot(status);
CREATE INDEX idx_show_slot_eb_event   ON show_slot(eb_event_id);
-- Conflict detection query index: venue + time range overlap
CREATE INDEX idx_show_slot_venue_time ON show_slot(venue_id, start_time, end_time);
-- Cache invalidation + reconciliation job: ACTIVE slots with eb_event_id, grouped by city
CREATE INDEX idx_show_slot_active_eb  ON show_slot(status, eb_event_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_show_slot_org_city   ON show_slot(organization_id, city_id);
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

#### `GET /api/v1/admin/dashboard/venues/flagged`

> **Resolved Decision — Deviation #2:** Path changed from `/api/v1/admin/catalog/venues/flagged` to `/api/v1/admin/dashboard/venues/flagged` to avoid path collision with the `discovery-catalog`-owned endpoint at the same original path (row 9 of the endpoint summary). The `dashboard` segment makes admin-aggregation ownership visible in the URL.

Aggregates venues with `syncStatus = DRIFT_FLAGGED`.

**Auth:** `ROLE_ADMIN`  
**Query params:** `orgId` (Long, required)  
**Response `200`:** paginated list of flagged `VenueResponse` with `lastSyncError`  
**Notes:** Delegates to `GET /api/v1/admin/catalog/venues/flagged` in `discovery-catalog`. The admin aggregation endpoint path is `/api/v1/admin/dashboard/venues/flagged` (not `/catalog/`) — see Deviation #2.

---

### Internal REST Calls (module-to-module — not public API)

These are intra-service calls that reuse existing public endpoints — no new endpoints needed.

| Caller | Callee | Endpoint reused | Purpose |
|---|---|---|---|
| `scheduling` | `discovery-catalog` | `GET /api/v1/catalog/venues/{id}` | Fetch venue capacity + seatingMode before conflict check |
| `scheduling` | `discovery-catalog` | `GET /api/v1/catalog/venues?cityId={id}&orgId={id}` | Fetch nearby venues for conflict alternative suggestions |
| `admin/` | `scheduling` | `GET /api/v1/scheduling/slots?status=PENDING_SYNC` | Aggregate flagged slot data for dashboard |
| `admin/` | `scheduling` | `GET /api/v1/scheduling/slots/mismatches` | Aggregate EB mismatch data for dashboard |
| `admin/` | `discovery-catalog` | `GET /api/v1/admin/catalog/venues/flagged` | Aggregate drift-flagged venues for dashboard (admin exposes this as `GET /api/v1/admin/dashboard/venues/flagged`) |

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
| 20 | GET | `/api/v1/admin/dashboard/venues/flagged` *(agg.)* | admin | ROLE_ADMIN |


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

