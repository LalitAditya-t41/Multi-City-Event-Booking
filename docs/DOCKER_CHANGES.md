# Docker Setup — Changes, Working & Setup Guide

> **What this document covers:**  
> Every file that was added or changed to support Docker, why each change was needed, how the pieces
> work together, and the exact commands to run from zero to a working stack.

---

## Table of Contents

1. [Why Docker — The Core Problem](#1-why-docker--the-core-problem)
2. [Files Added (Summary Table)](#2-files-added-summary-table)
3. [Architecture — How the Services Connect](#3-architecture--how-the-services-connect)
4. [File-by-File Deep Dive](#4-file-by-file-deep-dive)
   - 4.1 [Dockerfile — Java Multi-Stage Build](#41-dockerfile--java-multi-stage-build)
   - 4.2 [mock-eventbrite-api/Dockerfile — Python FastAPI](#42-mock-eventbrite-apidockerfile--python-fastapi)
   - 4.3 [docker-compose.yml — The Orchestrator](#43-docker-composeyml--the-orchestrator)
   - 4.4 [application-docker.yaml — Spring Profile Override](#44-application-dockeryaml--spring-profile-override)
   - 4.5 [docker/init.sql — Seed Data](#45-dockerinitsql--seed-data)
   - 4.6 [.env.example — Secrets Template](#46-envexample--secrets-template)
   - 4.7 [.dockerignore — Build Context Filter](#47-dockerignore--build-context-filter)
5. [What Changed vs the Original application.yaml](#5-what-changed-vs-the-original-applicationyaml)
6. [Step-by-Step Setup Guide](#6-step-by-step-setup-guide)
7. [How Startup Sequence Works](#7-how-startup-sequence-works)
8. [Daily Developer Workflows](#8-daily-developer-workflows)
9. [Port Reference & What Gets Mapped Where](#9-port-reference--what-gets-mapped-where)
10. [Volume & Data Lifecycle](#10-volume--data-lifecycle)
11. [How DB Schema + Seed Data Landing Works](#11-how-db-schema--seed-data-landing-works)
12. [Stripe CLI with Docker](#12-stripe-cli-with-docker)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Why Docker — The Core Problem

When you run the app normally (bare-metal), every service lives on **your machine**:

```
Spring Boot app  →  connects to  localhost:5432  (PostgreSQL)
Spring Boot app  →  connects to  localhost:6379  (Redis)
Spring Boot app  →  connects to  localhost:8888  (Mock Eventbrite)
```

Inside Docker, **every container is its own isolated machine**. The word `localhost` inside
a container means *that container itself*, not your laptop. When Spring Boot says
`jdbc:postgresql://localhost:5432` it is looking for PostgreSQL *inside* the same container —
where it doesn't exist.

**Docker's solution:** a shared virtual network.  
Compose creates a bridge network called `event-network`. Every container joins it.
Docker's internal DNS lets containers look each other up **by service name**:

```
Spring Boot app → connects to  postgres:5432    ← resolves to the postgres container
Spring Boot app → connects to  redis:6379       ← resolves to the redis container
Spring Boot app → connects to  mock-eventbrite:8888  ← resolves to that container
```

Every change in the Docker setup flows from this single rule:
> **Replace `localhost` with the Docker Compose service name.**

---

## 2. Files Added (Summary Table)

| File | Type | Purpose |
|---|---|---|
| `Dockerfile` | New | Builds the Spring Boot fat JAR into a container image |
| `mock-eventbrite-api/Dockerfile` | New | Packages the Python FastAPI mock into a container image |
| `docker-compose.yml` | New | Declares all 4 services, their config, health checks, and startup order |
| `app/src/main/resources/application-docker.yaml` | New | Spring profile overlay — replaces `localhost` with Docker service names |
| `docker/init.sql` | New | SQL that seeds org / city / venue / admin user into Postgres on first start |
| `.env.example` | New | Template for the `.env` secrets file that Compose reads |
| `.dockerignore` | New | Tells Docker which files to exclude from the build context (speeds builds) |

No existing source files were modified. All changes are additive.

---

## 3. Architecture — How the Services Connect

```
┌──────────────────────────────────────────────────────────────────────┐
│  Docker Compose  —  event-network (bridge)                           │
│                                                                      │
│  ┌──────────────┐     ┌──────────────┐     ┌─────────────────────┐  │
│  │   postgres   │     │    redis     │     │   mock-eventbrite   │  │
│  │  port 5432   │     │  port 6379   │     │     port 8888       │  │
│  │  (internal)  │     │  (internal)  │     │    (internal)       │  │
│  └──────┬───────┘     └──────┬───────┘     └──────────┬──────────┘  │
│         │                   │                          │             │
│         └────────────────┬──┘                          │             │
│                          │        JDBC / Redis / HTTP  │             │
│                    ┌─────▼──────────────────────────────┐            │
│                    │          app  (Spring Boot)         │            │
│                    │           port 8080 (internal)      │            │
│                    └─────────────────────────────────────┘            │
│                                          │                            │
└──────────────────────────────────────────│────────────────────────────┘
                                           │  port mapping
                          ┌────────────────▼──────────────────┐
                          │         HOST MACHINE               │
                          │  localhost:8080  (your browser)   │
                          │  localhost:5433  (psql / DBeaver) │
                          │  localhost:6379  (redis-cli)      │
                          │  localhost:8888  (mock admin UI)   │
                          │                                    │
                          │   Stripe CLI (runs on HOST)        │
                          │   forwards webhooks → :8080        │
                          └────────────────────────────────────┘
```

**Key points:**
- Containers talk to each other using **service names** as hostnames (Docker DNS).
- The host machine reaches containers through **port mappings** (`host_port:container_port`).
- Postgres is mapped to `5433` on the host (not 5432) to avoid clashing with a local Postgres instance.
- Stripe CLI stays on the **host** because it needs outbound internet access to Stripe cloud.

---

## 4. File-by-File Deep Dive

### 4.1 `Dockerfile` — Java Multi-Stage Build

**Location:** `/Dockerfile` (project root)

A multi-stage build uses two separate images so the final image is tiny:

```
Stage 1  (builder)  — eclipse-temurin:21-jdk-alpine  (~400 MB)
                      Contains: Maven, JDK, source code, all 9 module pom.xml files
                      Runs: mvn clean package → produces app-0.0.1-SNAPSHOT.jar

Stage 2  (runtime)  — eclipse-temurin:21-jre-alpine  (~170 MB)
                      Contains: JRE only, the compiled fat JAR
                      No Maven, no source code, no compiler
```

**Why two stages?**  
If you only used the JDK image for the final container, you'd ship Maven, all downloaded
dependency JARs, the entire source code, and the JDK (200 MB of compiler tools you don't need
at runtime). The two-stage pattern reduces the final image from ~1 GB to ~250 MB.

**Layer caching strategy — this matters for build speed:**

```dockerfile
# SLOW: copy everything, then install deps
COPY . .
RUN mvn dependency:go-offline   # ← rebuilds EVERY time any file changes

# FAST: copy poms first, then source
COPY pom.xml .
COPY app/pom.xml app/pom.xml
# ... all module pom.xml files ...
RUN mvn dependency:go-offline   # ← only rebuilds when a pom.xml changes

COPY app/src app/src             # source changes don't invalidate dep cache
# ... rest of source ...
RUN mvn clean package
```

When you change a Java file, Docker skips re-downloading dependencies (the slow layer)
and only re-runs `mvn package`. A typical second build takes ~30 seconds vs ~5 minutes.

**JVM flags in the `ENTRYPOINT`:**

| Flag | Why |
|---|---|
| `-XX:+UseContainerSupport` | JVM reads cgroup limits (CPU/memory) instead of the host's total RAM |
| `-XX:MaxRAMPercentage=75.0` | Heap = 75% of container RAM (not a fixed `-Xmx`) — adapts to any container size |
| `-XX:+ExitOnOutOfMemoryError` | Crash immediately on OOM instead of hanging; Docker/Compose will restart |
| `-Djava.security.egd=file:/dev/./urandom` | Faster startup — avoids blocking on `/dev/random` entropy drain |

**RSA keys for JWT:**  
The JWT private/public key PEM files at `app/src/main/resources/certs/` are packaged into
the JAR by Maven's default resource copying. No bind mount is needed.

---

### 4.2 `mock-eventbrite-api/Dockerfile` — Python FastAPI

**Location:** `mock-eventbrite-api/Dockerfile`

**Critical line:**
```dockerfile
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8888"]
#                                          ^^^^^^^^^^^
#                          MUST be 0.0.0.0, NOT 127.0.0.1
```

When uvicorn binds to `127.0.0.1` (loopback) it only accepts connections from *within the same
container*. `0.0.0.0` means "accept connections on all network interfaces", which includes
Docker's virtual network interface — so the Spring Boot container can reach it.

Without this change, every Eventbrite API call from the app would fail with a connection refused
error even though the mock service is "running".

**Dependency caching:**
```dockerfile
COPY pyproject.toml .     # copy manifest first
RUN pip install ...        # install — cached layer
COPY app/ ./app/           # copy source — doesn't invalidate pip layer
```

Same pattern as the Java Dockerfile: manifests before source code so the slow step
(downloading packages) is only repeated when `pyproject.toml` changes.

---

### 4.3 `docker-compose.yml` — The Orchestrator

**Location:** `docker-compose.yml` (project root)

This file ties everything together. Key concepts:

**Health checks and `depends_on`:**

```yaml
app:
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_healthy
    mock-eventbrite:
      condition: service_healthy
```

Without health checks, `depends_on` only waits for the container to *start*, not for the
service *inside* it to be ready. Postgres takes a few seconds to initialize its data directory.
If Spring Boot starts too early, it fails with `Connection refused` and the JVM exits.

`service_healthy` means: "wait until the container passes its `healthcheck` before starting me."

Each service's health check:

| Service | Check command | What it tests |
|---|---|---|
| postgres | `pg_isready -U eventplatform -d eventplatform` | DB is accepting TCP connections |
| redis | `redis-cli ping` → expects `PONG` | Redis is ready for commands |
| mock-eventbrite | HTTP GET `/mock/dashboard` | FastAPI is serving requests |
| app | `wget -qO- .../actuator/health` contains `status:UP` | Spring context is fully loaded |

**Environment variables:**

```yaml
app:
  environment:
    SPRING_PROFILES_ACTIVE: docker
    STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY:-sk_test_placeholder}
```

`${VAR:-default}` is Compose syntax: use `$STRIPE_SECRET_KEY` from the environment (or `.env`
file), fall back to `sk_test_placeholder` if not set. This prevents a hard error if you haven't
set up real Stripe keys yet.

**Volumes:**

```yaml
volumes:
  - postgres-data:/var/lib/postgresql/data   # named volume (survives down)
  - ./docker/init.sql:/docker-entrypoint-initdb.d/init.sql:ro  # bind mount (read-only)
```

The named volume `postgres-data` keeps your database across restarts. `docker compose down`
stops containers but the volume persists. `docker compose down -v` deletes both.

---

### 4.4 `application-docker.yaml` — Spring Profile Override

**Location:** `app/src/main/resources/application-docker.yaml`

**How Spring loads it:**  
When `SPRING_PROFILES_ACTIVE=docker`, Spring Boot automatically loads:
1. `application.yaml` (base config)
2. `application-docker.yaml` (overrides — takes priority for matching keys)

This means you don't duplicate the entire config. You only write the keys that *change*.

**The three hostname swaps:**

| Original (`application.yaml`) | Docker override (`application-docker.yaml`) | Why |
|---|---|---|
| `jdbc:postgresql://localhost:5432/...` | `jdbc:postgresql://postgres:5432/...` | `postgres` = Compose service name |
| `spring.data.redis.host: localhost` | `spring.data.redis.host: redis` | `redis` = Compose service name |
| `eventbrite.base-url: http://localhost:8888` | `eventbrite.base-url: http://mock-eventbrite:8888` | `mock-eventbrite` = Compose service name |
| `app.internal-base-url: http://localhost:8080` | `app.internal-base-url: http://app:8080` | `app` = the Spring Boot service itself |

**Stripe is not overridden** because Stripe is external (Stripe's cloud servers). The SDK
connects outbound — a container can reach the internet just like your laptop can. No change needed.

**DEBUG logging** is turned on in the docker profile for easier troubleshooting:
```yaml
logging:
  level:
    com.eventplatform: DEBUG
```

---

### 4.5 `docker/init.sql` — Seed Data

**Location:** `docker/init.sql`

**How it runs:**  
PostgreSQL's Docker entrypoint runs all `*.sql` files in `/docker-entrypoint-initdb.d/`
on the **very first startup** (when the data directory is empty). The file is bind-mounted there:

```yaml
volumes:
  - ./docker/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

It does **not** run again after the volume exists — it's a one-time initialization.

**What it seeds and why:**

| Table | Row | Why needed |
|---|---|---|
| `organizations` | `id=1, eb_org_id='org_1'` | `app.default-org-id=1` is hardcoded in `application.yaml`; all Eventbrite ACL calls require an org ID |
| `cities` | `name='Mumbai'` | Creating venues requires a city FK; test data needs at least one city |
| `venues` | `eb_venue_id='venue_1'` | `venue_1` is the pre-seeded Eventbrite mock venue ID; sync will match on this |
| `users` | `admin@eventplatform.com / Admin@1234` | JWT login requires at least one user; admin role allows all endpoints |
| `user_wallets` | wallet for admin | FK NOT NULL constraint requires every user to have a wallet row |
| `cancellation_policies` | 48h full / 24h partial / 50% | Booking cancellation endpoints require a default policy |

All inserts use `ON CONFLICT DO NOTHING` — running the file multiple times is safe.

**Password:**  
`Admin@1234` — BCrypt hash (cost 10) is baked into the SQL. Change this if deploying beyond localhost.

---

### 4.6 `.env.example` — Secrets Template

**Location:** `.env.example`

This is a template. To use it:
```bash
cp .env.example .env
# Edit .env with your real values
```

`docker-compose.yml` automatically reads `.env` from the directory it runs in. Values declared
in `.env` become available as environment variables inside Compose.

`.env` is in `.gitignore` (via `.dockerignore` commenting and standard Git patterns).  
**Never commit the real `.env` file.**

---

### 4.7 `.dockerignore` — Build Context Filter

**Location:** `.dockerignore`

When you run `docker build`, Docker packs the entire `context` directory and sends it to the
Docker daemon. Without `.dockerignore`, that includes `target/` directories (~500 MB of compiled
JARs and class files).

What the file excludes:

| Pattern | Why |
|---|---|
| `**/target/` | Compiled output — not needed; Maven rebuilds from source inside the container |
| `.git/` | Version control history — irrelevant to the build, large |
| `docs/` | Markdown files — not needed in the image |
| `POQ/` | Planning notes — not needed |
| `.venv/` | Python dev virtual env — container installs its own deps |
| `*.md` | Documentation |
| `.env` | Secrets must NOT be baked into images |

This reduces build context from ~600 MB to ~30 MB, making `docker build` send data to the
daemon much faster.

---

## 5. What Changed vs the Original `application.yaml`

Nothing in the original `application.yaml` was modified. The docker profile is purely additive:

```
application.yaml          — unchanged, still works for bare-metal local dev
application-docker.yaml   — new file, loaded only when SPRING_PROFILES_ACTIVE=docker
```

This means you can still run the app locally without Docker by just starting Postgres, Redis, and
the mock Eventbrite service on their default ports — exactly as before.

**Side-by-side comparison of the keys that change:**

```yaml
# application.yaml (original — untouched)              # application-docker.yaml (new — docker only)
spring:                                                 spring:
  datasource:                                             datasource:
    url: jdbc:postgresql://localhost:5432/eventplatform     url: jdbc:postgresql://postgres:5432/eventplatform
  data:                                                   data:
    redis:                                                  redis:
      host: localhost                                         host: redis
eventbrite:                                             eventbrite:
  base-url: http://localhost:8888                         base-url: http://mock-eventbrite:8888
app:                                                    app:
  internal-base-url: http://localhost:8080                internal-base-url: http://app:8080
```

That is literally all that changes at the application level.

---

## 6. Step-by-Step Setup Guide

### Prerequisites

```bash
# Verify Docker is installed
docker --version        # Docker 24+ recommended
docker compose version  # Compose v2 (the `docker compose` command, not `docker-compose`)

# Verify you are in the project root
ls Dockerfile docker-compose.yml   # should print both file names
```

### Step 1 — Create your `.env` file

```bash
cp .env.example .env
```

Open `.env` and fill in:

```dotenv
STRIPE_SECRET_KEY=sk_test_51...       # from https://dashboard.stripe.com/test/apikeys
STRIPE_PUBLISHABLE_KEY=pk_test_51...
STRIPE_WEBHOOK_SECRET=whsec_...       # from: stripe listen --forward-to http://localhost:8080/api/v1/webhooks/stripe
OPENAI_API_KEY=sk-proj-...            # from https://platform.openai.com/api-keys (optional)
```

> If you skip OpenAI, review moderation will fail gracefully — reviews stay `PENDING_MODERATION`.
> The app starts fine without it.

### Step 2 — Build and start everything

```bash
docker compose up --build
```

What this does, in order:
1. Builds the Spring Boot image (Stage 1: `mvn package`, Stage 2: copy JAR)
2. Builds the mock-eventbrite Python image
3. Starts `postgres` — waits until `pg_isready` passes
4. Starts `redis` — waits until `PONG`
5. Starts `mock-eventbrite` — waits until `/mock/dashboard` responds
6. Starts `app` — Spring Boot loads, Flyway runs migrations (creates ~40 tables)

First build takes **4–8 minutes** (Maven downloads all dependencies). Subsequent builds
with no `pom.xml` changes take **~30–60 seconds**.

Watch the logs. When you see this line, the app is ready:

```
event-app | Started AppApplication in 12.3 seconds (process running for 13.5)
```

### Step 3 — Verify everything is healthy

```bash
# Check all containers are running
docker compose ps

# Expected output:
# NAME                   STATUS          PORTS
# event-postgres         running (healthy)   0.0.0.0:5433->5432/tcp
# event-redis            running (healthy)   0.0.0.0:6379->6379/tcp
# event-mock-eventbrite  running (healthy)   0.0.0.0:8888->8888/tcp
# event-app              running (healthy)   0.0.0.0:8080->8080/tcp
```

### Step 4 — Test the app

```bash
# Health check
curl http://localhost:8080/actuator/health

# Login with seeded admin
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@eventplatform.com","password":"Admin@1234"}'
# Returns: {"accessToken":"eyJ...","refreshToken":"eyJ..."}
```

### Step 5 — Set up Stripe CLI (on your host machine, not Docker)

```bash
# Install Stripe CLI if not already installed
# macOS:
brew install stripe/stripe-cli/stripe

# Ubuntu/Debian:
curl -s https://packages.stripe.dev/api/security/keypair/stripe-cli-gpg/public | gpg --dearmor | sudo tee /usr/share/keyrings/stripe.gpg
echo "deb [signed-by=/usr/share/keyrings/stripe.gpg] https://packages.stripe.dev/stripe-cli-debian-local stable main" | sudo tee /etc/apt/sources.list.d/stripe.list
sudo apt update && sudo apt install stripe

# Login
stripe login

# Start webhook forwarding (run in a separate terminal, keep it open)
stripe listen --forward-to http://localhost:8080/api/v1/webhooks/stripe
# Output: Your webhook signing secret is whsec_...
# Copy that into your .env STRIPE_WEBHOOK_SECRET and restart the app
```

---

## 7. How Startup Sequence Works

```
Time 0s    docker compose up starts all containers simultaneously

Time ~2s   postgres    HEALTHY  (pg_isready passes)
Time ~1s   redis       HEALTHY  (redis-cli ping returns PONG)
Time ~5s   mock-eb     HEALTHY  (HTTP /mock/dashboard returns 200)

Time ~5s   app container starts (depends_on: all three healthy)
             ↓
           JVM starts
             ↓
           Spring context initializes
             ↓
           Flyway runs db/migration/V0__*.sql → V40__*.sql  (~40 migrations)
             ↓
           DataSource connection pool warms up
             ↓
           Redis connection verified
             ↓
           Eventbrite base URL verified (health-check ping to mock-eventbrite)
             ↓
Time ~17s  Spring Boot prints "Started AppApplication in X seconds"
             ↓
           actuator/health returns {"status":"UP"}
             ↓
           app container becomes HEALTHY
```

If any service fails its health check before the app starts, Docker does not start the app.
You'll see it in a waiting state with a log message like `Waiting for dependencies`.

---

## 8. Daily Developer Workflows

### After changing Java source code

```bash
# Rebuild only the app image (skips postgres/redis/mock-eb)
docker compose build app

# Restart only the app container
docker compose up -d app
```

### After changing a pom.xml (new dependency)

```bash
# Full rebuild needed — dependency layer is invalidated
docker compose build app
docker compose up -d app
```

### After changing the mock-eventbrite Python code

```bash
docker compose build mock-eventbrite
docker compose up -d mock-eventbrite
```

### View logs

```bash
# All services (tail)
docker compose logs -f

# Single service
docker compose logs -f app
docker compose logs -f postgres

# Last 100 lines
docker compose logs --tail=100 app
```

### Connect to Postgres from host (e.g. DBeaver, psql)

```
Host:     localhost
Port:     5433         ← note: 5433, not 5432
Database: eventplatform
User:     eventplatform
Password: eventplatform
```

```bash
# Via psql on host
psql -h localhost -p 5433 -U eventplatform -d eventplatform
```

### Open a shell inside a running container

```bash
docker exec -it event-app sh          # Spring Boot container (Alpine Linux)
docker exec -it event-postgres bash   # Postgres
docker exec -it event-redis sh        # Redis
```

### Complete reset (wipe DB, rebuild everything)

```bash
docker compose down -v     # stops containers AND deletes postgres-data volume
docker compose up --build  # fresh build and fresh DB
```

---

## 9. Port Reference & What Gets Mapped Where

| Service | Internal port | Host port | Who uses it |
|---|---|---|---|
| `app` (Spring Boot) | 8080 | **8080** | Browser, Postman, Stripe CLI |
| `postgres` | 5432 | **5433** | DBeaver, psql, DataGrip (host tools only) |
| `redis` | 6379 | **6379** | redis-cli from host (optional) |
| `mock-eventbrite` | 8888 | **8888** | Browser admin UI at `http://localhost:8888/mock/dashboard` |

**Why Postgres is 5433 on the host:**  
If you have a local Postgres running on port 5432 (common on developer machines), mapping to
the same port would cause a conflict and `docker compose up` would fail. Port 5433 avoids this.
Inside Docker, it's still 5432 — `postgres:5432` still works for the Spring Boot container.

---

## 10. Volume & Data Lifecycle

```
docker compose up        → containers start; postgres-data volume reused if it exists
docker compose down      → containers removed; postgres-data volume KEPT (data safe)
docker compose down -v   → containers removed; postgres-data volume DELETED (fresh DB)
docker compose restart   → containers restarted; nothing deleted
docker compose stop      → containers stopped (not removed); volumes kept
```

The `postgres-data` volume is a Docker-managed directory on your host:

```bash
# Find where it lives on disk
docker volume inspect multi-city-event-booking_postgres-data
```

---

## 11. How DB Schema + Seed Data Landing Works

This is the trickiest part to understand — there are **two separate systems** writing to the database:

### System A — Flyway (inside the Spring Boot app)

- Runs automatically when Spring starts, managed by `spring.flyway.enabled=true`
- Reads SQL files from `app/src/main/resources/db/migration/V0__*.sql` through `V40__*.sql`
- **Creates the schema** — all 40+ tables (users, events, bookings, etc.)
- Runs every time the app starts, but skips already-applied migrations (tracked in `flyway_schema_history`)

### System B — `docker/init.sql` (inside Postgres container)

- Runs once, executed by the Postgres container's own entrypoint on **first volume creation**
- **Inserts seed rows** into the tables that Flyway already created
- Runs before the Spring Boot app starts

**The sequence on a fresh run:**

```
1. postgres container starts
2. init.sql runs → tries to INSERT into tables
   ⚠️  But Flyway hasn't run yet! Tables don't exist!
   → init.sql fails silently (errors are ignored by pg entrypoint on init scripts)

3. app container starts
4. Flyway runs → creates all 40 tables (schema is now in place)

5. Tables exist, but seed data was NOT inserted (init.sql already ran and failed)
```

### The Fix — Manual Re-seed

After the very first `docker compose up`, run the seed manually:

```bash
docker exec -i event-postgres psql -U eventplatform -d eventplatform < docker/init.sql
```

This command pipes `init.sql` into the running postgres container after Flyway has created
the schema. All `ON CONFLICT DO NOTHING` guards make it safe to run multiple times.

### Alternative — ApplicationRunner Bean (Permanent Fix)

For a zero-manual-step setup, you can add a Spring bean that inserts seed data after Flyway:

```java
// app/src/main/java/com/eventplatform/DataSeeder.java
@Component
@Profile("docker")
public class DataSeeder implements ApplicationRunner {

    @Autowired private JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("""
            INSERT INTO organizations (id, name, eb_org_id)
            VALUES (1, 'TestOrg', 'org_1')
            ON CONFLICT (id) DO NOTHING
        """);
        // ... rest of seed inserts ...
        log.info("Docker seed data applied");
    }
}
```

This runs after Flyway (Spring guarantees Flyway finishes before `ApplicationRunner` beans execute)
and only when the `docker` profile is active.

---

## 12. Stripe CLI with Docker

Stripe CLI **runs on your host machine** (not inside Docker). Here's why and how it connects:

```
Stripe Cloud
    │  sends test events to Stripe CLI on your machine
    ▼
Stripe CLI  (localhost)
    │  forwards POST /api/v1/webhooks/stripe
    ▼
localhost:8080
    │  Docker maps host:8080 → container:8080
    ▼
event-app container (Spring Boot)
    ▼
StripeWebhookController handles the event
```

**Commands:**

```bash
# Terminal 1 — keep this running the whole session
stripe listen --forward-to http://localhost:8080/api/v1/webhooks/stripe

# Terminal 2 — trigger test events manually
stripe trigger payment_intent.succeeded
stripe trigger payment_intent.payment_failed
stripe trigger charge.refunded
```

The webhook signing secret (`whsec_...`) printed by `stripe listen` must be set in your `.env`
before starting Docker. If you update it after Docker is running, restart the app:

```bash
docker compose restart app
```

---

## 13. Troubleshooting

### App fails to start — "Connection refused" to postgres

```bash
docker compose logs postgres | tail -30
# Check if postgres is healthy:
docker compose ps postgres
```

If postgres isn't healthy, the app shouldn't even start (due to `depends_on: service_healthy`).
If it does start prematurely, wait 30 seconds and check again.

### "No suitable driver found" or "could not connect to server"

The `application-docker.yaml` wasn't loaded. Check:
```bash
docker compose logs app | grep "active profiles"
# Should see: The following 1 profile is active: "docker"
```

If not, check that:
1. `SPRING_PROFILES_ACTIVE=docker` is in the Compose environment
2. `application-docker.yaml` is in `app/src/main/resources/`

### Mock Eventbrite always returns 404

```bash
docker compose logs mock-eventbrite | tail -30
curl http://localhost:8888/mock/dashboard
```

If it can't be reached from the host, the port mapping may have failed. Try:
```bash
docker compose restart mock-eventbrite
```

### Tables don't have seed data (login fails)

Run the manual re-seed:
```bash
docker exec -i event-postgres psql -U eventplatform -d eventplatform < docker/init.sql
```

### Stripe webhook signature verification fails

The signing secret in your `.env` is stale. Re-run `stripe listen`, copy the new `whsec_...`
into `.env`, and restart:
```bash
docker compose restart app
```

### Out of disk space / Docker is slow

Clean up dangling images from old builds:
```bash
docker system prune -f          # removes stopped containers, unused networks, dangling images
docker image prune -a -f        # removes ALL unused images (frees the most space)
```

### Port 8080 / 5433 / 6379 / 8888 already in use

Find what's using the port and stop it, or change the host port in `docker-compose.yml`:
```bash
# Find what is using port 8080
sudo lsof -i :8080

# Or change the port in docker-compose.yml:
# ports:
#   - "8081:8080"   ← change left side only; internal stays the same
```

---

## Quick Reference Card

```bash
# ── FIRST TIME ──────────────────────────────────────────────────────────────
cp .env.example .env && nano .env      # fill in Stripe + OpenAI keys
docker compose up --build              # build images, start stack
# (wait for "Started AppApplication")
docker exec -i event-postgres psql -U eventplatform -d eventplatform < docker/init.sql
# (seed org/city/venue/admin)

# ── VERIFY ──────────────────────────────────────────────────────────────────
docker compose ps                      # all four: running (healthy)
curl http://localhost:8080/actuator/health

# ── DAILY START/STOP ────────────────────────────────────────────────────────
docker compose up -d                   # start in background
docker compose down                    # stop and remove containers (keep DB)

# ── AFTER CODE CHANGE ───────────────────────────────────────────────────────
docker compose build app && docker compose up -d app

# ── FULL RESET ──────────────────────────────────────────────────────────────
docker compose down -v && docker compose up --build
docker exec -i event-postgres psql -U eventplatform -d eventplatform < docker/init.sql

# ── LOGS ────────────────────────────────────────────────────────────────────
docker compose logs -f app
docker compose logs -f postgres
```
