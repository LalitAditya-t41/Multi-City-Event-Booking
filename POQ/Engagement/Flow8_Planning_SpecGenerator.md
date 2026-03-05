User: Create a SPEC_8.md file for the functional flow 8. create stage 1 outline and go stage by stage.

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/agents/SPEC_GENERATOR.md#1-1), lines 1 to 100

Searched for files matching `**/POQ/*/SPEC_8*`, no matches

Searched for files matching `**/docs/requirements/*`, 2 matches

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#1-1), lines 1 to 100

Read [](file:///home/dell/Multi-City-Event-Booking/docs/requirements/Functional%20Req)

Read [](file:///home/dell/Multi-City-Event-Booking/docs/requirements/Project%20Need)

Searched for regex `FR8|engagement|review|chatbot|RAG|moderation`, 20 results

Read [](file:///home/dell/Multi-City-Event-Booking/docs/EVENTBRITE_INTEGRATION.md#1-1), lines 1 to 200

Searched for text `engagement`, 30 results

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#280-280), lines 280 to 500

Read [](file:///home/dell/Multi-City-Event-Booking/PRODUCT.md#740-740), lines 740 to 1000

Now I have enough context. Let me present the **Stage 1 outline** for FR8 following the confirmation gate protocol before writing the full spec.

---

## FR8 — Stage 1 Requirements Outline

**Feature:** Review & Rating Submission → Attendance Verification → Moderation Queue → Public Publish  
**Module:** `engagement` (primary)  
**Depends on:** SPEC_5.md (FR5 — bookings/payments confirmed), SPEC_3.MD (FR3 — JWT auth)  
**External facades:** `EbAttendeeService` (attendance verification only), OpenAI Moderation API via `shared/openai/`

---

### Proposed Requirement Areas

**Area A — Review Eligibility Gate**
- On `BookingConfirmedEvent`, create a `ReviewEligibility` record (unlocked) per booking
- On `BookingCancelledEvent`, revoke eligibility for affected booking
- Only one review allowed per `(userId, eventId)` pair — guard before submission
- Only `CONFIRMED` + not-cancelled bookings can unlock eligibility

**Area B — Attendance Verification (Eventbrite cross-check)**
- Before creating a review: check internal booking status via REST to payments-ticketing
- Cross-check `EbAttendeeService.getAttendees(ebEventId)` filtered by user email; verify `cancelled=false`, `refunded=false`
- If EB attendee record missing but internal booking is `CONFIRMED`: policy decision — allow with `ATTENDANCE_SELF_REPORTED` flag or block
- Eventbrite is consulted but is NOT the source of truth for eligibility — internal booking is primary

**Area C — Review Submission**
- Expose `POST /api/v1/engagement/reviews` (JWT `ROLE_USER`)
- Payload: `eventId`, `rating` (1–5), `title`, `body`
- Status transitions: `SUBMITTED → PENDING_MODERATION`
- Idempotency: duplicate `(userId, eventId)` rejected after first submission

**Area D — Auto-Moderation**
- Auto-moderation via OpenAI Moderation API (profanity, hate, spam detection)
- `ModerationRecord` created for every review — stores input, decision, flags, scores
- On `APPROVED`: transition review → `PUBLISHED`
- On `REJECTED`: review is terminal; `REJECTED` with `rejectionReason`
- On API failure: review stays `PENDING_MODERATION`; retry queue triggered

**Area E — Manual Moderation Override (Admin)**
- Admin endpoint: `PUT /api/v1/admin/engagement/reviews/{id}/moderate` — `APPROVE` or `REJECT` with reason
- Visible in admin panel with filter by `PENDING_MODERATION`
- Audit trail: `ModerationRecord` updated with `moderatorId`, `method=MANUAL`, `decidedAt`

**Area F — Review Read APIs**
- `GET /api/v1/engagement/reviews/events/{eventId}` — list `PUBLISHED` reviews, paginated (public, no JWT)
- `GET /api/v1/engagement/reviews/me` — list current user's reviews with status (JWT required)
- `GET /api/v1/engagement/reviews/events/{eventId}/summary` — average rating + rating distribution (public)

**Area G — Non-Functional / Architecture**
- `engagement` module never imports `@Repository` or `@Service` from other modules
- `BookingConfirmedEvent` consumed via `@TransactionalEventListener(AFTER_COMMIT)`
- Attendance verification is async-optional (non-blocking for submission UX); can be synchronous with timeout fallback
- No Eventbrite reviews API — all review data is internal

---

**Out of Scope (proposed):**
- AI Chatbot (FR11) — separate sub-flow within `engagement`, separate spec section
- RAG index pipeline beyond event-triggered refresh
- Review editing after submission
- Organizer replies to reviews
- Notification emails for review status changes

---

Please confirm, modify, or add to this outline — then I'll write the full Stage 1 requirements and create the file. 

Asked a question (Stage 1 OK?)

Created 8 todos

Created [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)

Starting: *Stage 2 outline + confirm + write* (2/8)

Stage 1 written to SPEC_8.md. 

---

## Stage 2 — Domain Modeling Outline

### Proposed Entities

**ReviewEligibility**
- Identity: `id BIGSERIAL`
- Business key: `(userId, eventId)` — unique
- Fields: `userId`, `eventId`, `slotId`, `bookingId`, `status` (`UNLOCKED` / `REVOKED` / `EXPIRED`), `eligibleUntil` (nullable), `createdAt`, `updatedAt`
- Lifecycle: created on `BookingConfirmedEvent`; updated to `REVOKED` on `BookingCancelledEvent`

**Review**
- Identity: `id BIGSERIAL`
- Business key: `(userId, eventId)` — unique (one review per attended event)
- Fields: `userId`, `eventId`, `rating` (1–5), `title`, `body`, `status` (enum below), `attendanceVerificationStatus`, `rejectionReason` (nullable), `publishedAt` (nullable), `submittedAt`, `updatedAt`
- Status lifecycle: `SUBMITTED → PENDING_MODERATION → APPROVED → PUBLISHED` / `REJECTED` (terminal)

**ModerationRecord**
- Identity: `id BIGSERIAL`
- Belongs to: `Review` (many-to-one — multiple attempts possible via retry)
- Fields: `reviewId`, `method` (`AUTO` / `MANUAL`), `inputText`, `decision` (`APPROVED` / `REJECTED`), `flags` (String — comma-separated), `scoresJson` (JSON), `moderatorId` (nullable — null for AUTO), `reason` (nullable), `autoRetryCount`, `retryAfter` (nullable timestamp), `decidedAt`

**ReviewEligibilityStatus** (enum): `UNLOCKED`, `REVOKED`, `EXPIRED`
**ReviewStatus** (enum): `SUBMITTED`, `PENDING_MODERATION`, `APPROVED`, `PUBLISHED`, `REJECTED`
**AttendanceVerificationStatus** (enum): `EB_VERIFIED`, `ATTENDANCE_SELF_REPORTED`, `ATTENDANCE_EB_UNAVAILABLE`
**ModerationMethod** (enum): `AUTO`, `MANUAL`
**ModerationDecision** (enum): `APPROVED`, `REJECTED`

### Domain Summary
A `ReviewEligibility` is unlocked when a `BookingConfirmedEvent` arrives; it gates all submission attempts. A `Review` is the aggregate root — it carries the user's rating and text through a moderation lifecycle. A `ModerationRecord` is the audit log of every moderation pass (auto or manual), including retry state for failed API calls.

### Value Objects / Read Models
- `ReviewRatingSummary` — computed: `averageRating`, `totalReviews`, `distribution` (map 1–5 → count). Not persisted; derived from `reviews` table + cached in Redis.

---

Confirm this domain model or request changes, then I'll write the full Stage 2. 

Asked a question (Stage 2 OK?)

Replacing 2 lines with 154 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Starting: *Stage 3 outline + confirm + write* (3/8)

Stage 2 written. 

---

## Stage 3 — Architecture & File Structure Outline

**Architecture pattern:** Modular Monolith (fixed). `engagement` module. Fully self-contained domain layer.

### Proposed file layout inside `engagement/`

```
engagement/
└── src/main/java/com/eventplatform/engagement/
    ├── api/
    │   ├── controller/
    │   │   ├── ReviewController.java           (POST /reviews, GET /reviews/me, GET /reviews/events/{id}, GET /reviews/events/{id}/summary)
    │   │   └── AdminModerationController.java  (PUT /admin/reviews/{id}/moderate, GET /admin/reviews)
    │   └── dto/
    │       ├── request/  ReviewSubmitRequest.java, ModerationDecisionRequest.java
    │       └── response/ ReviewResponse.java, ReviewSummaryResponse.java, ModerationRecordResponse.java
    ├── domain/
    │   ├── Review.java
    │   ├── ReviewEligibility.java
    │   ├── ModerationRecord.java
    │   └── enums/  ReviewStatus.java, ReviewEligibilityStatus.java, AttendanceVerificationStatus.java, ModerationMethod.java, ModerationDecision.java
    ├── service/
    │   ├── ReviewService.java                  (submission, eligibility check, publish)
    │   ├── AttendanceVerificationService.java  (internal booking check + EB cross-check)
    │   ├── ModerationService.java              (auto-mod trigger, retry scheduler, manual override)
    │   └── ReviewRatingSummaryService.java     (aggregate + Redis cache)
    ├── repository/
    │   ├── ReviewRepository.java
    │   ├── ReviewEligibilityRepository.java
    │   └── ModerationRecordRepository.java
    ├── mapper/
    │   └── ReviewMapper.java                   (MapStruct — Review ↔ DTOs, ModerationRecord ↔ DTOs)
    ├── event/
    │   ├── listener/
    │   │   ├── BookingConfirmedEventListener.java   (@TransactionalEventListener AFTER_COMMIT)
    │   │   └── BookingCancelledEventListener.java   (@TransactionalEventListener AFTER_COMMIT)
    │   └── published/
    │       └── ReviewPublishedEvent.java            (record: reviewId, eventId, rating)
    └── exception/
        ├── ReviewNotEligibleException.java
        ├── ReviewAlreadySubmittedException.java
        └── ReviewAlreadyModeratedException.java
```

### Shared additions needed (shared)
- `shared/openai/service/OpenAiModerationService.java` — ACL wrapper for OpenAI Moderation API (if not already present)
- `shared/common/event/published/ReviewPublishedEvent.java` — if consumed by other modules in future
- No new shared reader interface needed (engagement is a leaf module — nothing below it)

### Inter-module REST calls made by `engagement`
| Target | Endpoint | Purpose |
|---|---|---|
| payments-ticketing | `GET /api/v1/internal/bookings/by-user-event` | Verify CONFIRMED booking |
| identity | `GET /api/v1/internal/users/{id}/display-name` | Reviewer display name at read time |
| discovery-catalog | `GET /api/v1/catalog/events/{eventId}/eb-metadata` | Fetch `ebEventId` for EB attendee check |

### Spring Events consumed
| Event | Published by | Handler |
|---|---|---|
| `BookingConfirmedEvent` | payments-ticketing | `BookingConfirmedEventListener` → unlock eligibility |
| `BookingCancelledEvent` | payments-ticketing | `BookingCancelledEventListener` → revoke eligibility |

### Spring Events published
| Event | Consumed by |
|---|---|
| `ReviewPublishedEvent` | (audit/analytics — no current consumer; prepared for extension) |

---

Confirm the file structure or request changes, then I'll write the full Stage 3. 

Asked a question (Stage 3 OK?)

Replacing 2 lines with 142 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Starting: *Stage 4 outline + confirm + write* (4/8)

Let me check the current migration version before proposing the DB schema. 

Searched for files matching `**/resources/db/migration/V3*.sql`, 9 matches

Latest migration is V37. FR8 will use V38+. 

---

## Stage 4 — DB Schema Outline

### Proposed Flyway Migrations

**V38 — `create_review_eligibility_table.sql`**
- `review_eligibility` table
- Indexes: unique on `(user_id, event_id)`, index on `status`, index on `booking_id`

**V39 — `create_reviews_table.sql`**
- `reviews` table
- Indexes: unique on `(user_id, event_id)`, index on `status`, index on `event_id` (for listing), index on `user_id` (for `/me` endpoint)

**V40 — `create_moderation_records_table.sql`**
- `moderation_records` table
- FK → `reviews(id)` ON DELETE CASCADE
- Indexes: index on `review_id`, index on `(decision, method)`, index on `retry_after` (for retry scheduler query)

### No foreign keys crossing module boundaries
- `user_id`, `event_id`, `slot_id`, `booking_id` are plain `BIGINT` — no cross-module FK constraints

---

Confirm the schema outline or request changes, then I'll write the full Stage 4 DDL.  

Asked a question (Stage 4 OK?)

Replacing 5 lines with 5 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)

Searched for text `Spring Events AFTER_COMMIT | Both event listeners`, 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md#405-405), lines 405 to 412

Replacing 4 lines with 131 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Starting: *Stage 5 outline + confirm + write* (5/8)

Stage 4 written.

---

## Stage 5 — API Design Outline

### Proposed Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/engagement/reviews` | JWT USER | Submit a review |
| `GET` | `/api/v1/engagement/reviews/events/{eventId}` | Public | List PUBLISHED reviews for event |
| `GET` | `/api/v1/engagement/reviews/events/{eventId}/summary` | Public | Avg rating + distribution |
| `GET` | `/api/v1/engagement/reviews/me` | JWT USER | Current user's own reviews |
| `GET` | `/api/v1/admin/engagement/reviews` | JWT ADMIN | Admin moderation queue |
| `PUT` | `/api/v1/admin/engagement/reviews/{reviewId}/moderate` | JWT ADMIN | Approve or reject a review |

### Request / Response Shapes (Outline)

**POST `/reviews`** — body: `{ eventId, rating, title, body }` → 201 `{ reviewId, status, message }`

**GET `/reviews/events/{eventId}`** — query: `page`, `size`, `sort` → 200 `Page<ReviewResponse>` (id, rating, title, body, publishedAt, reviewerDisplayName)

**GET `/reviews/events/{eventId}/summary`** → 200 `{ averageRating, totalReviews, distribution: {1: n, 2: n, ...} }`

**GET `/reviews/me`** → 200 `Page<ReviewResponse>` (includes `status`, `rejectionReason`)

**GET `/admin/reviews?status=PENDING_MODERATION&eventId=&page=&size=`** → 200 `Page<AdminReviewResponse>`

**PUT `/admin/reviews/{id}/moderate`** — body: `{ decision: "APPROVE"|"REJECT", reason? }` → 200 `{ reviewId, newStatus, decidedAt }`

### HTTP Status Codes (Key)

| Scenario | Code |
|---|---|
| Review submitted OK | 201 |
| Not eligible (no booking / revoked) | 403 REVIEW_NOT_ELIGIBLE |
| Already submitted | 409 REVIEW_ALREADY_SUBMITTED |
| Invalid rating (not 1–5) | 400 INVALID_RATING |
| Already moderated | 409 REVIEW_ALREADY_MODERATED |
| Review not found | 404 REVIEW_NOT_FOUND |

---

Confirm the API outline or request changes, then I'll write the full Stage 5 contract.   

Asked a question (Stage 5 OK?)

Searched for text `idx_moderation_review_id`, 2 results

Continue to iterate?
[object Object]

Made changes.

User: @agent Continue: "Continue to iterate?"

GitHub Copilot: Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md#530-530), lines 530 to 539

Replacing 4 lines with 254 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Starting: *Stage 6 outline + confirm + write* (6/8)

Stage 5 written. 

---

## Stage 6 — Business Logic Outline

### Proposed service-level orchestration sequences:

**`ReviewService.submitReview(userId, request)`**
1. Load `ReviewEligibility` by `(userId, eventId)` → assert `status = UNLOCKED` → else throw `ReviewNotEligibleException`
2. Assert no existing `Review` for `(userId, eventId)` → else `ReviewAlreadySubmittedException`
3. Call `AttendanceVerificationService.verify(userId, eventId)` → get `AttendanceVerificationStatus`
4. Persist `Review(status=SUBMITTED)` → immediately update to `PENDING_MODERATION` in same `@Transactional`
5. Fire `@Async` event to trigger `ModerationService.triggerAutoModeration(reviewId)`
6. Return `ReviewSubmitResponse`

**`AttendanceVerificationService.verify(userId, eventId)`**
1. REST GET to payments-ticketing → assert `CONFIRMED` booking; if fails → throw `ReviewNotEligibleException`
2. REST GET to discovery-catalog → fetch `ebEventId` (nullable)
3. If `ebEventId` present: call `EbAttendeeService.getAttendeesByEvent(orgToken, ebEventId)` → filter by user email
4. Apply status resolution logic (EB_VERIFIED / SELF_REPORTED / EB_UNAVAILABLE)

**`ModerationService.triggerAutoModeration(reviewId)`** (async)
1. Load `Review`, build `inputText = title + " " + body`
2. Create `ModerationRecord(method=AUTO, decision=PENDING)`
3. Call `OpenAiModerationService.moderate(inputText)`
4. On success: update `ModerationRecord.decision`, update `Review.status` (APPROVED → PUBLISHED or REJECTED)
5. If PUBLISHED: publish `ReviewPublishedEvent`; invalidate Redis summary cache
6. On OpenAI failure: increment `auto_retry_count`, set `retry_after` with backoff, leave `PENDING_MODERATION`

**`ModerationService.retryPendingModerations()`** (scheduled — `@Scheduled(fixedDelay=30000)`)
1. Query `moderation_records WHERE decision='PENDING' AND retry_after <= NOW() AND auto_retry_count < 3`
2. For each: repeat auto-moderation attempt; on 3rd failure, log and leave for manual queue

**`ModerationService.applyManualDecision(adminId, reviewId, decision, reason)`**
1. Load `Review` → assert `status = PENDING_MODERATION` → else `ReviewAlreadyModeratedException`
2. Create `ModerationRecord(method=MANUAL, moderatorId=adminId, decision, reason, decidedAt=now)`
3. Transition `Review.status` accordingly; publish `ReviewPublishedEvent` on APPROVE

**`ReviewRatingSummaryService.getSummary(eventId)`**
1. Try Redis GET `engagement:review:summary:{eventId}` → deserialize if hit
2. On miss: `SELECT AVG(rating), COUNT(*), COUNT(CASE rating WHEN n THEN 1 END)... WHERE event_id=? AND status='PUBLISHED'`
3. Cache result with TTL 5 min; return

---

Confirm this business logic outline or request changes, then I'll write the full Stage 6.  

Asked a question (Stage 6 OK?)

Searched for text `PUT /api/v1/admin/engagement/reviews/*/moderate`, 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md#778-778), lines 778 to 789

Replacing 4 lines with 186 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Starting: *Stage 7 outline + confirm + write* (7/8)

Stage 6 written. 

---

## Stage 7 — Error Handling Outline

### Proposed exception catalog and HTTP mappings

| Exception | HTTP | Error Code | When Thrown |
|---|---|---|---|
| `ReviewNotEligibleException` | 403 | `REVIEW_NOT_ELIGIBLE` | No UNLOCKED eligibility, booking not CONFIRMED |
| `ReviewNotEligibleException` | 403 | `REVIEW_WINDOW_CLOSED` | Eligibility expired past window |
| `ReviewAlreadySubmittedException` | 409 | `REVIEW_ALREADY_SUBMITTED` | `(userId, eventId)` pair already has a review |
| `ReviewAlreadyModeratedException` | 409 | `REVIEW_ALREADY_MODERATED` | Admin tries to moderate PUBLISHED/REJECTED review |
| `ReviewNotFoundException` | 404 | `REVIEW_NOT_FOUND` | reviewId not found |
| Standard `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` | Bean validation failures (rating OOB, blank fields) |

### External failure handling (non-exceptions to user)

| Scenario | Handling |
|---|---|
| payments-ticketing booking check fails / timeout | Block submission → `403 REVIEW_NOT_ELIGIBLE` |
| discovery-catalog eb-metadata timeout | Skip EB check → `ATTENDANCE_EB_UNAVAILABLE` (submission proceeds) |
| Eventbrite `EbAttendeeService` failure | Continue with `ATTENDANCE_EB_UNAVAILABLE` (never propagate to user) |
| OpenAI Moderation failure (single attempt) | Retry with backoff (max 3 attempts); leave `PENDING_MODERATION` if all fail |
| identity display-name timeout on read | Return `"Anonymous"` display name; 200 response unchanged |
| Redis cache failure on summary | Fall through to DB query; return live result without caching |

---

Confirm this error handling outline or request changes, then I'll write the full Stage 7.   

Asked a question (Stage 7 OK?)

Searched for text `Redis cache failure on summary`, no results

Searched for text `Redis SET with TTL 5 minutes`, 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md#958-958), lines 958 to 970

Replacing 5 lines with 126 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Starting: *Stage 8 outline + confirm + write* (8/8)

Stage 7 written.

---

## Stage 8 — Test Plan Outline

### Proposed test layers (following TESTING_GUIDE.md 4-layer structure)

**Layer 1 — `domain/` (Unit Tests, no Spring context)**
- `ReviewTest` — status transition guards (e.g., can't publish from SUBMITTED without APPROVED step)
- `ReviewEligibilityTest` — eligibility rules (REVOKED/EXPIRED can't unlock)

**Layer 2 — `service/` (Mockito, unit — all dependencies mocked)**
- `ReviewServiceTest` — happy path submit, ineligible guard, duplicate guard, eligibility window expiry
- `AttendanceVerificationServiceTest` — EB_VERIFIED, SELF_REPORTED, EB_UNAVAILABLE, booking check hard-block
- `ModerationServiceTest` — auto-mod approved, auto-mod rejected, API failure retry schedule, manual approve/reject, retries exhausted fallback
- `ReviewRatingSummaryServiceTest` — cache hit, cache miss (DB query), cache invalidation on publish

**Layer 3 — `api/` (`@WebMvcTest`)**
- `ReviewControllerTest` — POST submit (201), 403 ineligible, 409 duplicate, 400 bad rating
- `AdminModerationControllerTest` — GET queue (200 + pagination), PUT moderate approve (200), PUT moderate reject with reason (200), 409 already moderated

**Layer 4 — `arch/` (ArchUnit)**
- `engagement` classes do not import from payments-ticketing, booking-inventory, identity, promotions, scheduling, or discovery-catalog packages
- All Eventbrite calls originate only via `shared.eventbrite`
- All OpenAI calls originate only via `shared.openai`
- No `@ControllerAdvice` in `engagement` package

---

Confirm the test plan outline or request changes, then I'll write the full Stage 8.    

Asked a question (Stage 8 OK?)

Searched for text `reconciliation endpoint`, 1 result

Read [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md#1078-1078), lines 1078 to 1090

Replacing 4 lines with 233 lines in [](file:///home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md)


```

```


Completed: *Stage 8 outline + confirm + write* (8/8)

Let me do a final check on the file to confirm it's complete. 

Ran terminal command:  wc -l /home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md && grep "^## Stage" /home/dell/Multi-City-Event-Booking/POQ/Engagement/SPEC_8.md

SPEC_8.md is complete — 1320 lines covering all 8 stages.

**Summary of what was specced:**

| Stage | Content |
|---|---|
| 1 — Requirements | 7 areas: eligibility gate, attendance verification (EB cross-check), submission, auto-moderation, manual override, read APIs, arch rules |
| 2 — Domain Model | 3 entities (`ReviewEligibility`, `Review`, `ModerationRecord`), 5 enums, `ReviewRatingSummary` read model, lifecycle diagram |
| 3 — Architecture | Full `engagement/` file layout, shared additions needed (`OpenAiModerationService`), inter-module REST calls, events consumed/published, Redis keys |
| 4 — DB Schema | V38–V40 Flyway migrations with DDL, constraints, and partial indexes |
| 5 — API Design | 6 endpoints with full request/response shapes, error codes, security config |
| 6 — Business Logic | 8 service orchestration sequences including async moderation, retry scheduler, manual override, cache-aside pattern |
| 7 — Error Handling | Exception catalog, per-dependency failure policies (EB advisory, booking check mandatory, OpenAI retry), concurrent submission guard |
| 8 — Tests | Domain unit tests, 4 service Mockito test classes, 2 `@WebMvcTest` classes, ArchUnit rules (5 rules) |

**Open Questions** are documented at the end — the most critical is resolving how `eventId` reaches `BookingConfirmedEventListener` (recommended: add `eventId` field to `BookingConfirmedEvent`).

Made changes.