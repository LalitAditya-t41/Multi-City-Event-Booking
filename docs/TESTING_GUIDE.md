# Testing Guide — Modular Monolith

> Tests are not optional. Every feature must have coverage across all four layers.
> The `arch/` tests in particular are self-enforcing — they fail the build automatically
> if anyone violates a module boundary. Never delete or weaken an arch test.

---

## Core Principle — Each Layer Tests One Thing

The most common testing mistake is putting the wrong test in the wrong layer.
Use this rule: **each layer tests exactly one concern.**

| Layer | Tests One Concern | Spring Context? | Key Annotation |
|---|---|---|---|
| `domain/` | Business rules on the domain object | None | Plain JUnit 5 |
| `service/` | Orchestration — right calls in right order | None | `@ExtendWith(MockitoExtension.class)` |
| `api/` | HTTP contract — status codes, JSON shape, validation | Web layer only | `@WebMvcTest` |
| `arch/` | Architectural boundaries | None | `@AnalyzeClasses` |
| `mapper/` | Field translation correctness | None | Plain JUnit 5 |

---

## Test Folder Structure

Every module must mirror its source structure exactly:

```
[module-name]/
└── src/
    ├── main/java/com/[org]/[module]/
    │   ├── api/controller/
    │   ├── domain/
    │   ├── service/
    │   ├── repository/
    │   ├── mapper/
    │   └── event/
    └── test/java/com/[org]/[module]/
        ├── domain/        ← Pure unit tests. No Spring. No infrastructure mocks.
        ├── service/       ← Mockito only. Mocks repositories and ACL facades.
        ├── api/           ← @WebMvcTest slice. Mocks service and mapper.
        ├── arch/          ← ArchUnit. Enforces module boundary rules at build time.
        └── mapper/        ← MapStruct translation tests. No Spring.
```

---

## Test Naming Convention (Mandatory)

All test methods must follow this exact pattern:

```
should_[expected_behaviour]_when_[condition]
```

The name must be readable as a sentence describing the behaviour being verified.
Never use vague names like `testConfirm()`, `test1()`, or `happyPath()`.

**Good examples:**
```
should_confirm_booking_when_status_is_pending
should_throw_BusinessRuleException_when_confirming_a_cancelled_booking
should_return_200_when_booking_is_found
should_return_400_when_seat_count_exceeds_maximum
should_return_404_when_booking_does_not_exist
should_publish_BookingConfirmedEvent_after_successful_confirmation
should_throw_BookingNotFoundException_when_booking_id_is_unknown
should_map_confirmed_booking_to_response_with_correct_status
```

**Bad examples (never use):**
```
testConfirm()
test_happy_path()
confirmWorks()
booking_test_1()
```

---

## Layer 1 — `domain/` Tests

### Purpose

Verify that the domain object enforces its own rules correctly.
These are the fastest tests in the suite — they must run in milliseconds.

### What to Test Here

- Valid state transitions succeed and produce the correct new state
- Invalid state transitions throw `BusinessRuleException` with the correct message
- Guard conditions (preconditions on methods) are enforced
- Value object construction validates its invariants
- Terminal states cannot be transitioned out of
- Null or invalid inputs are rejected

### What NOT to Test Here

- Persistence (no repositories, no DB)
- HTTP (no controllers, no MockMvc)
- Spring beans (no `@SpringBootTest`, no `@ExtendWith(SpringExtension.class)`)
- Mocks of infrastructure — if you need a mock here, the domain has an infrastructure
  dependency. Fix the domain, not the test.

### Annotations

None. Plain JUnit 5 only.

```java
// ✅ Correct — no annotations on the class
class BookingTest {
    ...
}

// ❌ Wrong — domain tests never load Spring
@SpringBootTest
class BookingTest { ... }

// ❌ Wrong — if you need this, your domain imports infrastructure
@ExtendWith(MockitoExtension.class)
class BookingTest { ... }
```

---

### Happy Path Tests

Test that the domain method succeeds and produces the correct outcome under valid conditions.
Cover every valid state transition defined in the spec's state machine.

```java
class BookingTest {

    // Happy path: valid transition succeeds
    @Test
    void should_confirm_booking_when_status_is_pending() {
        // Arrange — set up the entity in a valid starting state
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.PENDING);

        // Act — call the domain method
        booking.confirm();

        // Assert — verify the expected outcome
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void should_cancel_booking_when_status_is_pending() {
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.PENDING);

        booking.cancel();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void should_cancel_booking_when_status_is_confirmed_and_event_is_more_than_48h_away() {
        LocalDateTime eventTime = LocalDateTime.now().plusDays(3);
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.CONFIRMED, eventTime);

        booking.cancel();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
```

---

### Negative Path Tests

Test that invalid operations are rejected with the correct exception and message.
Cover every forbidden transition in the spec's state machine.

```java
class BookingTest {

    // Negative path: invalid transition throws BusinessRuleException
    @Test
    void should_throw_BusinessRuleException_when_confirming_a_cancelled_booking() {
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.CANCELLED);

        assertThatThrownBy(booking::confirm)
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Cannot confirm a cancelled booking");
    }

    @Test
    void should_throw_BusinessRuleException_when_confirming_an_already_confirmed_booking() {
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.CONFIRMED);

        assertThatThrownBy(booking::confirm)
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Booking is already confirmed");
    }

    @Test
    void should_throw_BusinessRuleException_when_cancelling_within_48h_of_event() {
        LocalDateTime eventTime = LocalDateTime.now().plusHours(24); // inside the 48h window
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.CONFIRMED, eventTime);

        assertThatThrownBy(booking::cancel)
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Cannot cancel within 48 hours of the event");
    }
}
```

---

### Exception / Edge Case Tests

Test boundary conditions, null inputs, minimum/maximum values, and terminal states.

```java
class BookingTest {

    @Test
    void should_throw_when_booking_id_is_null() {
        assertThatThrownBy(() -> new Booking(null, BookingStatus.PENDING))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_throw_when_seat_count_exceeds_maximum() {
        assertThatThrownBy(() -> new Booking(UUID.randomUUID(), BookingStatus.PENDING, 9))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Cannot book more than 8 seats");
    }

    @Test
    void should_throw_when_operating_on_a_terminal_cancelled_state() {
        Booking booking = new Booking(UUID.randomUUID(), BookingStatus.CANCELLED);

        // All operations on a terminal state must be rejected
        assertThatThrownBy(booking::confirm).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(booking::cancel).isInstanceOf(BusinessRuleException.class);
    }
}
```

---

## Layer 2 — `service/` Tests

### Purpose

Verify that the service correctly orchestrates domain objects, repositories, ACL facades,
and event publishing. These tests prove the *use case* works — not the domain rule, not the
HTTP contract.

### What to Test Here

- The correct repository method is called with the correct arguments
- The correct ACL facade method is called when an external system is involved
- `ApplicationEventPublisher.publishEvent()` is called with the correct event type and payload
- `BookingNotFoundException` (or equivalent) is thrown when the entity is not found
- `BusinessRuleException` propagates correctly when the domain rejects an operation
- `[System]IntegrationException` is thrown (and compensation is triggered) when an external call fails
- No event is published when the use case fails

### What NOT to Test Here

- Domain rules — those are tested in `domain/`. Do not re-test `booking.confirm()` logic here.
- HTTP concerns — no MockMvc, no status codes.

### Annotations

```java
@ExtendWith(MockitoExtension.class) // Mockito only — no Spring
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;     // Always mock the repository

    @Mock
    private ApplicationEventPublisher eventPublisher; // Always mock the event publisher

    @Mock
    private PaymentAclService paymentAclService;      // Mock ACL facades for external systems

    @InjectMocks
    private BookingService bookingService;            // The class under test — real instance
}
```

---

### Happy Path Tests

Test the complete use case: entity is found, domain method succeeds, repository is saved,
event is published, ACL facade is called if required.

```java
@Test
void should_confirm_booking_and_publish_event_when_booking_is_pending() {
    // Arrange
    UUID bookingId = UUID.randomUUID();
    Booking booking = new Booking(bookingId, BookingStatus.PENDING);
    when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

    // Act
    bookingService.confirmBooking(bookingId);

    // Assert — verify orchestration
    verify(bookingRepository).save(booking);                               // saved
    verify(eventPublisher).publishEvent(any(BookingConfirmedEvent.class)); // event fired
}

@Test
void should_initiate_booking_and_call_payment_facade_when_request_is_valid() {
    // Arrange
    CreateBookingRequest request = new CreateBookingRequest(userId, seatIds, paymentMethodId);
    when(paymentAclService.charge(any())).thenReturn(new PaymentResult(paymentIntentId));
    when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    Booking result = bookingService.initiateBooking(request);

    // Assert
    verify(paymentAclService).charge(any(ChargeRequest.class));           // external call made
    verify(bookingRepository).save(any(Booking.class));                    // persisted
    verify(eventPublisher).publishEvent(any(BookingInitiatedEvent.class)); // event fired
    assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
}
```

---

### Negative Path Tests

#### Entity Not Found

```java
@Test
void should_throw_BookingNotFoundException_when_booking_does_not_exist() {
    // Arrange
    UUID bookingId = UUID.randomUUID();
    when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

    // Act + Assert
    assertThatThrownBy(() -> bookingService.confirmBooking(bookingId))
        .isInstanceOf(BookingNotFoundException.class);

    // Verify no side effects occurred
    verify(eventPublisher, never()).publishEvent(any());
    verify(bookingRepository, never()).save(any());
}
```

#### Domain Rule Violation Propagates

```java
@Test
void should_propagate_BusinessRuleException_when_domain_rejects_confirmation() {
    // Arrange — entity is in an invalid state for this operation
    UUID bookingId = UUID.randomUUID();
    Booking booking = new Booking(bookingId, BookingStatus.CANCELLED);
    when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

    // Act + Assert — service must NOT swallow domain exceptions
    assertThatThrownBy(() -> bookingService.confirmBooking(bookingId))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("Cannot confirm a cancelled booking");

    // Verify no side effects — nothing saved, no event fired
    verify(bookingRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
}
```

#### External System Failure

```java
@Test
void should_throw_PaymentIntegrationException_when_payment_facade_fails() {
    // Arrange
    CreateBookingRequest request = new CreateBookingRequest(userId, seatIds, paymentMethodId);
    when(paymentAclService.charge(any()))
        .thenThrow(new PaymentIntegrationException("Payment gateway unavailable"));

    // Act + Assert
    assertThatThrownBy(() -> bookingService.initiateBooking(request))
        .isInstanceOf(PaymentIntegrationException.class);

    // Verify no booking was persisted and no event was fired
    verify(bookingRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
}

@Test
void should_refund_and_throw_when_payment_succeeds_but_db_write_fails() {
    // Arrange — payment succeeds but save throws
    when(paymentAclService.charge(any())).thenReturn(new PaymentResult(paymentIntentId));
    when(bookingRepository.save(any())).thenThrow(new RuntimeException("DB unavailable"));

    // Act + Assert
    assertThatThrownBy(() -> bookingService.initiateBooking(request))
        .isInstanceOf(RuntimeException.class);

    // Verify compensation — refund must be called
    verify(paymentAclService).refund(paymentIntentId);
}
```

---

### Event Payload Tests

Verify that published events carry the correct data — the consumer depends on this contract.

```java
@Test
void should_publish_BookingConfirmedEvent_with_correct_booking_id_and_user_id() {
    UUID bookingId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Booking booking = new Booking(bookingId, userId, BookingStatus.PENDING);
    when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

    bookingService.confirmBooking(bookingId);

    ArgumentCaptor<BookingConfirmedEvent> captor =
        ArgumentCaptor.forClass(BookingConfirmedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());

    BookingConfirmedEvent event = captor.getValue();
    assertThat(event.bookingId()).isEqualTo(bookingId);
    assertThat(event.userId()).isEqualTo(userId);
    // Verify no @Entity objects leaked into the event
    assertThat(event).isNotInstanceOf(Booking.class);
}
```

---

## Layer 3 — `api/` Tests

### Purpose

Verify the HTTP contract: correct status codes, correct response JSON shape, request
validation enforcement, and `@PreAuthorize` role checks. Nothing else.

### What to Test Here

- `GET` returns 200 with correctly shaped JSON when found
- `POST` returns 201 with correctly shaped JSON when created
- Required fields missing → 400 with `ErrorResponse`
- Field constraint violated (e.g. value out of range) → 400
- Entity not found → 404 with `ErrorResponse`
- Business rule violated → 422 with `ErrorResponse`
- External system failure → 502 with `ErrorResponse`
- Unauthenticated request to secured endpoint → 401
- Insufficient role → 403

### What NOT to Test Here

Business logic. Mock the service to return a fixed value and test only the HTTP layer.

### Annotations

```java
@WebMvcTest(BookingController.class)  // loads ONLY the web layer
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;   // Mock — do not test its logic here

    @MockBean
    private BookingMapper bookingMapper;     // Mock — do not test mapping here
}
```

---

### Happy Path Tests

```java
@Test
void should_return_200_and_booking_response_when_booking_is_found() throws Exception {
    UUID bookingId = UUID.randomUUID();
    BookingResponse response = new BookingResponse(bookingId, "CONFIRMED", userId);

    when(bookingService.getBooking(bookingId)).thenReturn(mock(Booking.class));
    when(bookingMapper.toResponse(any())).thenReturn(response);

    mockMvc.perform(get("/api/bookings/{id}", bookingId)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(bookingId.toString()))
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
}

@Test
void should_return_201_and_booking_response_when_booking_is_created() throws Exception {
    BookingResponse response = new BookingResponse(UUID.randomUUID(), "PENDING", userId);

    when(bookingService.initiateBooking(any())).thenReturn(mock(Booking.class));
    when(bookingMapper.toResponse(any())).thenReturn(response);

    mockMvc.perform(post("/api/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "seatIds": ["seat-uuid-1"],
                  "paymentMethodId": "pm_test_123"
                }
                """)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"));
}
```

---

### Negative Path Tests — Validation (400)

```java
@Test
void should_return_400_when_seat_ids_is_missing() throws Exception {
    mockMvc.perform(post("/api/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")   // missing required fields
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").exists());  // ErrorResponse shape
}

@Test
void should_return_400_when_seat_count_exceeds_maximum() throws Exception {
    // Build a request with 9 seats (max is 8)
    String body = buildRequestWithSeatCount(9);

    mockMvc.perform(post("/api/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest());
}
```

---

### Negative Path Tests — Not Found (404)

```java
@Test
void should_return_404_when_booking_does_not_exist() throws Exception {
    UUID bookingId = UUID.randomUUID();
    when(bookingService.getBooking(bookingId))
        .thenThrow(new BookingNotFoundException(bookingId));

    mockMvc.perform(get("/api/bookings/{id}", bookingId)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(
            containsString("Booking with id " + bookingId + " not found")));
}
```

---

### Negative Path Tests — Business Rule Violation (422)

```java
@Test
void should_return_422_when_cancellation_window_has_passed() throws Exception {
    UUID bookingId = UUID.randomUUID();
    when(bookingService.cancelBooking(bookingId))
        .thenThrow(new BusinessRuleException("Cannot cancel within 48 hours of the event"));

    mockMvc.perform(delete("/api/bookings/{id}", bookingId)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value(
            containsString("Cannot cancel within 48 hours")));
}
```

---

### Negative Path Tests — External System Failure (502)

```java
@Test
void should_return_502_when_payment_gateway_is_unavailable() throws Exception {
    when(bookingService.initiateBooking(any()))
        .thenThrow(new PaymentIntegrationException("Payment gateway unavailable"));

    mockMvc.perform(post("/api/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validRequestBody())
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.message").value("A payment error occurred. Please try again."));
    // Verify: internal error message is NOT exposed to the client
}
```

---

### Authorization Tests

```java
@Test
void should_return_401_when_request_is_unauthenticated() throws Exception {
    mockMvc.perform(post("/api/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validRequestBody()))
        // No Authorization header
        .andExpect(status().isUnauthorized());
}

@Test
void should_return_403_when_user_lacks_admin_role() throws Exception {
    mockMvc.perform(get("/api/admin/bookings")
            .header("Authorization", "Bearer user-token")) // ROLE_USER, not ROLE_ADMIN
        .andExpect(status().isForbidden());
}
```

---

## Layer 4 — `arch/` Tests

### Purpose

Enforce architectural boundaries at build time using ArchUnit. A failing arch test means
someone violated a boundary — **fix the code, never weaken the rule.**

Every module must have exactly one `[Module]ArchTest.java` in its `arch/` test folder.
These run as part of the normal test suite — no special setup or CI configuration needed.

### Mandatory Rules (Copy for Every Module)

```java
@AnalyzeClasses(packages = "com.[org].[module]")
class [Module]ArchTest {

    // Rule 1: Domain must be pure — no infrastructure dependencies
    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
        noClasses()
            .that().resideInAPackage("..[module].domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..[module].repository..",
                "org.springframework.web..",
                "jakarta.persistence..",
                "org.springframework.data.."
            )
            .because("Domain must be pure Java — no JPA, no HTTP, no Spring annotations");

    // Rule 2: Controllers must not bypass the service layer
    @ArchTest
    static final ArchRule controllers_must_not_import_repositories =
        noClasses()
            .that().resideInAPackage("..[module].api.controller..")
            .should().dependOnClassesThat()
            .resideInAPackage("..[module].repository..")
            .because("Controllers must delegate to service — never access repositories directly");

    // Rule 3: No module imports another module's repository
    @ArchTest
    static final ArchRule no_cross_module_repository_access =
        noClasses()
            .that().resideInAPackage("..[module]..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..[other-module-a].repository..",
                "..[other-module-b].repository.."
                // add all peer and downstream modules here
            )
            .because("Modules must never import another module's repository — call their API instead");

    // Rule 4: Events must carry only primitives and value objects — never JPA entities
    @ArchTest
    static final ArchRule events_must_not_contain_entities =
        noClasses()
            .that().resideInAPackage("..[module].event.published..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .because("Events must carry only primitives, IDs, enums, or value objects — never JPA entities");

    // Rule 5: Mappers must not call services — pure translation only
    @ArchTest
    static final ArchRule mappers_must_not_call_services =
        noClasses()
            .that().resideInAPackage("..[module].mapper..")
            .should().dependOnClassesThat()
            .resideInAPackage("..[module].service..")
            .because("Mappers are pure translation — they must not call services or trigger business logic");

    // Rule 6: No direct external system calls — must use shared/ ACL facades
    @ArchTest
    static final ArchRule no_direct_eventbrite_calls =
        noClasses()
            .that().resideInAPackage("..[module]..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.eventbrite.client..")
            .because("Eventbrite calls must use EbEventSyncService, EbTicketService, etc. from shared/eventbrite/service/");

    @ArchTest
    static final ArchRule no_direct_razorpay_calls =
        noClasses()
            .that().resideInAPackage("..[module]..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.razorpay..")
            .because("Razorpay calls must use RazorpayAclService from shared/payment/service/");

    @ArchTest
    static final ArchRule no_direct_openai_calls =
        noClasses()
            .that().resideInAPackage("..[module]..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.openai..")
            .because("OpenAI calls must use ChatbotAclService from shared/ai/service/");

    // Rule 7: No module imports another module's service, entity, or repository
    @ArchTest
    static final ArchRule no_cross_module_service_imports =
        noClasses()
            .that().resideInAPackage("..[module]..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..[other-module-a].service..",
                "..[other-module-b].service..",
                "..[other-module-a].domain..",
                "..[other-module-b].domain..",
                "..[other-module-a].repository..",
                "..[other-module-b].repository.."
            )
            .because("Modules communicate only via REST API or Spring Events — never import another module's internal classes");

    // Rule 8: shared/ can NEVER depend on any module
    @ArchTest
    static final ArchRule shared_must_not_depend_on_modules =
        noClasses()
            .that().resideInAPackage("..shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "..discovery-catalog..",
                "..scheduling..",
                "..identity..",
                "..booking-inventory..",
                "..payments-ticketing..",
                "..promotions..",
                "..engagement.."
            )
            .because("shared/ is foundational — it cannot depend on any module");

    // Rule 9: Only one GlobalExceptionHandler exists in shared/common/exception/
    @ArchTest
    static final ArchRule only_one_global_exception_handler =
        noClasses()
            .that()
            .resideInAPackage("..[module].api.exception..")
            .should().beAnnotatedWith(ControllerAdvice.class)
            .because("All exception handling routes through shared/common/exception/GlobalExceptionHandler — never add @ControllerAdvice in a module");

    // Rule 10: Dependency direction is strictly downward; no circular imports
    @ArchTest
    static final ArchRule module_dependency_direction_is_downward =
        layeredArchitecture()
            .layer("shared").definedBy("..shared..")
            .layer("discovery-catalog").definedBy("..discovery-catalog..")
            .layer("scheduling").definedBy("..scheduling..")
            .layer("identity").definedBy("..identity..")
            .layer("booking-inventory").definedBy("..booking-inventory..")
            .layer("payments-ticketing").definedBy("..payments-ticketing..")
            .layer("promotions").definedBy("..promotions..")
            .layer("engagement").definedBy("..engagement..")
            .layer("admin").definedBy("..admin..")
            .layer("app").definedBy("..app..")
            
            .whereLayer("app").mayNotAccessAnyLayer()
            .whereLayer("admin").mayNotAccessAnyLayer()
            .whereLayer("engagement").mayNotAccessLayers("admin", "app")
            .whereLayer("promotions").mayNotAccessLayers("engagement", "admin", "app")
            .whereLayer("payments-ticketing").mayNotAccessLayers("promotions", "engagement", "admin", "app")
            .whereLayer("booking-inventory").mayNotAccessLayers("payments-ticketing", "promotions", "engagement", "admin", "app")
            .whereLayer("scheduling").mayNotAccessLayers("booking-inventory", "payments-ticketing", "promotions", "engagement", "admin", "app")
            .whereLayer("identity").mayNotAccessLayers("booking-inventory", "payments-ticketing", "promotions", "engagement", "admin", "app")
            .whereLayer("discovery-catalog").mayNotAccessLayers("scheduling", "identity", "booking-inventory", "payments-ticketing", "promotions", "engagement", "admin", "app")
            .whereLayer("shared").mayNotAccessAnyLayer()
            
            .because("Dependency order is: shared ← discovery-catalog ← scheduling/identity ← booking-inventory ← payments-ticketing ← promotions ← engagement ← admin ← app. No circular dependencies allowed.");
}
```

### Testing Eventbrite Integration Rules

Add these additional rules to any module that integrates with Eventbrite to enforce ACL facade usage:

```java
    // Rule: Only EbEventSyncService may read Eventbrite events
    @ArchTest
    static final ArchRule only_acl_service_calls_eventbrite_api =
        noClasses()
            .that().resideInAPackage("..[module]..")
            .and().doNotHaveSimpleName("EbEventSyncService")
            .and().doNotHaveSimpleName("EbVenueService")
            .and().doNotHaveSimpleName("EbScheduleService")
            .and().doNotHaveSimpleName("EbTicketService")
            .and().doNotHaveSimpleName("EbCapacityService")
            .and().doNotHaveSimpleName("EbOrderService")
            .and().doNotHaveSimpleName("EbAttendeeService")
            .and().doNotHaveSimpleName("EbDiscountSyncService")
            .and().doNotHaveSimpleName("EbRefundService")
            .and().doNotHaveSimpleName("EbWebhookService")
            .should().dependOnClassesThat()
            .resideInAPackage("..eventbrite..")
            .because("Only the 10 ACL facades in shared/eventbrite/service/ may call Eventbrite API");

    // Rule: User and Order creation must never happen via Eventbrite API
    @ArchTest
    static final ArchRule no_eventbrite_user_or_order_creation =
        noClasses()
            .that().resideInAPackage("..[module].service..")
            .should().callMethodWhere(
                target -> target.getFullName().contains("EbAttendeeService.createUser") ||
                          target.getFullName().contains("EbOrderService.createOrder") ||
                          target.getFullName().contains("EbOrderService.create")
            )
            .because("User registration and order creation are internal-only — Eventbrite has no API for these; " +
                     "use identity module for users; orders are created in the bookings table on Stripe payment confirmation; " +
                     "EB Checkout Widget is removed");
```

### When to Add a New Arch Rule

Add a new rule to `[Module]ArchTest.java` whenever:
- A new architectural decision is made for this module
- A new layer or package is introduced
- A new external system integration is added
- A code review reveals a violation that should be prevented automatically in future

Clear example: If you implement a seat-locking state machine, add a rule preventing any @Service from bypassing the state machine and directly updating seat status.

Never add a rule to suppress a valid violation. Fix the code.

---

## Mapper Tests

### Purpose

Verify that every field is correctly translated in both directions: domain → response DTO,
and request DTO → domain object. Mappers are the contract between your domain and your API —
a wrong mapping silently breaks both.

### Annotations

None. Instantiate the MapStruct implementation directly.

```java
class BookingMapperTest {
    // Instantiate directly — no Spring context needed
    private final BookingMapper mapper = Mappers.getMapper(BookingMapper.class);
}
```

---

### Happy Path — Domain to Response

```java
@Test
void should_map_confirmed_booking_to_response_with_correct_fields() {
    UUID bookingId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Booking booking = new Booking(bookingId, userId, BookingStatus.CONFIRMED);

    BookingResponse response = mapper.toResponse(booking);

    assertThat(response.getId()).isEqualTo(bookingId);
    assertThat(response.getUserId()).isEqualTo(userId);
    assertThat(response.getStatus()).isEqualTo("CONFIRMED");
}
```

---

### Happy Path — Request DTO to Domain

```java
@Test
void should_map_create_request_to_booking_domain_with_correct_fields() {
    UUID userId = UUID.randomUUID();
    List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    CreateBookingRequest request = new CreateBookingRequest(userId, seatIds);

    Booking booking = mapper.toDomain(request);

    assertThat(booking.getUserId()).isEqualTo(userId);
    assertThat(booking.getSeatIds()).containsExactlyElementsOf(seatIds);
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
}
```

---

### Edge Case — Null / Optional Fields

```java
@Test
void should_map_null_cancellation_reason_to_null_in_response() {
    Booking booking = new Booking(UUID.randomUUID(), UUID.randomUUID(), BookingStatus.CONFIRMED);
    booking.setCancellationReason(null);

    BookingResponse response = mapper.toResponse(booking);

    assertThat(response.getCancellationReason()).isNull();
}

@Test
void should_map_optional_notes_field_when_present() {
    CreateBookingRequest request = new CreateBookingRequest(userId, seatIds);
    request.setNotes("Wheelchair access required");

    Booking booking = mapper.toDomain(request);

    assertThat(booking.getNotes()).isEqualTo("Wheelchair access required");
}
```

---

## Quick Reference — Which Test Goes Where?

Use this table to decide the correct layer for any test scenario:

| Scenario | Correct Layer | Reason |
|---|---|---|
| `booking.confirm()` succeeds when status is PENDING | `domain/` | Pure domain rule |
| `booking.confirm()` throws when status is CANCELLED | `domain/` | Pure domain rule |
| `BookingService.confirmBooking()` saves and publishes event | `service/` | Orchestration |
| `BookingService.confirmBooking()` throws when booking not found | `service/` | Orchestration — entity lookup |
| `BookingService.initiateBooking()` calls PaymentAclService | `service/` | Orchestration — ACL call |
| `BookingService` refunds when DB write fails after payment | `service/` | Compensation logic |
| `GET /api/bookings/{id}` returns 200 with correct JSON | `api/` | HTTP contract |
| `POST /api/bookings` returns 400 for missing seatIds | `api/` | Request validation |
| `DELETE /api/bookings/{id}` returns 403 without admin role | `api/` | Authorization |
| No class in `booking.domain` imports a `@Repository` | `arch/` | Boundary rule |
| No class in `booking` imports `payments.repository` | `arch/` | Cross-module boundary |
| Events in `booking.event.published` carry no `@Entity` | `arch/` | Event payload rule |
| `BookingMapper.toResponse()` maps all fields correctly | `mapper/` | Translation contract |
| `BookingMapper.toDomain()` maps request to domain correctly | `mapper/` | Translation contract |

---

## Common Mistakes to Avoid

| Mistake | Why It's Wrong | Fix |
|---|---|---|
| Testing `booking.confirm()` logic in `service/` tests | Re-tests domain rules — adds no value | Move to `domain/` |
| Using `@SpringBootTest` in `service/` or `domain/` tests | Loads entire context — massively slow | Use `@ExtendWith(MockitoExtension.class)` or no annotation |
| Putting `@MockBean` in `domain/` tests | If you need a mock, domain has an infra dependency | Remove the dependency from the domain class |
| Testing business logic in `api/` tests | Controller tests should only care about HTTP contract | Mock the service; move logic test to `service/` or `domain/` |
| Deleting or weakening an `arch/` rule to make a test pass | Hides a real architectural violation | Fix the production code, not the test |
| Using `assertThat(response).isNotNull()` as the only assertion | Proves nothing — a null check is not a contract | Assert every field that matters to the caller |
| Missing event payload assertions in `service/` tests | A wrong payload breaks the consuming module silently | Use `ArgumentCaptor` to assert every event field |
| Mapping fields inside `service/` or `controller/` tests | Indicates production code is mapping in the wrong place | All mapping belongs in `mapper/` — fix the source |
