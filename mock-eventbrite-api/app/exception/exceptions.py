from __future__ import annotations


class ApiError(Exception):
    def __init__(self, code: str, description: str, status_code: int) -> None:
        super().__init__(description)
        self.code = code
        self.description = description
        self.status_code = status_code
