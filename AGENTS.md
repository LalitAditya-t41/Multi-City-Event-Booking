# AGENTS.md — Reference Index

> Every agent working in this codebase must read the files listed below before producing
> any output. Each file governs a specific concern. Read the ones that apply to your task.

---

## Reference Files

| File | Governs |
|---|---|
| `PRODUCT.md` | The 7 modules, what each one owns, dependency order, inter-module event map, Eventbrite ACL facade names, hard architectural rules, and where new code goes. Read this before touching any module. |
| `docs/EVENTBRITE_INTEGRATION.md` | Eventbrite integration architecture: ACL facades, all FR flows (FR1–FR7 implemented), critical constraints (no user creation, no seat locking; single-order cancel/refunds are handled via `StripeRefundService`). Read before designing any Eventbrite-touching feature. |
| `docs/MODULE_STRUCTURE_TEMPLATE.md` | Canonical folder and package layout for every module. The exact location of every layer: `api/controller/`, `api/dto/`, `domain/`, `service/`, `repository/`, `mapper/`, `event/published/`, `event/listener/`, `statemachine/`, `exception/`. Anti-patterns list. Read this before writing any class. |
| `agents/SPEC_GENERATOR.md` | How to produce a `SPEC.md` for any feature. Eight confirmation-gated stages with module context and Eventbrite constraints built-in. Requirements → Domain Model → Architecture & File Structure → DB Schema → API → Business Logic → Error Handling → Tests. Read this before speccing any feature. |
| `agents/CODING_AGENT.md` | Spring Boot 3.5.11 / Java 21 coding standards. Domain behaviour, service orchestration, mapper rules, controller rules, event rules, 10 Eventbrite ACL facades, inter-module communication (REST + Spring Events), admin/ special rules, state machine, Spring AI. Read this before writing any production code. |
| `agents/TEST_AGENT.md` | Test agent responsibilities and best practices. Builds tests from `SPEC_*.md` Stage 8, executes tests, fixes test/code issues caused by behavior mismatches, and writes timestamped logs under `test_result_folder/`. Read this before writing or running tests. |
| `docs/TESTING_GUIDE.md` | Four-layer test structure: `domain/` (unit), `service/` (Mockito), `api/` (@WebMvcTest), `arch/` (ArchUnit enforcing 10 hard rules + Eventbrite constraints). All rule examples, happy path, negative path, exception patterns. Read before writing any test. |
| `mock-eventbrite-api/README.md` | **Mock Eventbrite Service:** Python FastAPI in-memory mock of Eventbrite API v3. Used for local development and testing. Exposes all 10 ACL facade endpoints without hitting production Eventbrite. See setup instructions below. |

---

## Module Quick Reference

Full detail for each module is in `PRODUCT.md`. This table is a lookup only.

| Module | Owns | External ACL Facades Used |
|---|---|---|
| `discovery-catalog` | Events, Venues, Cities, catalog sync | `EbEventSyncService`, `EbTicketService` |
| `scheduling` | Show slots, conflict validation | `EbEventSyncService` |
| `identity` | Users, JWT, profiles, preferences, wallet | — |
| `booking-inventory` | Seats, SeatMap, Cart, Pricing, Seat Lock State Machine | `EbTicketService` |
| `payments-ticketing` | Bookings, Payments, E-Tickets, Cancellations, Refunds, Wallet | `StripePaymentService`, `StripeRefundService`, `StripeWebhookHandler` |
| `promotions` | Coupons, Promotions, Eligibility rules | `EbDiscountSyncService` |
| `engagement` | Reviews, Moderation, AI Chatbot (`service/chatbot/`), RAG pipeline | `EbEventSyncService`, `EbAttendeeService` |
| `admin/` | No domain ownership. Orchestration and aggregation only. | — |
| `shared/` | All external ACL facades, JWT, base classes, common DTOs, configs | — |

---

## Hard Rules — Never Violate

Canonical numbering and wording are owned by `PRODUCT.md` Section 9. These shorthand rules apply to every task without exception.

1. No module imports another module's `@Service`, `@Entity`, or `@Repository`.
  - Exception: `admin/` MAY import module `@Service` beans for READ operations only; all writes MUST go through the owning module REST API.
2. No module calls Eventbrite, Stripe, or OpenAI HTTP directly — only through `shared/` ACL facades.
3. ACL facade names are exactly as listed below — never invent new ones.
4. Spring Events carry only primitives, IDs, enums, and value objects — never `@Entity` objects.
5. One `GlobalExceptionHandler` in `shared/common/exception/` — never `@ControllerAdvice` in a module.
6. All field mapping goes in `mapper/` using MapStruct — never inside a service or controller.
7. `admin/` owns no `@Entity` and no database table.
8. `shared/` has no dependency on any module.
9. `app/` has zero business logic.
10. Dependency direction is strictly downward — no circular imports between modules.

---

## Eventbrite ACL Facade Names (HARD RULE #3)
Read the docs/eventbriteapiv3public.apib for detail API Endpoint with schema, Response, Request and errors

All 10 facades are in `shared/eventbrite/service/`. These are the ONLY things modules call for Eventbrite:

1. **EbEventSyncService** — Create, update, publish, cancel events; pull org event catalog
2. **EbVenueService** — Create and update venues; list org venues
3. **EbScheduleService** — Create recurring event schedules and occurrences
4. **EbTicketService** — Create/update ticket classes; manage inventory; check availability
5. **EbCapacityService** — Retrieve and update event capacity tiers
6. **EbOrderService** — Read orders post-checkout (admin/ reporting only — NOT used in FR5/FR6 payment flows; orders are created internally in `bookings` table when Stripe payment is confirmed)
7. **EbAttendeeService** — List attendees; verify attendance for reviews (engagement only — FR8 attendance verification)
8. **EbDiscountSyncService** — Create, update, delete discount codes
9. **EbRefundService** — INACTIVE for payments — all refunds go through `StripeRefundService`; original EB refund read-only API has no submission endpoint
10. **EbWebhookService** — Mock/testing only; not used in production registration flow

**See `docs/EVENTBRITE_INTEGRATION.md` for which module uses which facade and what gaps exist.**

---

## Stripe ACL Facades (payments-ticketing only)

All 3 facades are in `shared/stripe/service/`. Only `payments-ticketing` calls these:

1. **StripePaymentService** — Create PaymentIntents; confirm and capture payments
2. **StripeRefundService** — Submit refunds; query refund status
3. **StripeWebhookHandler** — Verify webhook signatures; parse `payment_intent.succeeded` / `payment_intent.payment_failed` / `charge.refunded` events

**Hard Rule:** Never call `com.stripe.*` SDK classes directly from a module service — always delegate to the facade in `shared/stripe/service/`.

---

## Module Dependency Order (HARD RULE #10)

```
shared/               <- foundational; no dependencies
  |
  v
discovery-catalog    <- depends on shared only
  |
  v
scheduling           <- depends on shared + discovery-catalog (REST reads)
identity             <- depends on shared only
  |
  v
booking-inventory    <- depends on shared + discovery-catalog (REST reads)
  |
  v
payments-ticketing   <- depends on shared + booking-inventory + identity (REST)
  |
  v
promotions           <- depends on shared + booking-inventory + identity (REST)
  |
  v
engagement           <- depends on shared + payments-ticketing + identity (REST)
  |
  v
admin/               <- thin orchestration; reads from all, writes via REST APIs
  |
  v
app/                 <- entry point only; zero business logic
```

**Golden Rule:** Module at level N can depend on level N-1 or lower (or `shared/`) only. Never import from sibling or downstream modules.

**See `PRODUCT.md` Section 2 for detailed rationale.**
