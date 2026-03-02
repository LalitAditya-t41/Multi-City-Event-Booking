# Modular Monolith — Canonical Folder Structure Template

> **Purpose:** This document is the canonical reference for building any new feature or module
> in this codebase. Every agent, developer, or tool that adds code MUST follow this structure
> exactly. It enforces high cohesion, low coupling, and zero cyclic dependencies by design.

---

## Core Architectural Rules (Read Before Writing Any Code)

### Rule 1 — Dependency Direction is One-Way and Downward Only
```
shared/          ← no dependencies on any module (foundational)
    ↓
[module-A]       ← may depend on shared only
    ↓
[module-B]       ← may depend on shared + module-A
    ↓
[module-C]       ← may depend on shared + module-A + module-B
    ↓
admin/           ← thin orchestration layer, depends on all modules but owns no domain
    ↓
app/             ← entry point only, no business logic
```
**A module NEVER imports from a module below it or at the same level.**
**If two modules need to talk, they do so through Spring Events — never direct service calls.**

### Rule 2 — Three Ways Modules Communicate (Pick the Right One)
| Communication Type | When to Use | Mechanism |
|---|---|---|
| Query (sync, read-only) | Module B needs data that Module A owns | Module B calls Module A's **public API endpoint** (HTTP), never imports Module A's service class directly |
| Command (async, fire-and-forget) | Module A completed something and others should react | Module A publishes a **Spring ApplicationEvent**; interested modules register a listener |
| Shared contract | Enums, base classes, standard DTOs used by 2+ modules | Lives in `shared/common/` only — never duplicated |

### Rule 3 — The ACL is the Only Door to External Systems
Any external API (Eventbrite, payment gateway, SMS provider, etc.) MUST be accessed
exclusively through the `shared/` ACL package. No module ever calls an external API directly.
If a new external integration is needed, it goes in `shared/` first, then modules consume it
via the ACL facade services.

### Rule 4 — A Module Owns Its Own Database Tables
No two modules share a JPA entity or database table. If Module B needs data from Module A's
table, it calls Module A's API — it does NOT import Module A's `@Entity` class or `@Repository`.
Violating this rule creates hidden coupling at the database layer.

### Rule 5 — The `admin/` Module Owns No Domain
The admin module only orchestrates and aggregates. It delegates all writes to the owning module.
It never defines its own `@Entity` or owns a database table. It is a read + orchestration layer.

For inter-module access, admin follows this rule:
- **Reads** — admin MAY import a module's `@Service` bean directly (same JVM, no overhead)
- **Writes** — admin MUST call the owning module's REST API (never bypass the module's own validation, events, and business rules)

### Rule 6 — Mapping Logic Belongs in the Mapper Layer Only
Converting domain objects to API response DTOs and request DTOs to domain objects must be done
exclusively in the `mapper/` package. Controllers and services must never call `.builder()` on a
response DTO directly or manually map fields.
- **Controllers** — call service, return result. No mapping.
- **Services** — orchestrate domain + infra. No mapping.
- **Mappers** — the only place field translation happens. Use MapStruct.

---

## Project Layout

```
[project-root]/
├── pom.xml                    ← Parent POM. All dependency versions managed here only.
├── shared/                    ← Internal library. Never imports from any module.
├── [module-name]/             ← One folder per bounded context.
├── admin/                     ← Thin orchestration layer. Owns no domain, no tables.
└── app/                       ← Spring Boot entry point. Zero business logic.
```

---

## shared/ Structure

Cross-cutting concerns only. Every module depends on this.

```
shared/
└── src/main/java/com/[org]/shared/
    ├── [external-system-acl]/     ← One package per external system (e.g. razorpay/, eventbrite/)
    │   ├── client/                ← Raw HTTP calls only
    │   ├── config/                ← WebClient bean, rate limiter config
    │   ├── dto/request/           ← External system's raw request shapes
    │   ├── dto/response/          ← External system's raw response shapes
    │   ├── mapper/                ← Translates external DTOs → internal domain objects
    │   ├── service/               ← THE ONLY THING MODULES CALL. One service per capability.
    │   ├── webhook/               ← Receives inbound webhooks, dispatches as Spring Events
    │   └── resilience/            ← Circuit breaker + fallback config per external system
    │
    ├── security/
    │   ├── JwtTokenProvider       ← Generate + validate JWTs
    │   ├── JwtAuthenticationFilter← Spring Security filter, registered once
    │   └── SecurityConstants      ← Token TTLs, header names, role constants
    │
    ├── common/
    │   ├── domain/
    │   │   ├── BaseEntity         ← id, createdAt, updatedAt — all entities extend this
    │   │   └── Money              ← Value object for all monetary amounts. Never use primitives.
    │   ├── dto/
    │   │   ├── ApiResponse        ← Standard success response wrapper
    │   │   ├── PageResponse       ← Paginated list wrapper
    │   │   └── ErrorResponse      ← Standard error shape
    │   ├── enums/                 ← Enums shared across 2+ modules only
    │   └── exception/
    │       ├── GlobalExceptionHandler  ← @ControllerAdvice. One handler for the entire app.
    │       ├── ResourceNotFoundException   ← 404
    │       ├── BusinessRuleException       ← 422
    │       └── IntegrationException        ← 502
    │
    └── config/
        ├── RedisConfig            ← Shared Redis bean
        ├── ElasticsearchConfig    ← Shared ES client bean
        ├── CacheConfig            ← Cache names and TTLs
        ├── AsyncConfig            ← Thread pool for @Async
        ├── OpenApiConfig          ← Springdoc global config
        ├── WebConfig              ← Global CORS + request logging. Never use @CrossOrigin on controllers.
        └── TracingConfig          ← MDC traceId on every log line. Essential for prod debugging.
```

---

## [module-name]/ Structure

One folder per bounded context. Name reflects the domain, not the tech.

```
[module-name]/
└── src/main/java/com/[org]/[module]/
    ├── api/
    │   ├── controller/            ← HTTP endpoints only. No business logic. Delegates to service.
    │   └── dto/
    │       ├── request/           ← [Action][Resource]Request
    │       └── response/          ← [Resource]Response. Flat, serializable, no JPA proxies.
    │
    ├── domain/                    ← Pure business logic. No Spring, no JPA, no infrastructure.
    │                                Entities have behavior (confirm(), cancel()) not just getters.
    │
    ├── service/                   ← Orchestrates domain + repositories + ACL + events. @Service beans.
    │                                One service per capability group. Methods = use cases not CRUD.
    │
    ├── repository/                ← @Repository interfaces only. No other module imports these.
    │
    ├── mapper/                    ← Domain ↔ DTO translation only. Use MapStruct.
    │
    ├── event/
    │   ├── published/             ← Events this module fires. Past tense. Primitives/IDs only.
    │   └── listener/              ← Events this module reacts to. No logic — delegate to service.
    │
    ├── statemachine/              ← OPTIONAL. Only if 4+ states with conditional transition guards.
    │   ├── [Resource]StateMachineConfig
    │   ├── [Resource]State
    │   ├── [Resource]Event
    │   ├── [Resource]StateMachineService
    │   ├── guard/
    │   └── action/
    │
    └── exception/                 ← OPTIONAL. Module-specific exceptions only.
```

---

## admin/ and app/

```
admin/
└── src/main/java/com/[org]/admin/
    ├── api/controller/            ← Admin-only endpoints (@PreAuthorize ROLE_ADMIN)
    └── service/                   ← Aggregates reads across modules. Delegates writes to module APIs.

app/
└── src/main/java/com/[org]/
    ├── [ProjectName]Application   ← @SpringBootApplication. One class, nothing else.
    └── resources/
        ├── application.yml
        ├── application-local.yml
        ├── application-prod.yml
        └── db/migration/          ← Flyway migrations. Global version sequence. Never modify existing files.
```

---

## Anti-Patterns — Never Do These

| Anti-Pattern | Why It's Wrong | Correct Approach |
|---|---|---|
| Module A imports `@Service` from Module B | Direct coupling — changes in B break A at compile time | Module A calls Module B's REST endpoint or listens to its Spring Event |
| Module A imports `@Entity` or `@Repository` from Module B | Hidden DB coupling — schema changes in B break A silently | Module A calls Module B's API to get the data it needs |
| Putting Eventbrite/Razorpay HTTP calls inside a module's service | Scattered integration — if the API changes, you hunt across modules | All external calls go through `shared/[system-acl]/service/` facades only |
| Sharing a database table between two modules | Breaks module autonomy — both modules must coordinate schema changes | Each module owns its own tables; sync via events or API calls |
| Putting business logic in a `@RestController` | Untestable and leaks domain logic into the HTTP layer | Controller delegates immediately to service; service owns all logic |
| Creating an `[Entity]Service` that just wraps CRUD | Anemic domain model — no real encapsulation | Put behavior on the entity itself; service orchestrates multi-step use cases |
| Publishing a Spring Event that contains a JPA `@Entity` | Entity may be a lazy-loaded proxy; causes serialization failures | Events carry only primitive values, IDs, or immutable value objects |
| Adding a new enum to `shared/common/enums/` for a single-module concept | Pollutes shared space with module-specific concerns | Single-module enums stay inside that module's `domain/` package |
| Writing business logic in `admin/` | Admin owns no domain; it becomes a second copy of truth | Admin delegates writes to the owning module's REST API; only reads and aggregates |
| Admin calling a module's `@Service` for writes | Bypasses the module's own validation, events, and business rules | Admin must call the owning module's REST API for all write operations |
| Mapping fields inside a `@Service` or `@RestController` | Pollutes orchestration/HTTP layers with translation logic; hard to test | All domain ↔ DTO mapping goes in `mapper/[Resource]Mapper` only |
| Using `@CrossOrigin` on individual controllers | Scattered CORS config — inconsistent across modules | Define CORS once in `shared/config/WebConfig` |

---

## Event Naming Conventions

```
Published events  →  Past tense + "Event" suffix
                     [WhatHappened]Event
                     e.g. BookingConfirmedEvent
                          SeatReleasedEvent
                          UserRegisteredEvent
                          PaymentFailedEvent

Listeners         →  [TriggerEvent]Listener
                     e.g. PaymentFailedListener (inside booking-inventory module)
                          BookingConfirmedListener (inside engagement module)
                          CartAssembledListener (inside payments module)

Event payload     →  Only primitives, IDs, enums, and value objects.
                     NEVER include @Entity objects in events.
                     NEVER include mutable collections.
```

---

## Module pom.xml Dependency Rules

```xml
<!-- Every module pom.xml follows this exact pattern -->
<dependencies>

  <!-- 1. Always: shared internal library -->
  <dependency>
    <groupId>com.[org]</groupId>
    <artifactId>shared</artifactId>
  </dependency>

  <!-- 2. Only upstream modules (modules this one reads from) -->
  <!-- Example: booking-inventory reads from discovery-catalog -->
  <dependency>
    <groupId>com.[org]</groupId>
    <artifactId>[upstream-module]</artifactId>
  </dependency>

  <!-- 3. NEVER depend on a peer or downstream module -->
  <!-- NEVER depend on admin/ -->
  <!-- NEVER depend on app/ -->

</dependencies>
```

---

## Flyway Migration Naming

```
V[auto-increment]__[verb]_[table_name].sql

Examples:
  V1__create_users.sql
  V2__create_events.sql
  V7__add_wallet_balance_to_users.sql
  V12__create_seat_lock_audit_log.sql

Rules:
  - NEVER modify an existing migration file (Flyway will reject it in prod)
  - ALWAYS add a new file for any schema change
  - Version numbers are global across ALL modules — maintain a single sequence in app/
  - Table names use snake_case
  - Description uses snake_case with underscores
```
