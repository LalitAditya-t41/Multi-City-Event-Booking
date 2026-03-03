# Test Result — Mock Eventbrite API

- Timestamp: 2026-03-03 09:49:25
- Spec: N/A (mock-eventbrite-api)
- Module(s): mock-eventbrite-api
- Commit/Working State: local

## Coverage of Stage 8 Cases
- Total planned: 13
- Implemented: 13
- Passed: 13
- Failed: 0
- Skipped: 0

## Failures Found
1. Initial run failed due to reserved SQLAlchemy attribute  on SeatMap.
   - Error: 
   - Root cause: SQLAlchemy 2.0 reserves  as a declarative attribute.
   - Fix applied: Renamed model attribute to  and mapped column name to .
2. Initial run failed due to duplicate seed inserts between tests.
   - Error: 
   - Root cause: startup seeding ran on a shared in-memory DB across tests.
   - Fix applied: reset DB before each TestClient startup.

## Code/Test Changes Applied
- mock-eventbrite-api/app/models/seatmap.py — renamed  attribute to  with column mapping
- mock-eventbrite-api/app/api/routes/seatmaps.py — map response from 
- mock-eventbrite-api/app/services/seed.py — seed using 
- mock-eventbrite-api/tests/conftest.py — add sys.path setup, reset DB per test, switch to TestClient
- mock-eventbrite-api/tests/test_auth.py — auth + rate limit headers tests
- mock-eventbrite-api/tests/test_events.py — event create/publish/list tests
- mock-eventbrite-api/tests/test_mock_admin.py — admin endpoints tests
- mock-eventbrite-api/tests/test_orders_attendees.py — order/attendee list filters tests
- mock-eventbrite-api/pyproject.toml — add pytest and requests

## Final Status
- PASS
- Notes: FastAPI emits deprecation warnings for  usage.
