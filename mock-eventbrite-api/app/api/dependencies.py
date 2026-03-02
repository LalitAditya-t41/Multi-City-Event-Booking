from __future__ import annotations

from fastapi import Header, Query

from app.exception.exceptions import ApiError


def require_auth(authorization: str | None = Header(default=None), token: str | None = Query(default=None)) -> str:
    if token and token.strip():
        return token.strip()

    if authorization is None or not authorization.strip():
        raise ApiError("NO_AUTH", "Authentication not provided.", 401)

    parts = authorization.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise ApiError("INVALID_AUTH_HEADER", "Authentication header is invalid.", 400)

    bearer = parts[1].strip()
    if not bearer:
        raise ApiError("INVALID_AUTH", "Authentication/OAuth token is invalid.", 400)

    return bearer
