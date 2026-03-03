from __future__ import annotations

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware

from app.services.rate_limiter import rate_limiter
from app.exception.exceptions import ApiError


EVENT_ACTION_PREFIXES = {
    "POST:/v3/organizations/",  # create event
    "POST:/v3/events/",  # update or publish/cancel/copy/schedules
}


class RateLimitMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        token = request.headers.get("Authorization", "").replace("Bearer", "").strip()
        if not token:
            token = request.query_params.get("token", "") or ""

        if token:
            is_event_action = _is_event_action(request)
            usage = rate_limiter.check_and_increment(token, is_event_action)
            response = await call_next(request)
            _add_headers(response, usage)
            return response

        return await call_next(request)


def _is_event_action(request: Request) -> bool:
    key = f"{request.method}:{request.url.path}"
    if key.startswith("POST:/v3/organizations/") and key.endswith("/events/"):
        return True
    if key.startswith("POST:/v3/events/") and (
        key.endswith("/publish/")
        or key.endswith("/unpublish/")
        or key.endswith("/cancel/")
        or key.endswith("/copy/")
        or key.endswith("/schedules/")
    ):
        return True
    return False


def _add_headers(response, usage):
    response.headers["X-RateLimit-Limit"] = str(usage["hourly"].limit)
    response.headers["X-RateLimit-Remaining"] = str(max(usage["hourly"].limit - usage["hourly"].usage, 0))
    response.headers["X-RateLimit-Reset"] = str(usage["hourly"].reset_at)
