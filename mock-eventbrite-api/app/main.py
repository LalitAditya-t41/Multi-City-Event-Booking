from __future__ import annotations

from fastapi import FastAPI

from app.config import settings
from app.database import init_db
from app.exception.exception_handlers import api_error_handler
from app.exception.exceptions import ApiError
from app.utils.rate_limit_middleware import RateLimitMiddleware
from app.api.routes import events, venues, schedules, ticket_classes, capacity, seatmaps, orders, attendees, discounts, mock_admin


def create_app() -> FastAPI:
    app = FastAPI(title=settings.app_name)
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_middleware(RateLimitMiddleware)

    app.include_router(events.router)
    app.include_router(venues.router)
    app.include_router(schedules.router)
    app.include_router(ticket_classes.router)
    app.include_router(capacity.router)
    app.include_router(seatmaps.router)
    app.include_router(orders.router)
    app.include_router(attendees.router)
    app.include_router(discounts.router)
    app.include_router(mock_admin.router)

    @app.on_event("startup")
    def _startup():
        init_db()
        from app.database import SessionLocal
        from app.services.seed import seed_data

        session = SessionLocal()
        try:
            seed_data(session)
        finally:
            session.close()

    return app


app = create_app()
