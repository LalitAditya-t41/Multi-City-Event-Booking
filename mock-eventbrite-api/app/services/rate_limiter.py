from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Dict

from app.config import runtime_config
from app.exception.exceptions import ApiError


@dataclass
class RateLimitBucket:
    usage: int
    reset_at: int
    limit: int


@dataclass
class TokenUsage:
    hourly: RateLimitBucket
    daily: RateLimitBucket
    event_action: RateLimitBucket


class RateLimiter:
    def __init__(self) -> None:
        self._store: Dict[str, TokenUsage] = {}

    def _reset_bucket(self, bucket: RateLimitBucket, window_seconds: int) -> None:
        now = int(time.time())
        if now >= bucket.reset_at:
            bucket.usage = 0
            bucket.reset_at = now + window_seconds

    def _get_usage(self, token: str) -> TokenUsage:
        now = int(time.time())
        if token not in self._store:
            self._store[token] = TokenUsage(
                hourly=RateLimitBucket(usage=0, reset_at=now + 3600, limit=runtime_config.general_rate_limit),
                daily=RateLimitBucket(usage=0, reset_at=now + 86400, limit=runtime_config.daily_rate_limit),
                event_action=RateLimitBucket(usage=0, reset_at=now + 3600, limit=runtime_config.event_action_rate_limit),
            )
        usage = self._store[token]
        usage.hourly.limit = runtime_config.general_rate_limit
        usage.daily.limit = runtime_config.daily_rate_limit
        usage.event_action.limit = runtime_config.event_action_rate_limit
        return usage

    def check_and_increment(self, token: str, is_event_action: bool) -> dict:
        usage = self._get_usage(token)
        self._reset_bucket(usage.hourly, 3600)
        self._reset_bucket(usage.daily, 86400)
        self._reset_bucket(usage.event_action, 3600)

        if usage.hourly.usage >= usage.hourly.limit:
            raise ApiError(
                "HIT_RATE_LIMIT",
                f"Rate limit exceeded: {usage.hourly.limit} calls/hour. Retry after {usage.hourly.reset_at - int(time.time())} seconds.",
                429,
            )
        if usage.daily.usage >= usage.daily.limit:
            raise ApiError(
                "HIT_RATE_LIMIT",
                f"Rate limit exceeded: {usage.daily.limit} calls/day. Retry after {usage.daily.reset_at - int(time.time())} seconds.",
                429,
            )
        if is_event_action and usage.event_action.usage >= usage.event_action.limit:
            raise ApiError(
                "HIT_RATE_LIMIT",
                f"Rate limit exceeded: {usage.event_action.limit} calls/hour. Retry after {usage.event_action.reset_at - int(time.time())} seconds.",
                429,
            )

        usage.hourly.usage += 1
        usage.daily.usage += 1
        if is_event_action:
            usage.event_action.usage += 1

        return {
            "hourly": usage.hourly,
            "daily": usage.daily,
            "event_action": usage.event_action,
        }

    def snapshot(self) -> dict:
        return self._store


rate_limiter = RateLimiter()
