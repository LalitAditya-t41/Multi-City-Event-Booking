# Test Result — FR3 User Onboarding Stage 8

- Timestamp: 2026-03-04 13:19:15
- Spec: SPEC_3.MD
- Module(s): identity
- Commit/Working State: 8ecde21 (local working tree with uncommitted changes)

## Coverage of Stage 8 Cases
- Total planned: 33
- Implemented: 33
- Passed: 33
- Failed: 0
- Skipped: 0

## Failures Found
1. `IdentityDomainTest.should_enforce_city_option_required_and_max_three_unique_genre_options`
   - Error: `BusinessRuleException: Invalid preference`
   - Root cause: domain duplicate-genre guard treated all unsaved options (null ids) as duplicates.
   - Fix applied: updated `UserSettings.replaceGenrePreferences` to only enforce duplicate check when option id is non-null.
2. `UserSettingsServiceTest.should_validate_active_preference_options_before_upsert`
   - Error: Mockito strict stubbing mismatch.
   - Root cause: service passed a `Collection` type different from stubbed `List` instance.
   - Fix applied: switched stubbing to `anyCollection()` with `eq(PreferenceOptionType.GENRE)`.
3. API test context/bootstrap failures (`AuthControllerTest`, `UserSettingsControllerTest`)
   - Error: missing/incorrect beans and unmapped handler flow.
   - Root cause: test context/slice setup around security filter + controller scanning.
   - Fix applied: moved API tests to `@SpringBootTest` + `@AutoConfigureMockMvc`, added `@Import(SecurityConfig.class)`, mocked required controller dependencies, and made mocked `JwtAuthenticationFilter` pass-through.
4. API security/status mismatch for unauthenticated requests
   - Error: expected unauthorized behavior mismatch.
   - Root cause: security exception handling was not explicitly configured.
   - Fix applied: added explicit authentication entry point (401) and access denied handler (403) in `shared/security/SecurityConfig`.

## Code/Test Changes Applied
- `identity/src/test/java/com/eventplatform/identity/domain/IdentityDomainTest.java` — implemented 10 domain tests from Stage 8.
- `identity/src/test/java/com/eventplatform/identity/service/AuthServiceTest.java` — implemented 8 auth service tests from Stage 8.
- `identity/src/test/java/com/eventplatform/identity/service/UserSettingsServiceTest.java` — implemented service validation test from Stage 8.
- `identity/src/test/java/com/eventplatform/identity/api/AuthControllerTest.java` — implemented 9 API tests for auth endpoints.
- `identity/src/test/java/com/eventplatform/identity/api/UserSettingsControllerTest.java` — implemented 2 API tests for settings upsert.
- `identity/src/test/java/com/eventplatform/identity/arch/IdentityArchTest.java` — implemented 3 ArchUnit tests from Stage 8.
- `identity/src/test/java/com/eventplatform/identity/IdentityTestApplication.java` — test application context for identity API tests.
- `identity/pom.xml` — added test dependencies (`archunit-junit5`, `spring-security-test`).
- `identity/src/main/java/com/eventplatform/identity/domain/UserSettings.java` — tightened preference domain guards.
- `identity/src/main/java/com/eventplatform/identity/domain/PasswordResetToken.java` — added `isUsable(Instant now)` helper used by tests.
- `shared/src/main/java/com/eventplatform/shared/security/SecurityConfig.java` — added explicit 401/403 exception handling.

## Final Status
- PASS
- Notes: executed `mvn -pl identity -am test`; identity Stage 8 suite is green (33/33).
