from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.database import reset_db
from app.main import create_app


def auth_headers(token: str = "test-token") -> dict:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture()
def client():
    app = create_app()
    reset_db()
    with TestClient(app) as client:
        yield client
