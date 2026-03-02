from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.db import get_db
from app.config import runtime_config
from app.database import reset_db
from app.models.event import Event
from app.models.venue import Venue
from app.models.ticket_class import TicketClass
from app.models.discount import Discount
from app.models.order import Order
from app.models.attendee import Attendee
from app.models.capacity_tier import CapacityTier
from app.models.seatmap import SeatMap
from app.models.webhook_log import WebhookLog
from app.services.seed import seed_data
from app.services.rate_limiter import rate_limiter

router = APIRouter(tags=["mock-admin"])


@router.post("/mock/config")
def update_config(body: dict):
    if "transitionDelayMs" in body:
        runtime_config.transition_delay_ms = body["transitionDelayMs"]
    if "webhookTargetUrl" in body:
        runtime_config.webhook_target_url = body["webhookTargetUrl"]
    if "failureRates" in body:
        runtime_config.failure_rates = body["failureRates"]
    if "generalRateLimit" in body:
        runtime_config.general_rate_limit = body["generalRateLimit"]
    if "dailyRateLimit" in body:
        runtime_config.daily_rate_limit = body["dailyRateLimit"]
    if "eventActionRateLimit" in body:
        runtime_config.event_action_rate_limit = body["eventActionRateLimit"]

    rate_status = {}
    for token, usage in rate_limiter.snapshot().items():
        rate_status[token] = {
            "hourlyUsage": usage.hourly.usage,
            "dailyUsage": usage.daily.usage,
            "eventActionUsage": usage.event_action.usage,
            "hourlyRemaining": max(usage.hourly.limit - usage.hourly.usage, 0),
            "dailyRemaining": max(usage.daily.limit - usage.daily.usage, 0),
            "eventActionRemaining": max(usage.event_action.limit - usage.event_action.usage, 0),
        }

    return {
        "config": {
            "transitionDelayMs": runtime_config.transition_delay_ms,
            "webhookTargetUrl": runtime_config.webhook_target_url,
            "generalRateLimit": runtime_config.general_rate_limit,
            "dailyRateLimit": runtime_config.daily_rate_limit,
            "eventActionRateLimit": runtime_config.event_action_rate_limit,
            "failureRates": runtime_config.failure_rates,
        },
        "rateLimitStatus": {"tokens": rate_status},
    }


@router.post("/mock/reset")
def reset_mock(db: Session = Depends(get_db)):
    reset_db()
    seed_data(db)
    return {
        "message": "Mock state cleared.",
        "defaultConfig": {
            "transitionDelayMs": runtime_config.transition_delay_ms,
            "webhookTargetUrl": runtime_config.webhook_target_url,
            "generalRateLimit": runtime_config.general_rate_limit,
            "dailyRateLimit": runtime_config.daily_rate_limit,
            "eventActionRateLimit": runtime_config.event_action_rate_limit,
            "failureRates": runtime_config.failure_rates,
        },
    }


@router.get("/mock/dashboard")
def dashboard(db: Session = Depends(get_db)):
    return {
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "events": [e.id for e in db.query(Event).all()],
        "venues": [v.id for v in db.query(Venue).all()],
        "orders": [o.id for o in db.query(Order).all()],
        "attendees": [a.id for a in db.query(Attendee).all()],
        "ticketClasses": [t.id for t in db.query(TicketClass).all()],
        "discounts": [d.id for d in db.query(Discount).all()],
        "capacityTiers": [c.id for c in db.query(CapacityTier).all()],
        "seatmaps": [s.id for s in db.query(SeatMap).all()],
        "webhookLog": [
            {
                "targetUrl": w.target_url,
                "action": w.action,
                "status": w.status,
                "attempts": w.attempts,
                "lastAttemptAt": w.last_attempt_at,
            }
            for w in db.query(WebhookLog).all()
        ],
        "config": {
            "transitionDelayMs": runtime_config.transition_delay_ms,
            "generalRateLimit": runtime_config.general_rate_limit,
            "dailyRateLimit": runtime_config.daily_rate_limit,
            "eventActionRateLimit": runtime_config.event_action_rate_limit,
            "webhookTargetUrl": runtime_config.webhook_target_url,
            "failureRates": runtime_config.failure_rates,
        },
        "rateLimitStatus": {
            "tokens": {
                token: {
                    "hourlyUsage": usage.hourly.usage,
                    "dailyUsage": usage.daily.usage,
                    "eventActionUsage": usage.event_action.usage,
                    "hourlyRemaining": max(usage.hourly.limit - usage.hourly.usage, 0),
                    "dailyRemaining": max(usage.daily.limit - usage.daily.usage, 0),
                    "eventActionRemaining": max(usage.event_action.limit - usage.event_action.usage, 0),
                }
                for token, usage in rate_limiter.snapshot().items()
            }
        },
    }
