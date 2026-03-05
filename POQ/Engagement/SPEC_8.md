# SPEC_8.md — FR8: Review & Rating Submission → Attendance Verification → Moderation Queue → Public Publish

**Owner:** engagement  
**Module(s):** `engagement` (primary), `shared/openai/` (moderation ACL), `shared/eventbrite/` (`EbAttendeeService` — attendance verification only), `payments-ticketing` (REST read — booking status), `identity` (JWT auth)  
**Flow:** Flow 8 — BookingConfirmedEvent → Review Eligibility Unlock → Attendance Verify → Review Submit → Auto-Moderation → Manual Override → Public Publish  
**Last Updated:** March 5, 2026  
**Status:** Stage 1 Complete — Stages 2–8 Pending

**Depends on:**
- `SPEC_5.md` (FR5) — `BookingConfirmedEvent(bookingId, cartId, seatIds, stripePaymentIntentId, userId)` published after Stripe payment confirmed; `BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)` published on cancellation
- `SPEC_3.MD` (FR3) — JWT with `sub=userId`, `role`, `orgId` claims required for all protected endpoints

**Key Architecture Decision:** There is no reviews API on Eventbrite. The entire review and moderation system is 100% internal. `EbAttendeeService` is used only as a secondary cross-check to confirm the user attended the event before review submission. The internal booking record is the primary source of eligibility truth — Eventbrite is a non-blocking advisory check.

---

## Stage 1 — Requirements

---

### Area A — Review Eligibility Gate

- `[MUST]` `engagement` must listen to `BookingConfirmedEvent` via `@TransactionalEventListener(phase = AFTER_COMMIT)` and create a `ReviewEligibility` record for the user + event pair — status `UNLOCKED`.
- `[MUST]` `engagement` must listen to `BookingCancelledEvent` and transition the matching `ReviewEligibility` record to `REVOKED` if the booking is fully cancelled (all items cancelled).
- `[MUST]` `ReviewEligibility` must be keyed on `(userId, eventId)` — unique constraint; a second `BookingConfirmedEvent` for the same `(userId, eventId)` must be a no-op (idempotent upsert).
- `[MUST]` Only `ReviewEligibility.status = UNLOCKED` records permit review submission. Any other status (`REVOKED`, `EXPIRED`) must return `403 REVIEW_NOT_ELIGIBLE`.
- `[SHOULD]` `ReviewEligibility` must store `bookingId` and `slotId` for audit linkage (sourced from event payload).
- `[SHOULD]` Eligibility may optionally expire after a configurable window (e.g., 30 days after event date). Expired eligibility returns `403 REVIEW_WINDOW_CLOSED`. This is configurable via `engagement.review.eligibility-window-days`.

---

### Area B — Attendance Verification (Eventbrite Cross-Check)

- `[MUST]` Before creating a review, call `payments-ticketing` REST endpoint `GET /api/v1/internal/bookings/by-user-event?userId={}&eventId={}` to confirm a `CONFIRMED` and non-cancelled booking exists.
- `[MUST]` If internal booking check fails (no CONFIRMED booking), return `403 REVIEW_NOT_ELIGIBLE` — do not proceed to Eventbrite.
- `[MUST]` If `ebEventId` is present on the slot, call `EbAttendeeService.getAttendeesByEvent(orgToken, ebEventId)` and filter by user's email to locate the attendee record.
- `[MUST]` If the Eventbrite attendee record exists: verify `cancelled = false` and `refunded = false`. If both pass, mark verification as `EB_VERIFIED`.
- `[MUST]` If Eventbrite attendee record is NOT found but internal booking is `CONFIRMED`: proceed with `ATTENDANCE_SELF_REPORTED` flag — submission is allowed. Log a `WARN` for reconciliation.
- `[MUST]` If Eventbrite API call fails (timeout / 5xx): do not block submission. Proceed with `ATTENDANCE_EB_UNAVAILABLE` flag. Log the failure. Never propagate EB errors to the user on this path.
- `[MUST]` If `ebEventId` is null on the slot: skip Eventbrite check entirely; proceed with `ATTENDANCE_SELF_REPORTED`.
- `[SHOULD]` Store the resolved `attendanceVerificationStatus` (`EB_VERIFIED` / `ATTENDANCE_SELF_REPORTED` / `ATTENDANCE_EB_UNAVAILABLE`) on the `Review` record for audit.
- `[WONT]` Eventbrite attendee record is never the sole gating factor. Internal booking is primary authority.

---

### Area C — Review Submission

- `[MUST]` Expose `POST /api/v1/engagement/reviews` (JWT `ROLE_USER`).
- `[MUST]` Request payload: `eventId` (Long), `rating` (Integer, 1–5 inclusive), `title` (String, max 100 chars), `body` (String, max 2000 chars).
- `[MUST]` Validate eligibility (Area A) and attendance (Area B) before persisting.
- `[MUST]` On success: persist `Review` with `status = SUBMITTED`; immediately transition to `PENDING_MODERATION` within the same transaction.
- `[MUST]` A second `POST` from the same user for the same `eventId` (after an already-submitted review exists) must return `409 REVIEW_ALREADY_SUBMITTED`.
- `[MUST]` A user whose review is `REJECTED` may NOT resubmit for the same `eventId`. Return `409 REVIEW_ALREADY_SUBMITTED` (terminal rejection).
- `[MUST]` Rating must be an integer between 1 and 5; any other value returns `400 INVALID_RATING`.
- `[SHOULD]` Trigger auto-moderation asynchronously after the HTTP response is returned (non-blocking UX). Use a `@Async` service or Spring Event.
- `[SHOULD]` Return the created `Review`'s `id`, `status`, and estimated moderation timeline in the response body.

---

### Area D — Auto-Moderation (OpenAI Moderation API)

- `[MUST]` After review submission, call OpenAI Moderation API via `shared/openai/OpenAiModerationService` — send concatenated `title + " " + body` as input text.
- `[MUST]` Persist a `ModerationRecord` for every moderation attempt with: `reviewId`, `method` (`AUTO`), `inputText`, `decision` (`APPROVED` / `REJECTED`), `flags` (comma-separated category names that triggered), `scores` (JSON map of category → score), `decidedAt`.
- `[MUST]` If OpenAI returns `flagged = false`: transition `Review.status` → `APPROVED`, then immediately → `PUBLISHED`. Publish `ReviewPublishedEvent(reviewId, eventId, rating)`.
- `[MUST]` If OpenAI returns `flagged = true`: transition `Review.status` → `REJECTED`. Persist `rejectionReason` from top flagged category. Review is terminal — no retry, no resubmit.
- `[MUST]` If OpenAI Moderation API call fails (timeout / 5xx / rate limit): review stays `PENDING_MODERATION`. Schedule a retry via an exponential backoff job (max 3 retries, intervals: 30s, 2min, 10min). After exhausting retries, leave `PENDING_MODERATION` for manual review in admin queue.
- `[MUST]` Auto-moderation must never throw an exception that rolls back the `Review` record. It is a fire-and-update flow — the review is already persisted as `PENDING_MODERATION`.
- `[SHOULD]` Expose a Micrometer counter `engagement.review.moderation.auto.approved` and `engagement.review.moderation.auto.rejected` for observability.
- `[WONT]` No OpenAI moderation re-check on already-`REJECTED` reviews.

---

### Area E — Manual Moderation Override (Admin)

- `[MUST]` Expose `PUT /api/v1/admin/engagement/reviews/{reviewId}/moderate` (JWT `ROLE_ADMIN`).
- `[MUST]` Request body: `decision` (`APPROVE` / `REJECT`), `reason` (String, required for `REJECT`, optional for `APPROVE`).
- `[MUST]` On `APPROVE`: transition `Review.status → PUBLISHED`. Publish `ReviewPublishedEvent(reviewId, eventId, rating)`.
- `[MUST]` On `REJECT`: transition `Review.status → REJECTED`. Persist `rejectionReason`. Terminal — no resubmit.
- `[MUST]` Create a new `ModerationRecord` with `method = MANUAL`, `moderatorId = admin's userId`, `decision`, `reason`, `decidedAt`.
- `[MUST]` Admin can only moderate reviews in `PENDING_MODERATION` state. Attempts on `PUBLISHED` or `REJECTED` reviews return `409 REVIEW_ALREADY_MODERATED`.
- `[MUST]` Expose `GET /api/v1/admin/engagement/reviews?status=PENDING_MODERATION` — paginated list of reviews pending manual moderation (JWT `ROLE_ADMIN`). Supports filter by `eventId`, `submittedAfter`.
- `[SHOULD]` Include in admin list response: `reviewId`, `eventId`, `userId`, `rating`, `title`, `body`, `submittedAt`, `autoModerationAttempts`, `lastAutoDecision`.

---

### Area F — Review Read APIs (Public & Authenticated)

- `[MUST]` Expose `GET /api/v1/engagement/reviews/events/{eventId}` — paginated list of `PUBLISHED` reviews for an event. No authentication required. Default sort: `submittedAt DESC`. Page size: 20.
- `[MUST]` Review response must include: `reviewId`, `rating`, `title`, `body`, `publishedAt`, `reviewerDisplayName` (from identity — fetched at read time via REST to `identity`).
- `[MUST]` Expose `GET /api/v1/engagement/reviews/events/{eventId}/summary` — aggregate stats: `averageRating` (1 decimal), `totalReviews`, `distribution` (map of 1–5 → count). No authentication required. Result cached in Redis with TTL = 5 minutes; invalidated on `ReviewPublishedEvent`.
- `[MUST]` Expose `GET /api/v1/engagement/reviews/me` — current user's reviews (all statuses). JWT `ROLE_USER` required. Sorted by `submittedAt DESC`.
- `[SHOULD]` `reviewerDisplayName` lookup to `identity` must use a short timeout (500ms). On timeout or error, return `"Anonymous"` — never fail the request.
- `[WONT]` Review editing after submission.
- `[WONT]` Organizer replies to reviews in FR8.

---

### Area G — Non-Functional & Architecture Rules

- `[MUST]` `engagement` module must never import `@Service`, `@Repository`, or `@Entity` from `payments-ticketing`, `identity`, or any other module. All cross-module reads go via REST.
- `[MUST]` Spring Events consumed by `engagement` (`BookingConfirmedEvent`, `BookingCancelledEvent`, `EventCatalogUpdatedEvent`) must be handled in `event/listener/` package with `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- `[MUST]` All Eventbrite calls go through `EbAttendeeService` in `shared/eventbrite/service/` — never direct HTTP from `engagement`.
- `[MUST]` All OpenAI calls go through `OpenAiModerationService` in `shared/openai/` — never direct SDK calls from `engagement`.
- `[MUST]` One `GlobalExceptionHandler` in `shared/` handles all module exceptions — `engagement` must not define `@ControllerAdvice`.
- `[MUST]` All field mapping (Review entity ↔ DTOs) done in `mapper/` using MapStruct — no manual mapping in service or controller.
- `[SHOULD]` Auto-moderation retry state must survive app restarts — persisted retry count and `retryAfter` timestamp on `ModerationRecord`.

---

### Out of Scope — FR8

- `[WONT]` AI Chatbot (FR11) — separate functional flow within `engagement`; separate spec section.
- `[WONT]` RAG index pipeline management beyond receiving `EventCatalogUpdatedEvent`.
- `[WONT]` Review editing or deletion by user after submission.
- `[WONT]` Organizer/admin reply to reviews.
- `[WONT]` Notification emails on review publish/reject.
- `[WONT]` Review helpfulness voting ("Was this helpful?").
- `[WONT]` Wallet credit or coupon rewards for verified reviews.

---

## Stage 2 — Domain Modeling

### Domain Summary

A `ReviewEligibility` record is the gatekeeper — created when a `BookingConfirmedEvent` arrives, revoked on cancellation. It is the pre-condition for any review submission. A `Review` is the core aggregate root, carrying the user's rating and textual content through a moderation lifecycle from `SUBMITTED` to `PUBLISHED` (or terminal `REJECTED`). A `ModerationRecord` is the immutable audit log of every moderation attempt (auto or manual), including retry state for failed OpenAI API calls. Multiple `ModerationRecord` rows can exist per `Review` (one per attempt). The rating summary for an event is a computed read model derived from published reviews and cached in Redis — it is never persisted as an entity.

---

### Entities

#### ReviewEligibility
- **Identity:** `id BIGSERIAL PK`
- **Business key:** `(user_id, event_id)` — unique constraint
- **Lifecycle:** `UNLOCKED` → `REVOKED` | `EXPIRED`
- **Relationships:** standalone; not linked to `Review` via FK (decoupled — eligibility can exist before any review is submitted)
- **Ownership:** `engagement` module

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `user_id` | `BIGINT` | FK reference (no cross-module FK — value copy from event) |
| `event_id` | `BIGINT` | Internal event ID (from `discovery-catalog`; no FK — cross-module) |
| `slot_id` | `BIGINT` | Nullable; sourced from `BookingConfirmedEvent` for audit |
| `booking_id` | `BIGINT` | Sourced from `BookingConfirmedEvent`; non-FK copy |
| `status` | `VARCHAR(30)` | `UNLOCKED`, `REVOKED`, `EXPIRED` |
| `eligible_until` | `TIMESTAMP` | Nullable; set if expiry window is configured |
| `created_at` | `TIMESTAMP` | Auto-managed by `BaseEntity` |
| `updated_at` | `TIMESTAMP` | Auto-managed by `BaseEntity` |

---

#### Review
- **Identity:** `id BIGSERIAL PK`
- **Business key:** `(user_id, event_id)` — unique constraint (one review per attended event)
- **Lifecycle:** `SUBMITTED → PENDING_MODERATION → APPROVED → PUBLISHED` / `REJECTED` (terminal)
- **Relationships:** one `Review` has many `ModerationRecord` rows
- **Ownership:** `engagement` module

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `user_id` | `BIGINT` | Non-FK value copy; source of truth is `identity` module |
| `event_id` | `BIGINT` | Non-FK value copy; source of truth is `discovery-catalog` |
| `rating` | `SMALLINT` | 1–5 inclusive; `CHECK (rating >= 1 AND rating <= 5)` |
| `title` | `VARCHAR(100)` | Not null |
| `body` | `VARCHAR(2000)` | Not null |
| `status` | `VARCHAR(30)` | `SUBMITTED`, `PENDING_MODERATION`, `APPROVED`, `PUBLISHED`, `REJECTED` |
| `attendance_verification_status` | `VARCHAR(40)` | `EB_VERIFIED`, `ATTENDANCE_SELF_REPORTED`, `ATTENDANCE_EB_UNAVAILABLE` |
| `rejection_reason` | `VARCHAR(255)` | Nullable; populated on `REJECTED` |
| `published_at` | `TIMESTAMP` | Nullable; set when transitioning to `PUBLISHED` |
| `submitted_at` | `TIMESTAMP` | Set on initial creation; not changed after |
| `created_at` | `TIMESTAMP` | Auto |
| `updated_at` | `TIMESTAMP` | Auto |

---

#### ModerationRecord
- **Identity:** `id BIGSERIAL PK`
- **Lifecycle:** immutable once `decided_at` is set; new row per attempt
- **Relationships:** many `ModerationRecord` rows belong to one `Review` (via `review_id FK`)
- **Ownership:** `engagement` module

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `review_id` | `BIGINT` | FK → `reviews(id)` ON DELETE CASCADE |
| `method` | `VARCHAR(20)` | `AUTO`, `MANUAL` |
| `input_text` | `TEXT` | Concatenated `title + body` sent to OpenAI moderation |
| `decision` | `VARCHAR(20)` | `APPROVED`, `REJECTED`, `PENDING` (in-flight / retrying) |
| `flags` | `VARCHAR(500)` | Nullable; comma-separated OpenAI category names triggering flagged=true |
| `scores_json` | `TEXT` | Nullable; raw JSON map of category → score from OpenAI response |
| `moderator_id` | `BIGINT` | Nullable; set only when `method = MANUAL` |
| `reason` | `VARCHAR(500)` | Nullable; mandatory for `MANUAL` REJECT; optional for MANUAL APPROVE |
| `auto_retry_count` | `INT` | Defaults to 0; incremented on each API failure retry |
| `retry_after` | `TIMESTAMP` | Nullable; next scheduled retry time (persisted for restart safety) |
| `decided_at` | `TIMESTAMP` | Nullable; set when final decision reached (non-null → terminal) |
| `created_at` | `TIMESTAMP` | Auto |
| `updated_at` | `TIMESTAMP` | Auto |

---

### Enumerations

| Enum | Values |
|---|---|
| `ReviewEligibilityStatus` | `UNLOCKED`, `REVOKED`, `EXPIRED` |
| `ReviewStatus` | `SUBMITTED`, `PENDING_MODERATION`, `APPROVED`, `PUBLISHED`, `REJECTED` |
| `AttendanceVerificationStatus` | `EB_VERIFIED`, `ATTENDANCE_SELF_REPORTED`, `ATTENDANCE_EB_UNAVAILABLE` |
| `ModerationMethod` | `AUTO`, `MANUAL` |
| `ModerationDecision` | `APPROVED`, `REJECTED`, `PENDING` |

---

### Value Objects / Read Models (Not Persisted)

**ReviewRatingSummary** — computed from `reviews` table, cached in Redis (TTL 5 min, key: `engagement:review:summary:{eventId}`)
- `eventId`: Long
- `averageRating`: BigDecimal (1 decimal place)
- `totalReviews`: int
- `distribution`: Map&lt;Integer, Integer&gt; — key = rating (1–5), value = count

---

### Review Status Lifecycle Diagram

```
                          BookingConfirmedEvent
                                  │
                          ┌───────▼────────┐
                          │ ReviewEligibility│
                          │  UNLOCKED       │◄── BookingCancelledEvent → REVOKED
                          └───────┬────────┘
                                  │  (eligibility check passes)
                          POST /api/v1/engagement/reviews
                                  │
                          ┌───────▼────────┐
                          │    Review       │
                          │  SUBMITTED      │
                          └───────┬────────┘
                                  │  (immediate in same transaction)
                          ┌───────▼────────┐
                          │ PENDING_MODERATION
                          └───────┬────────┘
                    ┌─────────────┴──────────────┐
              [AUTO-MOD]                    [MANUAL-MOD]
           OpenAI API call                Admin PUT /moderate
                    │                               │
          ┌─────────┴────────┐         ┌───────────┴──────────┐
          │                  │         │                       │
      flagged=false      flagged=true  APPROVE               REJECT
          │                  │         │                       │
      APPROVED           REJECTED  PUBLISHED             REJECTED (terminal)
          │              (terminal)    │
      PUBLISHED ←─────────────────────┘
```

---

### Cross-Module Data Dependencies (Read-Only, No Entity Imports)

| Data Needed | Source Module | Access Mechanism |
|---|---|---|
| Booking status confirmation | `payments-ticketing` | REST `GET /api/v1/internal/bookings/by-user-event` |
| User display name for review responses | `identity` | REST `GET /api/v1/internal/users/{userId}/display-name` |
| `ebEventId` for attendee verification | `discovery-catalog` (via `event_catalog.eventbrite_event_id`) | REST `GET /api/v1/catalog/events/{eventId}/eb-metadata` |
| Org token for EB ACL call | `shared/eventbrite/EbTokenStore` | In-process (shared module) |

> **Hard Rule:** `engagement` never imports a JPA `@Entity` or `@Repository` from any other module. All cross-module data comes via REST or Spring Events.

---

## Stage 3 — Architecture & File Structure

### Architecture Pattern
**Modular Monolith (fixed).** `engagement` is a leaf module — it depends on `shared/`, `payments-ticketing`, and `identity` (reads via REST). No module below it in the dependency order; no shared reader interface needs to be published FROM `engagement`.

### Module Dependency Position
```
shared/ → ... → payments-ticketing → promotions → engagement → admin/ → app/
```
`engagement` sits at the top of the user-facing stack. It depends on `payments-ticketing` (booking verification) and `identity` (user display name) via REST only. It never provides in-process interfaces to admin or app beyond its REST API.

---

### `engagement/` File Layout

```
engagement/
└── src/main/java/com/eventplatform/engagement/
    ├── api/
    │   ├── controller/
    │   │   ├── ReviewController.java
    │   │   │     POST   /api/v1/engagement/reviews
    │   │   │     GET    /api/v1/engagement/reviews/me
    │   │   │     GET    /api/v1/engagement/reviews/events/{eventId}
    │   │   │     GET    /api/v1/engagement/reviews/events/{eventId}/summary
    │   │   └── AdminModerationController.java
    │   │         GET    /api/v1/admin/engagement/reviews               (ROLE_ADMIN)
    │   │         PUT    /api/v1/admin/engagement/reviews/{id}/moderate (ROLE_ADMIN)
    │   └── dto/
    │       ├── request/
    │       │   ├── ReviewSubmitRequest.java       (eventId, rating, title, body)
    │       │   └── ModerationDecisionRequest.java (decision, reason)
    │       └── response/
    │           ├── ReviewResponse.java            (id, eventId, rating, title, body, status, publishedAt, reviewerDisplayName)
    │           ├── ReviewSummaryResponse.java     (averageRating, totalReviews, distribution)
    │           └── AdminReviewResponse.java       (id, eventId, userId, rating, title, body, status, submittedAt, autoModerationAttempts, lastAutoDecision)
    ├── domain/
    │   ├── Review.java                            (@Entity — extends BaseEntity)
    │   ├── ReviewEligibility.java                 (@Entity — extends BaseEntity)
    │   ├── ModerationRecord.java                  (@Entity — extends BaseEntity)
    │   └── enums/
    │       ├── ReviewStatus.java
    │       ├── ReviewEligibilityStatus.java
    │       ├── AttendanceVerificationStatus.java
    │       ├── ModerationMethod.java
    │       └── ModerationDecision.java
    ├── service/
    │   ├── ReviewService.java                     (submission orchestration, eligibility guard, publish transition)
    │   ├── AttendanceVerificationService.java     (internal booking REST check + EbAttendeeService cross-check)
    │   ├── ModerationService.java                 (auto-mod dispatch, retry scheduler, manual override)
    │   └── ReviewRatingSummaryService.java        (compute aggregate + Redis cache-aside)
    ├── repository/
    │   ├── ReviewRepository.java                  (JpaRepository<Review, Long>)
    │   ├── ReviewEligibilityRepository.java       (JpaRepository<ReviewEligibility, Long>)
    │   └── ModerationRecordRepository.java        (JpaRepository<ModerationRecord, Long>)
    ├── mapper/
    │   └── ReviewMapper.java                      (MapStruct — Review ↔ ReviewResponse; ModerationRecord ↔ AdminReviewResponse)
    ├── event/
    │   ├── listener/
    │   │   ├── BookingConfirmedEventListener.java (@TransactionalEventListener AFTER_COMMIT → unlock eligibility)
    │   │   └── BookingCancelledEventListener.java (@TransactionalEventListener AFTER_COMMIT → revoke eligibility)
    │   └── published/
    │       └── ReviewPublishedEvent.java          (record: Long reviewId, Long eventId, int rating)
    └── exception/
        ├── ReviewNotEligibleException.java        (extends BusinessRuleException — 403)
        ├── ReviewAlreadySubmittedException.java   (extends BusinessRuleException — 409)
        └── ReviewAlreadyModeratedException.java   (extends BusinessRuleException — 409)
```

---

### `shared/` Additions Required

| File | Location | Purpose |
|---|---|---|
| `OpenAiModerationService.java` | `shared/openai/service/` | ACL wrapper for `POST https://api.openai.com/v1/moderations`; returns `ModerationResult(flagged, categories, categoryScores)` |
| `ReviewPublishedEvent.java` | `shared/common/event/published/` | Published by `engagement`; consumed by admin analytics in future |

> **Note:** `BookingConfirmedEvent` and `BookingCancelledEvent` already exist in `shared/common/event/published/` from FR5/FR6. No changes needed.

---

### Inter-Module REST Calls (Outbound from `engagement`)

| Target Module | Endpoint | Caller Service | Timeout | Failure Handling |
|---|---|---|---|---|
| `payments-ticketing` | `GET /api/v1/internal/bookings/by-user-event?userId={}&eventId={}` | `AttendanceVerificationService` | 2s | Block submission on timeout — booking check is mandatory |
| `identity` | `GET /api/v1/internal/users/{userId}/display-name` | `ReviewService` (read path) | 500ms | Return `"Anonymous"` on timeout — never fail the read request |
| `discovery-catalog` | `GET /api/v1/catalog/events/{eventId}/eb-metadata` | `AttendanceVerificationService` | 1s | Skip EB check if timeout → proceed with `ATTENDANCE_EB_UNAVAILABLE` |

---

### Spring Events Consumed

| Event (in `shared/`) | Phase | Handler | Action |
|---|---|---|---|
| `BookingConfirmedEvent` | `AFTER_COMMIT` | `BookingConfirmedEventListener` | Upsert `ReviewEligibility(UNLOCKED)` for `(userId, eventId)` |
| `BookingCancelledEvent` | `AFTER_COMMIT` | `BookingCancelledEventListener` | Transition `ReviewEligibility → REVOKED` for cancelled booking |

### Spring Events Published

| Event | Trigger | Consumers |
|---|---|---|
| `ReviewPublishedEvent(reviewId, eventId, rating)` | Review transitions to `PUBLISHED` | (prepared for extension — no active consumer in FR8) |

---

### Redis Usage

| Key Pattern | TTL | Purpose |
|---|---|---|
| `engagement:review:summary:{eventId}` | 5 minutes | Cached `ReviewRatingSummary` (avg rating, distribution) |

Invalidated on `ReviewPublishedEvent` by `ReviewRatingSummaryService`.

---

### External ACL Facades Used

| Facade | Location | Purpose in FR8 |
|---|---|---|
| `EbAttendeeService` | `shared/eventbrite/service/` | `getAttendeesByEvent(orgToken, ebEventId)` — cross-check attendance |
| `OpenAiModerationService` | `shared/openai/service/` | `moderate(text)` — auto-moderation of review content |

---

### Hard Rules Applied

| Rule | Application |
|---|---|
| No cross-module `@Service` / `@Repository` import | All cross-module reads via REST in `AttendanceVerificationService` and `ReviewService` |
| All external calls via `shared/` ACL only | `EbAttendeeService` and `OpenAiModerationService` — never direct HTTP |
| One `GlobalExceptionHandler` in `shared/` | `engagement` exceptions extend `BusinessRuleException` from `shared/`; no `@ControllerAdvice` in module |
| MapStruct in `mapper/` only | `ReviewMapper` handles all Review ↔ DTO conversions |
| `admin/` owns no domain | `AdminModerationController` lives in `engagement/api/controller/` — admin calls this endpoint, but does NOT import the controller class |
| Spring Events AFTER_COMMIT | Both event listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` |

---

## Stage 4 — DB Schema

> **Migration baseline:** Last applied = V37. FR8 uses V38–V40.  
> **Convention:** All FKs to other modules' tables are intentionally omitted — cross-module constraints are enforced at the service layer only.

---

### V38 — `create_review_eligibility_table.sql`

```sql
CREATE TABLE review_eligibility (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             BIGINT          NOT NULL,
    event_id            BIGINT          NOT NULL,
    slot_id             BIGINT,
    booking_id          BIGINT,
    status              VARCHAR(30)     NOT NULL DEFAULT 'UNLOCKED',
    eligible_until      TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_review_eligibility_user_event UNIQUE (user_id, event_id),
    CONSTRAINT chk_review_eligibility_status
        CHECK (status IN ('UNLOCKED', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX idx_review_eligibility_status  ON review_eligibility (status);
CREATE INDEX idx_review_eligibility_booking ON review_eligibility (booking_id);
CREATE INDEX idx_review_eligibility_user    ON review_eligibility (user_id);
```

---

### V39 — `create_reviews_table.sql`

```sql
CREATE TABLE reviews (
    id                              BIGSERIAL       PRIMARY KEY,
    user_id                         BIGINT          NOT NULL,
    event_id                        BIGINT          NOT NULL,
    rating                          SMALLINT        NOT NULL,
    title                           VARCHAR(100)    NOT NULL,
    body                            VARCHAR(2000)   NOT NULL,
    status                          VARCHAR(30)     NOT NULL DEFAULT 'SUBMITTED',
    attendance_verification_status  VARCHAR(40)     NOT NULL DEFAULT 'ATTENDANCE_SELF_REPORTED',
    rejection_reason                VARCHAR(255),
    published_at                    TIMESTAMP,
    submitted_at                    TIMESTAMP       NOT NULL DEFAULT NOW(),
    created_at                      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_reviews_user_event UNIQUE (user_id, event_id),
    CONSTRAINT chk_reviews_rating
        CHECK (rating >= 1 AND rating <= 5),
    CONSTRAINT chk_reviews_status
        CHECK (status IN ('SUBMITTED', 'PENDING_MODERATION', 'APPROVED', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT chk_reviews_attendance_status
        CHECK (attendance_verification_status IN (
            'EB_VERIFIED', 'ATTENDANCE_SELF_REPORTED', 'ATTENDANCE_EB_UNAVAILABLE'
        ))
);

-- Listing reviews for an event (public read path)
CREATE INDEX idx_reviews_event_status   ON reviews (event_id, status);
-- User's own reviews
CREATE INDEX idx_reviews_user_id        ON reviews (user_id);
-- Admin moderation queue
CREATE INDEX idx_reviews_status         ON reviews (status);
-- Rating aggregation
CREATE INDEX idx_reviews_event_rating   ON reviews (event_id, rating) WHERE status = 'PUBLISHED';
```

---

### V40 — `create_moderation_records_table.sql`

```sql
CREATE TABLE moderation_records (
    id                  BIGSERIAL       PRIMARY KEY,
    review_id           BIGINT          NOT NULL
                            REFERENCES reviews(id) ON DELETE CASCADE,
    method              VARCHAR(20)     NOT NULL,
    input_text          TEXT            NOT NULL,
    decision            VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    flags               VARCHAR(500),
    scores_json         TEXT,
    moderator_id        BIGINT,
    reason              VARCHAR(500),
    auto_retry_count    INT             NOT NULL DEFAULT 0,
    retry_after         TIMESTAMP,
    decided_at          TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_moderation_method
        CHECK (method IN ('AUTO', 'MANUAL')),
    CONSTRAINT chk_moderation_decision
        CHECK (decision IN ('APPROVED', 'REJECTED', 'PENDING'))
);

-- Join from review to its moderation records
CREATE INDEX idx_moderation_review_id       ON moderation_records (review_id);
-- Retry scheduler: find records needing retry
CREATE INDEX idx_moderation_retry_after     ON moderation_records (retry_after)
    WHERE decision = 'PENDING' AND retry_after IS NOT NULL;
-- Admin: filter by method + decision
CREATE INDEX idx_moderation_method_decision ON moderation_records (method, decision);
```

---

### Index Rationale Summary

| Index | Query served |
|---|---|
| `uq_review_eligibility_user_event` | Idempotent eligibility upsert; submission guard |
| `uq_reviews_user_event` | Duplicate review prevention |
| `idx_reviews_event_status` | `GET /reviews/events/{eventId}` — PUBLISHED filter |
| `idx_reviews_event_rating WHERE status='PUBLISHED'` | Rating summary aggregate |
| `idx_reviews_user_id` | `GET /reviews/me` |
| `idx_moderation_retry_after WHERE PENDING` | Retry scheduler job — partial index keeps scan small |
| `idx_moderation_review_id` | `JOIN reviews ↔ moderation_records` |

---

## Stage 5 — API Design

> **Base path:** `/api/v1`  
> **Auth:** JWT Bearer token via `JwtAuthenticationFilter`.  
> **Error format:** Global `ApiError { errorCode, message, timestamp, path }` from `shared/common/exception/GlobalExceptionHandler`.

---

### 1. Submit a Review

```
POST /api/v1/engagement/reviews
Authorization: Bearer <JWT>   (ROLE_USER)
Content-Type: application/json
```

**Request Body:**
```json
{
  "eventId": 1001,
  "rating": 4,
  "title": "Incredible performance",
  "body": "The band was phenomenal. Production quality was top-notch."
}
```

**Validations:**
- `eventId`: not null
- `rating`: not null, min 1, max 5
- `title`: not blank, max 100 chars
- `body`: not blank, max 2000 chars

**Response — 201 Created:**
```json
{
  "reviewId": 55,
  "status": "PENDING_MODERATION",
  "message": "Your review has been submitted and is under moderation."
}
```

**Error Responses:**

| HTTP | `errorCode` | Condition |
|---|---|---|
| 400 | `INVALID_RATING` | `rating` outside 1–5 |
| 400 | `VALIDATION_ERROR` | Blank title/body or missing fields |
| 403 | `REVIEW_NOT_ELIGIBLE` | No UNLOCKED eligibility for (userId, eventId) |
| 403 | `REVIEW_NOT_ELIGIBLE` | Booking not CONFIRMED (booking REST check fails) |
| 409 | `REVIEW_ALREADY_SUBMITTED` | Review already exists for (userId, eventId) — any status |

---

### 2. List Published Reviews for an Event

```
GET /api/v1/engagement/reviews/events/{eventId}?page=0&size=20&sort=publishedAt,desc
Authorization: (none — public)
```

**Path Params:** `eventId` (Long)  
**Query Params:** `page` (default 0), `size` (default 20, max 100), `sort` (default `publishedAt,desc`)

**Response — 200 OK:**
```json
{
  "content": [
    {
      "reviewId": 55,
      "eventId": 1001,
      "rating": 4,
      "title": "Incredible performance",
      "body": "The band was phenomenal...",
      "publishedAt": "2026-03-05T18:00:00Z",
      "reviewerDisplayName": "Jane D."
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 142,
  "totalPages": 8
}
```

> `reviewerDisplayName` is fetched at read time from `identity` REST call with 500ms timeout. Falls back to `"Anonymous"` on failure.

**Error Responses:**

| HTTP | `errorCode` | Condition |
|---|---|---|
| 404 | `EVENT_NOT_FOUND` | `eventId` does not exist in `event_catalog` |

---

### 3. Get Event Rating Summary

```
GET /api/v1/engagement/reviews/events/{eventId}/summary
Authorization: (none — public)
```

**Response — 200 OK:**
```json
{
  "eventId": 1001,
  "averageRating": 4.2,
  "totalReviews": 142,
  "distribution": {
    "1": 3,
    "2": 7,
    "3": 18,
    "4": 55,
    "5": 59
  },
  "cachedAt": "2026-03-05T18:04:00Z"
}
```

> Result served from Redis cache (key: `engagement:review:summary:{eventId}`, TTL 5 min). Computed fresh on cache miss.

---

### 4. Get Current User's Reviews

```
GET /api/v1/engagement/reviews/me?page=0&size=20
Authorization: Bearer <JWT>   (ROLE_USER)
```

**Response — 200 OK:**
```json
{
  "content": [
    {
      "reviewId": 55,
      "eventId": 1001,
      "rating": 4,
      "title": "Incredible performance",
      "body": "The band was phenomenal...",
      "status": "PUBLISHED",
      "rejectionReason": null,
      "submittedAt": "2026-03-05T15:30:00Z",
      "publishedAt": "2026-03-05T15:32:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1
}
```

---

### 5. List Reviews — Admin Moderation Queue

```
GET /api/v1/admin/engagement/reviews?status=PENDING_MODERATION&eventId=1001&submittedAfter=2026-03-01T00:00:00Z&page=0&size=20
Authorization: Bearer <JWT>   (ROLE_ADMIN)
```

**Query Params:** `status` (optional, default `PENDING_MODERATION`), `eventId` (optional), `submittedAfter` (optional ISO-8601), `page`, `size`

**Response — 200 OK:**
```json
{
  "content": [
    {
      "reviewId": 56,
      "eventId": 1001,
      "userId": 200,
      "rating": 2,
      "title": "Disappointing",
      "body": "Full body text...",
      "status": "PENDING_MODERATION",
      "attendanceVerificationStatus": "EB_VERIFIED",
      "submittedAt": "2026-03-05T16:00:00Z",
      "autoModerationAttempts": 1,
      "lastAutoDecision": "PENDING"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 7,
  "totalPages": 1
}
```

---

### 6. Moderate a Review (Admin)

```
PUT /api/v1/admin/engagement/reviews/{reviewId}/moderate
Authorization: Bearer <JWT>   (ROLE_ADMIN)
Content-Type: application/json
```

**Request Body:**
```json
{
  "decision": "APPROVE",
  "reason": null
}
```
or
```json
{
  "decision": "REJECT",
  "reason": "Content violates community guidelines — hate speech detected"
}
```

**Validations:**
- `decision`: required, must be `APPROVE` or `REJECT`
- `reason`: required when `decision = REJECT`; max 500 chars

**Response — 200 OK:**
```json
{
  "reviewId": 56,
  "newStatus": "PUBLISHED",
  "decidedAt": "2026-03-05T18:10:00Z"
}
```

**Error Responses:**

| HTTP | `errorCode` | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `reason` missing on REJECT |
| 404 | `REVIEW_NOT_FOUND` | `reviewId` does not exist |
| 409 | `REVIEW_ALREADY_MODERATED` | Review is already `PUBLISHED` or `REJECTED` |

---

### SecurityConfig Route Summary

| Pattern | Access |
|---|---|
| `POST /api/v1/engagement/reviews` | `ROLE_USER` |
| `GET /api/v1/engagement/reviews/me` | `ROLE_USER` |
| `GET /api/v1/engagement/reviews/events/**` | `permitAll()` |
| `GET /api/v1/admin/engagement/reviews` | `ROLE_ADMIN` |
| `PUT /api/v1/admin/engagement/reviews/*/moderate` | `ROLE_ADMIN` |

---

## Stage 6 — Business Logic

---

### 6.1 — `BookingConfirmedEventListener`

```
trigger: BookingConfirmedEvent(bookingId, cartId, seatIds, stripePaymentIntentId, userId)
```

1. Resolve `eventId` from `slotId` sourced from `CartSummaryDto` — **NOTE:** `BookingConfirmedEvent` does not carry `eventId` directly. `engagement` must derive it by reading slot→event linkage. If this is unavailable from the event payload, `engagement` must call `GET /api/v1/scheduling/slots/{slotId}` to resolve `eventId` before upserting eligibility.  
   > **Action item:** Add `slotId` to `BookingConfirmedEvent` if not already present, or add a derived `eventId` field.
2. Upsert `ReviewEligibility` — `INSERT ... ON CONFLICT (user_id, event_id) DO NOTHING` to ensure idempotency.
3. If `engagement.review.eligibility-window-days` is configured: set `eligible_until = eventDate + window`.
4. Method is `@Transactional` — rollback on any persistence failure; event is re-deliverable.

---

### 6.2 — `BookingCancelledEventListener`

```
trigger: BookingCancelledEvent(bookingId, cartId, seatIds, userId, reason)
```

1. Lookup `ReviewEligibility` by `bookingId` column.
2. If found and `status = UNLOCKED`: transition to `REVOKED`, set `updatedAt`.
3. If review is already `PUBLISHED` for this user+event: do NOT revoke or unpublish — cancellation after review is published does not retroactively remove the review.
4. If review is in `PENDING_MODERATION`: transition `Review.status → REJECTED` with `rejectionReason = "Booking cancelled"` and transition `ReviewEligibility → REVOKED`.
5. Idempotent: no-op if eligibility already `REVOKED`.

---

### 6.3 — `ReviewService.submitReview(Long userId, ReviewSubmitRequest request)`

```java
@Transactional
public ReviewSubmitResponse submitReview(Long userId, ReviewSubmitRequest request)
```

**Step 1 — Eligibility guard:**
- Load `ReviewEligibility` by `(userId, eventId)`.
- If absent or `status != UNLOCKED` → throw `ReviewNotEligibleException("REVIEW_NOT_ELIGIBLE")`.
- If `eligible_until != null && eligible_until < now()` → update `status = EXPIRED`, throw `ReviewNotEligibleException("REVIEW_WINDOW_CLOSED")`.

**Step 2 — Duplicate guard:**
- `reviewRepository.existsByUserIdAndEventId(userId, eventId)` → if true → throw `ReviewAlreadySubmittedException`.

**Step 3 — Attendance verification:**
- Delegate to `AttendanceVerificationService.verify(userId, eventId)` → returns `AttendanceVerificationStatus`.
- If `ReviewNotEligibleException` thrown inside (booking not CONFIRMED): propagate.

**Step 4 — Persist review:**
- Create `Review` with `status = SUBMITTED`, `attendanceVerificationStatus` from Step 3.
- Flush to assign `id`.
- Immediately set `status = PENDING_MODERATION` within same transaction.
- Save and return `review`.

**Step 5 — Trigger async moderation:**
- Publish an internal Spring Event `ModerationRequiredEvent(reviewId)` (internal to `engagement` only — not in `shared/`).
- `ModerationService` listens to this event with `@Async @EventListener`.
- HTTP response is returned before moderation completes.

**Step 6 — Return:**
- Return `ReviewSubmitResponse(reviewId, status="PENDING_MODERATION", message)`.

---

### 6.4 — `AttendanceVerificationService.verify(Long userId, Long eventId)`

**Step 1 — Internal booking check (mandatory gate):**
- REST `GET /api/v1/internal/bookings/by-user-event?userId={}&eventId={}` to `payments-ticketing`.
- Timeout: 2 seconds.
- If response is not 200 or `status != CONFIRMED` → throw `ReviewNotEligibleException`.
- On timeout or 5xx → throw `ReviewNotEligibleException` (booking check is mandatory, not advisory).

**Step 2 — Fetch `ebEventId` (non-blocking):**
- REST `GET /api/v1/catalog/events/{eventId}/eb-metadata` to `discovery-catalog`.
- Timeout: 1 second.
- On timeout/error → `ebEventId = null` → skip EB check.

**Step 3 — Eventbrite attendee cross-check (advisory):**
- If `ebEventId` is null → return `ATTENDANCE_SELF_REPORTED`.
- Retrieve `orgToken` from `EbTokenStore.getValidToken(orgId)`.
- Call `EbAttendeeService.getAttendeesByEvent(orgToken, ebEventId)` → filter by user email.
- If attendee found and `!cancelled && !refunded` → return `EB_VERIFIED`.
- If attendee found but `cancelled = true || refunded = true` → return `ATTENDANCE_SELF_REPORTED` (internal booking already confirmed; EB state is advisory).
- If attendee NOT found → return `ATTENDANCE_SELF_REPORTED` (log `WARN`).
- On any EB API exception (timeout, 5xx, `EbAuthException`) → return `ATTENDANCE_EB_UNAVAILABLE` (log `WARN`; never throw to caller).

---

### 6.5 — `ModerationService.triggerAutoModeration(Long reviewId)` `@Async`

**Step 1 — Load and prepare:**
- Load `Review` by `reviewId`.
- Assert `review.status = PENDING_MODERATION` — if already `PUBLISHED` or `REJECTED`, no-op (idempotent guard).
- Build `inputText = review.title + " " + review.body`.

**Step 2 — Create ModerationRecord:**
- Persist `ModerationRecord(reviewId, method=AUTO, inputText, decision=PENDING)`.

**Step 3 — Call OpenAI Moderation API:**
- `OpenAiModerationService.moderate(inputText)` → returns `ModerationResult(flagged, flags, scores)`.

**Step 4a — Not flagged (`flagged = false`):**
- Update `ModerationRecord.decision = APPROVED`, `decidedAt = now`.
- Transition `Review.status → PUBLISHED`, set `published_at = now`.
- Publish `ReviewPublishedEvent(reviewId, eventId, rating)`.
- Invalidate Redis summary cache `engagement:review:summary:{eventId}`.

**Step 4b — Flagged (`flagged = true`):**
- Update `ModerationRecord.decision = REJECTED`, `flags = [top flagged categories]`, `scores_json`, `decidedAt = now`.
- Transition `Review.status → REJECTED`, set `rejection_reason = topCategory`.

**Step 4c — API failure (exception):**
- Increment `ModerationRecord.auto_retry_count`.
- Compute `retry_after` using exponential backoff: attempt 1 → +30s, attempt 2 → +2min, attempt 3 → +10min.
- If `auto_retry_count >= 3`: do not set `retry_after`; leave `PENDING_MODERATION` for manual queue. Log `ERROR`.
- Update `ModerationRecord` — do NOT throw; error is swallowed.

---

### 6.6 — `ModerationService.retryPendingModerations()` `@Scheduled`

```java
@Scheduled(fixedDelay = 30_000)  // every 30 seconds
```

1. Query: `SELECT * FROM moderation_records WHERE decision = 'PENDING' AND retry_after <= NOW() AND auto_retry_count < 3`.
2. For each record: call `triggerAutoModeration(record.reviewId)` synchronously (within the scheduler thread).
3. Catch all exceptions per-record — continue processing remaining records (isolated failure).
4. Log processed count and any per-record failures at INFO level.

---

### 6.7 — `ModerationService.applyManualDecision(Long adminId, Long reviewId, ModerationDecision decision, String reason)`

```java
@Transactional
public ModerationResponse applyManualDecision(...)
```

1. Load `Review` by `reviewId` → else throw `ReviewNotFoundException`.
2. Assert `review.status = PENDING_MODERATION` → else throw `ReviewAlreadyModeratedException`.
3. Persist `ModerationRecord(method=MANUAL, moderatorId=adminId, decision, reason, decidedAt=now)`.
4. If `decision = APPROVE`:
   - Transition `Review.status → PUBLISHED`, set `published_at = now`.
   - Publish `ReviewPublishedEvent`.
   - Invalidate Redis summary.
5. If `decision = REJECT`:
   - Transition `Review.status → REJECTED`, set `rejection_reason = reason`.
6. Return `ModerationResponse(reviewId, newStatus, decidedAt)`.

---

### 6.8 — `ReviewRatingSummaryService.getSummary(Long eventId)`

1. Try Redis GET key `engagement:review:summary:{eventId}`.
2. If hit: deserialize and return `ReviewRatingSummary`.
3. If miss: execute DB aggregate:
   ```sql
   SELECT
     AVG(rating)::NUMERIC(3,1)     AS average_rating,
     COUNT(*)                       AS total_reviews,
     COUNT(CASE WHEN rating=1 THEN 1 END) AS one,
     COUNT(CASE WHEN rating=2 THEN 1 END) AS two,
     COUNT(CASE WHEN rating=3 THEN 1 END) AS three,
     COUNT(CASE WHEN rating=4 THEN 1 END) AS four,
     COUNT(CASE WHEN rating=5 THEN 1 END) AS five
   FROM reviews
   WHERE event_id = ? AND status = 'PUBLISHED'
   ```
4. Build and serialize `ReviewRatingSummary`.
5. Redis SET with TTL 5 minutes.
6. Return summary.

**Cache invalidation:** `ReviewPublishedEvent` listener in `ReviewRatingSummaryService` calls `redisTemplate.delete("engagement:review:summary:" + eventId)`.

---

## Stage 7 — Error Handling

---

### 7.1 — Exception Catalog

All exceptions extend `BusinessRuleException` from `shared/common/exception/`. The single `GlobalExceptionHandler` in `shared/` maps them to `ApiError { errorCode, message, timestamp, path }`.

| Exception Class | HTTP Code | `errorCode` | Condition |
|---|---|---|---|
| `ReviewNotEligibleException("REVIEW_NOT_ELIGIBLE")` | 403 | `REVIEW_NOT_ELIGIBLE` | `ReviewEligibility` absent, `status != UNLOCKED`, or booking check returns non-CONFIRMED |
| `ReviewNotEligibleException("REVIEW_WINDOW_CLOSED")` | 403 | `REVIEW_WINDOW_CLOSED` | `eligible_until != null && eligible_until < now()` — review submission window expired |
| `ReviewAlreadySubmittedException` | 409 | `REVIEW_ALREADY_SUBMITTED` | A `Review` record already exists for `(userId, eventId)` in any status |
| `ReviewAlreadyModeratedException` | 409 | `REVIEW_ALREADY_MODERATED` | Admin attempts to moderate a review already in `PUBLISHED` or `REJECTED` state |
| `ReviewNotFoundException` | 404 | `REVIEW_NOT_FOUND` | `reviewId` not found in `reviews` table |
| `MethodArgumentNotValidException` (Spring) | 400 | `VALIDATION_ERROR` | Bean validation failure: `rating` out of 1–5 range, blank `title`/`body`, missing `eventId` |

**Error response shape (from `GlobalExceptionHandler`):**
```json
{
  "errorCode": "REVIEW_NOT_ELIGIBLE",
  "message": "You are not eligible to review this event.",
  "timestamp": "2026-03-05T18:00:00Z",
  "path": "/api/v1/engagement/reviews"
}
```

---

### 7.2 — External Dependency Failure Handling

#### `payments-ticketing` Booking Check (Mandatory Gate)

| Failure Mode | Handling | Effect on User |
|---|---|---|
| HTTP 404 — no booking found | Throw `ReviewNotEligibleException` | 403 REVIEW_NOT_ELIGIBLE |
| HTTP 200 but `status != CONFIRMED` | Throw `ReviewNotEligibleException` | 403 REVIEW_NOT_ELIGIBLE |
| Timeout (> 2s) | Throw `ReviewNotEligibleException` | 403 REVIEW_NOT_ELIGIBLE — booking check is mandatory |
| HTTP 5xx | Throw `ReviewNotEligibleException` | 403 REVIEW_NOT_ELIGIBLE — fail safe |

> Rationale: Allowing a review submission when booking status is unknown risks fraudulent reviews. Fail closed.

---

#### `discovery-catalog` EB-Metadata Fetch (Advisory)

| Failure Mode | Handling | Effect on Submission |
|---|---|---|
| Timeout (> 1s) | `ebEventId = null`, skip EB check | Proceed with `ATTENDANCE_SELF_REPORTED` |
| HTTP 404 | `ebEventId = null`, skip EB check | Proceed with `ATTENDANCE_SELF_REPORTED` |
| HTTP 5xx | `ebEventId = null`, skip EB check | Proceed with `ATTENDANCE_SELF_REPORTED` |

---

#### Eventbrite `EbAttendeeService` Call (Advisory)

| Failure Mode | Handling | Effect on Submission |
|---|---|---|
| `EbAuthException` — org token expired/invalid | Log WARN, return `ATTENDANCE_EB_UNAVAILABLE` | Proceed (non-blocking) |
| Timeout / HTTP 5xx | Log WARN, return `ATTENDANCE_EB_UNAVAILABLE` | Proceed (non-blocking) |
| Attendee not found in results | Log WARN (reconciliation), return `ATTENDANCE_SELF_REPORTED` | Proceed |
| Attendee found but `cancelled=true` | Return `ATTENDANCE_SELF_REPORTED` | Proceed — internal booking is authoritative |

> **Never** throw an exception from the EB cross-check path. EB is advisory; internal booking is the gate.

---

#### OpenAI Moderation API (Async — No User Impact)

| Failure Mode | Handling | Effect |
|---|---|---|
| Timeout / HTTP 5xx (attempt 1) | Set `retry_after = now + 30s`, `auto_retry_count = 1` | Review stays `PENDING_MODERATION` |
| Timeout / HTTP 5xx (attempt 2) | Set `retry_after = now + 2min`, `auto_retry_count = 2` | Review stays `PENDING_MODERATION` |
| Timeout / HTTP 5xx (attempt 3) | Log ERROR, no `retry_after` set | Review stays `PENDING_MODERATION` — enters manual queue |
| All 3 retries exhausted | Log ERROR with `reviewId` and last error; alert via metrics counter `engagement.review.moderation.auto.exhausted` | Review visible in admin moderation queue |
| Exception inside `@Async` handler | Caught and swallowed — does NOT propagate to caller | Review record already committed safely |

---

#### `identity` Display Name Fetch (Read Path — Non-Blocking)

| Failure Mode | Handling | Effect on Response |
|---|---|---|
| Timeout (> 500ms) | Return `"Anonymous"` for that reviewer | 200 response returned normally |
| HTTP 404 — user not found | Return `"Anonymous"` | 200 response returned normally |
| HTTP 5xx | Return `"Anonymous"` | 200 response returned normally |

---

#### Redis Cache Failure (Summary Endpoint)

| Failure Mode | Handling | Effect on Response |
|---|---|---|
| Redis `GET` throws exception | Log WARN, fall through to DB query | Summary computed live — slight latency, no error |
| Redis `SET` throws exception after DB query | Log WARN, return computed result without caching | Correct result returned; cache miss on next call |
| Redis `DEL` (invalidation) throws | Log WARN | Next read will return stale cache (max 5-min stale) |

---

### 7.3 — Concurrent Submission Guard

If two concurrent requests hit `POST /reviews` with the same `(userId, eventId)` simultaneously:
- The duplicate guard at service layer (`existsByUserIdAndEventId`) races against the `UNIQUE` constraint on `(user_id, event_id)` in the DB.
- The second request will receive a `DataIntegrityViolationException` from JPA on `UNIQUE` violation.
- `GlobalExceptionHandler` maps `DataIntegrityViolationException` → 409 `REVIEW_ALREADY_SUBMITTED` when the constraint name contains `uq_reviews_user_event`.
- This is safe and correct — no data corruption possible.

---

### 7.4 — Spring Event Listener Failure Policy

| Event | Listener | Failure Handling |
|---|---|---|
| `BookingConfirmedEvent` | `BookingConfirmedEventListener` | `@TransactionalEventListener(AFTER_COMMIT)` — if listener throws, eligibility write fails silently. Log ERROR. The sponsoring transaction is already committed. Re-processing only possible via replay mechanism (future). |
| `BookingCancelledEvent` | `BookingCancelledEventListener` | Same — log ERROR, no retry. Manual reconciliation job can repair. |

> **Operational note:** Both listeners are idempotent — re-running them with the same event produces a no-op (upsert / conditional update). A reconciliation endpoint `POST /api/v1/internal/engagement/reconcile-eligibility?bookingId={}` can be added for ops use.

---

## Stage 8 — Tests

> **Test structure follows `docs/TESTING_GUIDE.md` 4-layer model.**  
> **Location convention:** `engagement/src/test/java/com/eventplatform/engagement/`

---

### Layer 1 — Domain Unit Tests (`domain/`)

**`ReviewTest.java`**

| Test | Given | When | Then |
|---|---|---|---|
| `cannotPublishFromSubmitted` | `review.status = SUBMITTED` | Domain method `publish()` called | `IllegalStateException` or guard throws |
| `canPublishFromApproved` | `review.status = APPROVED` | `publish()` called | `status = PUBLISHED`, `publishedAt` set |
| `rejectedIsTerminal` | `review.status = REJECTED` | Any status setter called | State unchanged or guarded |
| `ratingBoundaryValid` | ratings 1 and 5 | Construct `Review` | No exception |
| `ratingBoundaryInvalid` | rating 0 or 6 | Construct `Review` | `IllegalArgumentException` |

**`ReviewEligibilityTest.java`**

| Test | Given | When | Then |
|---|---|---|---|
| `revokedCannotBeUnlocked` | `status = REVOKED` | `unlock()` called | Stays `REVOKED` / exception |
| `expiredOnWindowCheck` | `eligible_until = 1 day ago` | `isEligible(now)` | Returns false |
| `unlocked_isEligible` | `status = UNLOCKED`, `eligible_until = future` | `isEligible(now)` | Returns true |

---

### Layer 2 — Service Unit Tests (`service/`) — Mockito, no Spring context

**`ReviewServiceTest.java`**

```
Mocks: ReviewEligibilityRepository, ReviewRepository, AttendanceVerificationService, ApplicationEventPublisher
```

| Test | Scenario | Expected |
|---|---|---|
| `submitReview_happyPath` | Eligibility UNLOCKED, no existing review, attendance = EB_VERIFIED | Review saved with PENDING_MODERATION, `ModerationRequiredEvent` published |
| `submitReview_noEligibility` | No `ReviewEligibility` row found | `ReviewNotEligibleException(REVIEW_NOT_ELIGIBLE)` |
| `submitReview_eligibilityRevoked` | `ReviewEligibility.status = REVOKED` | `ReviewNotEligibleException(REVIEW_NOT_ELIGIBLE)` |
| `submitReview_windowExpired` | `eligible_until = yesterday` | `ReviewNotEligibleException(REVIEW_WINDOW_CLOSED)` |
| `submitReview_duplicateReview` | Existing `Review` for same (userId, eventId) | `ReviewAlreadySubmittedException` |
| `submitReview_bookingCheckFails` | `AttendanceVerificationService` throws `ReviewNotEligibleException` | Propagated as `ReviewNotEligibleException` |
| `submitReview_selfReportedAttendance` | EB attendee not found, booking CONFIRMED | Review saved with `ATTENDANCE_SELF_REPORTED` |

---

**`AttendanceVerificationServiceTest.java`**

```
Mocks: BookingVerificationClient (REST), EbMetadataClient (REST), EbAttendeeService, EbTokenStore
```

| Test | Scenario | Expected |
|---|---|---|
| `verify_bookingConfirmed_ebVerified` | Booking CONFIRMED, EB attendee found, not cancelled/refunded | Returns `EB_VERIFIED` |
| `verify_bookingConfirmed_ebAttendeeNotFound` | Booking CONFIRMED, EB attendee not found | Returns `ATTENDANCE_SELF_REPORTED` |
| `verify_bookingNotConfirmed` | Booking REST returns `CANCELLED` status | `ReviewNotEligibleException` thrown |
| `verify_bookingCheckTimeout` | REST call to payments-ticketing times out | `ReviewNotEligibleException` thrown (fail closed) |
| `verify_ebMetadataTimeout` | discovery-catalog timeout (1s) | `ebEventId = null`, returns `ATTENDANCE_SELF_REPORTED` |
| `verify_ebApiException` | `EbAttendeeService` throws exception | Returns `ATTENDANCE_EB_UNAVAILABLE`, no exception to caller |
| `verify_ebAuthException` | `EbTokenStore` throws `EbAuthException` | Returns `ATTENDANCE_EB_UNAVAILABLE`, no exception to caller |
| `verify_noEbEventId` | `ebEventId` is null (slot not synced) | Skip EB check, return `ATTENDANCE_SELF_REPORTED` |

---

**`ModerationServiceTest.java`**

```
Mocks: ReviewRepository, ModerationRecordRepository, OpenAiModerationService, ApplicationEventPublisher, StringRedisTemplate
```

| Test | Scenario | Expected |
|---|---|---|
| `autoModeration_approved` | OpenAI returns `flagged=false` | `ModerationRecord.decision=APPROVED`, `Review.status=PUBLISHED`, `ReviewPublishedEvent` published, Redis invalidated |
| `autoModeration_rejected` | OpenAI returns `flagged=true`, top flag = `hate` | `ModerationRecord.decision=REJECTED`, `Review.status=REJECTED`, `rejectionReason="hate"` |
| `autoModeration_apiFailure_firstAttempt` | OpenAI throws `RuntimeException` | `auto_retry_count=1`, `retry_after = now+30s`, `decision=PENDING`, review stays `PENDING_MODERATION` |
| `autoModeration_apiFailure_thirdAttempt` | `auto_retry_count=2` before call, OpenAI throws | `auto_retry_count=3`, no `retry_after`, metrics counter incremented |
| `autoModeration_idempotent_alreadyPublished` | `review.status=PUBLISHED` before auto-mod runs | No-op — method returns without any state change |
| `retryPendingModerations_processesEligibleRecords` | 2 records with `retry_after <= now`, 1 with future | Processes 2, skips 1 |
| `retryPendingModerations_continuesOnPerRecordFailure` | First record throws, second is valid | Second processed successfully despite first failure |
| `manualDecision_approve` | Review in `PENDING_MODERATION` | status=PUBLISHED, `ModerationRecord` created with method=MANUAL, event published |
| `manualDecision_reject` | Review in `PENDING_MODERATION`, `reason` provided | status=REJECTED, `ModerationRecord` created with method=MANUAL |
| `manualDecision_alreadyModerated` | Review in `PUBLISHED` | `ReviewAlreadyModeratedException` |

---

**`ReviewRatingSummaryServiceTest.java`**

```
Mocks: ReviewRepository, StringRedisTemplate, ObjectMapper
```

| Test | Scenario | Expected |
|---|---|---|
| `getSummary_cacheHit` | Redis returns valid JSON | Deserialized `ReviewRatingSummary` returned; no DB call |
| `getSummary_cacheMiss` | Redis returns null | DB aggregate executed; result cached in Redis |
| `getSummary_redisGetThrows` | Redis throws exception | Falls through to DB query; result returned without caching |
| `getSummary_redisSetThrows` | DB succeeds but Redis SET throws | Result returned; WARN logged |
| `invalidateCache_onPublish` | `ReviewPublishedEvent` received | Redis DEL called for `engagement:review:summary:{eventId}` |

---

### Layer 3 — Controller Tests (`api/`) — `@WebMvcTest`

**`ReviewControllerTest.java`**

| Test | HTTP | Scenario | Expected |
|---|---|---|---|
| `submitReview_201` | POST `/reviews` | Valid body, mocked service returns success | 201, body contains `reviewId` and `status=PENDING_MODERATION` |
| `submitReview_403_notEligible` | POST `/reviews` | Service throws `ReviewNotEligibleException` | 403, `errorCode=REVIEW_NOT_ELIGIBLE` |
| `submitReview_409_duplicate` | POST `/reviews` | Service throws `ReviewAlreadySubmittedException` | 409, `errorCode=REVIEW_ALREADY_SUBMITTED` |
| `submitReview_400_invalidRating` | POST `/reviews` | `rating=0` | 400, `errorCode=VALIDATION_ERROR` |
| `submitReview_400_blankTitle` | POST `/reviews` | `title=""` | 400, `errorCode=VALIDATION_ERROR` |
| `submitReview_401_noJwt` | POST `/reviews` | No Authorization header | 401 |
| `listReviews_200_public` | GET `/reviews/events/1001` | No auth, valid eventId | 200, paginated `ReviewResponse` list |
| `listReviews_defaultSort` | GET `/reviews/events/1001` | No `sort` param | Default sort `publishedAt,desc` applied |
| `getReviewSummary_200` | GET `/reviews/events/1001/summary` | Cache hit in mock | 200, `averageRating`, `distribution` |
| `getMyReviews_200` | GET `/reviews/me` | Valid JWT | 200, user's reviews with `status` field |
| `getMyReviews_401_noJwt` | GET `/reviews/me` | No JWT | 401 |

---

**`AdminModerationControllerTest.java`**

| Test | HTTP | Scenario | Expected |
|---|---|---|---|
| `getAdminQueue_200` | GET `/admin/reviews?status=PENDING_MODERATION` | ROLE_ADMIN | 200 paginated `AdminReviewResponse` |
| `getAdminQueue_403_notAdmin` | GET `/admin/reviews` | ROLE_USER JWT | 403 |
| `moderateApprove_200` | PUT `/admin/reviews/56/moderate` | `decision=APPROVE`, ROLE_ADMIN | 200, `newStatus=PUBLISHED` |
| `moderateReject_200` | PUT `/admin/reviews/56/moderate` | `decision=REJECT`, `reason` provided | 200, `newStatus=REJECTED` |
| `moderateReject_400_missingReason` | PUT `/admin/reviews/56/moderate` | `decision=REJECT`, no `reason` | 400, `VALIDATION_ERROR` |
| `moderateReview_409_alreadyModerated` | PUT `/admin/reviews/56/moderate` | Service throws `ReviewAlreadyModeratedException` | 409 |
| `moderateReview_404_notFound` | PUT `/admin/reviews/56/moderate` | Service throws `ReviewNotFoundException` | 404 |
| `moderateReview_403_noAdminRole` | PUT `/admin/reviews/56/moderate` | ROLE_USER JWT | 403 |

---

### Layer 4 — Architecture Tests (`arch/`) — ArchUnit

**`EngagementArchTest.java`**

```java
// Rule 1: No cross-module package imports
noClasses()
    .that().resideInAPackage("..engagement..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "..payments.ticketing..",
        "..booking.inventory..",
        "..identity..",
        "..promotions..",
        "..scheduling..",
        "..discovery.catalog.."
    )
    .because("engagement must not import other module's internal classes");

// Rule 2: Eventbrite calls only via shared ACL
noClasses()
    .that().resideInAPackage("..engagement..")
    .should().dependOnClassesThat().resideInAPackage("..com.eventbrite..")
    .because("Eventbrite calls must go through shared/eventbrite ACL facades");

// Rule 3: OpenAI calls only via shared ACL
noClasses()
    .that().resideInAPackage("..engagement..")
    .should().dependOnClassesThat().resideInAPackage("..com.theokanning.openai..")
    .because("OpenAI calls must go through shared/openai ACL facades");

// Rule 4: No @ControllerAdvice in engagement
noClasses()
    .that().resideInAPackage("..engagement..")
    .should().beAnnotatedWith(ControllerAdvice.class)
    .because("Single GlobalExceptionHandler in shared/");

// Rule 5: Mapping only in mapper layer
noClasses()
    .that().resideInAPackage("..engagement.service..")
    .or().resideInAPackage("..engagement.api.controller..")
    .should().callConstructor(ReviewResponse.class)
    .because("Field mapping belongs in mapper/ layer only (MapStruct)");
```

---

### Test File Locations

```
engagement/src/test/java/com/eventplatform/engagement/
├── domain/
│   ├── ReviewTest.java
│   └── ReviewEligibilityTest.java
├── service/
│   ├── ReviewServiceTest.java
│   ├── AttendanceVerificationServiceTest.java
│   ├── ModerationServiceTest.java
│   └── ReviewRatingSummaryServiceTest.java
├── api/
│   ├── ReviewControllerTest.java
│   └── AdminModerationControllerTest.java
└── arch/
    └── EngagementArchTest.java
```

---

## Open Questions

1. **`eventId` in `BookingConfirmedEvent`** — The event currently carries `bookingId, cartId, seatIds, stripePaymentIntentId, userId`. It does NOT directly carry `eventId`. The `engagement` listener needs `eventId` to create `ReviewEligibility`. Decision options:
   - (a) Add `eventId` as a field to `BookingConfirmedEvent` in `shared/` — requires updating `payments-ticketing` publisher and all consumers.
   - (b) `engagement` derives `eventId` by calling `GET /api/v1/scheduling/slots/{slotId}` at listener time — adds REST latency in `AFTER_COMMIT` listener.
   - (c) Add `slotId` to `BookingConfirmedEvent` and have `engagement` call `discovery-catalog` to resolve `eventId`.
   - **Recommended:** Option (a) — add `eventId` directly to `BookingConfirmedEvent` as it is naturally known at booking confirmation time.

2. **Eligibility expiry enforcement** — `REVIEW_WINDOW_CLOSED` case requires knowing the `eventDate`. Should `ReviewEligibility` store `eventDate` at creation time, or should it call `discovery-catalog` on each submission check?

3. **EB attendee email matching** — The user's email is stored in `identity`. `AttendanceVerificationService` must call `identity` REST to fetch user email before the EB API call, or the user email should be included in `BookingConfirmedEvent`. Which is preferred?

4. **`orgId` for EB token retrieval** — `EbTokenStore.getValidToken(orgId)` requires an `orgId`. The `engagement` listener receives `orgId` from `BookingConfirmedEvent` (already carried). Confirm this field is reliably present.

5. **Reviewer display name privacy** — Should display names be anonymized (e.g., "Jane D." — first name + last initial) or shown in full? This affects `identity`'s internal display name endpoint contract.

6. **`OpenAiModerationService`** — Does this facade already exist in `shared/openai/service/`? If not, it must be created as part of FR8 pre-work before implementation begins.












