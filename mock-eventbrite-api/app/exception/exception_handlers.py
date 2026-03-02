from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse

from app.exception.exceptions import ApiError


def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "error": exc.code,
            "error_description": exc.description,
            "status_code": exc.status_code,
        },
    )
