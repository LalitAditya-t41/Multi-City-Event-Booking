# =============================================================================
# Mock Eventbrite API — Python FastAPI (Dockerfile)
#
# Slim Python 3.12 image. Installs dependencies via pip (not uv) for
# reproducibility inside Docker. Source code is copied in; runs uvicorn.
# =============================================================================

FROM python:3.12-slim AS runtime

# Security: non-root user
RUN groupadd -r mockuser && useradd -r -g mockuser mockuser

WORKDIR /app

# Install deps first (cached layer unless pyproject.toml changes)
COPY pyproject.toml .

# Use pip to install — parse deps from pyproject.toml
# We extract the list directly to avoid needing uv inside Docker.
RUN pip install --no-cache-dir \
    "fastapi==0.104.1" \
    "uvicorn==0.24.0" \
    "sqlalchemy==2.0.23" \
    "pydantic==2.5.0" \
    "pydantic-settings==2.1.0" \
    "httpx==0.25.1" \
    "apscheduler==3.10.4" \
    "python-multipart==0.0.6" \
    "requests==2.31.0"

# Copy application source
COPY app/ ./app/

RUN chown -R mockuser:mockuser /app
USER mockuser

EXPOSE 8888

# HOST must be 0.0.0.0 inside Docker so the port is reachable from other containers
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8888"]
