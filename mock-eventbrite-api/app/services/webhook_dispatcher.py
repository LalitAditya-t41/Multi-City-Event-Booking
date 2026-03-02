from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import httpx
from sqlalchemy.orm import Session

from app.config import runtime_config
from app.models.webhook_log import WebhookLog


async def dispatch_webhook(session: Session, action: str, api_url: str, data_obj: dict) -> None:
    target_url = runtime_config.webhook_target_url
    if not target_url:
        return

    payload = {
        "action": action,
        "api_url": api_url,
        "data": {"object": data_obj},
        "config": {
            "action": action,
            "webhook_id": "mock-webhook-1",
            "endpoint_url": target_url,
        },
    }

    attempts = 0
    status = "FAILED"
    for delay in (1, 2, 4):
        attempts += 1
        try:
            async with httpx.AsyncClient(timeout=5) as client:
                resp = await client.post(target_url, json=payload)
                if 200 <= resp.status_code < 300:
                    status = "DELIVERED"
                    break
        except Exception:
            pass
        await asyncio.sleep(delay)

    log = WebhookLog(
        target_url=target_url,
        action=action,
        status=status,
        attempts=attempts,
        last_attempt_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        payload=payload,
    )
    session.add(log)
    session.commit()
