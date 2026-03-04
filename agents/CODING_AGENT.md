# CODING_AGENT.md — Spring & Java Coding Standards

> Read this file before writing any production code.
> These are the coding standards for this codebase. Every class, method, and annotation
> must follow these rules. When in doubt, refer to `docs/MODULE_STRUCTURE_TEMPLATE.md` for
> where code lives and this file for how to write it.

---

## Before Writing Any Code — Checklist

- [ ] Read `PRODUCT.md` — confirm which module owns this feature, check the inter-module event map and ACL facade names
- [ ] Read the confirmed `SPEC.md` for this feature — domain model, API, business logic, error handling
- [ ] Read `docs/MODULE_STRUCTURE_TEMPLATE.md` — confirm which package and layer each class belongs to
- [ ] Read `agents/TEST_AGENT.md` — testing implementation/execution/logging ownership is delegated to the Test Agent

### Scope Boundary for CODING_AGENT

- CODING_AGENT owns **production code quality and architecture compliance**.
- CODING_AGENT does **not** own end-to-end test authoring/execution workflow by default.
- Test creation, execution, and failure/fix logging are owned by `agents/TEST_AGENT.md`.
- If explicitly asked to write tests in the same task, follow `docs/TESTING_GUIDE.md` and coordinate with TEST_AGENT standards.

---

## Java & Spring Version Baseline

```
Java:          21 (use records, sealed classes, pattern matching, text blocks where appropriate)
Spring Boot:   3.5.11
Spring:        6.x
MapStruct:     1.5.x
Lombok:        use sparingly — prefer Java records for DTOs and value objects
JPA:           Jakarta Persistence (jakarta.persistence.*) — NOT javax.persistence
Flyway:        version managed in parent pom.xml only
```

---

## General Coding Rules

### Immutability First

Prefer immutable objects. Use Java records for DTOs, events, and value objects.
Only use mutable classes when the lifecycle explicitly requires mutation (e.g. JPA entities).

```java
// ✅ Correct — record for response DTO
public record BookingResponse(UUID id, String status, UUID userId) {}

// ✅ Correct — record for event payload
public record BookingConfirmedEvent(UUID bookingId, UUID userId, UUID showSlotId) {}

// ❌ Wrong — mutable class for a DTO
public class BookingResponse {
    private UUID id;
    public void setId(UUID id) { this.id = id; }
}
```

### No Nulls in Public APIs

Use `Optional<T>` for values that may be absent. Never return `null` from a public method.
Never accept `null` as a valid input — validate at the boundary with `@NotNull`.

```java
// ✅ Correct
public Optional<Booking> findById(UUID id) {
    return bookingRepository.findById(id);
}

// ❌ Wrong — null return leaks nullability into callers
public Booking findById(UUID id) {
    return bookingRepository.findById(id).orElse(null);
}
```

### Use `Money` for All Monetary Values

Never use `double`, `float`, or raw `BigDecimal` for monetary amounts.
Always use the `Money` value object from `shared/common/domain/Money`.

```java
// ✅ Correct
private final Money price;

// ❌ Wrong
private BigDecimal price;
private double price;
```

### Logging

Use SLF4J with `@Slf4j` (Lombok). Never use `System.out.println`.
Log at the correct level:

| Level | When to Use |
|---|---|
| `TRACE` | Verbose debug — method entry/exit during development only |
| `DEBUG` | Internal state during a complex operation |
| `INFO` | Significant business events: booking confirmed, payment received |
| `WARN` | Recoverable issues: seat lock expired, retry attempted |
| `ERROR` | Unrecoverable failures: payment charged but DB write failed |

Always include structured context. Use MDC for `traceId` and `requestId` (configured in `shared/config/TracingConfig`).

```java
// ✅ Correct
log.info("Booking confirmed. bookingId={} userId={} amount={}", bookingId, userId, amount);
log.error("DB write failed after payment charge. bookingId={} paymentIntentId={}", bookingId, paymentIntentId, e);

// ❌ Wrong
System.out.println("booking done");
log.info("Booking confirmed: " + bookingId); // string concatenation, not parameterised
```

---

## Inter-Module Communication Patterns

Modules communicate via REST (sync queries) or Spring Events (async commands). Never import another module's @Service directly (exception: `admin/` may import module `@Service` for READ-only operations).

**Pattern 1: REST Query** — Module B needs data from upstream Module A
```java
// ✅ Call REST API
private final RestClient restClient;
public Payment getPaymentDetails(UUID paymentId) {
    return restClient.get("/api/payments/{id}", paymentId, Payment.class);
}

// ❌ Never import @Service
private final PaymentService paymentService; // WRONG
```

**Pattern 2: Spring Events** — Module A publishes, others listen
```java
// Module A publishes
eventPublisher.publishEvent(new PaymentConfirmedEvent(bookingId, amount));

// Module B listens (delegates immediately to service)
@Component
public class PaymentConfirmedListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        bookingService.confirmBooking(event.bookingId());
    }
}

// ❌ Never call service directly from event trigger
// ❌ Never block the listener — delegate immediately
```

**Pattern 3: Shared Contracts** — Enums, DTOs, base classes in `shared/common/` only
```java
// ✅ Define once in shared/common/enums/
public enum BookingStatus { PENDING, CONFIRMED, CANCELLED, REFUNDED }

// ❌ Never duplicate in multiple modules
```

**Pattern 4: Shared Reader Interfaces (hot-path in-process reads)** — For high-frequency reads where HTTP overhead is unacceptable, define a read-only interface in `shared/common/service/` and implement it in the owning module. The consuming module injects the `shared/` interface — never the owning module's concrete `@Service`.

```java
// In shared/common/service/ — interface only, no implementation
public interface CartSnapshotReader {
    List<CartItemSnapshotDto> getCartItems(Long cartId);
}

// In booking-inventory/service/ — owns the data, implements the contract
@Service
@Transactional(readOnly = true)
public class CartSnapshotReaderImpl implements CartSnapshotReader {
    private final CartItemRepository cartItemRepository;
    public List<CartItemSnapshotDto> getCartItems(Long cartId) {
        return cartItemRepository.findByCartId(cartId).stream()
            .map(mapper::toDto).toList();
    }
}

// In payments-ticketing/service/ — injects shared interface only, never the impl directly
@Service
public class PaymentService {
    private final CartSnapshotReader cartSnapshotReader; // ✅ shared interface
    // ❌ private final CartSnapshotReaderImpl ... // WRONG — direct module import
}
```

Current shared reader interfaces:
- `SlotSummaryReader` → implemented in `scheduling`, consumed by `booking-inventory`
- `SlotPricingReader` → implemented in `scheduling`, consumed by `booking-inventory`
- `CartSnapshotReader` → implemented in `booking-inventory`, consumed by `payments-ticketing` (payment confirmation hot path)
- `PaymentConfirmationReader` → implemented in `payments-ticketing`, consumed by `booking-inventory` `PaymentTimeoutWatchdog`

---

## Eventbrite ACL Facades — The Only Door to External Systems

**HARD RULE: No module calls Eventbrite HTTP directly. All calls go through shared/eventbrite/service/ facades only.**

### The 10 Eventbrite Facades (All in `shared/eventbrite/service/`)

| Facade | Used By | Purpose |
|---|---|---|
| EbEventSyncService | discovery-catalog, scheduling, engagement | Create, update, publish, cancel events; pull catalog |
| EbVenueService | scheduling | Create/update venues; list org venues |
| EbScheduleService | scheduling | Create recurring event schedules |
| EbTicketService | discovery-catalog, booking-inventory | Create/update ticket classes; check availability |
| EbCapacityService | admin/ | Update event capacity tiers |
| EbOrderService | admin/ (reporting only) | Read EB orders for organizer-side admin reporting only — **NOT used in FR5/FR6 payment flows**; Stripe owns all payments |
| EbAttendeeService | engagement | Get attendees; verify attendance for reviews (FR8 only) |
| EbDiscountSyncService | promotions | Create/update/delete discount codes |
| EbRefundService | — (inactive) | Retained for legacy compatibility only — **do NOT call for any refund**; all refunds via `StripeRefundService` |
| EbWebhookService | mock-eventbrite-api only | Mock/testing webhook registration and delivery simulation (NOT production) |

### Correct Pattern

```java
// ✅ Inject facade, call it
@Service
public class EventService {
    private final EbEventSyncService ebEventService; // from shared/
    
    public Event createEvent(EventRequest request) {
        Event event = new Event(request.name(), request.venue());
        eventRepository.save(event); // save internally first
        
        EbEventResponse ebResp = ebEventService.createEvent(
            mapper.toEbCreateRequest(event));
        event.setEventbriteEventId(ebResp.id()); // store EB ID
        eventRepository.save(event);
        return event;
    }
}

// ❌ Never import and call HTTP client directly
private final EventbriteWebClient ebClient;
public void createEvent(...) {
    ebClient.post("/events", ...); // VIOLATES HARD RULE #2
}
```

### Critical Constraints (These Don't Exist on Eventbrite)

- **No user creation API** — Users 100% internal; link post-purchase via order email
- **No order creation API** — Orders are created internally in your `bookings` table when Stripe payment is confirmed. The EB Checkout Widget is **removed** — do NOT use or reference it.
- **No single-order cancel** — Only bulk event cancel; per-order buyer cancellation uses `StripeRefundService` — no EB call involved
- **No seat lock API** — Entire state machine (AVAILABLE → SOFT_LOCKED → HARD_LOCKED → CONFIRMED) is internal Redis + Spring State Machine
- **No refund submission API** — All refunds submitted via `StripeRefundService` (`POST /v1/refunds`). `EbRefundService` is **inactive** — do NOT call it for any refund flow.
- **No conflict validation API** — Turnaround gaps enforced entirely in internal DB
- **No reviews API** — Reviews 100% internal; Eventbrite attendance verification only (EbAttendeeService, engagement module, FR8 only)

**See docs/EVENTBRITE_INTEGRATION.md for full constraints and workarounds.**

---

## Stripe ACL Facades — Payments and Refunds

**HARD RULE: No module calls Stripe HTTP directly. All Stripe calls go through `shared/stripe/service/` facades only.**

### The 3 Stripe Facades (All in `shared/stripe/service/`)

| Facade | Used By | Purpose |
|---|---|---|
| `StripePaymentService` | payments-ticketing | `POST /v1/payment_intents` — create PaymentIntent; `GET /v1/payment_intents/{id}` — retrieve and verify status |
| `StripeRefundService` | payments-ticketing | `POST /v1/refunds` — full or partial refund; `GET /v1/refunds/{id}` — retrieve async refund status |
| `StripeWebhookHandler` | payments-ticketing (StripeWebhookController) | Verify `Stripe-Signature` header via `Webhook.constructEvent()`; routes events to service methods |

### Key Rules

- `client_secret` **must never be stored or logged** — pass to frontend response DTO directly and discard
- `stripe_payment_intent_id` (`pi_...`) and `stripe_charge_id` (`ch_...`) are stored in `payments` and `bookings` tables
- All amounts in **smallest currency unit** (paise for INR) on the wire and in DB; convert for display only
- Stripe webhook endpoint (`POST /api/webhooks/stripe`) **must verify** `Stripe-Signature` before any processing; return `400` on failure
- All webhook handlers are **idempotent** — check current state before acting; skip if already in target state

```java
// ✅ Correct — use facade only
@Service
public class PaymentService {
    private final StripePaymentService stripePaymentService; // from shared/stripe/service/

    public CheckoutInitResponse createCheckout(CartAssembledEvent event) {
        StripePaymentIntentResponse resp = stripePaymentService.createPaymentIntent(
            new StripePaymentIntentRequest(
                event.totalAmountInSmallestUnit(), event.currency(),
                event.userEmail(), "Booking " + bookingRef, idempotencyKey,
                Map.of("booking_ref", bookingRef)
            )
        );
        // clientSecret → return to caller only, never persist
        return new CheckoutInitResponse(cartId, bookingRef, resp.paymentIntentId(), resp.clientSecret(), ...);
    }
}

// ❌ Never call Stripe SDK directly from a module
private final Stripe stripe; // WRONG — violates Hard Rule #4
PaymentIntent.create(params);  // WRONG — must go through StripePaymentService
```

Admin owns no domain, no @Entity classes, no database tables. It reads and orchestrates.

### Reading from Modules — Direct Import OK

```java
// ✅ Reads only — can import @Service directly (same JVM)
@Service
public class AdminDashboardService {
    private final BookingService bookingService;  // from booking-inventory
    private final PaymentService paymentService;  // from payments-ticketing
    
    public Dashboard getMetrics() {
        return new Dashboard(
            bookingService.getTotalBookings(),
            paymentService.getTotalRevenue()
        );
    }
}
```

### Writing to Modules — REST API Only

```java
// ❌ WRONG — bypasses module's validation and events
private final BookingRepository bookingRepository;
public void bulkCancel(...) {
    bookingRepository.deleteAll(bookings); // skips business rules
}

// ✅ CORRECT — calls module's REST API
public void bulkCancel(List<UUID> bookingIds) {
    for (UUID id : bookingIds) {
        restClient.delete("/api/bookings/{id}", id);
    }
}
```

---

## Layer-by-Layer Coding Standards

---

### Domain Layer (`domain/`)

The domain is the most important layer. It owns all business rules. It has no knowledge
of Spring, JPA, HTTP, or any infrastructure.

**Rules:**
- No `@Service`, `@Component`, `@Repository`, `@Autowired` in domain classes
- No Spring framework imports of any kind
- Entities have **behaviour methods** — not just getters and setters
- All state transitions go through domain methods — never set a status field directly from a service
- Throw `BusinessRuleException` (from `shared/`) for rule violations
- All entities extend `BaseEntity` from `shared/common/domain/`

**Note on JPA in domain:**
This codebase intentionally keeps JPA annotations on domain entities (standard Spring Data JPA
approach). We accept the tradeoff (domain is not fully persistence-ignorant) to avoid the
overhead of a separate persistence model. Do not introduce a second model unless explicitly
approved.

```java
// ✅ Correct domain entity — has behaviour, enforces its own rules
@Entity
@Table(name = "bookings")
public class Booking extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false)
    private UUID userId;

    // Behaviour method — enforces the rule itself
    public void confirm() {
        if (this.status != BookingStatus.PAYMENT_PENDING) {
            throw new BusinessRuleException(
                "Cannot confirm a booking in status: " + this.status);
        }
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        if (this.status == BookingStatus.CANCELLED) {
            throw new BusinessRuleException("Booking is already cancelled");
        }
        if (this.status == BookingStatus.FAILED) {
            throw new BusinessRuleException("Cannot cancel a failed booking");
        }
        this.status = BookingStatus.CANCELLED;
    }

    // ❌ Never expose raw status setter — callers must use behaviour methods
    // public void setStatus(BookingStatus status) { ... }
}
```

**Value objects are immutable records:**

```java
// ✅ Correct — value object as Java record
public record SeatReference(UUID seatId, String row, int number, PricingTier tier) {

    // Validate in the compact constructor
    public SeatReference {
        Objects.requireNonNull(seatId, "seatId must not be null");
        Objects.requireNonNull(row, "row must not be null");
        if (number < 1) throw new IllegalArgumentException("seat number must be positive");
    }
}
```

---

### Service Layer (`service/`)

The service orchestrates: it calls domain methods, saves via repositories, publishes events,
and calls ACL facades for external systems. It owns no business logic itself — the domain does.

**Rules:**
- Annotate with `@Service`
- Use constructor injection — never `@Autowired` on fields
- One service per capability group (e.g. `BookingService`), not one per entity
- Methods map to use cases, not CRUD operations
- **Mapper ownership:** Controllers call the mapper to convert the returned domain object → response DTO. Services call the mapper to convert inbound request DTOs / commands → domain objects before persistence. Neither layer maps fields manually.
- Never map fields manually in a service or controller — always delegate to a `@Mapper` class
- Wrap multi-step operations in `@Transactional`
- Never call another module's `@Service` directly — use REST or Events (exception: `admin/` read-only aggregation)
- **CRITICAL: All external system calls go ONLY through ACL facade services from `shared/`. Never call Eventbrite or OpenAI HTTP directly. See "Eventbrite ACL Facades" section above in "Coding Standards".**

```java
// ✅ Correct service
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EbOrderService ebOrderService; // ACL facade from shared/eventbrite/service/

    @Transactional
    public Booking confirmBooking(UUID bookingId) {
        // 1. Load the entity — throw if not found
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        // 2. Delegate to domain — domain enforces the rule
        booking.confirm();

        // 3. Persist
        bookingRepository.save(booking);

        // 4. Publish event — other modules react asynchronously
        eventPublisher.publishEvent(new BookingConfirmedEvent(
            booking.getId(), booking.getUserId(), booking.getShowSlotId()));

        log.info("Booking confirmed. bookingId={} userId={}", bookingId, booking.getUserId());
        return booking;
    }

    @Transactional
    public Booking initiateBooking(CreateBookingCommand command) {
        // 5. Checkout happens via Eventbrite JS widget; backend only reads order after callback
        EbOrder order = ebOrderService.getOrderById(command.eventbriteOrderId());

        Booking booking = new Booking(command.userId(), command.cartId(), order.id());
        bookingRepository.save(booking);

        eventPublisher.publishEvent(new BookingInitiatedEvent(booking.getId(), booking.getUserId()));
        return booking;
    }
}
```

---

### Repository Layer (`repository/`)

Spring Data JPA interfaces. No business logic. No service calls. No event publishing.

**Rules:**
- Extend `JpaRepository<Entity, UUID>` — always use UUID primary keys
- Custom queries use `@Query` with JPQL — avoid native SQL unless absolutely necessary
- No other module imports this interface
- Projection interfaces for read-only queries that return partial data

```java
// ✅ Correct repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    // JPQL — preferred over native SQL
    @Query("SELECT b FROM Booking b WHERE b.userId = :userId ORDER BY b.createdAt DESC")
    List<Booking> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);

    // Paginated query — use Page<T> for any list endpoint exposed to the API
    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    // Projection for read-only use case
    Page<BookingSummaryProjection> findByShowSlotId(UUID showSlotId, Pageable pageable);

    // Existence check — more efficient than findById
    boolean existsByUserIdAndShowSlotId(UUID userId, UUID showSlotId);
}
```

### Pagination Rules

- **Always use `Page<T>` and `Pageable` for any list endpoint** exposed via the API — never return unbounded `List<T>` from a controller.
- Accept `Pageable` as a controller parameter via `@PageableDefault` — Spring auto-binds `?page=0&size=20&sort=createdAt,desc`.
- Pass `Pageable` through service → repository unchanged. Never construct a `PageRequest` inside a service unless the page size is a business rule (e.g. max 8 seats).
- Return `Page<ResponseDTO>` from the controller, wrapped in `ApiResponse`.

```java
// ✅ Correct paginated controller
@GetMapping
@PreAuthorize("hasRole('USER')")
public ResponseEntity<ApiResponse<Page<BookingSummaryResponse>>> listBookings(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    Page<Booking> page = bookingService.listBookings(pageable);
    return ResponseEntity.ok(ApiResponse.success(page.map(bookingMapper::toSummaryResponse)));
}

// ✅ Correct paginated service method
@Transactional(readOnly = true)
public Page<Booking> listBookings(Pageable pageable) {
    return bookingRepository.findAll(pageable);
}

// ❌ Wrong — unbounded list; will break under load
public List<Booking> listBookings() {
    return bookingRepository.findAll();
}
```

---

### Mapper Layer (`mapper/`)

MapStruct interfaces. Pure translation — no business logic, no service calls, no conditions
other than null checks.

**Rules:**
- Annotate with `@Mapper(componentModel = "spring")`
- Always declare `@Mapping` for fields with different names
- Never call a service from inside a mapper
- Never apply business rules inside a mapper
- Test every mapper in isolation — instantiate via `Mappers.getMapper()`

```java
// ✅ Correct mapper
@Mapper(componentModel = "spring")
public interface BookingMapper {

    @Mapping(source = "showSlotId", target = "slotId")
    @Mapping(source = "status", target = "bookingStatus")
    BookingResponse toResponse(Booking booking);

    @Mapping(target = "id", ignore = true)           // DB generates the ID
    @Mapping(target = "createdAt", ignore = true)    // BaseEntity sets this
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "status", constant = "INITIATED")
    Booking toDomain(CreateBookingRequest request);
}

// ❌ Wrong — business logic inside a mapper
@Mapper(componentModel = "spring")
public interface BookingMapper {
    default BookingResponse toResponse(Booking booking) {
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            // applying logic — wrong layer
            return new BookingResponse(booking.getId(), "REFUNDED");
        }
        ...
    }
}
```

---

### Controller Layer (`api/controller/`)

Handles HTTP. Validates input. Delegates to service. Maps result to response DTO. Nothing else.

**Rules:**
- Annotate with `@RestController` and `@RequestMapping`
- Use `@PreAuthorize` for role-based access — never check roles in service or domain
- Validate request body with `@Valid` — never manually validate in controller code
- Call service, pass the result to the mapper, return the response — that is all
- Never catch exceptions in the controller — let `GlobalExceptionHandler` handle them
- Return `ResponseEntity<ApiResponse<T>>` for all responses
- Never define `@ExceptionHandler` in a controller — use `GlobalExceptionHandler` only

```java
// ✅ Correct controller
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Booking management endpoints")
public class BookingController {

    private final BookingService bookingService;
    private final BookingMapper bookingMapper;

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Get booking by ID")
    public ResponseEntity<ApiResponse<BookingResponse>> getBooking(
            @PathVariable UUID id) {
        Booking booking = bookingService.getBooking(id);
        return ResponseEntity.ok(ApiResponse.success(bookingMapper.toResponse(booking)));
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Initiate a new booking")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {
        Booking booking = bookingService.initiateBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(bookingMapper.toResponse(booking)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> cancelBooking(@PathVariable UUID id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.noContent().build();
    }
}
```

**Request DTOs use Bean Validation:**

```java
// ✅ Correct request DTO
public record CreateBookingRequest(

    @NotNull(message = "cartId is required")
    UUID cartId,

    @NotNull(message = "paymentMethodId is required")
    @NotBlank(message = "paymentMethodId must not be blank")
    String paymentMethodId,

    @NotEmpty(message = "seatIds must not be empty")
    @Size(max = 8, message = "Cannot book more than 8 seats")
    List<@NotNull UUID> seatIds
) {}
```

---

### Event Layer (`event/`)

**Published events** are immutable records. **Listeners** delegate immediately to service — no logic.

```java
// ✅ Correct published event — record, primitives/IDs only
public record BookingConfirmedEvent(
    UUID bookingId,
    UUID userId,
    UUID showSlotId,
    Money totalAmount   // value object is allowed
    // ❌ Never: Booking booking — no @Entity in events
) {}
```

```java
// ✅ Correct listener — delegates immediately, no logic
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedListener {

    private final ETicketService eTicketService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        log.info("Handling BookingConfirmedEvent. bookingId={}", event.bookingId());
        eTicketService.generateTicket(event.bookingId()); // delegate — no logic here
    }
}
```

**Use `@TransactionalEventListener(phase = AFTER_COMMIT)`** for events that must not fire
if the transaction rolls back (e.g. booking confirmed → generate ticket).
Use `@EventListener` only for events that should fire regardless of transaction outcome.

### Async Event Error Handling (Mandatory)

`@Async` listeners run outside the original transaction. Uncaught exceptions are swallowed by the thread pool and will not surface to the caller. Every `@Async` listener **must** handle its own failures explicitly.

**Rules:**
- Wrap the delegated service call in a try/catch — log the error with full context at `ERROR` level
- Never let an async listener throw silently — it will look like a success to the publisher
- For business-critical side effects (e.g. e-ticket generation, wallet credit), publish a compensating event or persist a retry record so the operation can be retried
- Configure a global `AsyncUncaughtExceptionHandler` in `shared/config/AsyncConfig` as a last-resort safety net

```java
// ✅ Correct async listener — explicit error handling
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedListener {

    private final ETicketService eTicketService;
    private final FailedEventRepository failedEventRepository; // for retry persistence

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        log.info("Handling BookingConfirmedEvent. bookingId={}", event.bookingId());
        try {
            eTicketService.generateTicket(event.bookingId());
        } catch (Exception e) {
            // Log with full context — never swallow silently
            log.error("E-ticket generation failed after booking confirmed. bookingId={} userId={}",
                event.bookingId(), event.userId(), e);
            // Persist for retry — do not re-throw (async thread cannot propagate to caller)
            failedEventRepository.save(FailedEvent.from(event, e.getMessage()));
        }
    }
}

// ✅ Correct global fallback in shared/config/AsyncConfig.java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Unhandled async exception in method={}. params={}", method.getName(), params, ex);
    }
}

// ❌ Wrong — exception silently swallowed, ticket never generated, no trace
@Async
public void onBookingConfirmed(BookingConfirmedEvent event) {
    eTicketService.generateTicket(event.bookingId()); // if this throws, nobody knows
}
```

---

### Exception Handling

Never define `@ControllerAdvice` or `@ExceptionHandler` in a module.
All exceptions are caught by `GlobalExceptionHandler` in `shared/common/exception/`.

**Module-specific exceptions extend the shared base classes:**

```java
// ✅ Correct — extends shared base, adds module-specific context
public class BookingNotFoundException extends ResourceNotFoundException {
    public BookingNotFoundException(UUID bookingId) {
        super("Booking with id " + bookingId + " not found");
    }
}

// ❌ Wrong — extends RuntimeException directly, bypasses GlobalExceptionHandler mapping
public class BookingNotFoundException extends RuntimeException { ... }
```

**Throw the right exception:**

```java
// 404 — entity not found
throw new BookingNotFoundException(bookingId);

// 422 — domain rule violated
throw new BusinessRuleException("Cannot confirm a booking in status: " + status);

// 502 — external system failure
throw new SystemIntegrationException("Eventbrite API returned 503: " + detail);
```

---

### Spring State Machine (for `booking-inventory`)

Used only when an entity has 4+ states with guarded transitions. See `PRODUCT.md` for the
seat lock state machine definition.

**Rules:**
- Config class defines all states, events, transitions, guards, and actions
- Guards return `boolean` — they check pre-conditions only, no side effects
- Actions execute side effects — they do not check conditions
- `StateMachineService` is the only class that calls the state machine — never call it from a controller
- All Redis lock operations happen inside `Action` classes — never inside the service or domain

```java
// ✅ Correct guard — pure condition check
@Component
public class IsSeatAvailableGuard implements Guard<SeatLockState, SeatLockEvent> {

    private final SeatRepository seatRepository;

    @Override
    public boolean evaluate(StateContext<SeatLockState, SeatLockEvent> context) {
        UUID seatId = (UUID) context.getExtendedState().getVariables().get("seatId");
        return seatRepository.findById(seatId)
            .map(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
            .orElse(false);
    }
}

// ✅ Correct action — executes side effect
@Component
@RequiredArgsConstructor
@Slf4j
public class AcquireRedisLockAction implements Action<SeatLockState, SeatLockEvent> {

    private final RedissonClient redissonClient;

    @Override
    public void execute(StateContext<SeatLockState, SeatLockEvent> context) {
        UUID seatId = (UUID) context.getExtendedState().getVariables().get("seatId");
        RLock lock = redissonClient.getLock("seat:lock:" + seatId);
        boolean acquired = lock.tryLock(100, 600_000, TimeUnit.MILLISECONDS); // 10 min TTL
        if (!acquired) {
            log.warn("Failed to acquire Redis lock for seat. seatId={}", seatId);
            context.getStateMachine().setStateMachineError(
                new BusinessRuleException("Seat is already locked by another user"));
        }
    }
}
```

---

### Spring AI — `engagement/service/chatbot/`

The AI chatbot lives inside the `engagement` module at `engagement/service/chatbot/`.
It is not a standalone module. Uses Spring AI with OpenAI GPT-4o. RAG over event catalog
via Elasticsearch vector store. Tool Calling wired to internal and Eventbrite search APIs.

**Rules:**
- All OpenAI API calls go through `OpenAiChatService` in `shared/openai/` — never call the OpenAI client directly from the module
- Tool definitions are registered in the service layer, not in the controller
- RAG retrieval queries Elasticsearch via `shared/config/ElasticsearchConfig`
- Conversation history is stored in `chat_sessions` / `chat_messages` — owned by `engagement`

```java
// ✅ Correct AI service — calls ACL facade, never OpenAI client directly
@Service
@RequiredArgsConstructor
@Slf4j
public class EventAssistantService {

    private final OpenAiChatService openAiChatService;       // ACL facade from shared/
    private final EventCatalogSearchTool eventSearchTool;    // Tool definition (internal)
    private final EventbriteSearchTool eventbriteSearchTool; // Tool definition (via ACL)
    private final ChatSessionRepository chatSessionRepository;

    public String chat(UUID userId, String userMessage) {
        ChatSession session = chatSessionRepository.findActiveByUserId(userId)
            .orElseGet(() -> createNewSession(userId));

        // RAG retrieval happens inside OpenAiChatService — not here
        String response = openAiChatService.chat(
            session.getHistory(),
            userMessage,
            List.of(eventSearchTool, eventbriteSearchTool)
        );

        session.addMessage(userMessage, response);
        chatSessionRepository.save(session);

        return response;
    }
}
```

---

### ACL Facade Pattern (`shared/[system]/`)

Every external system integration follows this structure. Never deviate.

```java
// shared/eventbrite/service/EbOrderService.java
// ✅ Correct ACL facade — modules read order data from Eventbrite post-checkout
@Service
@RequiredArgsConstructor
@Slf4j
public class EbOrderService {

    private final EventbriteOrderWebClient eventbriteOrderWebClient; // raw HTTP in shared ACL only
    private final EbOrderMapper mapper;

    public EbOrder getOrderById(String orderId) {
        try {
            EbOrderResponse response = eventbriteOrderWebClient.getOrder(orderId);
            return mapper.toDomain(response);
        } catch (WebClientResponseException e) {
            log.error("Eventbrite order read failed. orderId={} status={}", orderId, e.getStatusCode());
            throw new SystemIntegrationException("Eventbrite order lookup failed: " + e.getMessage());
        }
    }
}
```

---

## Spring Security & JWT

All security configuration lives in `shared/config/SecurityConfig` and `shared/security/`. No module defines its own security filter or `SecurityFilterChain`.

### Where Things Live

| Component | Location | Responsibility |
|---|---|---|
| `SecurityConfig` | `shared/config/SecurityConfig` | Filter chain, CORS, CSRF, route matchers |
| `JwtAuthenticationFilter` | `shared/security/JwtAuthenticationFilter` | Validates JWT, populates `SecurityContext` |
| `JwtTokenProvider` | `shared/security/JwtTokenProvider` | Mint and verify JWT tokens |
| `CurrentUserArgumentResolver` | `shared/security/CurrentUserArgumentResolver` | Resolves `@CurrentUser` annotation in controllers |
| `AppUserDetails` | `shared/security/AppUserDetails` | Wraps `UserPrincipal` claims for Spring Security |

### Accessing the Authenticated User in Controllers

Never call `SecurityContextHolder.getContext()` directly in controllers or services. Use the `@CurrentUser` annotation resolved by `CurrentUserArgumentResolver`.

```java
// ✅ Correct — @CurrentUser injected by resolver
@GetMapping("/me/bookings")
@PreAuthorize("hasRole('USER')")
public ResponseEntity<ApiResponse<Page<BookingSummaryResponse>>> myBookings(
        @CurrentUser UserPrincipal principal,
        @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(ApiResponse.success(
        bookingService.listForUser(principal.userId(), pageable)
            .map(bookingMapper::toSummaryResponse)));
}

// ❌ Wrong — couples service to security context; untestable
public Page<Booking> listForUser() {
    UUID userId = (UUID) SecurityContextHolder.getContext()
        .getAuthentication().getPrincipal(); // never do this in a service
    return bookingRepository.findByUserId(userId, Pageable.unpaged());
}
```

### Authorization Rules

- Route-level security is declared in `SecurityConfig` (public routes, actuator, swagger)
- Method-level security uses `@PreAuthorize` on controller methods — never on service methods
- Role names follow `ROLE_` prefix convention: `hasRole('USER')`, `hasRole('ADMIN')`
- Never hardcode role strings outside of `@PreAuthorize` annotations — define constants in `shared/security/Roles`

```java
// ✅ Correct — constants from shared
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")

// ❌ Wrong — magic string duplicated across codebase
@PreAuthorize("hasRole('ADMIN')")
```

### JWT Claims Convention

Claims extracted from the token and available on `UserPrincipal`:

| Claim | Type | Description |
|---|---|---|
| `sub` | `UUID` | Internal user ID (`userId`) |
| `email` | `String` | User email |
| `roles` | `List<String>` | Granted roles |
| `walletId` | `UUID` | Linked wallet ID |

Never add module-specific claims to the JWT. If a service needs additional user context, fetch it via the `identity` module REST API.

---



Always use **constructor injection**. Never use field injection with `@Autowired`.

```java
// ✅ Correct — constructor injection via Lombok
@Service
@RequiredArgsConstructor
public class BookingService {
    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;
}

// ❌ Wrong — field injection
@Service
public class BookingService {
    @Autowired
    private BookingRepository bookingRepository;
}
```

---

## `@Transactional` Rules

| Scenario | Annotation |
|---|---|
| Service method that reads + writes DB | `@Transactional` |
| Service method that reads only | `@Transactional(readOnly = true)` |
| Event listener that must only fire after commit | `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| Multi-step operation with external call | `@Transactional` + manual compensation on external failure |
| Never on controllers | Controllers never have `@Transactional` |
| Never on domain methods | Domain methods are plain Java — no transaction management |

---

## API Documentation (Springdoc / OpenAPI)

All endpoints must be documented. Global config is in `shared/config/OpenApiConfig`.

```java
// ✅ Correct Springdoc annotations on controller
@Operation(summary = "Confirm a booking", description = "Confirms a booking after payment is verified.")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Booking confirmed"),
    @ApiResponse(responseCode = "404", description = "Booking not found",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "422", description = "Business rule violation",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
@PatchMapping("/{id}/confirm")
@PreAuthorize("hasRole('USER')")
public ResponseEntity<ApiResponse<BookingResponse>> confirmBooking(@PathVariable UUID id) {
    ...
}
```

---

## Code Quality Rules

### Naming

| Element | Convention | Example |
|---|---|---|
| Classes | PascalCase | `BookingService`, `SeatLockState` |
| Methods | camelCase, verb-first | `confirmBooking()`, `findByUserId()` |
| Variables | camelCase | `bookingId`, `paymentResult` |
| Constants | UPPER_SNAKE_CASE | `MAX_SEAT_COUNT`, `LOCK_TTL_SECONDS` |
| DB tables | snake_case | `booking_items`, `seat_locks` |
| DB columns | snake_case | `created_at`, `payment_intent_id` |
| Spring Events | PascalCase + `Event` suffix | `BookingConfirmedEvent` |
| Event Listeners | PascalCase + `Listener` suffix | `BookingConfirmedListener` |
| Exceptions | PascalCase + `Exception` suffix | `BookingNotFoundException` |

### What Never to Write

```java
// ❌ Never use Optional.get() without isPresent() — use orElseThrow()
Optional<Booking> opt = bookingRepository.findById(id);
Booking booking = opt.get(); // NullPointerException waiting to happen

// ✅ Correct
Booking booking = bookingRepository.findById(id)
    .orElseThrow(() -> new BookingNotFoundException(id));

// ❌ Never catch Exception or Throwable broadly
try {
    bookingService.confirm(id);
} catch (Exception e) {
    // swallows everything — hides real errors
}

// ❌ Never use @SuppressWarnings("unchecked") without a comment explaining why

// ❌ Never commit TODO comments — raise as Open Questions in the spec instead

// ❌ Never hardcode URLs, secrets, or config values
String url = "https://api.eventbrite.com/v3/orders"; // wrong
// Use @Value or @ConfigurationProperties from shared/config/

// ❌ Never use Thread.sleep() in production code — use scheduled tasks or events
```

---

## Checklist Before Committing Code

- [ ] Every new class is in the correct layer and package per `docs/MODULE_STRUCTURE_TEMPLATE.md`
- [ ] No `@Service`, `@Component`, or `@Autowired` in `domain/` classes
- [ ] No field mapping in services or controllers — all in `mapper/`
- [ ] **Mapper ownership: services use mapper for request DTO → domain; controllers use mapper for domain → response DTO. Neither maps manually.**
- [ ] **No external API calls inside module code — ALL through `shared/` ACL facades (Eventbrite, OpenAI)**
- [ ] **Eventbrite: No module calls Eventbrite HTTP directly. All calls via EbEventSyncService, EbVenueService, EbTicketService, EbOrderService, EbAttendeeService, etc.**
- [ ] **Eventbrite: Never attempt order creation, user creation, single-order cancel, seat locking, or cart persistence (not supported)**
- [ ] No cross-module `@Service` or `@Repository` imports
- [ ] `@Transactional` applied to all multi-step service methods
- [ ] `@Transactional(readOnly = true)` applied to all read-only service methods
- [ ] `@Valid` applied to all `@RequestBody` parameters
- [ ] All exceptions extend `ResourceNotFoundException` or `BusinessRuleException` from `shared/`
- [ ] No `@ControllerAdvice` or `@ExceptionHandler` defined in any module
- [ ] All monetary values use `Money` — no `BigDecimal`, no `double`
- [ ] All Spring Events use records with primitives/IDs/value objects — no `@Entity` in payloads
- [ ] `@TransactionalEventListener(phase = AFTER_COMMIT)` used for post-commit listeners
- [ ] **All `@Async` listeners have explicit try/catch with `ERROR` logging and failure persistence — no silent swallowing**
- [ ] Constructor injection used everywhere — no `@Autowired` on fields
- [ ] New Flyway migration file added for every schema change, with correct global version number
- [ ] **All list-returning API endpoints use `Page<T>` and accept `Pageable` — no unbounded `List<T>` from controllers**
- [ ] **`@CurrentUser UserPrincipal` used in controllers — never `SecurityContextHolder` in services**
- [ ] **`@PreAuthorize` on controller methods only — never on service methods**
- [ ] **admin/ Module: Reads can import module @Service directly. Writes MUST call owning module's REST API.**
- [ ] **If feature touches multiple modules: Spring Events used for async reactions, never direct service calls**
