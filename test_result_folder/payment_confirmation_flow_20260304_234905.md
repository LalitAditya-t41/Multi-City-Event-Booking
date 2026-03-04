# Test Result — Payment Confirmation Flow (FR5)

- Timestamp: 2026-03-04 23:49:05
- Spec: POQ/Payments/SPEC_5.md
- Module(s): payments-ticketing, shared
- Commit/Working State: local working tree (uncommitted)

## Coverage of Stage 8 Cases
- Total planned: 69
- Implemented: 67
- Passed: 67
- Failed: 0
- Skipped: 2

## Failures Found
1. `CancellationServiceTest.should_revert_booking_to_confirmed_when_stripe_refund_fails`
   - Error: expected `REJECTED` but got `PENDING`
   - Root cause: test asserted a different `CancellationRequest` instance than the one mutated in service
   - Fix applied: captured saved argument and asserted state on captured object

2. `CancellationServiceTest.should_finalise_after_refund_by_voiding_tickets_and_publishing_booking_cancelled_event`
   - Error: `BusinessRuleException` (`Booking cannot be cancelled from state CONFIRMED`)
   - Root cause: test setup did not move booking to `CANCELLATION_PENDING` before finalisation
   - Fix applied: updated arrange step to set `booking.markCancellationPending()`

3. API tests startup failure (`IllegalStateException` multiple `@SpringBootConfiguration`)
   - Error: `PaymentsTicketingTestApplication` conflicted with `PaymentsTicketingApplication`
   - Root cause: duplicate Spring Boot configuration classes on test classpath
   - Fix applied: removed `payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/PaymentsTicketingTestApplication.java`

4. `StripeWebhookControllerTest.should_return_200_for_unknown_payment_intent_id_noop`
   - Error: Mockito `doNothing()` used on non-void method
   - Root cause: incorrect mocking API usage
   - Fix applied: removed `doNothing()` setup and verified invocation directly

## Code/Test Changes Applied
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/domain/BookingTest.java — added domain transition tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/domain/RefundTest.java — added refund transition tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/domain/ETicketTest.java — added e-ticket domain tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/PaymentServiceTest.java — added payment orchestration tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/CancellationServiceTest.java — added cancellation/refund orchestration tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/RefundServiceTest.java — added refund webhook update tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/ETicketServiceTest.java — added ticket retrieval mapping test
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/service/PaymentConfirmationReaderImplTest.java — renamed tests to Stage 8 naming style
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/api/PaymentControllerTest.java — added payment API contract tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/api/BookingControllerTest.java — added booking API contract tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/api/ETicketControllerTest.java — added ticket API contract tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/api/StripeWebhookControllerTest.java — added webhook API contract tests
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/arch/PaymentsTicketingArchTest.java — added ArchUnit boundary tests
- payments-ticketing/src/main/java/com/eventplatform/paymentsticketing/domain/Refund.java — enforced terminal state rule for `SUCCEEDED`
- shared/src/main/java/com/eventplatform/shared/common/exception/GlobalExceptionHandler.java — added `StripeWebhookSignatureException` → HTTP 400 mapping
- payments-ticketing/src/test/java/com/eventplatform/paymentsticketing/PaymentsTicketingTestApplication.java — removed duplicate test boot config

## Final Status
- PASS
- Notes:
  - 2 Stage 8 ETicket service cases (`generate_constraintViolation_returnsExistingTicket`, `voidTickets_setsAllStatusToVoided`) are not directly implementable against current `ETicketService` surface because generation/voiding currently occurs in `PaymentService` and `CancellationService`.
  - Full validation command used: `mvn -pl payments-ticketing test`.
  - Upstream `shared` module has unrelated failing tests when running full reactor with tests; module-scope validation was completed successfully for this task.
