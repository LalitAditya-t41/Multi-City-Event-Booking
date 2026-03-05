# Docker Guide — Multi-City Event Booking Platform
## How Dockerization Works, Config Changes, DB Setup & Fake Services

---

## Table of Contents

1. [How Docker Works with a Java Spring Boot App](#1-how-docker-works-with-a-java-spring-boot-app)
2. [The Multi-Stage Dockerfile Explained](#2-the-multi-stage-dockerfile-explained)
3. [How Docker Compose Orchestrates the Stack](#3-how-docker-compose-orchestrates-the-stack)
4. [The Critical Config Change: localhost → Service Names](#4-the-critical-config-change-localhost--service-names)
5. [Database Changes for Docker](#5-database-changes-for-docker)
6. [Mock Eventbrite Service Changes](#6-mock-eventbrite-service-changes)
7. [Stripe CLI with Docker](#7-stripe-cli-with-docker)
8. [OpenAI in Docker](#8-openai-in-docker)
9. [Getting Started — First Run](#9-getting-started--first-run)
10. [Daily Development Workflow](#10-daily-development-workflow)
11. [Environment Variables & Secrets](#11-environment-variables--secrets)
12. [How Flyway Migrations Work in Docker](#12-how-flyway-migrations-work-in-docker)
13. [Data Persistence — Volumes Explained](#13-data-persistence--volumes-explained)
14. [Docker Networking Explained](#14-docker-networking-explained)
15. [Docker Health Checks & Startup Order](#15-docker-health-checks--startup-order)
16. [Rebuild & Iteration Workflow](#16-rebuild--iteration-workflow)
17. [Useful Docker Commands](#17-useful-docker-commands)
18. [Troubleshooting](#18-troubleshooting)

---

## 1. How Docker Works with a Java Spring Boot App

### The Core Idea

Without Docker, running this app requires: Java 21, Maven, PostgreSQL, Redis, and Python — all installed and correctly configured on your machine, all using `localhost` to talk to each other.

With Docker, **each service runs in its own isolated container** — a lightweight, portable environment that contains only what that service needs. Your host machine only needs Docker installed.

```
Without Docker (bare metal):
  Your OS
  ├── Java 21 process  →  localhost:8080
  ├── PostgreSQL       →  localhost:5432
  ├── Redis            →  localhost:6379
  └── Python/uvicorn   →  localhost:8888

With Docker:
  Docker Engine (on your OS)
  ├── Container: event-app          →  port 8080 mapped to host:8080
  ├── Container: event-postgres     →  port 5432 (internal) / host:5433
  ├── Container: event-redis        →  port 6379 mapped to host:6379
  └── Container: event-mock-eventbrite →  port 8888 mapped to host:8888
         all connected on: event-network (Docker bridge network)
```

### Why "localhost" Stops Working Inside Docker

This is the most important concept. When your Spring Boot app runs inside `event-app` container and says `spring.datasource.url=jdbc:postgresql://localhost:5432/...`, it is asking:

> "What is listening on port 5432 inside **this container**?"

The answer is: **nothing**. PostgreSQL runs in the `event-postgres` container, not inside `event-app`.

The fix: use the Docker Compose **service name** (`postgres`) instead of `localhost`. Docker's internal DNS resolves `postgres` to the IP address of the `event-postgres` container on the shared `event-network`.

---

## 2. The Multi-Stage Dockerfile Explained

The `Dockerfile` at the project root uses two stages:

```dockerfile
# Stage 1: Eclipse Temurin JDK 21 Alpine (~350MB)
# - Has: Java compiler (javac), Maven, full JDK
# - Does: mvn clean package → produces app-0.0.1-SNAPSHOT.jar
FROM eclipse-temurin:21-jdk-alpine AS builder

# Stage 2: Eclipse Temurin JRE 21 Alpine (~170MB)  
# - Has: JVM only — no compiler, no Maven, no source code
# - Does: java -jar app.jar
FROM eclipse-temurin:21-jre-alpine AS runtime
```

### Why Two Stages?

| Concern | Builder | Runtime |
|---|---|---|
| Image size | ~500MB | ~200MB final image |
| Security surface | Large (compiler, build tools, source) | Minimal (JVM + JAR only) |
| What ends up in prod | Discarded after build | This is the shipped image |

**The key line in Stage 2:**
```dockerfile
COPY --from=builder /workspace/app/target/app-0.0.1-SNAPSHOT.jar app.jar
```

Only the fat JAR moves from Stage 1 to Stage 2. All Maven cache, source code, and intermediate `.class` files stay in Stage 1 and are thrown away.

### Why We Copy pom.xml Files Before Source Code

```dockerfile
# FIRST: copy all pom.xml files
COPY pom.xml .
COPY app/pom.xml app/pom.xml
# ... all other poms ...

# Download dependencies (cached layer)
RUN mvn -B dependency:go-offline

# THEN: copy source code
COPY app/src app/src
# ... all other src ...
```

Docker builds images in **layers** and caches each layer. If you copy poms first and then download dependencies, Docker caches the "download dependencies" layer. On the next build, **if no pom.xml changed**, Docker reuses the cached dependency layer and jumps straight to compiling your changed source. This turns a 5-minute build into a 30-second build for incremental changes.

### The JVM Flags Explained

```bash
-XX:+UseContainerSupport    # Respect Docker CPU/memory limits (on by default Java 11+)
-XX:MaxRAMPercentage=75.0   # Use 75% of container RAM for heap — adapts to whatever
                             # memory you give the container. Better than a fixed -Xmx.
-XX:+ExitOnOutOfMemoryError # Kill the process cleanly on OOM instead of thrashing
-Djava.security.egd=file:/dev/./urandom  # Faster startup — JVM doesn't block waiting
                             # for entropy from /dev/random (common in containers)
```

---

## 3. How Docker Compose Orchestrates the Stack

`docker-compose.yml` defines 4 services that form the complete running system:

```yaml
services:
  postgres         ← PostgreSQL 15 database
  redis            ← Redis 7 seat lock cache
  mock-eventbrite  ← Python FastAPI mock of Eventbrite v3 API
  app              ← Spring Boot application (all 7 modules)
```

### Startup Order (depends_on + healthchecks)

Docker Compose starts services in dependency order:

```
Step 1: postgres starts    → health check polls pg_isready every 5s
Step 2: redis starts       → health check polls redis-cli ping every 5s
Step 3: mock-eventbrite starts → health check polls /mock/dashboard
Step 4: app starts         → ONLY after postgres, redis, mock-eventbrite are healthy
                           → Flyway runs migrations
                           → Spring context loads
                           → App ready on :8080
```

This is enforced by:
```yaml
app:
  depends_on:
    postgres:
      condition: service_healthy    # ← waits for pg_isready to pass
    redis:
      condition: service_healthy    # ← waits for redis-cli ping to pass
    mock-eventbrite:
      condition: service_healthy    # ← waits for /mock/dashboard to respond
```

Without this, the app would start, try to connect to PostgreSQL before it's ready, and crash. The health checks eliminate that race condition.

---

## 4. The Critical Config Change: localhost → Service Names

This is the **only structural code change** needed to run in Docker. Every `localhost` in your configuration that refers to another service must become that service's Docker Compose service name.

### Before (bare-metal `application.yaml`):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/eventplatform   # ← local PG
  data:
    redis:
      host: localhost                                      # ← local Redis

eventbrite:
  base-url: http://localhost:8888                          # ← local Python mock

app:
  internal-base-url: http://localhost:8080                 # ← this process
```

### After (Docker `application-docker.yaml`):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/eventplatform     # ← Docker service: postgres
  data:
    redis:
      host: redis                                          # ← Docker service: redis

eventbrite:
  base-url: http://mock-eventbrite:8888                    # ← Docker service: mock-eventbrite

app:
  internal-base-url: http://app:8080                       # ← Docker service: app
```

### How Spring Profiles Make This Seamless

The `application-docker.yaml` file (profile: `docker`) is loaded **on top of** the base `application.yaml`. It only overrides the values that differ. You never have to modify `application.yaml` — the Docker config is completely isolated in its own profile file.

```
Spring config loading order when SPRING_PROFILES_ACTIVE=docker:
  1. application.yaml          ← base config (all defaults)
  2. application-docker.yaml   ← Docker overrides (only localhost → service-names)
```

---

## 5. Database Changes for Docker

### 5.1 What Changes

| Aspect | Bare Metal | Docker |
|---|---|---|
| JDBC URL host | `localhost` | `postgres` (service name) |
| Host port | `5432` | `5433` (mapped to avoid conflicts) |
| Schema creation | Flyway on app startup | Same — Flyway still runs on app startup |
| Initial seed data | Manual `psql` commands | `docker/init.sql` auto-runs on first volume create |
| Data persistence | Lives in OS PostgreSQL data dir | Lives in Docker named volume `postgres-data` |

### 5.2 How `docker/init.sql` Works

```yaml
# In docker-compose.yml:
volumes:
  - ./docker/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

The `postgres:15-alpine` image automatically runs every `.sql` file placed in `/docker-entrypoint-initdb.d/` **on first initialization** (when the data volume is empty). It runs **after** Flyway has created all tables (because the Spring Boot app starts after postgres is healthy, runs Flyway, and signals healthy — but wait, actually Flyway runs inside the Spring app, not in postgres).

**Timing clarification — ORDER OF EVENTS:**

```
1. postgres container starts
2. pg_isready passes → postgres is healthy
3. app container starts (depends_on postgres: service_healthy)
4. Spring Boot starts → Flyway runs ALL 40 migrations → tables created
5. App fully starts

HOWEVER — init.sql is run by postgres itself at startup (step 1-2),
BEFORE Flyway. So tables DON'T EXIST yet when init.sql runs!
```

**This means `init.sql` INSERT statements will fail if Flyway hasn't run yet.**

### 5.3 The Correct Solution: Use `depends_on` + a Separate Seed Step

The `docker/init.sql` approach works on **subsequent restarts** because:
- On restart, postgres re-mounts the existing volume (tables already exist from the first Flyway run)
- `init.sql` only runs if the data directory is empty (fresh volume)

**For the very first time (empty volume), we handle this with:**

**Option 1 (Recommended): Post-startup seed script**

```bash
# After docker compose up --build completes and app is healthy:
docker compose exec postgres psql -U eventplatform -d eventplatform \
  -f /docker-entrypoint-initdb.d/init.sql
```

**Option 2: Use a DB initialization service in compose**

Add a one-shot `db-seed` service that waits for the app to be healthy (ensuring Flyway has run), then seeds data:

```yaml
# Add to docker-compose.yml:
db-seed:
  image: postgres:15-alpine
  depends_on:
    app:
      condition: service_healthy
  volumes:
    - ./docker/init.sql:/init.sql:ro
  networks:
    - event-network
  command: >
    psql postgresql://eventplatform:eventplatform@postgres:5432/eventplatform
    -f /init.sql
  restart: "no"
```

**Option 3 (Simplest for dev): `ApplicationRunner` in Spring Boot**

Add a `DataSeeder` bean in `app/` that runs after context loads:

```java
// app/src/main/java/com/eventplatform/app/DataSeeder.java
@Component
@Profile("docker")
public class DataSeeder implements ApplicationRunner {
    // inject org repository, city repository, etc.
    // insert seed rows if they don't exist
    // this runs AFTER Flyway — guaranteed
}
```

This is the most reliable approach: seed runs inside the app after schema creation, in the correct transaction context.

### 5.4 Accessing the Dockerized Database

From your host machine:
```bash
# Port 5433 on host maps to 5432 in container (avoids conflict with local PG)
psql -h localhost -p 5433 -U eventplatform -d eventplatform

# Or via docker exec:
docker exec -it event-postgres psql -U eventplatform -d eventplatform

# Run a quick check:
docker exec event-postgres psql -U eventplatform -d eventplatform \
  -c "SELECT status, COUNT(*) FROM bookings GROUP BY status;"
```

### 5.5 Resetting the Database

```bash
# Stop the stack, delete the postgres volume, restart fresh
docker compose down -v
docker compose up --build

# Or keep other service data and only wipe postgres:
docker compose down
docker volume rm multi-city-event-booking_postgres-data
docker compose up
```

---

## 6. Mock Eventbrite Service Changes

### 6.1 What Changed

| Aspect | Bare Metal | Docker |
|---|---|---|
| URL in app config | `http://localhost:8888` | `http://mock-eventbrite:8888` |
| How it starts | `uv run uvicorn ...` manually | Docker Compose starts it automatically |
| Webhook target | `http://localhost:8080/...` | `http://app:8080/...` (Docker network) |
| Reset state | `curl POST localhost:8888/mock/reset` | Same, but from host: `curl POST localhost:8888/mock/reset` (port is host-mapped) |

### 6.2 The Mock Eventbrite Dockerfile

```dockerfile
FROM python:3.12-slim AS runtime

# Install exact pinned versions (same as pyproject.toml)
RUN pip install --no-cache-dir \
    "fastapi==0.104.1" \
    "uvicorn==0.24.0" ...

COPY app/ ./app/

# Must bind to 0.0.0.0 (not 127.0.0.1) inside Docker
# 127.0.0.1 inside a container is NOT reachable from other containers
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8888"]
```

The `--host 0.0.0.0` is critical. If uvicorn binds to `127.0.0.1` (loopback), it only accepts connections from within the same container. Binding to `0.0.0.0` means it accepts connections from all container IPs on the Docker network.

### 6.3 Mock Eventbrite in the Docker Network

```
event-app container:
  HTTP POST http://mock-eventbrite:8888/v3/organizations/org_1/events/
                     ↑
           Docker DNS resolves "mock-eventbrite" to the container's IP
                     ↓
event-mock-eventbrite container:
  uvicorn listening on 0.0.0.0:8888 receives the request
```

### 6.4 Accessing Mock Eventbrite Dashboard from Host

```bash
# From your laptop (port 8888 is host-mapped):
curl http://localhost:8888/mock/dashboard

# Reset mock state between test runs:
curl -X POST http://localhost:8888/mock/reset
```

### 6.5 Mock Webhook Delivery in Docker

When Mock Eventbrite fires webhooks (e.g. event cancellation), it posts to:
```
http://app:8080/api/v1/webhooks/eventbrite
```

This resolves correctly because both `mock-eventbrite` and `app` are on `event-network`. Configure this via the mock's config endpoint:

```bash
curl -X POST http://localhost:8888/mock/config \
  -H "Content-Type: application/json" \
  -d '{"webhookTargetUrl": "http://app:8080/api/v1/webhooks/eventbrite"}'
```

---

## 7. Stripe CLI with Docker

### Why Stripe CLI Runs on the Host, Not in Docker

Stripe CLI needs to:
1. Open a persistent connection to `api.stripe.com` (outbound)
2. Forward incoming webhook events to your local app

Since your app port 8080 is mapped to `host:8080`, the Stripe CLI on your host machine can reach it directly:

```
Stripe Dashboard (cloud)
        ↓  (webhook event)
Stripe CLI (your host machine)
        ↓  --forward-to http://localhost:8080/api/v1/webhooks/stripe
Host's port 8080
        ↓  (Docker port mapping: host:8080 → container:8080)
event-app container:8080
```

### Setup (run on host, outside Docker)

```bash
# Terminal 1 — keep running during all payment tests:
stripe listen \
  --forward-to http://localhost:8080/api/v1/webhooks/stripe \
  --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.updated,refund.failed

# It prints:
# > Ready! Your webhook signing secret is whsec_XXXXXXX (^C to quit)
```

Set the webhook secret **before starting Docker Compose**:

```bash
# In your .env file:
STRIPE_WEBHOOK_SECRET=whsec_XXXXXXX

# docker-compose.yml reads this from .env automatically
```

### Triggering Test Payments (from host)

```bash
# Simulate a successful payment for a PaymentIntent you created via the app:
stripe payment_intents confirm pi_XXXXXXXXXXXXXXX \
  --payment-method=pm_card_visa

# Trigger test webhook events:
stripe trigger payment_intent.succeeded
stripe trigger charge.refund.updated
```

---

## 8. OpenAI in Docker

No configuration change needed. The `OPENAI_API_KEY` is passed as an environment variable. The app makes outbound HTTPS calls to `api.openai.com` — Docker containers have outbound internet access by default.

```yaml
# docker-compose.yml:
app:
  environment:
    OPENAI_API_KEY: ${OPENAI_API_KEY:-}   # reads from .env or host env
```

```yaml
# application-docker.yaml — no override needed:
openai:
  base-url: https://api.openai.com   # external URL unchanged — no "localhost" here
  api-key: ${OPENAI_API_KEY:}
  moderation-model: omni-moderation-latest
```

If `OPENAI_API_KEY` is empty: reviews stay `PENDING_MODERATION` after 3 retry attempts, then require manual admin moderation. The app does not crash.

---

## 9. Getting Started — First Run

### Prerequisites

```bash
# Install Docker Engine
sudo apt install docker.io docker-compose-plugin -y

# Or Docker Desktop: https://www.docker.com/products/docker-desktop/

# Verify
docker --version          # Docker version 25.x.x
docker compose version    # Docker Compose version v2.x.x
```

### Step 1 — Create Your .env File

```bash
cd /home/dell/Multi-City-Event-Booking
cp .env.example .env
# Edit .env and fill in your real Stripe and OpenAI keys
nano .env
```

### Step 2 — Start Stripe Webhook Listener (host terminal)

```bash
stripe listen \
  --forward-to http://localhost:8080/api/v1/webhooks/stripe \
  --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.updated,refund.failed
```

Copy the `whsec_...` secret and add it to your `.env` file as `STRIPE_WEBHOOK_SECRET`.

### Step 3 — First Build and Start

```bash
docker compose up --build
```

What happens:

```
[1/4] postgres starts → waits for pg_isready
[2/4] redis starts → waits for redis-cli ping
[3/4] mock-eventbrite builds Python image → starts → waits for /mock/dashboard
[4/4] app builds Java image (5-10 min first time, ~30s after) → Flyway runs → app healthy

First build time breakdown:
  mvn dependency:go-offline   ≈ 3-5 min   (cached after first time)
  mvn clean package           ≈ 2-3 min   (cached poms → faster after first)
  Python pip install          ≈ 1-2 min   (cached after first time)
  Total first time            ≈ 8-12 min
  Total after first build     ≈ 30-60s    (only recompiled changed modules)
```

### Step 4 — Verify Everything is Running

```bash
# All containers should be healthy
docker compose ps

# Expected output:
# NAME                        STATUS              PORTS
# event-app                   Up (healthy)        0.0.0.0:8080->8080/tcp
# event-postgres              Up (healthy)        0.0.0.0:5433->5432/tcp
# event-redis                 Up (healthy)        0.0.0.0:6379->6379/tcp
# event-mock-eventbrite       Up (healthy)        0.0.0.0:8888->8888/tcp

# Test the app health endpoint:
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}

# Test mock eventbrite:
curl http://localhost:8888/mock/dashboard
```

### Step 5 — Seed Initial Data

```bash
# After the app is healthy (Flyway has created all tables), seed base data:
docker exec event-postgres psql -U eventplatform -d eventplatform \
  -f /docker-entrypoint-initdb.d/init.sql
```

Verify seed worked:
```bash
docker exec event-postgres psql -U eventplatform -d eventplatform \
  -c "SELECT * FROM organizations; SELECT * FROM cities; SELECT * FROM venues;"
```

---

## 10. Daily Development Workflow

### Code Change → Rebuild → Test

```bash
# Make your Java code changes ...

# Rebuild only the app container (postgres + redis + mock keep running):
docker compose build app

# Restart only the app (zero-downtime for DB/Redis/mock):
docker compose up -d app

# Watch logs:
docker compose logs -f app
```

### Start / Stop

```bash
# Start all (background):
docker compose up -d

# Stop all (containers stopped, volumes preserved):
docker compose down

# Full wipe (containers + volumes — fresh DB):
docker compose down -v
```

### Access Running Containers

```bash
# Shell into the app container:
docker exec -it event-app sh

# Shell into postgres:
docker exec -it event-postgres psql -U eventplatform -d eventplatform

# Monitor Redis seat locks in real time:
docker exec -it event-redis redis-cli monitor

# Check current seat locks:
docker exec event-redis redis-cli keys "seat:lock:*"
```

---

## 11. Environment Variables & Secrets

### How Variables Flow Into Docker

```
.env file (on host)
    ↓  read by docker compose automatically
docker-compose.yml
    environment:
      STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY:-sk_test_placeholder}
    ↓  passed into container environment
application-docker.yaml (Spring Boot)
    stripe:
      secret-key: ${STRIPE_SECRET_KEY:sk_test_placeholder}
    ↓  injected into Spring beans via @Value / @ConfigurationProperties
StripePaymentService, StripeRefundService, etc.
```

### Variable Reference Table

| Variable | Where set | Used by | Required? |
|---|---|---|---|
| `STRIPE_SECRET_KEY` | `.env` | `StripePaymentService` | Yes (for payment) |
| `STRIPE_PUBLISHABLE_KEY` | `.env` | Returned to frontend | Yes (for payment) |
| `STRIPE_WEBHOOK_SECRET` | `.env` (from `stripe listen`) | `StripeWebhookHandler` | Yes (for webhooks) |
| `OPENAI_API_KEY` | `.env` | `OpenAiModerationService` | No (FR8 graceful degrade) |
| `EVENTBRITE_API_KEY` | `docker-compose.yml` hardcoded | All Eb ACL facades | Fixed as `mock-token` |
| `SPRING_PROFILES_ACTIVE` | `docker-compose.yml` hardcoded | Spring Boot profile | Fixed as `docker` |

---

## 12. How Flyway Migrations Work in Docker

Flyway runs **inside the Spring Boot app** on startup — it is NOT a separate container. It connects to PostgreSQL using the same JDBC URL from `application-docker.yaml` and applies all pending migrations.

```
app container starts
    ↓
Spring Boot context loads
    ↓
FlywayAutoConfiguration (Spring Boot auto-config)
    ↓
Flyway.configure().dataSource(postgres:5432).load()
    ↓
Queries flyway_schema_history table
    ↓
Applies V0__create_organization_table.sql through V40__create_moderation_records_table.sql
    ↓
Schema is fully up to date → JPA/Hibernate validates → App continues loading
```

### Flyway Migration Failures in Docker

If a migration fails, the app will refuse to start. The error appears in:
```bash
docker compose logs app | grep -i "flyway\|migration"
```

Common causes:
- `init.sql` tried to insert into a table before Flyway created it → retry after app started
- Old migration was modified (Flyway detects checksum change) → `docker compose down -v` to reset

---

## 13. Data Persistence — Volumes Explained

```yaml
volumes:
  postgres-data:   # named Docker volume — survives container restart/removal
    driver: local
```

### Volume Lifecycle

| Command | Containers | postgres-data Volume | Your Data |
|---|---|---|---|
| `docker compose down` | Removed | Preserved | Preserved |
| `docker compose up` | Recreated | Reattached | Preserved |
| `docker compose down -v` | Removed | **Deleted** | **Lost** |
| `docker compose restart` | Restarted | Attached | Preserved |

### Where data actually lives on disk

```bash
docker volume inspect multi-city-event-booking_postgres-data
# Shows "Mountpoint": "/var/lib/docker/volumes/multi.../data"
# This is on your host filesystem — controlled by Docker daemon
```

### Backup the Database

```bash
# Dump to file on host:
docker exec event-postgres pg_dump -U eventplatform eventplatform \
  > ./docker/backup-$(date +%Y%m%d).sql

# Restore from dump:
docker exec -i event-postgres psql -U eventplatform -d eventplatform \
  < ./docker/backup-20260305.sql
```

---

## 14. Docker Networking Explained

All 4 containers are on `event-network` (a Docker bridge network). Within this network:

- Each container is assigned a virtual IP (e.g. `172.18.0.2`)
- Docker's embedded DNS server maps service names to IPs automatically
- A container can reach any other container by **service name** (not IP)

```
event-network (172.18.0.0/16):
  postgres       → 172.18.0.2  (DNS: "postgres")
  redis          → 172.18.0.3  (DNS: "redis")
  mock-eventbrite → 172.18.0.4 (DNS: "mock-eventbrite")
  app            → 172.18.0.5  (DNS: "app")

Inside event-app:
  JDBC URL: jdbc:postgresql://postgres:5432/...
  Docker DNS resolves "postgres" → 172.18.0.2
  TCP connect to 172.18.0.2:5432
  ✓ Connection to PostgreSQL established
```

### Port Mapping (Container → Host)

```
Container port  Host port  Purpose
8080           :8080      Spring Boot API (Postman, Stripe CLI, browser)
5432 (postgres):5433      PostgreSQL access from host psql / DBeaver
6379           :6379      Redis access from host redis-cli
8888           :8888      Mock Eventbrite access from host curl / Postman
```

> Port 5432 on host → 5433 to avoid conflicting with any local PostgreSQL installation.

---

## 15. Docker Health Checks & Startup Order

```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U eventplatform -d eventplatform"]
    interval: 5s       # check every 5 seconds
    timeout: 5s        # fail check if no response in 5s
    retries: 10        # mark unhealthy after 10 consecutive failures
    start_period: 10s  # don't count failures in the first 10s (startup grace)
```

Typical startup times:
- `postgres`: healthy in ~5-10s
- `redis`: healthy in ~2-3s
- `mock-eventbrite`: healthy in ~15-20s (Python import time)
- `app`: healthy in ~45-90s (JVM startup + Flyway + Spring context)

You can watch the health status:
```bash
watch docker compose ps
# or
docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

---

## 16. Rebuild & Iteration Workflow

### Scenario 1: Java Source Changed

```bash
docker compose build app      # rebuilds only the app image (~30s if cached deps)
docker compose up -d app      # restarts only the app container
docker compose logs -f app    # watch startup
```

### Scenario 2: pom.xml Changed (New Dependency)

```bash
docker compose build app      # mvn dependency:go-offline re-runs (~2-5 min)
docker compose up -d app
```

### Scenario 3: Mock Eventbrite Code Changed

```bash
docker compose build mock-eventbrite
docker compose up -d mock-eventbrite
```

### Scenario 4: application-docker.yaml Changed

No rebuild needed — YAML is inside the fat JAR. Rebuild app:
```bash
docker compose build app && docker compose up -d app
```

### Scenario 5: init.sql Changed (New Seed Data)

The init.sql only runs on empty volumes. To apply new seed:
```bash
# Option A: Manually execute on running container:
docker exec event-postgres psql -U eventplatform -d eventplatform \
  -c "INSERT INTO ..."

# Option B: Full reset (destroys all data):
docker compose down -v && docker compose up --build
```

---

## 17. Useful Docker Commands

```bash
# ── Stack Management ──────────────────────────────────────────────────────────
docker compose up --build          # First run: build images + start all
docker compose up -d               # Start all in background
docker compose down                # Stop + remove containers (keep volumes)
docker compose down -v             # Stop + remove containers + volumes (full reset)
docker compose restart app         # Restart only the app container
docker compose build app           # Rebuild only the app image (no restart)
docker compose pull                # Pull latest base images (postgres, redis, python)

# ── Logs ─────────────────────────────────────────────────────────────────────
docker compose logs -f             # Tail all services
docker compose logs -f app         # Tail only app
docker compose logs -f app | grep -i "error\|exception"  # Filter errors
docker compose logs --since=5m app # Last 5 minutes

# ── Container Access ──────────────────────────────────────────────────────────
docker exec -it event-app sh                                # Shell into app
docker exec -it event-postgres psql -U eventplatform -d eventplatform
docker exec -it event-redis redis-cli                       # Redis CLI
docker exec event-redis redis-cli keys "seat:lock:*"        # Check seat locks
docker exec event-redis redis-cli flushall                  # Clear all Redis (dev only)

# ── Inspection ────────────────────────────────────────────────────────────────
docker compose ps                  # Service status + health
docker stats                       # Live CPU/memory per container
docker inspect event-app           # Full container JSON config
docker volume ls                   # List volumes
docker network ls                  # List networks
docker network inspect multi-city-event-booking_event-network  # Inspect bridge

# ── Cleanup (free disk space) ─────────────────────────────────────────────────
docker system prune                # Remove stopped containers, unused networks, images
docker system prune -a             # Also prune unused images (frees most space)
docker volume prune                # Remove unused volumes
docker image prune -a              # Remove all unused images
```

---

## 18. Troubleshooting

### App Won't Start — DB Connection Refused

```bash
docker compose logs app | grep -i "datasource\|connection\|postgres"
```

**Cause:** App started before postgres was healthy.
**Fix:** The `depends_on: condition: service_healthy` should prevent this. If it still happens:
```bash
docker compose ps   # check postgres status — is it "Up (healthy)"?
docker compose logs postgres  # any postgres startup errors?
```

---

### Flyway Failed — Table Already Exists

```bash
docker compose logs app | grep -i "flyway"
```

**Cause:** Volume from old schema exists + migration number conflict.
**Fix:**
```bash
docker compose down -v              # destroys postgres-data volume
docker compose up --build           # fresh schema from scratch
```

---

### Redis Health Fails

```bash
docker compose logs redis
docker exec event-redis redis-cli ping
```

If `redis-cli` responds but health check fails, the health check command format may have changed. Verify:
```bash
docker inspect event-redis | grep -A5 "Health"
```

---

### Mock Eventbrite Image Build Fails

```bash
docker compose build mock-eventbrite
# Common cause: network issue downloading pip packages
# Fix: retry, or rebuild with --no-cache:
docker compose build --no-cache mock-eventbrite
```

---

### app Takes Too Long to Start (>90s)

Default JVM startup in a container with `MaxRAMPercentage=75.0` and default heap sizing can be slow on low-memory machines.

```bash
# Check available memory:
docker stats event-app --no-stream

# If memory < 512MB, increase Docker Desktop memory allocation
# Or add explicit heap limits to the app service in docker-compose.yml:
app:
  deploy:
    resources:
      limits:
        memory: 1g   # minimum recommended for this app
```

---

### Stripe Webhook Signature Verification Fails

```bash
docker compose logs app | grep -i "stripe-signature\|webhook"
```

**Cause:** `STRIPE_WEBHOOK_SECRET` in `.env` doesn't match what `stripe listen` printed.
**Fix:**
1. Stop the Stripe CLI listener (Ctrl+C)
2. Restart: `stripe listen --forward-to http://localhost:8080/api/v1/webhooks/stripe`
3. Copy the new `whsec_...` secret to your `.env`
4. Restart app: `docker compose restart app`

---

### Cannot Connect to Mock Eventbrite from the App

```bash
# Check from inside the app container:
docker exec -it event-app sh
wget -qO- http://mock-eventbrite:8888/mock/dashboard

# If "Name does not resolve": containers are on different networks
docker network inspect multi-city-event-booking_event-network | grep -A3 "Containers"
# All 4 containers should appear in the output
```

---

### Review Stays PENDING_MODERATION Forever

```bash
docker compose logs app | grep -i "openai\|moderation"
```

**Cause A:** `OPENAI_API_KEY` not set → 401 from OpenAI → retry exhausted → stays `PENDING_MODERATION`.  
**Fix:** Add key to `.env`, restart app, manually re-trigger via admin moderation endpoint.

**Cause B:** Rate limit (429) → retry 3 times → stays `PENDING_MODERATION`.  
**Fix:** Wait 60s and manually moderate via `PUT /api/v1/admin/engagement/reviews/{id}/moderate`.

---

*End of Docker Guide*
