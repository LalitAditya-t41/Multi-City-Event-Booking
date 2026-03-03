I'll rewrite the mock Eventbrite API prompt to match the updated PRODUCT.md:

---

# Build Mock Eventbrite API Service

## Executive Brief

Build a fake/mock service for the Eventbrite API. Use Java with Spring Boot and an embedded H2 database as in-memory persistent storage. It should run locally as a drop-in replacement for the real Eventbrite API during development and testing of the modular monolith.

**Reference:** eventbriteapiv3public.apib (provided in repo — treat as the authoritative spec)

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

The 6 ACL facades in `shared/eventbrite/service/` are the **ONLY** callers of Eventbrite in the system. The mock must simulate all 10 Eventbrite facades so each module can be developed and tested in isolation without hitting the real API.

---

## 10 Eventbrite ACL Facades → Endpoints Map (What to Mock)

### 1. EbEventSyncService  
**Used by:** discovery-catalog, scheduling, engagement  
**Owns:** Event CRUD, publish/unpublish, catalog search, sync integrity

Endpoints:
```
POST   /v3/events/                          Create event
GET    /v3/events/{id}/                     Get event by ID
POST   /v3/events/{id}/                     Update event
DELETE /v3/events/{id}/                     Delete event (DRAFT only)
GET    /v3/events/search/                   Search by city + changed_since (for incremental sync)
POST   /v3/events/{id}/publish/             Publish event → LIVE
POST   /v3/events/{id}/unpublish/           Unpublish event → DRAFT
POST   /v3/events/{id}/cancel/              Cancel event → CANCELLED (triggers bulk refunds)
POST   /v3/events/{id}/copy/                Duplicate event template
```

---

### 2. EbVenueService  
**Used by:** discovery-catalog, scheduling  
**Owns:** Venue CRUD, org venue list

Endpoints:
```
POST   /v3/organizations/{org_id}/venues/   Create venue
GET    /v3/organizations/{org_id}/venues/   List org venues
GET    /v3/venues/{id}/                     Get venue by ID
PUT    /v3/venues/{id}/                     Update venue
```

---

### 3. EbScheduleService  
**Used by:** scheduling  
**Owns:** Recurring event schedules

Endpoints:
```
POST   /v3/events/{event_id}/schedules/     Create recurring schedule
GET    /v3/events/{event_id}/series/        Get series info
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
**Owns:** Event capacity tiers, seat maps

Endpoints:
```
GET    /v3/events/{event_id}/capacity_tier/            Get capacity
POST   /v3/events/{event_id}/capacity_tier/            Set capacity
GET    /v3/events/{event_id}/seatmaps/                 List seat maps
POST   /v3/events/{event_id}/seatmaps/                 Attach seat map
```

---

### 6. EbOrderService  
**Used by:** payments-ticketing, identity  
**Owns:** Order read-only (no creation via API), order details retrieval

Endpoints:
```
POST   /v3/events/{event_id}/orders/        Create order (for mock simulation of widget behavior)
GET    /v3/orders/{id}/                     Get order by ID
GET    /v3/organizations/{org_id}/orders/   List org orders
GET    /v3/events/{event_id}/orders/        List event orders
```

**Note:** Real Eventbrite: Orders created exclusively by JS SDK Checkout Widget. Mock simulates this via POST for testing.

---

### 7. EbAttendeeService  
**Used by:** payments-ticketing, engagement, identity  
**Owns:** Attendee registration, verification, attendance check-in

Endpoints:
```
POST   /v3/events/{event_id}/attendees/           Register attendee
GET    /v3/events/{event_id}/attendees/           List event attendees
GET    /v3/events/{event_id}/attendees/{id}/      Get attendee by ID
```

---

### 8. EbDiscountSyncService  
**Used by:** promotions  
**Owns:** Discount code CRUD, usage tracking

Endpoints:
```
POST   /v3/events/{event_id}/discounts/     Create discount code
GET    /v3/events/{event_id}/discounts/     List event discounts
POST   /v3/discounts/{id}/                  Update discount (mark used, etc)
DELETE /v3/discounts/{id}/                  Delete discount
```

---

### 9. EbRefundService  
**Used by:** payments-ticketing  
**Owns:** Refund request submission, refund status read-only

Endpoints:
```
POST   /v3/orders/{order_id}/refunds/       Submit refund request
GET    /v3/orders/{order_id}/refunds/       List order refunds
GET    /v3/orders/{order_id}/refunds/{id}/  Get refund by ID
```

**Note:** Real Eventbrite: No programmatic refund submission API. Mock simulates for testing.

---

### 10. EbWebhookService  
**Used by:** shared (inbound dispatcher)  
**Owns:** Webhook registration, webhook delivery simulation

Endpoints:
```
POST   /v3/organizations/{org_id}/webhooks/     Register webhook
GET    /v3/organizations/{org_id}/webhooks/     List org webhooks
POST   /v3/webhooks/{id}/test/                  Test webhook delivery
```

**Inbound (from mock to host app):**
```
POST   /api/webhooks/eventbrite                 Webhook arrival endpoint (on host monolith)
```

---

## Functional Requirement Integration Points

The mock must map to these FRs from PRODUCT.md:

| FR | Module | Mock Contracts |
|---|---|---|
| FR1 | discovery-catalog | Event/Venue CRUD, catalog search |
| FR2 | scheduling | Event lifecycle (DRAFT → LIVE → CANCELLED), ticket classes, capacity, recurring schedules |
| FR3 | identity | User ↔ Eventbrite link post-purchase (order email match) |
| FR4 | booking-inventory | Ticket availability check, seat lock (internal via Redis) |
| FR5 | payments-ticketing | Order creation, confirmation, attendee registration |
| FR6 | payments-ticketing | Refund submission + status tracking |
| FR7 | promotions | Discount code CRUD, usage count sync |
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

**Implementation:** Use `@Scheduled` background task to transition states at configurable intervals. Trigger webhook delivery on each transition.

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
- Per-ticket-class `ReentrantLock` in `TicketClassService`
- Availability check + decrement are atomic within the lock
- First request succeeds → order PENDING
- Second request sees quantity 0 after lock released → return 400 SOLD_OUT immediately

**Test:** Concurrent test using `ExecutorService` with 2 threads firing simultaneous requests. Assert one succeeds (201) and one fails (400 SOLD_OUT).

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

## Configuration (application.yml)

```yaml
spring:
  application:
    name: mock-eventbrite-api
  datasource:
    url: jdbc:h2:mem:mockdb
    driver-class-name: org.h2.Driver
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: create-drop
  h2:
    console:
      enabled: true
      path: /h2-console

server:
  port: 8888

mock:
  eventbrite:
    transitionDelayMs: 2000
    failureRates:
      order: 0.0
      refund: 0.0
      webhook: 0.0
    webhookTargetUrl: null
```

---

## Mock Control Endpoints

### POST /mock/config
Configure mock behavior at runtime.

```json
{
  "transitionDelayMs": 2000,
  "failureRates": {
    "order": 0.0,
    "refund": 0.0,
    "webhook": 0.1
  },
  "webhookTargetUrl": "http://localhost:8080/api/webhooks/eventbrite"
}
```

Response: HTTP 200 with updated config.

---

### POST /mock/reset
Wipe all H2 state. Reset config to defaults.

Response: HTTP 200
```json
{
  "message": "Mock state cleared.",
  "defaultConfig": { ... }
}
```

---

### GET /mock/dashboard
Full debug view of all mock state.

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
  "config": { ... }
}
```

---

## Project Structure

```
mock-eventbrite-api/
├── src/main/java/com/mock/eventbrite/
│   ├── controller/
│   │   ├── EventController.java
│   │   ├── VenueController.java
│   │   ├── ScheduleController.java
│   │   ├── TicketClassController.java
│   │   ├── CapacityController.java
│   │   ├── OrderController.java
│   │   ├── AttendeeController.java
│   │   ├── DiscountController.java
│   │   ├── RefundController.java
│   │   └── MockAdminController.java
│   ├── service/
│   │   ├── EventService.java
│   │   ├── VenueService.java
│   │   ├── ScheduleService.java
│   │   ├── TicketClassService.java
│   │   ├── CapacityService.java
│   │   ├── OrderService.java
│   │   ├── AttendeeService.java
│   │   ├── DiscountService.java
│   │   ├── RefundService.java
│   │   └── StatusTransitionEngine.java
│   ├── webhook/
│   │   └── WebhookDispatcher.java
│   ├── repository/
│   ├── model/entity/
│   ├── model/dto/request/
│   ├── model/dto/response/
│   ├── model/enums/
│   ├── config/
│   └── exception/
├── src/test/java/com/mock/eventbrite/
│   ├── unit/
│   └── integration/
└── pom.xml
```

---

## Tech Stack

- Java 21
- Spring Boot 3.5.11
- Spring Web (REST controllers)
- Spring Data JPA + H2 (in-memory)
- Spring Scheduling (@EnableScheduling)
- Spring Validation (@Valid)
- Lombok
- Maven
- WebClient (for webhook delivery with retry)

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

## Testing Strategy

### Test Coverage

| Layer | Scope |
|---|---|
| Unit Tests | Individual service methods (happy path, error conditions) |
| Integration Tests (@SpringBootTest) | Full request/response cycles, auth rejection, duplicate codes, 404s, 409s, SOLD_OUT race |
| Concurrency Tests | Two threads simultaneously POST orders for 1-unit ticket class; assert one succeeds, one gets SOLD_OUT |
| Status Lifecycle Tests | Verify DRAFT → LIVE → CANCELLED transitions fire in correct order |
| Webhook Tests | Delivery logged, retry fires on target unreachable, dashboard reflects current state |
| Config Tests | /mock/config tunes delay, /mock/reset wipes state, /mock/dashboard is accurate |

---

## Deliverables

1. **pom.xml** — Maven project configuration
2. **Full implementation** — All 10 controllers, 9 services, entities, DTOs, enums, exception handler
3. **Comprehensive test suite** — 50+ tests covering all endpoints, edge cases, concurrency
4. **SPEC.md** — Complete endpoint reference with status lifecycles, error codes, webhook format
5. **Integration example** — Sample code showing how `shared/eventbrite/service/EbEventSyncService` calls the mock

---

**Status:** Ready for implementation.