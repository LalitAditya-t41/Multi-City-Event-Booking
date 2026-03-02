# SPEC_GENERATOR.md — Universal Feature Spec Generator for AI Agents

## Purpose

This file defines how an AI agent should generate a `SPEC.md` for any feature or user flow.
When a user describes a feature, flow, or system, the agent must follow the structured process
below — in order — and produce a complete `SPEC.md` as output.

The agent must not skip sections, silently make assumptions, or flatten nuanced decisions
into a single choice without surfacing alternatives. Every generated spec must be self-contained,
developer-ready, and testable.

---

## Agent Instructions

### How to Use This File

1. Read the **Generation Process** section to understand the order of work.
2. Read the **Output Template** section to understand the required structure.
3. Ask the user clarifying questions (see **Pre-Generation Checklist**) before generating.
4. Generate the full `SPEC.md` in one pass following the ordered flow below.
5. End every spec with an **Open Questions** section — never silently resolve ambiguity.

---

## Pre-Generation Checklist

Before generating, the agent must confirm or ask the user for:

- [ ] **Feature name** — what is this spec for?
- [ ] **User types** — who are the actors? (end user, admin, third-party system, etc.)
- [ ] **Tech stack** — what DB, framework, auth system, and external services are in use?
- [ ] **Scope boundary** — what is explicitly OUT of scope for this version?
- [ ] **Existing system context** — does this integrate with existing models, APIs, or flows?
- [ ] **Priority level** — which requirements are non-negotiable vs. nice-to-have?
- [ ] **PRODUCT.md** — the module registry must be available before Stage 3 can proceed. If not provided upfront, the agent must request it before reaching the Architecture stage.

If any of the above are unclear, the agent should ask before proceeding. Do not assume.

---

## Generation Process (Ordered Flow)

The agent must generate every section in this order. Each stage builds on the previous.
Do not reorder or collapse sections.

```
Requirements → Domain Modeling → Architecture & File Structure → DB Schema → API → Business Logic → Error Handling → Tests
```

---

### Confirmation Gate Protocol (Mandatory)

**Every stage follows a two-step loop — no exceptions:**

```
Step 1 → Agent drafts the OUTLINE for that stage
Step 2 → Agent stops and explicitly asks the user to confirm, add, remove, or change items
Step 3 → User confirms (or requests changes)
Step 4 → Agent writes the FULL content for that stage only
Step 5 → Agent moves to the next stage and repeats from Step 1
```

The agent must **never write full content for a stage before receiving confirmation on its outline.**
The agent must **never move to the next stage before finishing and confirming the current one.**
If the user asks the agent to skip confirmation for a stage, the agent may do so — but only
for that specific stage, not as a blanket override for the rest.

#### What the outline looks like per stage

Each stage has a different shape for its outline. The agent must follow these formats exactly:

| Stage | Outline Format                                                                                                       |
|---|----------------------------------------------------------------------------------------------------------------------|
| Requirements | Bulleted list of requirement statements, tagged MUST/SHOULD/COULD/WONT, grouped by area                              |
| Domain Modeling | List of entities with one-line descriptions and their relationships to each other                                    |
| Architecture & File Structure | Modules read from `PRODUCT.md`→ agent lists them → user picks owning module → agent lists feature files only         |
| DB Schema | List of table names with key fields only (no types, indexes, or constraints yet)                                     |
| API | List of endpoints: `METHOD /path` — one-line description of what it does                                             |
| Business Logic | List of rules, state transitions, and side effects to be documented (not the full prose yet)                         |
| Error Handling | List of failure scenarios (not the messages or actions yet — just the scenarios)                                     |
| Tests | List of test case titles grouped by area (no Given/When/Then yet)                                                    |

#### Confirmation prompt template

After presenting each outline, the agent must ask:

> "Does this look complete? Anything to add, remove, or change before I write this section in full?"

The agent must wait for an explicit go-ahead before proceeding. "Looks good", "yes", "go ahead",
or equivalent confirms the stage. If the user suggests changes, the agent updates the outline,
re-presents it, and asks again before writing.

---

### Stage 1 — Requirements

Define what the feature must do. Tag every requirement:

- `[MUST]` — non-negotiable, required for launch
- `[SHOULD]` — strongly preferred, include if feasible
- `[COULD]` — nice to have, defer if time-constrained
- `[WONT]` — explicitly out of scope for this version

Group requirements by functional area (e.g. "User Facing", "Admin", "Background Jobs").
Do not write vague requirements. Every requirement must describe a specific, verifiable behavior.

**Bad:** "Users can manage their account."
**Good:** "Users must be able to update their email address, triggering a re-verification email."

---

### Stage 2 — Domain Modeling

Before writing schema or APIs, identify the core domain concepts and their relationships.

For each entity, define:
- Its **identity** (what makes it unique)
- Its **lifecycle** (what states does it move through)
- Its **relationships** to other entities (one-to-many, many-to-many, etc.)
- Its **ownership** (which actor owns or controls it)

Produce a plain-language summary of the domain model, e.g.:

> A `Project` belongs to an `Organization`. A `Project` has many `Tasks`.
> A `Task` is owned by one `User` and moves through states: `todo → in_progress → done → archived`.
> A `Comment` belongs to a `Task` and is owned by a `User`.

This section exists so that schema, API, and business logic are derived from a consistent
mental model — not ad-hoc field additions.

---

### Stage 3 — Architecture & File Structure

#### Architecture

**Pattern: Modular Monolithic (Fixed)**

The architecture is pre-decided and non-negotiable. The agent must never propose a different
pattern, invent new modules, or restructure existing ones.

The full module registry is defined in **`PRODUCT.md`** — a separate file maintained by the
product owner. The agent must read `PRODUCT.md` before proceeding with this stage.

> The agent must never assume module names, invent new modules, or place files in a module
> not listed in `PRODUCT.md`. If `PRODUCT.md` is not available or not provided, the agent
> must stop and ask the user to provide it before continuing.

#### Module Assignment (Mandatory Step)

After reading `PRODUCT.md`, the agent must:

1. **Present the list of available modules** from `PRODUCT.md` to the user.
2. **Ask the user to confirm which module owns this feature.** The agent must not decide this unilaterally.

Prompt template:
> "Based on `PRODUCT.md`, the available modules are: [list]. Which module should this feature live in?"

#### Cross-Module Features

If the feature touches more than one module (e.g. it writes to the `billing` module but
also modifies state in the `auth` module), the agent must:

1. **Flag the cross-module dependency explicitly** — do not silently spread files across modules.
2. **Stop and ask the user to decide the primary owning module** before continuing.
3. **Document the dependency** in the spec under this section:
   - Primary module: `[module-name]` — owns the feature, contains core files
   - Dependent module(s): `[module-name]` — describe what is touched and why

Prompt template:
> "This feature appears to touch multiple modules: [list]. Which module should be the primary
> owner? I'll document the dependency on [other modules] but keep all core files in the primary."

The agent must not proceed to DB Schema until module ownership is resolved and confirmed.

#### Cross-Module Communication Rules (Mandatory — Apply Before Writing Any File)

Before listing any files, the agent must classify every inter-module interaction using
the three rules below. Using the wrong mechanism is an architectural violation.

| Interaction Type | When to Use | Correct Mechanism |
|---|---|---|
| Query (sync read) | This module needs data owned by another module | Call the other module's **REST API endpoint** — never import its `@Service` or `@Repository` |
| Command (async) | This module completed something others should react to | Publish a **Spring `ApplicationEvent`** — never call the other module directly |
| Shared contract | An enum, base class, or DTO used by 2+ modules | Lives in **`shared/common/`** only — never duplicated across modules |

The agent must document every cross-module interaction in this section using the table above.
If an interaction doesn't fit one of these three types, stop and raise it as an Open Question.

#### Feature File Structure (Agent-Generated)

Once the owning module is confirmed, the agent generates **only the files for this specific
feature inside that module** — no full project tree, no files outside the confirmed module
unless a cross-module dependency was explicitly approved above.

The structure must follow the canonical Java layered package layout exactly.
Use the module-to-package mapping rule defined in `PRODUCT.md` for `[confirmed-module-package]`.

```
[confirmed-module]/src/main/java/com/[org]/[confirmed-module-package]/
│
├── api/
│   ├── controller/
│   │   └── [Resource]Controller.java          # @RestController. HTTP only. Delegates to service immediately.
│   │                                            No business logic. @PreAuthorize for role checks.
│   └── dto/
│       ├── request/
│       │   └── [Action][Resource]Request.java  # What the caller sends in. Bean validation annotations here.
│       └── response/
│           └── [Resource]Response.java         # What the caller receives back. Flat, no JPA proxies.
│
├── domain/
│   ├── [CoreAggregate].java                   # Aggregate root. Has behavior methods, not just getters.
│   │                                            e.g. booking.confirm(), seat.lock()
│   │                                            NO Spring annotations. NO JPA. NO infrastructure imports.
│   ├── [SupportingEntity].java                # Only exists within this module's aggregate boundary.
│   └── [ValueObject].java                     # Immutable. No identity. e.g. Money, DateRange
│
├── service/
│   └── [Capability]Service.java               # @Service. Orchestrates domain + repo + events + ACL.
│                                                Methods map to use cases, not CRUD.
│                                                e.g. initiateBooking(), confirmBooking(), cancelBooking()
│
├── repository/
│   └── [Resource]Repository.java              # Spring Data JPA interface. Owned exclusively by this module.
│                                                No other module imports this.
│
├── mapper/
│   └── [Resource]Mapper.java                  # MapStruct interface. The ONLY place domain ↔ DTO translation happens.
│                                                Controllers and services must never map fields directly.
│                                                toResponse(Domain) → ResponseDTO
│                                                toDomain(RequestDTO) → DomainObject
│
├── event/
│   ├── published/
│   │   └── [WhatHappened]Event.java           # Fired when something important occurs in this module.
│   │                                            Naming: past tense + Event. e.g. BookingConfirmedEvent
│   │                                            Payload: primitives, IDs, enums, value objects ONLY.
│   │                                            NEVER include @Entity objects.
│   └── listener/
│       └── [TriggerEvent]Listener.java        # Reacts to events fired by upstream modules.
│                                                @EventListener or @TransactionalEventListener.
│                                                No logic here — delegates immediately to service.
│
├── statemachine/                              # OPTIONAL — only if entity has 4+ states with guarded transitions.
│   ├── [Resource]StateMachineConfig.java
│   ├── [Resource]State.java                   # Enum of all valid states
│   ├── [Resource]Event.java                   # Enum of all valid trigger events
│   ├── [Resource]StateMachineService.java
│   ├── guard/
│   │   └── [Condition]Guard.java
│   └── action/
│       └── [Transition]Action.java
│
└── exception/                                 # OPTIONAL — only if exception needs module-specific context.
    └── [Resource]NotFoundException.java       # Extends ResourceNotFoundException from shared/common/

Cross-module files (if applicable, only after explicit approval above):
[other-module]/src/main/java/com/[org]/[other-module]/
└── event/listener/[TriggerEvent]Listener.java  # Listens to event published by [confirmed-module]
```

**Agent checklist before finalising this section:**
- [ ] Every layer present above has at least one file listed for this feature
- [ ] `mapper/` is included — no mapping inside service or controller
- [ ] If the feature triggers other modules → `event/published/` is listed with event name and payload fields
- [ ] If the feature reacts to another module's event → `event/listener/` is listed
- [ ] `statemachine/` is only included if the entity genuinely has 4+ guarded state transitions
- [ ] No TypeScript file extensions, no flat file structure — Java packages only
- [ ] External system calls (payment, email, SMS) are NOT listed here — they go in `shared/[system-acl]/`

---

### Stage 4 — DB Schema

Translate the domain model into concrete schema definitions. Every table defined here
must have a corresponding Flyway migration file. Schema and migrations are always produced together.

For each table, provide:
- Field name, type, nullable/required status
- Indexes (including composite indexes for common query patterns)
- Foreign key relationships and cascade behavior
- Any enum types or constrained value sets
- Soft delete strategy if applicable (`deleted_at` vs. hard delete)

**Table format** (adapt field types to the project's DB dialect):

```
TableName
---------
id            UUID         PK, default gen_random_uuid()
foreign_id    UUID         FK → other_table.id, ON DELETE CASCADE
status        ENUM         ('active', 'inactive', 'archived'), NOT NULL, default 'active'
created_at    TIMESTAMPTZ  NOT NULL, default now()
updated_at    TIMESTAMPTZ  NOT NULL, default now()

Indexes:
  - (foreign_id)              -- for FK lookups
  - (status, created_at)      -- for filtered list queries
```

#### Flyway Migrations (Mandatory)

Every table or schema change defined above must have a corresponding migration file listed here.
Migration files live in `app/src/main/resources/db/migration/` — one global sequence across all modules.

**Naming convention:** `V[n]__[verb]_[table_name].sql`

```
Examples:
  V7__create_bookings.sql
  V8__create_booking_items.sql
  V9__add_status_index_to_bookings.sql
```

**Rules the agent must follow and state in the spec:**
- Version numbers are global — the agent must ask the user for the current highest migration number
  before assigning new version numbers. Never guess or assume `V1`.
- NEVER modify an existing migration file. Always add a new file for any schema change.
- Table names use `snake_case`. Description uses `snake_case` with underscores.
- The agent must list every migration file this feature requires, in the order they must run.

**Format for this section in the generated spec:**

```
Migration Files (run in order):
  V[n]__create_[table_name].sql      — Creates [table] with all columns and constraints
  V[n+1]__create_[table_name].sql    — Creates [table] with all columns and constraints
  V[n+2]__add_[column]_to_[table].sql — Adds [column] to existing [table]
```

Note any migration concerns for existing tables: backfill requirements, locking risks on
large tables, zero-downtime strategy (e.g. add nullable column first, backfill, then add constraint).

---

### Stage 5 — API

Define every endpoint the feature requires. For each endpoint:

- Method + path
- Auth requirement (none / authenticated user / role-scoped via `@PreAuthorize`)
- Path/query params with types
- Request body — reference the `[Action][Resource]Request` DTO defined in `api/dto/request/`
- Success response — reference the `[Resource]Response` DTO defined in `api/dto/response/`
- All named error responses with HTTP status, exception class, and human-readable message

**Format:**

```
METHOD /api/[resource]/:param

Auth:     none | @PreAuthorize("hasRole('USER')") | @PreAuthorize("hasRole('ADMIN')")
Params:   param (UUID) — description
Query:    key (String, optional) — description
Body:     [Action][Resource]Request { field: type @NotNull/@NotBlank/@Valid }

Response 200: [Resource]Response { field: type, ... }
Response 201: [Resource]Response { field: type, ... }  ← for creation

Errors:
  404  ResourceNotFoundException      → "Human-readable: [resource] with id {id} not found"
  422  BusinessRuleException          → "Human-readable: [rule that was violated]"
  400  MethodArgumentNotValidException → "Validation failed: [field] [constraint message]"
  502  [System]IntegrationException   → "An external service error occurred. Please try again."
```

**Error handling rules:**
- All exceptions are caught by `GlobalExceptionHandler` in `shared/common/exception/` — the agent
  must never define a `@ControllerAdvice` inside a module.
- Error responses always use `ErrorResponse` from `shared/common/dto/ErrorResponse` — never a
  raw `{ error: string, code: string }` JSON shape defined inline.
- Module-specific exceptions (e.g. `BookingNotFoundException`) extend `ResourceNotFoundException`
  from `shared/common/exception/` — they are never defined from scratch.
- Payment, external service, or integration errors must never expose vendor internals to the client.
  Always use `[System]IntegrationException` → maps to 502 via `GlobalExceptionHandler`.

**External integration rule:**
If any endpoint triggers a call to an external system (payment gateway, email, SMS, etc.),
the agent must explicitly note that the call goes through `shared/[system-acl]/service/[Facade]Service`
and must NEVER be placed directly inside the module's own service class.

All endpoints that mutate state require authentication unless explicitly justified otherwise.

---

### Stage 6 — Business Logic

Document the rules that govern how the system behaves. This section bridges the API contract
and the actual implementation. Be explicit — "the system should handle this correctly" is not
business logic.

Cover every subsection below. Write "N/A" only if genuinely not applicable.

**State Machine**
If an entity has lifecycle states, define every valid transition, what triggers it, and what
is forbidden. Use a table:

```
| From State   | Trigger / Event         | To State    | Guard (condition that must be true) |
|--------------|-------------------------|-------------|-------------------------------------|
| PENDING      | confirmBooking()        | CONFIRMED   | payment must be successful          |
| PENDING      | cancelBooking()         | CANCELLED   | always allowed                      |
| CONFIRMED    | cancelBooking()         | CANCELLED   | must be > 48h before event          |
| CONFIRMED    | confirmBooking()        | —           | FORBIDDEN → throws BusinessRuleException |
| CANCELLED    | any                     | —           | FORBIDDEN → terminal state          |
```

If the entity has 4+ states with guards, flag it for `statemachine/` implementation.
If it has 2-3 states with simple transitions, a status field with domain method guards is sufficient.

**Validation Rules**
List every invariant that must hold before a write succeeds. These become `domain/` tests.

```
- [Entity].[method]() must throw BusinessRuleException if status is [X]
- [Field] must not be null or empty
- [Field] must be > 0
- [Date] must be in the future
```

**Mapper Responsibilities**
List every mapping this feature requires. All mapping happens in `mapper/[Resource]Mapper` only.

```
- [Resource]Mapper.toResponse(Domain) → [Resource]Response
- [Resource]Mapper.toDomain([Action]Request) → Domain
```

The agent must flag it as a violation if any mapping logic appears in a service or controller.

**Inter-Module Events**
List every Spring ApplicationEvent this feature publishes or consumes. For each event:

Published events (this module fires):
```
Event:    [WhatHappened]Event
Fired by: [Capability]Service.[method]() after [condition]
Payload:  { fieldName: type, ... }  ← primitives, IDs, enums, value objects ONLY. No @Entity.
Listeners: [other-module]/event/listener/[TriggerEvent]Listener.java
```

Consumed events (this module listens to):
```
Event:    [WhatHappened]Event (published by [other-module])
Listener: [TriggerEvent]Listener.java
Action:   delegates to [Capability]Service.[method]() — no logic in the listener itself
```

If the feature has NO inter-module events, write: "This feature does not publish or consume events."

**External System Calls**
If this feature calls any external system (payment gateway, email provider, SMS, etc.):

```
External system: [SystemName]
Called via:      shared/[system-acl]/service/[System][Capability]Service.java
Called from:     [module]/service/[Capability]Service.java
Never:           the module service must never instantiate an HTTP client or call an external API directly
```

If no external calls: "This feature makes no external system calls."

**Concurrency & Locking**
Name the locking strategy explicitly. "The system handles this" is not acceptable.

Options: pessimistic row lock (`SELECT FOR UPDATE`), optimistic lock (`@Version` field),
Redis distributed lock (via `shared/config/RedisConfig`), database unique constraint as guard.

**Authorization Rules**
Who can perform each action, under what conditions. These become `api/` and `arch/` tests.

```
- Only authenticated users (ROLE_USER) may call POST /api/[resource]
- Only the resource owner OR ROLE_ADMIN may call DELETE /api/[resource]/{id}
- ROLE_ADMIN endpoints must use @PreAuthorize("hasRole('ADMIN')") on the controller method
```

**Background Jobs / @Async**
Any async processing, scheduled tasks, or event-driven cleanup:

```
- Job: [description]
- Trigger: [cron / @TransactionalEventListener / manual]
- Retry policy: [none | exponential backoff, max N attempts]
- On exhaustion: [log + alert | dead-letter | refund + notify]
```

---

### Stage 7 — Error Handling

Define how the system handles failure. All exceptions flow through `GlobalExceptionHandler`
in `shared/common/exception/` — the agent must never define a `@ControllerAdvice` in a module.

**Exception classes to use (never create from scratch):**

| Exception Class | HTTP Status | When to Use |
|---|---|---|
| `ResourceNotFoundException` | 404 | Entity not found by ID |
| `BusinessRuleException` | 422 | A domain rule was violated (invalid state transition, constraint breach) |
| `[System]IntegrationException` | 502 | External system call failed (Stripe, email, SMS, etc.) |
| `MethodArgumentNotValidException` | 400 | Bean validation failure on request DTO (`@Valid`) |

Module-specific exceptions (e.g. `BookingNotFoundException`) must extend `ResourceNotFoundException`
from `shared/common/exception/` — never extend `RuntimeException` directly.

**Cover every item below:**

- **User-facing messages** — every exception must have a human-readable message. No stack traces,
  no vendor error codes, no JPA/Hibernate internals exposed to clients.
- **Idempotency** — for any flow where an external charge or irreversible action occurs: define
  exactly what happens if the action succeeds but the DB write fails. Who gets compensated? How?
- **Retry strategy** — which external calls are retried? Backoff policy? Max attempts? What happens
  on exhaustion? (Use `shared/[system-acl]/resilience/[System]CircuitBreakerConfig`)
- **Alert conditions** — which failures require an engineering alert, not just a log line?
- **Partial failure** — for multi-step operations, define rollback at each step.

**Format — table first, prose for nuanced flows:**

```
| Scenario                          | Exception Class               | HTTP | User Message                                      | Internal Action                        | Alert? |
|-----------------------------------|-------------------------------|------|---------------------------------------------------|----------------------------------------|--------|
| Entity not found by ID            | ResourceNotFoundException     | 404  | "[Resource] with id {id} not found."              | Log WARN                               | No     |
| Invalid state transition          | BusinessRuleException         | 422  | "Cannot [action] a [status] [resource]."          | Log WARN                               | No     |
| External payment call fails       | [System]IntegrationException  | 502  | "A payment error occurred. Please try again."     | Circuit breaker, retry x3, then alert  | YES    |
| Charge succeeds, DB write fails   | BusinessRuleException         | 500  | "Booking failed. You will not be charged."        | Refund via ACL facade, page on-call    | YES    |
| Bean validation fails             | MethodArgumentNotValidException | 400 | "Validation failed: [field] [constraint message]" | Log INFO                               | No     |
```

---

### Stage 8 — Tests

Every requirement tagged `[MUST]` must have at least one test. Every business logic rule,
state transition, and error path must have a test. Tests are not optional.

Tests are organised into four layers matching the source package structure. The agent must
assign every test case to the correct layer — never put business logic tests in `api/`
or HTTP contract tests in `domain/`.

**Test naming convention (mandatory):**
```
should_[expected_behaviour]_when_[condition]

Examples:
  should_confirm_booking_when_status_is_pending
  should_throw_when_confirming_a_cancelled_booking
  should_return_404_when_booking_not_found
  should_publish_event_after_confirming_booking
  should_return_400_when_request_body_is_invalid
```

---

#### Layer 1 — `domain/` Tests (Pure Unit Tests)

**What belongs here:** Every business rule, state transition, guard condition, and domain
exception on the aggregate and value objects.
**What does NOT belong here:** Persistence, HTTP, Spring context, Mockito mocks of infrastructure.
**Annotation:** None — plain JUnit 5. No `@SpringBootTest`, no `@ExtendWith(SpringExtension.class)`.

For each domain test, the agent must write cases covering:

**Happy path** — the rule executes successfully under valid preconditions:
```
[ ] should_[do_thing]_when_[valid_precondition]
    Given: entity is in state [X] / field has value [Y]
    When:  [domain method] is called
    Then:  state transitions to [Z] / field is updated to [W]
```

**Negative path** — the rule is blocked under invalid preconditions:
```
[ ] should_throw_BusinessRuleException_when_[invalid_precondition]
    Given: entity is in state [X] (invalid for this operation)
    When:  [domain method] is called
    Then:  BusinessRuleException is thrown with message containing "[expected message fragment]"
```

**Boundary / edge cases** — null inputs, minimum/maximum values, terminal states:
```
[ ] should_throw_when_[field]_is_null
[ ] should_throw_when_[entity]_is_in_terminal_state
```

---

#### Layer 2 — `service/` Tests (Orchestration Unit Tests)

**What belongs here:** Correct orchestration — right repository methods are called, events are
published, ACL facades are invoked, exceptions propagate correctly.
**What does NOT belong here:** Domain rules (those are in `domain/`), HTTP concerns.
**Annotation:** `@ExtendWith(MockitoExtension.class)` — Mockito only, no Spring context.
**Mock:** All `@Repository`, `@EventPublisher`, and `shared/` ACL facade services.

For each service test, the agent must write cases covering:

**Happy path** — the use case completes and all side effects fire:
```
[ ] should_[complete_use_case]_when_[valid_input]
    Given: repository returns [entity], ACL service returns [result]
    When:  service.[useCase]() is called
    Then:  repository.[save/update]() is called once
           eventPublisher.publishEvent([Event].class) is called once
           [ACL facade].[method]() is called with correct args
```

**Negative path — entity not found:**
```
[ ] should_throw_[Resource]NotFoundException_when_entity_does_not_exist
    Given: repository.findById() returns Optional.empty()
    When:  service.[useCase]() is called
    Then:  [Resource]NotFoundException is thrown
           no event is published
           no external call is made
```

**Negative path — external system failure:**
```
[ ] should_throw_[System]IntegrationException_when_external_call_fails
    Given: ACL facade throws [System]IntegrationException
    When:  service.[useCase]() is called
    Then:  exception propagates to caller
           compensating action is taken if required (e.g. refund initiated)
```

**Negative path — domain rule violated:**
```
[ ] should_propagate_BusinessRuleException_when_domain_rejects_transition
    Given: entity is in invalid state for this use case
    When:  service.[useCase]() is called
    Then:  BusinessRuleException propagates — service does not swallow it
```

---

#### Layer 3 — `api/` Tests (HTTP Contract Tests)

**What belongs here:** HTTP status codes, request validation, response JSON shape, and
`@PreAuthorize` enforcement. Nothing else.
**What does NOT belong here:** Business logic — mock the service and test only the HTTP layer.
**Annotation:** `@WebMvcTest([Resource]Controller.class)` — loads web layer only.
**Mock:** `@MockBean` the service and mapper.

For each controller test, the agent must write cases covering:

**Happy path — correct request returns correct response:**
```
[ ] should_return_200_when_[resource]_found
    Given: service.[method]() returns valid domain object
           mapper.toResponse() returns populated [Resource]Response
    When:  GET /api/[resource]/{id} is called with valid UUID
    Then:  status is 200
           response JSON contains expected fields and values

[ ] should_return_201_when_[resource]_created
    Given: service.[method]() returns created domain object
    When:  POST /api/[resource] is called with valid request body
    Then:  status is 201
           response JSON matches [Resource]Response shape
```

**Negative path — validation failures (400):**
```
[ ] should_return_400_when_required_field_is_missing
    Given: request body is missing a @NotNull / @NotBlank field
    When:  POST /api/[resource] is called
    Then:  status is 400
           response body matches ErrorResponse shape

[ ] should_return_400_when_[field]_fails_constraint
    Given: request body has [field] violating @[constraint]
    When:  POST /api/[resource] is called
    Then:  status is 400
```

**Negative path — not found (404):**
```
[ ] should_return_404_when_[resource]_does_not_exist
    Given: service throws ResourceNotFoundException
    When:  GET /api/[resource]/{id} is called
    Then:  status is 404
           response body matches ErrorResponse shape
```

**Negative path — business rule violation (422):**
```
[ ] should_return_422_when_business_rule_is_violated
    Given: service throws BusinessRuleException with message "[message]"
    When:  [endpoint] is called
    Then:  status is 422
           response body contains human-readable message
```

**Authorization:**
```
[ ] should_return_403_when_caller_lacks_required_role
    Given: request is authenticated but lacks [ROLE_X]
    When:  [protected endpoint] is called
    Then:  status is 403
```

---

#### Layer 4 — `arch/` Tests (Architectural Boundary Tests)

**What belongs here:** ArchUnit rules that automatically fail the build if anyone violates
module boundaries. Every module must have exactly one `[Module]ArchTest.java`.
**What does NOT belong here:** Business logic, HTTP tests.
**Annotation:** `@AnalyzeClasses(packages = "com.[org].[module]")`

The agent must list every ArchUnit rule required for this feature:

```
[ ] domain_must_not_depend_on_infrastructure
    — no class in ...[module].domain.. may depend on ..repository.., spring.web, jakarta.persistence

[ ] controllers_must_not_import_repositories
    — no class in ...[module].api.controller.. may depend on ...[module].repository..

[ ] no_cross_module_repository_access
    — no class in ...[module].. may depend on ..[other-module].repository..

[ ] events_must_not_contain_entities
    — no class in ...[module].event.published.. may depend on classes annotated with @Entity

[ ] mappers_must_not_call_services
    — no class in ...[module].mapper.. may depend on ...[module].service..

[ ] [add any new rule introduced by this feature's architectural decisions]
```

A failing arch test means code violates a boundary — fix the code, never relax the rule.

---

#### Mapper Tests

Mappers must be tested independently. They are the contract between domain and API.
No Spring context needed — instantiate MapStruct implementation directly.

```
[ ] should_map_[domain_object]_to_[response_dto]_correctly
    Given: domain object with known field values
    When:  mapper.toResponse() is called
    Then:  all response fields match expected values

[ ] should_map_[request_dto]_to_[domain_object]_correctly
    Given: request DTO with known field values
    When:  mapper.toDomain() is called
    Then:  all domain fields match expected values

[ ] should_map_null_[optional_field]_to_null_in_response
    (cover nullable field handling explicitly)
```

---

## Output Template

When generating a spec, produce a Markdown file with this structure (all sections required):

```markdown
# SPEC.md — [Feature Name]

## 1. Overview
[2-3 sentences. What does this feature do, who uses it, why does it exist?]

**Primary users:** ...
**Secondary users:** ...
**Tech stack:** ...
**Scope:** v[X.Y] — [Release name or description]

---

## 2. Goals & Non-Goals

### Goals
- ...

### Non-Goals (Out of Scope)
- ...

---

## 3. Requirements

### [Functional Area 1]
- [MUST] ...
- [SHOULD] ...
- [COULD] ...
- [WONT] ...

### [Functional Area 2]
- ...

---

## 4. Domain Model

[Plain-language description of entities, relationships, ownership, and lifecycle states.]

---

## 5. Architecture & File Structure

### Architecture Pattern
**Modular Monolithic (Fixed)** — module registry defined in `PRODUCT.md`.

### Module Ownership
**Primary module:** `[confirmed-module-name]`
**Cross-module dependencies:** [none | list affected modules + interaction type: Query/Command/SharedContract]

### Inter-Module Communication
| Interaction | Type | Mechanism |
|---|---|---|
| [this module] needs [data] from [other module] | Query | Calls [other-module] REST API |
| [this module] completing [action] triggers [other module] | Command | Publishes [WhatHappened]Event |

### Feature Files
```
[confirmed-module]/src/main/java/com/[org]/[confirmed-module-package]/
├── api/controller/[Resource]Controller.java
├── api/dto/request/[Action][Resource]Request.java
├── api/dto/response/[Resource]Response.java
├── domain/[CoreAggregate].java
├── service/[Capability]Service.java
├── repository/[Resource]Repository.java
├── mapper/[Resource]Mapper.java
├── event/published/[WhatHappened]Event.java       (if applicable)
├── event/listener/[TriggerEvent]Listener.java     (if applicable)
├── statemachine/...                               (if applicable — 4+ guarded states only)
└── exception/[Resource]NotFoundException.java    (if applicable)

Cross-module files (only if explicitly approved):
[other-module]/event/listener/[TriggerEvent]Listener.java  — [what it does]
```

---

## 6. DB Schema

### Tables
[Table definitions with fields, types, indexes, FKs per Stage 4 format.]

### Flyway Migrations
```
V[n]__create_[table_name].sql       — [what it creates]
V[n+1]__create_[table_name].sql     — [what it creates]
```
Current highest migration version confirmed with user: V[n-1]

---

## 7. API Contracts

[Endpoint definitions per Stage 5 format. Error responses use ErrorResponse from shared/common/dto.
Exception classes: ResourceNotFoundException (404), BusinessRuleException (422), [System]IntegrationException (502).]

---

## 8. Business Logic

### State Machine: [Entity Name]
[Table of valid transitions, triggers, guards, and forbidden transitions.]

### Validation Rules
[List of invariants that must hold before writes succeed. These become domain/ tests.]

### Mapper Responsibilities
[List of mappings: toResponse() and toDomain() for each resource.]

### Inter-Module Events
**Published:** [WhatHappened]Event — fired by [Service].[method]() — payload: { fields }
**Consumed:** [WhatHappened]Event from [other-module] — handled by [TriggerEvent]Listener

### External System Calls
[None | System → shared/[system-acl]/service/[Facade]Service — called from [Capability]Service]

### Concurrency & Locking
[Named strategy: pessimistic row lock / optimistic @Version / Redis distributed lock / DB unique constraint]

### Authorization Rules
[Who can do what, under what conditions. Mapped to @PreAuthorize annotations.]

### Background Jobs
[Trigger, retry policy, on-exhaustion behavior. N/A if none.]

---

## 9. Error Handling

All exceptions caught by GlobalExceptionHandler in shared/common/exception/. Never define @ControllerAdvice in a module.

| Scenario | Exception Class | HTTP | User Message | Internal Action | Alert? |
|---|---|---|---|---|---|
| [scenario] | [ExceptionClass] | [code] | "[message]" | [action] | Yes/No |

[Prose for idempotency, retry strategy, and partial failure rollback where needed.]

---

## 10. Acceptance Criteria (Tests)

### Layer 1 — `domain/` (Pure Unit Tests)
**Happy path:**
- [ ] should_[behaviour]_when_[valid_precondition]

**Negative path:**
- [ ] should_throw_BusinessRuleException_when_[invalid_state]

**Edge cases:**
- [ ] should_throw_when_[field]_is_null

### Layer 2 — `service/` (Orchestration Unit Tests — Mockito only)
**Happy path:**
- [ ] should_[complete_use_case]_when_[valid_input]
- [ ] should_publish_[Event]_after_[action]

**Negative path:**
- [ ] should_throw_[Resource]NotFoundException_when_entity_not_found
- [ ] should_throw_[System]IntegrationException_when_external_call_fails
- [ ] should_propagate_BusinessRuleException_when_domain_rejects_transition

### Layer 3 — `api/` (@WebMvcTest — HTTP contract only)
**Happy path:**
- [ ] should_return_200_when_[resource]_found
- [ ] should_return_201_when_[resource]_created

**Negative path:**
- [ ] should_return_400_when_required_field_is_missing
- [ ] should_return_404_when_[resource]_does_not_exist
- [ ] should_return_422_when_business_rule_is_violated
- [ ] should_return_403_when_caller_lacks_required_role

### Layer 4 — `arch/` (ArchUnit boundary rules)
- [ ] domain_must_not_depend_on_infrastructure
- [ ] controllers_must_not_import_repositories
- [ ] no_cross_module_repository_access
- [ ] events_must_not_contain_entities
- [ ] mappers_must_not_call_services

### Mapper Tests
- [ ] should_map_[domain]_to_[response]_correctly
- [ ] should_map_[request]_to_[domain]_correctly

---

## 11. Open Questions

> Unresolved decisions that may affect implementation. Must be resolved before building
> the related section. Do NOT silently resolve these — surface them here.

- [ ] [Question] — [Why it matters / what it blocks]
- [ ] ...

---

## 12. Change Log

| Version | Date | Author | Summary |
|---------|------|--------|---------|
| 1.0 | YYYY-MM-DD | [Author] | Initial spec |
```

---

## Agent Rules (Non-Negotiable)

These rules apply to every spec the agent generates:

1. **Always run the Confirmation Gate.** For every stage: draft outline → stop → ask for confirmation → write full content. Never write full content before the outline is confirmed. Never move to the next stage before the current one is confirmed and written.
2. **Never skip a section.** If a section doesn't apply, write "N/A — [reason]."
3. **Never silently resolve ambiguity.** If something is unclear, put it in Open Questions.
4. **Every MUST requirement must have at least one acceptance criterion.**
5. **Every error scenario must have a human-readable user message defined.**
6. **Every payment or money-adjacent flow must define idempotency and rollback behavior explicitly.**
7. **Every state machine must define forbidden transitions, not just allowed ones.**
8. **API errors use `ErrorResponse` from `shared/common/dto/` and exceptions from `shared/common/exception/`.** Never define a raw `{ error, code }` JSON shape inline. Never extend `RuntimeException` directly in a module.
9. **DB schema must include indexes for every FK and every commonly-filtered column. Every table must have a corresponding Flyway migration file listed.**
10. **Concurrency and locking strategy must be named explicitly** — "the system handles this" is not acceptable.
11. **Tag every requirement** with MUST / SHOULD / COULD / WONT. Untagged requirements will be ignored.
12. **Architecture and file structure must be confirmed before DB Schema is written** — no schema without a known module home.
13. **`mapper/` must always be included.** No field mapping inside services or controllers — ever.
14. **Inter-module events must be fully specified** — event name, payload fields (no `@Entity`), publisher location, listener location. If a feature has side effects in another module, it must publish an event.
15. **External system calls must go through `shared/[system-acl]/service/`** — never directly inside a module service. Flag any external integration and name the ACL facade class.
16. **Tests must be assigned to the correct layer** — `domain/` for rules, `service/` for orchestration, `api/` for HTTP contract, `arch/` for boundaries, `mapper/` for translation. Every module must have an `arch/` test.

---

In every case: ask the Pre-Generation Checklist questions first if the context is missing,
then generate the full spec in order.
