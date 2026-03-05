# Test Result — FR8 Engagement Reviews & Moderation

- Timestamp: 2026-03-05 07:12:44 IST
- Spec: POQ/Engagement/SPEC_8.md (Stage 8)
- Module(s): engagement
- Commit/Working State: 37bccac + local uncommitted changes

## Coverage of Stage 8 Cases
- Total planned: 62
- Implemented: 62
- Passed: 62
- Failed: 0
- Skipped: 0

## Failures Found
1. `ModerationServiceTest` initialization failed (`MeterRegistry` null with `@InjectMocks`)
   - Error: `InjectMocksException` / `NullPointerException`
   - Root cause: Mockito attempted constructor injection before manual setup.
   - Fix applied: Removed `@InjectMocks`; instantiated `ModerationService` explicitly in `@BeforeEach` with `SimpleMeterRegistry`.

2. API `@WebMvcTest` bootstrap failed
   - Error: `Unable to find a @SpringBootConfiguration`
   - Root cause: New `engagement` module lacked test boot configuration class.
   - Fix applied: Added `EngagementTestApplication` under `engagement/src/test/java/com/eventplatform/engagement/`.

3. Strict Mockito unnecessary stubbing failures
   - Error: `UnnecessaryStubbingException` in attendance and summary service tests.
   - Root cause: Shared setup stubs unused in subset of tests.
   - Fix applied: Moved stubs into per-test scope and removed global stubbing.

4. ArchUnit boundary rule false-positive
   - Error: Rule flagged `@Scheduled`/`@Async` annotations as forbidden due to `..scheduling..` pattern.
   - Root cause: Over-broad package match captured Spring framework packages.
   - Fix applied: Changed forbidden package patterns to explicit module namespaces (`com.eventplatform.scheduling..`, etc.).

5. `ModerationServiceTest` matcher and verification failures
   - Error: `InvalidUseOfMatchersException` and `TooManyActualInvocations`.
   - Root cause: Mixed raw args + matchers; duplicate `verify(save())` expectations against two actual saves.
   - Fix applied: Used `eq(...)` consistently with matchers and `times(2)` for save verification.

## Code/Test Changes Applied
- `engagement/src/test/java/com/eventplatform/engagement/domain/ReviewTest.java` — Stage 8 domain tests for review lifecycle and rating boundaries.
- `engagement/src/test/java/com/eventplatform/engagement/domain/ReviewEligibilityTest.java` — Stage 8 domain tests for eligibility status/window behavior.
- `engagement/src/test/java/com/eventplatform/engagement/service/ReviewServiceTest.java` — Stage 8 service tests for submission guards and event publish.
- `engagement/src/test/java/com/eventplatform/engagement/service/AttendanceVerificationServiceTest.java` — Stage 8 service tests for booking/EB advisory logic.
- `engagement/src/test/java/com/eventplatform/engagement/service/ModerationServiceTest.java` — Stage 8 service tests for auto/manual moderation and retries.
- `engagement/src/test/java/com/eventplatform/engagement/service/ReviewRatingSummaryServiceTest.java` — Stage 8 service tests for cache hit/miss/error/invalidation.
- `engagement/src/test/java/com/eventplatform/engagement/api/ReviewControllerTest.java` — Stage 8 controller contract/security tests for user/public endpoints.
- `engagement/src/test/java/com/eventplatform/engagement/api/AdminModerationControllerTest.java` — Stage 8 controller contract/security tests for admin moderation endpoints.
- `engagement/src/test/java/com/eventplatform/engagement/arch/EngagementArchTest.java` — Stage 8 ArchUnit boundary/ACL/mapping rules.
- `engagement/src/test/java/com/eventplatform/engagement/EngagementTestApplication.java` — Test boot configuration for module `@WebMvcTest` loading.
- `engagement/src/main/java/com/eventplatform/engagement/domain/Review.java` — Added rating boundary guard (`IllegalArgumentException` for invalid ratings).
- `engagement/src/main/java/com/eventplatform/engagement/domain/ReviewEligibility.java` — Added revoked unlock guard + `isEligible(now)` helper.
- `engagement/src/main/java/com/eventplatform/engagement/api/controller/ReviewController.java` — Default public list sort changed to `publishedAt,desc`.
- `engagement/src/main/java/com/eventplatform/engagement/repository/ReviewRepository.java` — Published list query switched to `OrderByPublishedAtDesc`.
- `engagement/src/main/java/com/eventplatform/engagement/service/ReviewService.java` — Updated published list repository method call.
- `engagement/src/main/java/com/eventplatform/engagement/api/controller/AdminModerationController.java` — Added explicit REJECT reason validation for 400 contract.

## Final Status
- PASS
- Notes: Executed `mvn -q -pl engagement test`; all Stage 8 tests in engagement module pass.
