# SPEC_1.md — FR1 Catalog Search (City Selection → Venue Discovery → Event Listing)

## Stage 1 — Requirements

### User Facing
- **[MUST]** Users must list cities from the internal `cities` table.
- **[MUST]** Users must list venues by `city_id` from internal `venues`.
- **[MUST]** Users must list events filtered by `city_id` and optional `venue_id`.
- **[MUST]** Event listing must be served from internal `event_catalog` (DB is source of truth).

### Snapshot Cache & Refresh
- **[MUST]** Cache scope is `(org_id, city_id)` (not per-venue).
- **[MUST]** Webhook arrival invalidates cache immediately using async + batched invalidation.
- **[MUST]** Snapshot TTL = 1 hour.
- **[MUST]** If snapshot is missing or expired, query DB directly.
- **[SHOULD]** Track cache hit/miss ratio per org via Micrometer counters (exposed via `/actuator/metrics`).
- **[SHOULD]** Redis eviction policy should be LRU with max 10GB memory (configurable).

### Webhook & TTL Fallback (Hybrid Strategy)
- **[MUST]** Catalog updates are driven by Eventbrite webhooks when available.
- **[MUST]** **Read-path check (lazy):** on every read, check `webhook_config.last_webhook_at`. If > 1 hour ago, trigger async refresh and return data immediately with stale flag.
- **[MUST]** **Scheduled job (proactive):** every 4 hours, scan all orgs and trigger async refresh if `last_webhook_at` > 1 hour ago.
- **[MUST]** Skip orgs in cooldown (`cooldown_until > now`) for scheduled refresh.
- **[MUST]** Cooldown lasts 3 hours after repeated failures and exits immediately on successful webhook delivery.
- **[SHOULD]** Log read-path refresh triggers (info level).
- **[SHOULD]** Log scheduled refresh triggers (info level).
- **[SHOULD]** Track read-path refresh count vs scheduled refresh count in metrics.

### Webhook Registration & Security
- **[MUST]** When a new org is created, `identity` publishes `OrgCreatedEvent(orgId, adminUserId, createdAt)` after DB commit. `discovery-catalog`'s `OrgCreatedEventListener` handles this via `@TransactionalEventListener(AFTER_COMMIT)` and calls `EbWebhookService.registerWebhook(orgId)`. `identity` never calls `EbWebhookService` directly (Hard Rule #1).
- **[MUST]** Webhook registration uses Eventbrite Create Webhook schema: `endpoint_url`, `actions`, optional `event_id` (null = org-wide).
- **[MUST]** Actions list must be a subset of Eventbrite-supported actions (e.g., `event.created`, `event.published`, `event.updated`, `event.unpublished`, `order.placed`, `order.refunded`, `order.updated`, `attendee.updated`, `venue.updated`).
- **[MUST]** Inbound Eventbrite webhooks are received by `shared/eventbrite/webhook/EbWebhookController`, validated by `EbWebhookDispatcher`, then dispatched as Spring Events to module listeners. No module owns a raw webhook HTTP controller.
- **[MUST]** Validate Eventbrite webhook signature in `EbWebhookDispatcher` using `EventbriteWebhookSignatureValidator` from `shared/eventbrite/webhook/`.

### Eventbrite Sync
- **[MUST]** Use hybrid API: `GET /venues/{venue_id}/events/` when `venue_id` is present; otherwise `GET /organizations/{organization_id}/events/`.
- **[MUST]** Import Eventbrite-only events into `event_catalog` with `source=EVENTBRITE_EXTERNAL`.
- **[SHOULD]** Use webhook-driven changes (or `event.changed`) to selectively call `GET /events/{event_id}/`.
- **[MUST]** Missing events in Eventbrite list should be soft-deleted via `deleted_at`.

### Data Model Alignment
- **[MUST]** Internal schema should align with Eventbrite Event fields to simplify mapping.
- **[SHOULD]** Store `eventbrite_changed_at` for delta comparisons.

### Operational
- **[MUST]** Single-flight lock per scope `(org_id, city_id, venue_id)`.
- **[COULD]** Scheduled hourly org-level sync as backup.

### Out of Scope
- **[WONT]** Eventbrite public search (`/events/search/`).
- **[WONT]** Orders, payments, ticketing, or seat inventory flows.
- **[WONT]** Admin UI for manual sync controls.

---

## Stage 2 — Domain Modeling

### Domain Summary
A `City` contains many `Venues` and many `EventCatalogItems`. A `Venue` belongs to one `City` and may map to an Eventbrite `venue_id`. An `EventCatalogItem` represents a city/venue-scoped event listing aligned to the Eventbrite Event object; it belongs to one `City` and optionally one `Venue`. A `WebhookConfig` belongs to an organization and tracks webhook lifecycle, last delivery time, cooldown state, and retry/failure metadata. Redis snapshot caching and single-flight locks are operational mechanisms, not persisted entities.

### Entities

**City**
- **Identity:** `city_id`
- **Lifecycle:** stable master data (created, updated)
- **Relationships:** one `City` has many `Venues`; one `City` has many `EventCatalogItems`
- **Ownership:** internal system

**Venue**
- **Identity:** `venue_id`
- **Lifecycle:** created/updated internally; mapped to Eventbrite via `eventbrite_venue_id`
- **Relationships:** belongs to one `City`; has many `EventCatalogItems`
- **Ownership:** internal system

**EventCatalogItem**
- **Identity:** `event_id` (internal)
- **Lifecycle:** created, updated, soft-deleted (via `deleted_at`)
- **Relationships:** belongs to one `City`; optionally belongs to one `Venue`
- **Ownership:** internal system; `source=INTERNAL` or `source=EVENTBRITE_EXTERNAL`

**WebhookConfig**
- **Identity:** `id` (surrogate PK); `organization_id` (unique FK, business identity)
- **Lifecycle:** created at org onboarding; updated on every webhook delivery; enters cooldown after repeated failures; exits cooldown on success or after cooldown timeout
- **Relationships:** one `WebhookConfig` per org; governs event sync for all cities/venues in the org
- **Ownership:** internal system
- **Merged State:** includes sync failure counters and last sync attempt fields (no separate `OrgEventSyncState`)
- **Domain Methods:** `isInCooldown()` → true when `cooldown_until > now`; `recordFailure()` → increments `consecutiveFailures`, enters cooldown at threshold 5; `exitCooldown()` → resets `consecutiveFailures = 0`, clears `cooldown_until`

### Non-Persisted Operational Concepts

**CacheSnapshot (Redis)**
- **Identity:** key `(org_id, city_id)`
- **Lifecycle:** TTL-based eviction (1 hour), invalidated on webhook; regenerated on demand
- **Persistence:** not stored in DB

**EventSyncLock (Single-flight)**
- **Identity:** key `(org_id, city_id, venue_id)`
- **Lifecycle:** acquired during async refresh; released on completion or timeout
- **Persistence:** not stored in DB

---

## Stage 3 — Architecture & File Structure

### Architecture
- **Pattern:** Modular Monolith (fixed)
- **Primary module:** `discovery-catalog`
- **Dependent modules:** `shared` only (Eventbrite ACL, webhook validator, Redis config)
- **No cross-module REST calls** required for this feature.

### Cross-Module Interaction Classification
- **Webhook registration:** `identity` publishes `OrgCreatedEvent` → `discovery-catalog`'s `OrgCreatedEventListener` calls `EbWebhookService.registerWebhook(orgId)`. `identity` never calls `EbWebhookService` directly.
- **Webhook ingress:** ALL inbound Eventbrite webhooks enter via `shared/eventbrite/webhook/EbWebhookController` → `EbWebhookDispatcher` validates signature + publishes `EbWebhookReceivedEvent` → `discovery-catalog`'s `EventbriteWebhookEventListener` handles catalog-specific logic. No module owns a raw webhook HTTP controller (Hard Rule #2).
- **External Eventbrite calls:** via `shared/eventbrite/service/` only (`EbEventSyncService`).
- **Webhook signature validation:** inside `EbWebhookDispatcher` in `shared/eventbrite/webhook/`.
- **Integration exceptions:** in `shared/eventbrite/exception/`.
- **No module-to-module service/repo imports.**

### Feature File Structure (discovery-catalog)

`discovery-catalog/src/main/java/com/eventplatform/discoverycatalog/`

**api/controller/**
- `CityCatalogController.java`
- `VenueCatalogController.java`
- `EventCatalogController.java`

**api/dto/request/**
- `EventCatalogSearchRequest.java`

**api/dto/response/**
- `CityResponse.java`
- `VenueResponse.java`
- `EventCatalogItemResponse.java`
- `EventCatalogSearchResponse.java`
- `PaginationInfo.java`

**exception/**  _(module-level, not under api/)_
- `CatalogNotFoundException.java` (extends `ResourceNotFoundException` from `shared`)
- `CatalogSyncException.java` (extends `BaseException` from `shared`)
- `CatalogLockException.java` (extends `BaseException` from `shared`)
- `InvalidCatalogSearchException.java` (extends `ValidationException` from `shared`)
- `MissingWebhookPayloadException.java` (extends `ValidationException` from `shared`)

**domain/**
- `City.java`
- `Venue.java`
- `EventCatalogItem.java`
- `WebhookConfig.java`

**domain/enum/**
- `EventSource.java`
- `EventState.java`
- `WebhookStatus.java`

**domain/value/**
- `SnapshotPayload.java`
- `EventSyncLockKey.java`

**service/**
- `CityCatalogService.java`
- `VenueCatalogService.java`
- `EventCatalogService.java`
- `EventCatalogRefreshService.java` (async refresh + locking)
- `EventCatalogSyncService.java`
- `WebhookStateManager.java`

**service/cache/**
- `EventCatalogSnapshotCache.java`

**service/lock/**
- `EventSyncLockManager.java`

**service/metrics/**
- `EventCatalogMetrics.java`

**service/scheduler/**
- `EventCatalogRefreshScheduler.java`

**repository/**
- `CityRepository.java`
- `VenueRepository.java`
- `EventCatalogRepository.java`
- `WebhookConfigRepository.java`

**mapper/**
- `CityMapper.java`
- `VenueMapper.java`
- `EventCatalogMapper.java`

**event/published/**
- `EventCatalogUpdatedEvent.java`

**event/listener/**
- `EventCatalogUpdatedEventListener.java`
- `OrgCreatedEventListener.java` (listens for `OrgCreatedEvent` from `identity`; calls `EbWebhookService.registerWebhook()` at `AFTER_COMMIT` phase)
- `EventbriteWebhookEventListener.java` (listens for `EbWebhookReceivedEvent` from `shared`; handles city_id lookup, cache invalidation, publishes `EventCatalogUpdatedEvent`)

**config/**
- `DiscoveryCatalogConfig.java`

### Cross-Module Files (shared)

`shared/src/main/java/com/eventplatform/shared/`

**eventbrite/service/**
- `EbEventSyncService.java` (existing facade)
- `EbWebhookService.java` (register/list/delete webhooks; uses Create Webhook schema)

**eventbrite/webhook/**
- `EbWebhookController.java` (receives ALL inbound Eventbrite webhooks at `/admin/v1/webhooks/eventbrite`)
- `EbWebhookDispatcher.java` (validates signature via `EventbriteWebhookSignatureValidator`; publishes `EbWebhookReceivedEvent`)
- `EventbriteWebhookSignatureValidator.java`

**exception/**
- `BaseException.java` (abstract base; owns `errorCode` + `httpStatus`)
- `ResourceNotFoundException.java` (404 base)
- `ValidationException.java` (400 base)

**eventbrite/exception/**
- `EbIntegrationException.java` (502; Eventbrite 5xx/timeout)
- `EbAuthException.java` (502; Eventbrite 401/403 — immediate cooldown)
- `EbWebhookSignatureException.java` (401; invalid signature)

**common/exception/**
- `GlobalExceptionHandler.java` (`@RestControllerAdvice` — ONE handler for ALL modules)
- `ErrorResponse.java` (shared error response record)

**config/**
- `RedisConfig.java`
- `SharedCommonConfig.java`

---

## Stage 4 — DB Schema

### Tables

**organization**
- `id` BIGSERIAL PK
- `name` VARCHAR(255) NOT NULL
- `slug` VARCHAR(255) NOT NULL UNIQUE
- `email` VARCHAR(255)
- `phone` VARCHAR(20)
- `website` VARCHAR(500)
- `logo_url` VARCHAR(500)
- `description` TEXT
- `country_code` VARCHAR(2)
- `timezone` VARCHAR(50) DEFAULT 'UTC'
- `status` organization_status ENUM('ACTIVE','INACTIVE','SUSPENDED') DEFAULT 'ACTIVE'
- `eventbrite_org_id` VARCHAR(255) UNIQUE
- `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP

Indexes:
- `idx_org_status` on `status`
- `idx_org_eventbrite_id` on `eventbrite_org_id`
- Unique: `uk_org_slug` on `slug`

**city**
- `id` BIGSERIAL PK
- `organization_id` BIGINT FK → organization(id) ON DELETE CASCADE
- `name` VARCHAR(255) NOT NULL
- `description` VARCHAR(500)
- `state` VARCHAR(10)
- `country_code` VARCHAR(2)
- `latitude` VARCHAR(50)
- `longitude` VARCHAR(50)
- `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP

Indexes:
- `idx_city_org_id` on `organization_id`
- `idx_city_name` on `name`
- Unique: `uk_org_city_name` on `(organization_id, name)`

**venue**
- `id` BIGSERIAL PK
- `organization_id` BIGINT FK → organization(id) ON DELETE CASCADE
- `city_id` BIGINT FK → city(id) ON DELETE CASCADE
- `eventbrite_venue_id` VARCHAR(255) UNIQUE
- `name` VARCHAR(255) NOT NULL
- `address` TEXT
- `zip_code` VARCHAR(50)
- `latitude` VARCHAR(255)
- `longitude` VARCHAR(255)
- `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP

Indexes:
- `idx_venue_city_id` on `city_id`
- `idx_venue_org_id` on `organization_id`
- `idx_venue_eventbrite_id` on `eventbrite_venue_id`

**event_catalog**
- `id` BIGSERIAL PK
- `organization_id` BIGINT FK → organization(id) ON DELETE CASCADE
- `city_id` BIGINT FK → city(id) ON DELETE CASCADE
- `venue_id` BIGINT FK → venue(id) ON DELETE SET NULL
- `eventbrite_event_id` VARCHAR(255) NOT NULL UNIQUE
- `name` VARCHAR(500) NOT NULL
- `description` TEXT
- `url` VARCHAR(500)
- `start_time` TIMESTAMP NOT NULL
- `end_time` TIMESTAMP NOT NULL
- `state` event_state ENUM('DRAFT','PUBLISHED','CANCELLED') DEFAULT 'PUBLISHED'
- `source` event_source ENUM('INTERNAL','EVENTBRITE_EXTERNAL') DEFAULT 'EVENTBRITE_EXTERNAL'
- `currency` VARCHAR(10)
- `eventbrite_changed_at` TIMESTAMP
- `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `deleted_at` TIMESTAMP

Indexes:
- `idx_event_city_active` on `(city_id, deleted_at)`
- `idx_event_venue_active` on `(venue_id, deleted_at)`
- `idx_event_org_id` on `organization_id`
- `idx_event_deleted_at` on `deleted_at`
- `idx_event_eventbrite_id` on `eventbrite_event_id`
- `idx_event_start_time` on `start_time`

**webhook_config**
- `id` BIGSERIAL PK
- `organization_id` BIGINT UNIQUE FK → organization(id) ON DELETE CASCADE
- `webhook_id` VARCHAR(255) UNIQUE NOT NULL
- `endpoint_url` VARCHAR(500) NOT NULL
- `status` webhook_status ENUM('REGISTERED','IN_COOLDOWN','FAILED','INACTIVE','ORPHANED') DEFAULT 'REGISTERED'
- `registered_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `last_webhook_at` TIMESTAMP
- `last_sync_at` TIMESTAMP
- `last_error_at` TIMESTAMP
- `last_error_message` TEXT
- `consecutive_failures` INTEGER DEFAULT 0
- `cooldown_until` TIMESTAMP
- `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP

Indexes:
- `idx_webhook_org_id` on `organization_id`
- `idx_webhook_status` on `status`
- `idx_webhook_cooldown` on `cooldown_until`

### Migration Files (run in order)

**All migrations** live at `app/src/main/resources/db/migration/` (single Flyway scan path per `MODULE_STRUCTURE_TEMPLATE.md`). Module ownership is documented in each file header, not in file location.

- `V0__create_organization_table.sql` — creates `organization`, `organization_status` enum _(owner: identity module)_
- `V1__create_cities_table.sql` — creates `city` _(owner: discovery-catalog module)_
- `V2__create_venues_table.sql` — creates `venue` _(owner: discovery-catalog module)_
- `V3__create_event_catalog_table.sql` — creates `event_catalog`, enums `event_state`, `event_source` _(owner: discovery-catalog module)_
- `V4__create_webhook_config_table.sql` — creates `webhook_config`, enum `webhook_status` _(owner: discovery-catalog module)_

---

## Stage 5 — API

### 1) `GET /api/v1/catalog/cities`
**Purpose:** List all cities for the current organization.  
**Auth:** none

**Query Params:** none  
**Request Body:** none

**Success Response (200):**  
`CityListResponse`
- `cities`: array of `CityResponse`
- `totalCount`: integer

**Error Responses:**
- 500 Internal Server Error → `CatalogSyncException`

### 2) `GET /api/v1/catalog/venues`
**Purpose:** List venues filtered by city.  
**Auth:** none

**Query Params:**
- `cityId` (required, long)
- `page` (optional, default 0)
- `size` (optional, default 20)

**Request Body:** none

**Success Response (200):**  
`VenueListResponse`
- `venues`: array of `VenueResponse`
- `pagination`: `PaginationInfo`

**Error Responses:**
- 400 Bad Request → `InvalidCatalogSearchException` (missing cityId)
- 404 Not Found → `CatalogNotFoundException` (city not found)
- 500 Internal Server Error → `CatalogSyncException`

### 3) `GET /api/v1/catalog/events`
**Purpose:** Search events for discovery.  
**Auth:** none

**Query Params:**
- `cityId` (required, long)
- `venueId` (optional, long)
- `q` (optional, string)
- `state` (optional, enum: `PUBLISHED`, `DRAFT`, `CANCELLED`)
- `startAfter` (optional, ISO datetime)
- `startBefore` (optional, ISO datetime)
- `page` (optional, default 0)
- `size` (optional, default 20, max 100)

**Request Body:** none

**Success Response (200):**  
`EventCatalogSearchResponse`
- `events`: array of `EventCatalogItemResponse`
- `stale`: boolean
- `snapshotTimestamp`: ISO datetime or null
- `source`: enum (`CACHE`, `DB`)
- `pagination`: `PaginationInfo`

**Error Responses:**
- 400 Bad Request → `InvalidCatalogSearchException`
- 404 Not Found → `CatalogNotFoundException`
- 500 Internal Server Error → `CatalogSyncException`

### 4) `POST /admin/v1/webhooks/eventbrite`
**Purpose:** Eventbrite webhook receiver.  
**Handled by:** `shared/eventbrite/webhook/EbWebhookController` → `EbWebhookDispatcher`.  
**Auth:** Signature validation only (no JWT).

**Headers:**
- `X-Eventbrite-Signature` (required)

**Request Body:** raw Eventbrite JSON payload (delivery schema not defined in Eventbrite v3 public .apib; treat as opaque JSON; attempt to read `event_id` if present).

**HTTP Response Policy:**
- `401 Unauthorized` → invalid signature only (synchronous; returned before any ack to Eventbrite)
- `200 OK` → all other cases (valid signature = immediate delivery ack)
  ```json
  { "status": "OK" }
  ```
> Reason: Eventbrite marks webhook delivery as failed on any non-200 and retries. Only signature
> validation is synchronous. All business-logic errors (missing event_id, city_id lookup failure,
> DB write failure, cache failure) are handled asynchronously in `EventbriteWebhookEventListener`
> and never surface as HTTP errors to Eventbrite.

**Post-ack errors (async — logged only, never returned as HTTP):**
- Missing `event_id` in payload → `MissingWebhookPayloadException` logged at WARN; no state mutation
- `city_id` lookup failure → `MissingWebhookPayloadException` logged at WARN; no state mutation
- DB write failure → `CatalogSyncException` logged at ERROR; `consecutiveFailures` incremented
- Cache write failure → logged at WARN; DB remains source of truth; no `consecutiveFailures` increment

---

## Stage 6 — Business Logic

### Org Context Resolution

Two resolution strategies depending on auth context:

- **Public (unauthenticated) reads** — `/api/v1/catalog/*`
  - `org_id` resolved from `app.default-org-id` Spring config property.
  - Per `PRODUCT.md` single-org assumption for Phase 1. No JWT required or expected.

- **Authenticated reads** — future admin/user-scoped endpoints
  - `org_id` extracted from JWT claim.
  - Never imported via `@Service` from `identity` module (Hard Rule #1).

> Note: The Resolved Decisions statement "org_id is carried as a JWT claim" applies to inter-module
> communication in authenticated contexts only, not to public catalog reads.

### Catalog Read Path (Cities, Venues, Events)
1. Resolve `org_id` (default from config).
2. For `/events`:
   - Check Redis snapshot cache by key `(org_id, city_id)`.
   - If cache hit and TTL valid → return cached events with `stale=false` and `source=CACHE`.
   - If cache miss/expired → query DB, return results with `source=DB`.
3. On **every read**: check `webhook_config.last_webhook_at`:
   - If `last_webhook_at > 1 hour ago` → trigger async refresh (non-blocking).
   - Response returns immediately; set `stale=true` when refresh was triggered.

### Webhook Handling

**Webhook registration (org creation):**
- `EbWebhookService.registerWebhook(orgId, endpointUrl, actions, eventId?)` uses Eventbrite Create Webhook schema.
- `endpoint_url` = inbound webhook URL; `actions` per .apib; `event_id` null for org-wide.

**HTTP layer** (`shared/eventbrite/webhook/EbWebhookDispatcher`):
1. Validate signature header using `EventbriteWebhookSignatureValidator`.
2. If invalid → throw `EbWebhookSignatureException` → `EbWebhookController` returns `401`. No state mutation.
3. If valid → publish `EbWebhookReceivedEvent(orgId, payload)` and return `200 OK` immediately to Eventbrite.

**Business layer** (`discovery-catalog/event/listener/EventbriteWebhookEventListener`) — async, post-ack:
1. Update `webhook_config.last_webhook_at` and reset cooldown status.
2. Determine `city_id` by looking up `event_catalog` via `eventbrite_event_id` from payload.
3. Invalidate cache for `(org_id, city_id)` **asynchronously + batched**.
4. Publish `EventCatalogUpdatedEvent` (payload: `orgId`, `cityId`, `venueId`, `updatedAt`).
5. On any error: log + increment `consecutiveFailures` as appropriate (see Stage 7). Never return HTTP error to Eventbrite.

### Async Refresh Flow
1. Acquire single-flight lock `(org_id, city_id, venue_id)`.
2. Call Eventbrite list API:
   - If `venue_id` present → `GET /venues/{venue_id}/events/`
   - Else → `GET /organizations/{organization_id}/events/`
3. For each Eventbrite event:
   - **Delta decision uses `eventbrite_changed_at`.**
   - If changed/new → upsert into `event_catalog`.
4. Soft-delete missing events:
   - **Org-wide scheduled sync:** soft-delete missing across org scope.
   - **Venue-scoped refresh:** soft-delete missing only for that venue scope.
5. Refresh snapshot cache `(org_id, city_id)` after successful upsert.

### TTL Fallback Scheduler (Hybrid)
- Every 4 hours:
  - Scan `webhook_config` for all orgs.
  - If `last_webhook_at > 1 hour ago` and not in cooldown → trigger async refresh.
- Skip orgs where `cooldown_until > now`.

### Cooldown Logic
- **consecutiveFailures threshold = 5.**
- After **5 consecutive failures**, enter cooldown for 3 hours.
- Cooldown exits immediately on any successful webhook delivery or refresh.

### Metrics & Logging
- Cache hit/miss ratio tracked per org via Micrometer.
- Track refresh triggers: read-path vs scheduled job.
- Log refresh triggers and webhook processing outcomes (info level).

---

## Stage 7 — Error Handling

### 1) Eventbrite API Timeout / 5xx During Async Refresh
- Increment `consecutive_failures`.
- If `consecutive_failures` reaches **5**, enter cooldown for 3 hours.
- Log error with `org_id`, `city_id`, `venue_id`, and exception details.
- **Do not** update snapshot cache; serve stale data until refresh succeeds.

### 2) Eventbrite API 401/403 During Async Refresh (Auth Failure)
- Log error: `"Eventbrite auth failure: org={} — check API key config"` at **ERROR** level.
- Enter cooldown **immediately** (skip `consecutive_failures` counter).
- **Do not** increment `consecutive_failures` (not a transient failure).
- Emit a distinct **auth failure metric** to separate from transient failures.

### 3) Webhook Signature Validation Failure
- Return **401 Unauthorized**.
- Log warning including source IP.
- **Do not** update `webhook_config` (ignore the payload).

### 4) Webhook Payload Missing `event_id` or City Lookup Fails
- No HTTP error returned (policy: 200 OK for all valid signatures).
- Log warning: `"Webhook missing event_id or event not found in catalog: payload={}"`.
- **Do not** update `webhook_config.last_webhook_at`.
- **Do not** trigger cache invalidation.

### 5) Single-Flight Lock Acquisition Timeout
- Log warning: `"Lock timeout for org={} city={} venue={}"`.
- Return existing snapshot immediately.
- **Do not** retry immediately.

### 6) DB Write Failure During Upsert
- Log error.
- Increment `consecutive_failures`.
- Ensure lock release in a `finally` block.

### 7) Cache Write Failure After Successful Refresh
- Log warning: `"Cache write failed: org={} city={}"`.
- **Do not** roll back DB upsert (DB remains source of truth).
- **Do not** increment `consecutive_failures`.
- Next read serves directly from DB (graceful degradation).

### 8) TTL Fallback Scheduler Throws Unexpected Exception
- Catch `Throwable` at scheduler level.
- Log error: `"Scheduler run failed at {timestamp}: {exception}"`.
- **Do not** rethrow (prevents scheduler thread from dying).
- Increment `scheduler.error` Micrometer counter.

---


### 9) Webhook Registration/List/Delete Errors (Outbound via EbWebhookService)
- `400` from Eventbrite → `EbIntegrationException` (invalid params).
- `403` from Eventbrite → `EbAuthException` (not authorized / legacy endpoint).


## Stage 8 — Tests

### Domain Layer (Unit)
- **WebhookConfig increments consecutiveFailures on transient failure**
  - Given a transient failure (5xx/timeout), `consecutiveFailures` increments by 1.
  - Verify it does **not** enter cooldown before threshold 5.
- **WebhookConfig cooldown expiry check**
  - `isInCooldown()` returns **true** when `cooldown_until > now`.
  - `isInCooldown()` returns **false** when `cooldown_until < now`.
- **WebhookConfig enters cooldown at threshold**
  - After 5 consecutive failures, cooldown is set to `now + 3h`.
- **WebhookConfig exits cooldown on success**
  - Successful webhook resets cooldown and `consecutiveFailures`.
- **EventCatalogItem soft-delete behavior**
  - Soft delete sets `deleted_at` and excludes from active queries.

### Service Layer (Mockito)
- **Cache hit returns snapshot**
  - Cache hit returns data from Redis and does not query DB.
- **Cache miss returns DB**
  - Cache miss/expired queries DB and returns `source=DB`.
- **Read-path triggers async refresh when stale**
  - If `last_webhook_at > 1h`, async refresh is triggered and response is `stale=true`.
- **Scheduler skips orgs in cooldown**
  - TTL fallback scheduler does not trigger refresh if `cooldown_until > now`.
- **Scheduler triggers refresh for stale orgs**
  - TTL fallback scheduler triggers async refresh if `last_webhook_at > 1h` and not in cooldown.
- **Async refresh uses correct Eventbrite API**
  - Uses venue-specific endpoint when `venue_id` present; org-wide otherwise.
- **Upsert uses `eventbrite_changed_at`**
  - Only updates when Eventbrite `changed` is newer.
- **Soft-delete scope rules**
  - Org-wide sync: missing events soft-deleted across org.
  - Venue-scoped refresh: missing events soft-deleted only in that venue scope.
- **Auth failure handling (401/403)**
  - Enters cooldown immediately; does not increment `consecutiveFailures`.
- **Cache write failure does not count as failure**
  - DB upsert remains; no increment on `consecutiveFailures`.
- **Lock timeout returns snapshot**
  - Lock acquisition timeout returns existing snapshot without retry.
- **Webhook payload missing `event_id` (no mutation)**
  - Service does not update `webhook_config`.
  - No cache invalidation.
  - No `EventCatalogUpdatedEvent` published.
- **Signature invalid (service)**
  - `WebhookService.process()` throws `EbWebhookSignatureException`.
  - No DB or cache mutation.
- **OrgCreatedEventListener triggers webhook registration**
  - `OrgCreatedEventListener` calls `EbWebhookService.registerWebhook(orgId)` when `OrgCreatedEvent` received.
  - Verify listener does NOT fire before transaction commits (`AFTER_COMMIT` phase).

### API Layer (@WebMvcTest)
- **GET /api/v1/catalog/cities**
  - Returns 200 with city list.
- **GET /api/v1/catalog/venues**
  - Missing `cityId` → 400.
  - Valid request returns 200 with pagination.
- **GET /api/v1/catalog/events**
  - Invalid params → 400.
  - Cache hit + fresh webhook (`last_webhook_at < 1h`) → `stale=false`.
- **POST /admin/v1/webhooks/eventbrite**
  - Invalid signature → 401, body contains error code `INVALID_SIGNATURE`.
  - Missing `event_id` with valid signature → 200 OK (async handled).
- **Scheduler error handling**
  - Scheduler catches `Throwable`; subsequent scheduled runs still execute.

### ArchUnit
- **Hard Rule #1 — No cross-module service/entity/repo imports**
  - discovery-catalog must not import any `@Service`, `@Entity`, or `@Repository` from other modules.
- **Hard Rule #2 — Eventbrite calls only via shared ACL**
  - No Eventbrite HTTP client in discovery-catalog; only `shared/eventbrite/service/`.
- **Hard Rule #5 — No @ControllerAdvice in module**
  - discovery-catalog contains no `@ControllerAdvice`.
- **Hard Rule #6 — No mapping in service or controller**
  - Services/controllers in discovery-catalog do not map fields directly; mapping only in MapStruct mappers.
- **Hard Rule #10 — Dependency direction**
  - discovery-catalog does not depend on downstream modules.

---

## Resolved Decisions
- **Organization module ownership:** `identity` owns `organization` domain and org creation.
- **`org_id` resolution:**
  - Public endpoints: resolved from `app.default-org-id` Spring config property.
  - Authenticated endpoints: extracted from JWT claim.
  - Cross-module: passed as primitive in Spring Events or REST headers. Never imported via `@Service` (Hard Rule #1).
- **`updated_at` strategy:** `@EnableJpaAuditing` in `shared/config/JpaAuditingConfig.java`. All entities use `@EntityListeners(AuditingEntityListener.class)` with `@CreatedDate` + `@LastModifiedDate`. No DB triggers required.
- **Webhook registration trigger:** On org creation, `identity` publishes `OrgCreatedEvent(orgId, adminUserId, createdAt)` after DB commit. `discovery-catalog`'s `OrgCreatedEventListener` handles this via `@TransactionalEventListener(phase = AFTER_COMMIT)` and calls `EbWebhookService.registerWebhook(orgId)`. `identity` never calls `EbWebhookService` directly (Hard Rule #1).
- **Webhook ingress:** ALL inbound Eventbrite webhooks are received by `shared/eventbrite/webhook/EbWebhookController`. Only `401` is returned synchronously (invalid signature). All other responses are `200 OK`. Business logic runs async in `discovery-catalog/event/listener/EventbriteWebhookEventListener`. No module controller owns a raw webhook HTTP endpoint.
- **Migration location:** All Flyway migrations are in `app/src/main/resources/db/migration/`. Module ownership is documented as a comment in each file, not by file location.
