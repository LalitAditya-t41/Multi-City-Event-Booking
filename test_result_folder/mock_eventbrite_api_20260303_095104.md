# Test Result — Mock Eventbrite API

- Timestamp: 2026-03-03 09:51:04
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
1. Prior run failed due to reserved SQLAlchemy attribute named metadata on SeatMap.
   - Error: InvalidRequestError: Attribute name 'metadata' is reserved
   - Root cause: SQLAlchemy 2.0 reserves metadata as a declarative attribute.
   - Fix applied: Renamed model attribute to metadata_json and mapped column name to metadata.
2. Prior run failed due to duplicate seed inserts between tests.
   - Error: sqlite3.IntegrityError: UNIQUE constraint failed: attendees.id
   - Root cause: startup seeding ran on a shared in-memory DB across tests.
   - Fix applied: reset DB before each TestClient startup.
3. Prior run showed deprecation warnings for on_event lifecycle handlers.
   - Fix applied: replaced startup hook with FastAPI lifespan handler.

## Code/Test Changes Applied
- mock-eventbrite-api/app/main.py — use lifespan handler for DB init/seed

## Final Status
- PASS
- Notes: Only remaining warning is Pydantic V2 class-based config deprecation.
