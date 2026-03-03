# TEST_AGENT.md — Testing Standards & Execution Agent

> Read this file before writing or running tests.
> This agent is responsible for test planning, test implementation, execution, failure fixing, and result logging.

---

## Purpose

This file defines the responsibilities of the **Test Agent**.

- Build tests from `SPEC_*.md` Stage 8 test list (source of truth for test scope)
- Follow project testing architecture in `docs/TESTING_GUIDE.md`
- Execute tests and fix failures caused by new or changed code
- Keep production code and test code aligned with module boundaries from `PRODUCT.md`
- Produce a timestamped test result log in `test_result_folder/` at the repo root

---

## Scope Ownership

### Test Agent MUST

- Write tests for all layers listed in spec:
  - `domain/` (pure unit)
  - `service/` (Mockito orchestration)
  - `api/` (`@WebMvcTest` HTTP contract)
  - `arch/` (ArchUnit boundaries)
- Run relevant tests after implementation
- Fix failing tests and related test setup issues
- Fix production code only when required to satisfy approved spec behavior
- Log all found errors and all applied fixes in a test report file

### Test Agent MUST NOT

- Introduce unapproved features not present in `SPEC_*.md`
- Change module ownership or dependency direction rules
- Add module-level `@ControllerAdvice`
- Skip failed tests without documenting reason and impact

---

## Inputs (Required Before Writing Tests)

1. Confirmed feature spec: `SPEC_*.md`
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

1. Parse Stage 8 test list from the approved `SPEC_*.md`
2. Generate/adjust tests by layer
3. Run focused tests first (changed files only), then module suite
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

## Completion Criteria

Test Agent is complete only when:

- All feasible Stage 8 tests are implemented
- Relevant tests are executed
- Failures are fixed or explicitly documented as blocked
- A timestamped result log is saved in `test_result_folder/`

---

## Handoff Contract

- `SPEC_GENERATOR` defines what to test
- `CODING_AGENT` implements production code
- `TEST_AGENT` validates behavior, fixes test/logic defects, and publishes the final test report
