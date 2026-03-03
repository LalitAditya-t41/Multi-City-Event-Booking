# Build Mock Eventbrite API Service — FastAPI Edition

## Executive Brief

Build a fake/mock service for the Eventbrite API. Use Python with FastAPI and SQLite as an in-memory persistent storage backend. It should run locally as a drop-in replacement for the real Eventbrite API during development and testing of the modular monolith.

**Reference:** `docs/eventbriteapiv3public.apib` (provided in repo — treat as the authoritative spec)

**Match exactly:** endpoint paths, request/response schemas, auth pattern (Bearer token), error format, and status lifecycle from the official documentation.

---

## Context: The 7-Module Platform

This entertainment ticket booking platform (Spring Boot 3.5.11, Java 21) is organized as a **Modular Monolith** with 7 domain modules + shared infrastructure:

| Module | Owns | Eventbrite ACL Facades Used |
|---|---|---|
| `discovery-catalog` | Events, Venues, Cities, catalog sync | `EbEventSyncService`, `EbVenueService` |
| `scheduling` | Show slots, conflict validation, time config | `EbEventSyncService`, `EbVenueService`, `EbScheduleService`, `EbTicketService`, `EbCapacityService` |
| `identity` | Users, JWT, profiles, preferences, wallet | `EbOrderService`, `EbAttendeeService` (post-purchase only) |
| `booking-inventory` | Seats, SeatMap, Cart, Pricing, Seat Lock State Machine | `EbTicketService`, `EbCapacityService` |
| `payments-ticketing` | Bookings, Payments, E-Tickets, Cancellations, Refunds, Wallet | `EbOrderService`, `EbAttendeeService`, `EbRefundService` |
| `promotions` | Coupons, Promotions, Eligibility rules | `EbDiscountSyncService` |
| `engagement` | Reviews, Moderation, AI Chatbot, RAG pipeline | `EbEventSyncService`, `EbAttendeeService` |

**shared/** — all external integrations (Eventbrite ACL), JWT, base classes, common DTOs.  
**admin/** — thin orchestrator only. No entities. Reads from all modules.  
**app/** — entry point only. `@SpringBootApplication`. Zero business logic.

---

## Why This Mock Exists

The 10 ACL facades in `shared/eventbrite/service/` are the **ONLY** callers of Eventbrite in the system. The mock must simulate all 10 Eventbrite facades so each module can be developed and tested in isolation without hitting the real API.

---

## 10 Eventbrite ACL Facades → Endpoints Map (What to Mock)

### 1. EbEventSyncService  
**Used by:** discovery-catalog, scheduling, engagement  
**Owns:** Event CRUD, publish/unpublish, catalog search, sync integrity

Endpoints:
```
POST   /v3/organizations/{org_id}/events/   Create event (org-scoped)
GET    /v3/events/{event_id}/               Get event by ID
POST   /v3/events/{event_id}/               Update event
DELETE /v3/events/{event_id}/               Delete event (DRAFT only)
GET    /v3/events/search/                   Search by city + changed_since (deprecated, for legacy support)
GET    /v3/organizations/{org_id}/events/   List org events (for catalog sync)
POST   /v3/events/{event_id}/publish/       Publish event → LIVE
POST   /v3/events/{event_id}/unpublish/     Unpublish event → DRAFT
POST   /v3/events/{event_id}/cancel/        Cancel event → CANCELLED (triggers bulk refunds)
POST   /v3/events/{event_id}/copy/          Duplicate event template
```

---

### 2. EbVenueService  
**Used by:** discovery-catalog, scheduling  
**Owns:** Venue reference and listing (read-only)

**⚠️ NOTE:** Eventbrite API does NOT expose venue creation/update endpoints. Venues can only be managed through the Eventbrite Dashboard. Mock service:
- Returns pre-populated venue list only
- Does NOT support venue creation or updates

Endpoints:
```
GET    /v3/venues/{venue_id}/events/        List events at a venue (read-only)
```

---

### 3. EbScheduleService  
**Used by:** scheduling  
**Owns:** Recurring event schedules and series info

Endpoints:
```
POST   /v3/events/{event_id}/schedules/     Create recurring schedule (add occurrences)
GET    /v3/series/{event_series_id}/        Get series parent info
GET    /v3/series/{event_series_id}/events/ List all occurrences in series
```

---

### 4. EbTicketService  
**Used by:** scheduling, booking-inventory  
**Owns:** Ticket class CRUD, availability tracking

Endpoints:
```
POST   /v3/events/{event_id}/ticket_classes/           Create ticket class
GET    /v3/events/{event_id}/ticket_classes/           List ticket classes
POST   /v3/events/{event_id}/ticket_classes/{id}/      Update ticket class
DELETE /v3/events/{event_id}/ticket_classes/{id}/      Delete ticket class
GET    /v3/events/{event_id}/ticket_classes/for_sale/  Check availability count
```

---

### 5. EbCapacityService  
**Used by:** scheduling, booking-inventory  
**Owns:** Event capacity tiers and seat maps

Endpoints:
```
GET    /v3/events/{event_id}/capacity_tier/            Get event capacity tier
POST   /v3/events/{event_id}/capacity_tier/            Update event capacity tier
GET    /v3/organizations/{org_id}/seatmaps/            List org seat maps (read-only)
POST   /v3/events/{event_id}/seatmaps/                 Attach seat map to event
```

---

### 6. EbOrderService  
**Used by:** payments-ticketing, identity  
**Owns:** Order read-only retrieval (no creation via API)

Endpoints:
```
GET    /v3/orders/{order_id}/               Get order by ID
GET    /v3/organizations/{org_id}/orders/   List org orders
GET    /v3/events/{event_id}/orders/        List event orders
```

**⚠️ CRITICAL:** Eventbrite API does NOT expose order creation. Orders are created exclusively by the JS SDK Checkout Widget (`eb_widgets.js`). The mock service does NOT simulate order creation via POST — this is a constraint of the real API. Test order creation through the Widget only.

---

### 7. EbAttendeeService  
**Used by:** payments-ticketing, engagement, identity  
**Owns:** Attendee verification and listing (read-only)

Endpoints:
```
GET    /v3/events/{event_id}/attendees/           List event attendees
GET    /v3/events/{event_id}/attendees/{attendee_id}/  Get attendee by ID
```

**⚠️ NOTE:** Eventbrite API does NOT expose attendee creation. Attendees are created automatically by the JS SDK Checkout Widget when orders are placed. The mock service reads attendee records only.

---

### 8. EbDiscountSyncService  
**Used by:** promotions  
**Owns:** Discount code CRUD and usage tracking

Endpoints:
```
POST   /v3/organizations/{org_id}/discounts/  Create discount (org-level, can be applied to events)
GET    /v3/organizations/{org_id}/discounts/  List org discounts
GET    /v3/discounts/{discount_id}/           Get discount by ID
POST   /v3/discounts/{discount_id}/           Update discount (mark used, adjust limits, etc)
DELETE /v3/discounts/{discount_id}/           Delete discount
```

**Note:** Discounts are created at org level, not event level. An org discount code can be applied to multiple events.

---

### 9. EbRefundService  
**Used by:** payments-ticketing  
**Owns:** Refund status tracking (read-only)

**⚠️ CRITICAL:** Eventbrite API does NOT provide refund submission or listing endpoints. Refund status is embedded in the order object only. To read refund status:

Endpoints:
```
GET    /v3/orders/{order_id}/               Read order; check refund_request field for status
```

**Workaround:** Read the `refund_request` field on the order object. The mock service will simulate refund status changes on order reads based on time-based rules.

---

### 10. EbWebhookService  
**Used by:** shared (inbound dispatcher)  
**Owns:** Webhook delivery simulation (registration not available via API)

**⚠️ NOTE:** Eventbrite API does NOT expose webhook management endpoints. Webhooks are registered via the Eventbrite Dashboard only, not via API. The mock service simulates webhook delivery only:

**Inbound (from mock to host app):**
```
POST   /api/webhooks/eventbrite                 Webhook arrival endpoint (on host monolith)
```

**Mock-only configuration:**
- Webhooks are triggered by status transitions (event.published, order.placed, etc)
- Target URL configured via `/mock/config` endpoint
- Retry logic: 3 attempts with exponential backoff (1s, 2s, 4s)

---

## Functional Requirement Integration Points

The mock must map to these FRs from PRODUCT.md:

| FR | Module | Mock Contracts |
|---|---|---|
| FR1 | discovery-catalog | Event read/list, catalog search (venue reads only) |
| FR2 | scheduling | Event CRUD + lifecycle (DRAFT → LIVE → CANCELLED), ticket classes, capacity, recurring schedules |
| FR3 | identity | User ↔ Eventbrite link post-purchase (order email match) |
| FR4 | booking-inventory | Ticket availability check, seat lock (internal via Redis) |
| FR5 | payments-ticketing | Order reads (post-widget), confirmation, attendee reads |
| FR6 | payments-ticketing | Refund status tracking (no submission API) |
| FR7 | promotions | Discount code CRUD (org-level), usage count sync |
| FR8 | engagement | Attendee verification for review eligibility |
| FR10 | booking-inventory | Concurrent seat booking serialization (SOLD_OUT race) |

---

## Authentication

**Pattern:** `Authorization: Bearer <any-non-empty-token>`

- **Accept:** Any non-empty token value. No actual validation.
- **Reject (401):** Missing or empty Authorization header.

Error:
```json
{
  "error": "NOT_AUTH",
  "error_description": "Authorization header missing or token empty.",
  "status_code": 401
}
```

---

## Status Lifecycles — Simulate Async Transitions

### Event Status Flow
```
DRAFT → LIVE (on publish action)
  ↓
CANCELLED (on cancel action)
```

### Order Status Flow
```
PENDING → PLACED (after transitionDelayMs)
  ↓
CONFIRMED (after another transitionDelayMs, or from webhook callback)

OR (on simulated failure):
PENDING → ABANDONED
```

### Attendee Status Flow
```
NOT_ATTENDING → ATTENDING (after transitionDelayMs)
```

### Refund Status Flow
```
PENDING → PROCESSING (after transitionDelayMs)
  ↓
COMPLETED (after another transitionDelayMs)

OR (on failure):
PROCESSING → FAILED
```

**Implementation:** Use async background tasks (APScheduler or FastAPI background tasks) to transition states at configurable intervals. Trigger webhook delivery on each transition.

---

## Webhook Delivery Simulation

When a status transition occurs and `webhookTargetUrl` is configured, POST payload to that URL.

**Webhook Envelope:**
```json
{
  "action": "event.published" | "order.placed" | "order.confirmed" | "attendee.updated" | "refund.completed",
  "api_url": "https://www.eventbriteapi.com/v3/events/123/",
  "data": {
    "object": { "id": "...", "status": "...", ... }
  },
  "config": {
    "action": "...",
    "webhook_id": "mock-webhook-123",
    "endpoint_url": "http://localhost:8080/api/webhooks/eventbrite"
  }
}
```

**Retry Logic:**
- Target unreachable? Retry 3 times: 1s, 2s, 4s delays.
- Success → log as DELIVERED.
- Failure after 3 attempts → log as FAILED (do NOT block the status transition).
- **Decouple:** Transition always succeeds; webhook delivery is background async.

---

## Error Format (All Errors)

```json
{
  "error": "ERROR_CODE_STRING",
  "error_description": "Human readable message",
  "status_code": <HTTP status int>
}
```

### Error Codes to Implement

| Code | HTTP | Scenario |
|------|------|----------|
| NOT_AUTH | 401 | Missing or empty Bearer token |
| NOT_FOUND | 404 | Resource does not exist |
| ARGUMENTS_ERROR | 400 | Missing required field or invalid value |
| ALREADY_EXISTS | 409 | Duplicate creation (same code, same event name+time, etc) |
| SOLD_OUT | 400 | Ticket class quantity exhausted |

---

## Critical Edge Cases to Handle

### 1. Concurrent Booking Race (Ticket Capacity → SOLD_OUT)

**Scenario:** Two simultaneous POST /v3/events/{event_id}/orders/ requests for a ticket class with 1 unit remaining.

**Implementation:**
- Per-ticket-class `threading.Lock` or `asyncio.Lock` in `ticket_service.py`
- Availability check + decrement are atomic within the lock
- First request succeeds → order PENDING
- Second request sees quantity 0 after lock released → return 400 SOLD_OUT immediately

**Test:** Concurrent test using `concurrent.futures.ThreadPoolExecutor` with 2 threads (or `asyncio.gather`) firing simultaneous requests. Assert one succeeds (201) and one fails (400 SOLD_OUT).

---

### 2. Webhook Target Unreachable

**Scenario:** POST webhook to configured target URL, target is not responding.

**Behavior:**
- Retry 3 times with exponential backoff: 1s, 2s, 4s.
- If all 3 fail: mark as FAILED in webhook log.
- Do NOT crash or block the status transition.
- Status transitions always complete; webhook is fire-and-forget.

---

### 3. Changed_Since Delta Filter for Catalog Sync

**Scenario:** `GET /v3/events/search/?changed_since=2026-04-15T10:00:00Z`

**Behavior:**
- Return only events where `changed >= changed_since` timestamp.
- Used for incremental catalog sync — only pull recently modified events.

---

## Rate Limits — Enforcement & Override

The mock enforces Eventbrite's exact rate limit structure:

| Endpoint Category | Limit | Window | Override via /mock/config |
|---|---|---|---|
| **General API** | 2,000 calls | per hour | `general_rate_limit` |
| **Daily API** | 48,000 calls | per day | `daily_rate_limit` |
| **Event Actions** (Create, Copy, Publish, Schedule) | 200 calls | per hour | `event_action_rate_limit` |

### Rate Limit Response Headers
All responses include:
```
X-RateLimit-Limit: 2000
X-RateLimit-Remaining: 1999
X-RateLimit-Reset: 1712150400  (Unix timestamp)
```

### Rate Limit Exceeded Response
**HTTP 429 Too Many Requests**
```json
{
  "error": "RATE_LIMITED",
  "error_description": "Rate limit exceeded: 2000 calls/hour. Retry after 3600 seconds.",
  "status_code": 429,
  "retry_after": 3600
}
```

### Rate Limit Tracking
- Per-token tracking (Authorization header value)
- In-memory store with TTL-based cleanup (hour/day windows)
- Optional Redis backend for distributed scenarios (future)
- Dashboard shows current usage per token

---

## Configuration (environment variables + config.py)

```python
# config.py
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    app_name: str = "mock-eventbrite-api"
    database_url: str = "sqlite:///./mock_eventbrite.db"  # SQLite file-based or in-memory
    transition_delay_ms: int = 2000
    webhook_target_url: str = None
    failure_rates: dict = {
        "order": 0.0,
        "refund": 0.0,
        "webhook": 0.0
    }
    server_port: int = 8888
    server_host: str = "0.0.0.0"
    
    # Rate Limit Configuration
    general_rate_limit: int = 2000  # calls/hour
    daily_rate_limit: int = 48000   # calls/day
    event_action_rate_limit: int = 200  # calls/hour for Create/Copy/Publish/Schedule
    
    class Config:
        env_file = ".env"

settings = Settings()
```

**.env file:**
```
TRANSITION_DELAY_MS=2000
WEBHOOK_TARGET_URL=http://localhost:8080/api/webhooks/eventbrite
GENERAL_RATE_LIMIT=2000
DAILY_RATE_LIMIT=48000
EVENT_ACTION_RATE_LIMIT=200
```

---

## Mock Control Endpoints

### POST /mock/config
Configure mock behavior at runtime (rate limits, delays, webhook URL, failure rates).

```json
{
  "transitionDelayMs": 2000,
  "failureRates": {
    "order": 0.0,
    "refund": 0.0,
    "webhook": 0.1
  },
  "webhookTargetUrl": "http://localhost:8080/api/webhooks/eventbrite",
  "generalRateLimit": 2000,
  "dailyRateLimit": 48000,
  "eventActionRateLimit": 200
}
```

Response: HTTP 200 with updated config and current rate limit usage per token:
```json
{
  "config": {
    "transitionDelayMs": 2000,
    "webhookTargetUrl": "...",
    "generalRateLimit": 2000,
    "dailyRateLimit": 48000,
    "eventActionRateLimit": 200,
    "failureRates": {...}
  },
  "rateLimitStatus": {
    "tokens": {
      "mock-token": {
        "hourlyUsage": 150,
        "dailyUsage": 2400,
        "eventActionUsage": 25,
        "hourlyRemaining": 1850,
        "dailyRemaining": 45600,
        "eventActionRemaining": 175
      }
    }
  }
}
```

---

### POST /mock/reset
Wipe all SQLite state. Reset config to defaults.

Response: HTTP 200
```json
{
  "message": "Mock state cleared.",
  "defaultConfig": { ... }
}
```

---

### GET /mock/dashboard
Full debug view of all mock state, including rate limit usage.

Response: HTTP 200
```json
{
  "timestamp": "2026-04-15T10:30:00Z",
  "events": [...],
  "orders": [...],
  "attendees": [...],
  "ticketClasses": [...],
  "discounts": [...],
  "refunds": [...],
  "pendingTransitions": [...],
  "webhookLog": [
    {
      "targetUrl": "...",
      "action": "...",
      "status": "DELIVERED|FAILED",
      "attempts": 2,
      "lastAttemptAt": "..."
    }
  ],
  "config": {
    "transitionDelayMs": 2000,
    "generalRateLimit": 2000,
    "dailyRateLimit": 48000,
    "eventActionRateLimit": 200,
    "webhookTargetUrl": "...",
    "failureRates": {...}
  },
  "rateLimitStatus": {
    "tokens": {
      "mock-token-1": {
        "hourlyUsage": 150,
        "dailyUsage": 2400,
        "eventActionUsage": 25,
        "hourlyRemaining": 1850,
        "dailyRemaining": 45600,
        "eventActionRemaining": 175,
        "hourlyResetAt": "2026-04-15T11:30:00Z",
        "dailyResetAt": "2026-04-16T10:30:00Z"
      },
      "mock-token-2": {
        "hourlyUsage": 500,
        "dailyUsage": 8000,
        "eventActionUsage": 80,
        "hourlyRemaining": 1500,
        "dailyRemaining": 40000,
        "eventActionRemaining": 120,
        "hourlyResetAt": "2026-04-15T11:35:00Z",
        "dailyResetAt": "2026-04-16T10:35:00Z"
      }
    }
  }
}
```

---

## Project Structure

```
mock-eventbrite-api/
├── app/
│   ├── __init__.py
│   ├── main.py                              ← FastAPI app entry point
│   ├── config.py                            ← Settings, env vars
│   ├── database.py                          ← SQLAlchemy setup, session management
│   ├── api/
│   │   ├── __init__.py
│   │   ├── routes/
│   │   │   ├── __init__.py
│   │   │   ├── events.py                    ← Event endpoints
│   │   │   ├── venues.py                    ← Venue endpoints
│   │   │   ├── schedules.py                 ← Schedule endpoints
│   │   │   ├── ticket_classes.py            ← Ticket class endpoints
│   │   │   ├── capacity.py                  ← Capacity tier endpoints
│   │   │   ├── orders.py                    ← Order endpoints
│   │   │   ├── attendees.py                 ← Attendee endpoints
│   │   │   ├── discounts.py                 ← Discount endpoints
│   │   │   ├── refunds.py                   ← Refund endpoints
│   │   │   ├── webhooks.py                  ← Webhook endpoints
│   │   │   └── mock_admin.py                ← /mock/config, /mock/reset, /mock/dashboard
│   │   └── dependencies.py                  ← Auth, shared deps, DB session
│   ├── models/
│   │   ├── __init__.py
│   │   ├── event.py                         ← Event SQLAlchemy model
│   │   ├── venue.py
│   │   ├── order.py
│   │   ├── attendee.py
│   │   ├── ticket_class.py
│   │   ├── discount.py
│   │   ├── refund.py
│   │   ├── webhook_log.py
│   │   └── enums.py                         ← EventStatus, OrderStatus, etc.
│   ├── schemas/
│   │   ├── __init__.py
│   │   ├── event.py                         ← Pydantic request/response schemas
│   │   ├── order.py
│   │   ├── attendee.py
│   │   ├── ticket_class.py
│   │   ├── discount.py
│   │   ├── refund.py
│   │   └── error.py                         ← Eventbrite error envelope
│   ├── services/
│   │   ├── __init__.py
│   │   ├── event_service.py                 ← Business logic for events
│   │   ├── order_service.py
│   │   ├── attendee_service.py
│   │   ├── ticket_class_service.py
│   │   ├── discount_service.py
│   │   ├── refund_service.py
│   │   ├── status_transition_engine.py      ← Async status progression
│   │   ├── webhook_dispatcher.py            ← Outbound webhook delivery + retry
│   │   └── rate_limiter.py                  ← Rate limit tracking + enforcement
│   ├── exception/
│   │   ├── __init__.py
│   │   ├── exception_handlers.py            ← Global exception handler middleware
│   │   └── exceptions.py                    ← Custom exception classes (RateLimitExceeded)
│   └── utils/
│       ├── __init__.py
│       ├── auth.py                          ← Bearer token validation
│       └── rate_limit_middleware.py         ← Rate limit checking middleware
├── tests/
│   ├── __init__.py
│   ├── conftest.py                          ← Pytest fixtures, test DB setup
│   ├── unit/
│   │   ├── __init__.py
│   │   ├── test_event_service.py
│   │   ├── test_order_service.py
│   │   ├── test_ticket_class_service.py
│   │   └── ...
│   └── integration/
│       ├── __init__.py
│       ├── test_events_api.py               ← Full endpoint tests
│       ├── test_orders_api.py
│       ├── test_concurrent_booking.py       ← Concurrency race condition test
│       ├── test_webhook_delivery.py
│       └── test_mock_admin.py
├── requirements.txt                         ← Dependencies
├── .env.example                             ← Example env file
├── Dockerfile                               ← Optional containerization
├── docker-compose.yml
├── README.md                                ← Setup + run instructions
└── pytest.ini                               ← Test configuration
```

---

## Tech Stack

- **Python 3.10+**
- **FastAPI** — async REST framework
- **SQLAlchemy** — ORM (SQLite backend)
- **Pydantic** — request/response validation + settings
- **httpx** — async HTTP client (for webhook delivery + retry)
- **APScheduler** — background task scheduling (for status transitions)
- **pytest** — testing framework
- **pytest-asyncio** — async test support

**Dependencies (requirements.txt):**
```
fastapi==0.104.1
uvicorn==0.24.0
sqlalchemy==2.0.23
alembic==1.12.1
pydantic==2.5.0
pydantic-settings==2.1.0
httpx==0.25.1
apscheduler==3.10.4
pytest==7.4.3
pytest-asyncio==0.21.1
python-multipart==0.0.6
```

---

## Switching to Real Eventbrite (Zero Code Change)

In the host monolith's `application.yml`:

```yaml
eventbrite:
  baseUrl: ${EVENTBRITE_BASE_URL:http://localhost:8888}
  apiKey: ${EVENTBRITE_API_KEY:mock-token}
```

On environment change:
```bash
export EVENTBRITE_BASE_URL=https://www.eventbriteapi.com/v3
export EVENTBRITE_API_KEY=<your-real-private-token>
```

The 10 ACL facades in `shared/eventbrite/service/` make identical REST calls regardless of whether the endpoint is the mock or real Eventbrite. **Zero code change required.**

---

## Rate Limiting Implementation Details

### Architecture

**RateLimiter Service** (`services/rate_limiter.py`):
- Tracks API calls per token across three dimensions: hourly, daily, event-action (hourly)
- Stores usage in in-memory store (dict with token keys, hourly/daily windows)
- Each token has separate counters that reset on interval expiration
- On rate limit exceeded: raises `RateLimitExceeded` exception → middleware returns 429

**Middleware** (`utils/rate_limit_middleware.py`):
- Intercepts every request before routing
- Extracts bearer token from Authorization header
- Calls RateLimiter to check and increment usage
- Sets response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`
- Catches `RateLimitExceeded` exception → returns 429 with retry-after

**Event Action Detection**:
- POST /v3/events/ → Create Event (200/hour limit)
- POST /v3/events/{id}/ → Copy Event (200/hour limit)
- POST /v3/events/{id}/publish/ → Publish Event (200/hour limit)
- POST /v3/events/{id}/schedules/ → Create Schedule (200/hour limit)
- All other endpoints → General rate limit (2,000/hour)

### Data Structure

```python
# In-memory store: token -> {hourly: {...}, daily: {...}, event_action: {...}}
{
    "mock-token-1": {
        "hourly": {
            "usage": 150,
            "reset_at": 1712150400,  # Unix timestamp
            "limit": 2000
        },
        "daily": {
            "usage": 2400,
            "reset_at": 1712236800,
            "limit": 48000
        },
        "event_action": {
            "usage": 25,
            "reset_at": 1712150400,
            "limit": 200
        }
    }
}
```

### Reset Strategy
- Windows reset based on when token was first used:
  - Hourly: 60 min from first request
  - Daily: 24 hours from first request
  - Event Action: 60 min from first request (same as hourly)
- On reset, usage counter resets to 0, `reset_at` timestamp advances

### Testing Rate Limits
- Test exceeding hourly limit → assert 429 response
- Test remaining count decreases → assert X-RateLimit-Remaining header
- Test daily limit separate from hourly → different reset times
- Test event action endpoints use 200/hour limit → assert separate counter
- Test multiple tokens tracked independently → token A limit doesn't affect token B
- Test `/mock/config` can override limits → change limit, verify new enforcement

---

### Test Coverage

| Layer | Scope |
|---|---|
| Unit Tests | Individual service methods (happy path, error conditions) |
| Integration Tests | Full request/response cycles, auth rejection, duplicate codes, 404s, 409s, SOLD_OUT race |
| Concurrency Tests | Two threads simultaneously POST orders for 1-unit ticket class; assert one succeeds, one gets SOLD_OUT |
| Status Lifecycle Tests | Verify DRAFT → LIVE → CANCELLED transitions fire in correct order |
| Webhook Tests | Delivery logged, retry fires on target unreachable, dashboard reflects current state |
| Config Tests | /mock/config tunes delay, /mock/reset wipes state, /mock/dashboard is accurate |
| **Rate Limit Tests** | Hourly/daily/event-action limits enforced; 429 response; X-RateLimit-* headers; independent per-token tracking; /mock/config override |

---

## Deliverables

1. **requirements.txt** — All Python dependencies
2. **Full implementation** — All 10 controllers, 9 services, models, schemas, exception handlers, rate limiter
3. **Comprehensive test suite** — 50+ tests covering all endpoints, edge cases, concurrency, **rate limiting**
4. **README.md** — Setup, run, integration, and rate limit configuration instructions
5. **Integration example** — Sample Python/Java code showing how `shared/eventbrite/service/EbEventSyncService` calls the mock
6. **.env.example** — Example configuration with rate limit settings
7. **Rate Limit Documentation** — Usage examples, override scenarios, dashboard interpretation

---

## Run Instructions

### Setup
```bash
cd mock-eventbrite-api
python -m venv venv
source venv/bin/activate  # or `venv\Scripts\activate` on Windows
pip install -r requirements.txt
```

### Database Setup
```bash
alembic upgrade head  # Apply migrations (or skip if using auto-create)
```

### Run Mock Server
```bash
uvicorn app.main:app --host 0.0.0.0 --port 8888 --reload
```

Mock is now live at `http://localhost:8888`.

### Run Tests
```bash
pytest tests/ -v
pytest tests/integration/test_concurrent_booking.py -v  # Concurrency test
```

### View Mock State
```bash
curl http://localhost:8888/mock/dashboard
```

---

**Status:** Ready for implementation.
