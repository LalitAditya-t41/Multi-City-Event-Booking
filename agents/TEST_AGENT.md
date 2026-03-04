# TEST_AGENT.md — Testing Standards & Execution Agent

> Read this file before writing or running tests.
> This agent is responsible for test code implementation, execution, failure fixing, and result logging.

---

## Purpose

This file defines the responsibilities of the **Test Agent**.

The **SPEC_GENERATOR** is the sole source of truth for what to test — it writes all test cases in Stage 8 of the `SPEC_*.md` file. The **TEST_AGENT does not decide what to test**. Its only job is to:

- **Translate** the test cases written in `SPEC_*.md` Stage 8 into executable test code
- **Run** those tests
- **Fix** failures caused by new or changed code
- **Log** results in a timestamped report in `test_result_folder/`

---

## Scope Ownership

### Test Agent MUST

- Read Stage 8 of the `SPEC_*.md` file and implement exactly those test cases — nothing more, nothing less
- Write test code for all layers as specified in the test cases:
  - `domain/` (pure unit)
  - `service/` (Mockito orchestration)
  - `api/` (`@WebMvcTest` HTTP contract)
  - `arch/` (ArchUnit boundaries)
- Run relevant tests after implementation
- Fix failing tests and related test setup issues
- Fix production code only when required to satisfy approved spec behavior
- Log all found errors and all applied fixes in a test report file

### Test Agent MUST NOT

- Decide what test cases to write — that is SPEC_GENERATOR's job
- Add, remove, or modify test cases from Stage 8
- Introduce unapproved features not present in `SPEC_*.md`
- Change module ownership or dependency direction rules
- Add module-level `@ControllerAdvice`
- Skip failed tests without documenting reason and impact

---

## Inputs (Required Before Writing Tests)

1. Confirmed feature spec with Stage 8 test cases: `SPEC_*.md` ← **primary input**
2. Module ownership and boundaries: `PRODUCT.md`
3. Layer/package standards: `docs/MODULE_STRUCTURE_TEMPLATE.md`
4. Test patterns/rules: `docs/TESTING_GUIDE.md`
5. Coding constraints for compatibility: `agents/CODING_AGENT.md`

---

## Test Design Standards

### AAA Format (Mandatory)

All tests must follow Arrange–Act–Assert.

```java
@Test
void should_return_400_when_cityId_missing() {
    // Arrange
    MockHttpServletRequestBuilder request = get("/api/v1/catalog/venues");

    // Act
    ResultActions result = mockMvc.perform(request);

    // Assert
    result.andExpect(status().isBadRequest());
}
```

### Naming Convention (Mandatory)

`should_[expected_behaviour]_when_[condition]`

Examples:
- `should_soft_delete_event_when_missing_from_org_sync`
- `should_return_401_when_webhook_signature_invalid`
- `should_not_query_db_when_cache_hit`

### Layer Rules

- `domain/`: no Spring context, no mocks for infra
- `service/`: Mockito only (`@ExtendWith(MockitoExtension.class)`)
- `api/`: `@WebMvcTest`, services/mappers mocked
- `arch/`: ArchUnit boundary checks based on hard rules

---

## Execution Workflow

1. Read Stage 8 test cases from the approved `SPEC_*.md` — do not interpret or expand them
2. Implement each test case as code in the correct layer
3. Run focused tests first (changed files only), then full module suite
4. Capture failures
5. Fix test or code issues
6. Re-run until green (or blocked)
7. Produce and save test report in `test_result_folder/`

---

## Failure Triage Rules

When tests fail, classify and resolve in this order:

1. **Test setup issue** (mock, fixture, context) → fix test
2. **Contract mismatch** (spec vs endpoint/DTO) → fix code or test to match approved spec
3. **Behavior bug** (business logic wrong) → fix production code
4. **Architecture violation** (ArchUnit) → fix package/dependency layering
5. **External/infra flake** → document as blocked with reproducible evidence

Do not silently ignore failing tests.

---

## Required Result Logging

After every test cycle, generate a result log file in:

`test_result_folder/`

### Naming Convention (Mandatory)

`[feature_or_flow]_[yyyyMMdd_HHmmss].md`

Examples:
- `fr1_catalog_search_20260303_201530.md`
- `payment_confirmation_flow_20260303_204012.md`

### Log Template (Mandatory)

```markdown
# Test Result — [Feature/Flow]

- Timestamp: [yyyy-MM-dd HH:mm:ss]
- Spec: [SPEC_*.md]
- Module(s): [module list]
- Commit/Working State: [hash or local]

## Coverage of Stage 8 Cases
- Total planned:
- Implemented:
- Passed:
- Failed:
- Skipped:

## Failures Found
1. [test name]
   - Error:
   - Root cause:
   - Fix applied:

## Code/Test Changes Applied
- [file path] — [short change summary]

## Final Status
- [PASS | PARTIAL | BLOCKED]
- Notes:
```

---

## Known API Contract Changes (FR1–FR7) — Do Not Revert

The following contract changes were applied during the FR1–FR7 implementation cycle. Any test that references the old shape will fail. Fix the test — do not revert the production code.

| Change | Old | New | Affected Test Files |
|---|---|---|---|
| `CartResponse` discount field | `discountAmount` | `groupDiscountAmount` + `couponDiscountAmount` | `BookingInventoryControllerTest`, `CartServiceTest` |
| `BookingConfirmedEvent` first param | `(cartId, seatIds, ...)` | `(bookingId, cartId, seatIds, ...)` | `PaymentServiceTest` (uses `ArgumentCaptor` — no change needed) |
| `CartSnapshotReader` | single `getCartItems()` | `getCartItems()` + `getCartSummary()` | Any test mocking `CartSnapshotReader` must stub both methods |
| Event listener annotation | `@EventListener` | `@TransactionalEventListener(phase = AFTER_COMMIT)` on post-commit side-effects | Any listener test using `ApplicationEventPublisher.publishEvent()` must use `@Transactional` test config |
| `ShowSlotService.toEbCreateRequest()` | uses internal `venueId.toString()` | uses `venue.eventbriteVenueId()` from `venueCatalogClient.getVenue()` | `ShowSlotServiceTest` — must stub `venueCatalogClient.getVenue()` |

---

## Completion Criteria

Test Agent is complete only when:

- All Stage 8 test cases from `SPEC_*.md` are implemented as code
- All implemented tests are executed
- Failures are fixed or explicitly documented as blocked
- A timestamped result log is saved in `test_result_folder/`

---

## Handoff Contract

- `SPEC_GENERATOR` defines what to test — writes all test cases in Stage 8
- `CODING_AGENT` implements production code
- `TEST_AGENT` writes test code for Stage 8 cases, runs them, fixes failures, and publishes the final test report