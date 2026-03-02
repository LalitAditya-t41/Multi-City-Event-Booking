# AGENTS.md — Reference Index

> Every agent working in this codebase must read the files listed below before producing
> any output. Each file governs a specific concern. Read the ones that apply to your task.

---

## Reference Files

| File | Governs |
|---|---|
| `PRODUCT.md` | The 7 modules, what each one owns, dependency order, inter-module event map, Eventbrite ACL facade names, hard architectural rules, and where new code goes. Read this before touching any module. |
| `docs/MODULE_STRUCTURE_TEMPLATE.md` | Canonical folder and package layout for every module. The exact location of every layer: `api/controller/`, `api/dto/`, `domain/`, `service/`, `repository/`, `mapper/`, `event/published/`, `event/listener/`, `statemachine/`, `exception/`. Anti-patterns list. Read this before writing any class. |
| `agents/SPEC_GENERATOR.md` | How to produce a `SPEC.md` for any feature. Eight confirmation-gated stages: Requirements → Domain Model → Architecture & File Structure → DB Schema → API → Business Logic → Error Handling → Tests. Read this before speccing any feature. |
| `agents/CODING_AGENT.md` | Spring Boot 3.5.11 / Java 21 coding standards for every layer. Domain behaviour methods, service orchestration, mapper rules, controller rules, event rules, ACL facade pattern, state machine guards and actions, Spring AI chatbot wiring. Read this before writing any production code. |
| `docs/TESTING_GUIDE.md` | Four-layer test structure: `domain/` (pure unit), `service/` (Mockito orchestration), `api/` (@WebMvcTest HTTP contract), `arch/` (ArchUnit boundaries). Happy path, negative path, and exception test patterns with Java examples. Read this before writing any test. |

---

## Module Quick Reference

Full detail for each module is in `PRODUCT.md`. This table is a lookup only.

| Module | Owns | Eventbrite ACL Facades Used |
|---|---|---|
| `discovery-catalog` | Events, Venues, Cities, catalog sync | `EbEventSyncService`, `EbTicketService` |
| `scheduling` | Show slots, conflict validation | `EbEventSyncService` |
| `identity` | Users, JWT, profiles, preferences, wallet | `EbAttendeeService` |
| `booking-inventory` | Seats, SeatMap, Cart, Pricing, Seat Lock State Machine | `EbTicketService` |
| `payments-ticketing` | Bookings, Payments, E-Tickets, Cancellations, Refunds, Wallet | `EbOrderService`, `EbAttendeeService`, `EbRefundService` |
| `promotions` | Coupons, Promotions, Eligibility rules | `EbDiscountSyncService` |
| `engagement` | Reviews, Moderation, AI Chatbot (`service/chatbot/`), RAG pipeline | `EbEventSyncService` |
| `admin/` | No domain ownership. Orchestration and aggregation only. | — |
| `shared/` | All external ACL facades, JWT, base classes, common DTOs, configs | — |

---

## Hard Rules — Never Violate

These are repeated from `PRODUCT.md`. They apply to every task without exception.

1. No module imports another module's `@Service`, `@Entity`, or `@Repository`.
2. No module calls Eventbrite, Razorpay, or OpenAI HTTP directly — only through `shared/` ACL facades.
3. ACL facade names are exactly as listed in `PRODUCT.md` — never invent new ones.
4. Spring Events carry only primitives, IDs, enums, and value objects — never `@Entity` objects.
5. One `GlobalExceptionHandler` in `shared/common/exception/` — never `@ControllerAdvice` in a module.
6. All field mapping goes in `mapper/` using MapStruct — never inside a service or controller.
7. `admin/` owns no `@Entity` and no database table.
8. `shared/` has no dependency on any module.
9. `app/` has zero business logic.
10. Dependency direction is strictly downward — no circular imports between modules.
