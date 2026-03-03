from __future__ import annotations

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "mock-eventbrite-api"
    database_url: str = "sqlite+pysqlite:///:memory:"
    transition_delay_ms: int = 2000
    webhook_target_url: str | None = None
    failure_rates: dict = {
        "order": 0.0,
        "refund": 0.0,
        "webhook": 0.0,
    }
    server_port: int = 8888
    server_host: str = "0.0.0.0"

    # Rate limits
    general_rate_limit: int = 2000
    daily_rate_limit: int = 48000
    event_action_rate_limit: int = 200

    class Config:
        env_file = ".env"


settings = Settings()


class RuntimeConfig:
    def __init__(self) -> None:
        self.transition_delay_ms = settings.transition_delay_ms
        self.webhook_target_url = settings.webhook_target_url
        self.failure_rates = dict(settings.failure_rates)
        self.general_rate_limit = settings.general_rate_limit
        self.daily_rate_limit = settings.daily_rate_limit
        self.event_action_rate_limit = settings.event_action_rate_limit


runtime_config = RuntimeConfig()
