# =============================================================================
# Multi-City Event Booking — Spring Boot App (Multi-Stage Dockerfile)
#
# Stage 1 (builder): Uses Maven + JDK 21 to compile and package all modules.
# Stage 2 (runtime): Uses a slim JRE 21 image — no compiler, no Maven, no src.
#
# How to build:
#   docker build -t event-platform:latest .
#
# How to run standalone (all env vars required):
#   docker run -p 8080:8080 \
#     -e SPRING_PROFILES_ACTIVE=docker \
#     -e STRIPE_SECRET_KEY=sk_test_xxx \
#     -e STRIPE_WEBHOOK_SECRET=whsec_xxx \
#     -e OPENAI_API_KEY=sk-xxx \
#     event-platform:latest
#
# Use docker-compose.yml for the full stack (DB + Redis + mock-eventbrite).
# =============================================================================

# ── Stage 1: BUILD ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Install Maven in the builder image (Temurin JDK image does not include it)
RUN apk add --no-cache maven

# Copy root pom first — Docker layer caching: dependency download is the
# slowest step. We copy poms BEFORE source so that mvn dependency:go-offline
# only re-runs when a pom.xml changes, not every source file change.
COPY pom.xml .
COPY app/pom.xml                   app/pom.xml
COPY shared/pom.xml                shared/pom.xml
COPY discovery-catalog/pom.xml     discovery-catalog/pom.xml
COPY identity/pom.xml              identity/pom.xml
COPY scheduling/pom.xml            scheduling/pom.xml
COPY booking-inventory/pom.xml     booking-inventory/pom.xml
COPY payments-ticketing/pom.xml    payments-ticketing/pom.xml
COPY promotions/pom.xml            promotions/pom.xml
COPY engagement/pom.xml            engagement/pom.xml
COPY admin/pom.xml                 admin/pom.xml

# Download all dependencies offline — cached unless a pom changes
RUN mvn -B dependency:go-offline --no-transfer-progress -q 2>/dev/null || true

# Now copy all source code
COPY app/src                       app/src
COPY shared/src                    shared/src
COPY discovery-catalog/src         discovery-catalog/src
COPY identity/src                  identity/src
COPY scheduling/src                scheduling/src
COPY booking-inventory/src         booking-inventory/src
COPY payments-ticketing/src        payments-ticketing/src
COPY promotions/src                promotions/src
COPY engagement/src                engagement/src
COPY admin/src                     admin/src

# Build the entire project, skip tests (tests run in CI separately)
# -am: also-make all dependencies; -pl app: focus on app module for the final JAR
RUN mvn -B clean package -DskipTests --no-transfer-progress -pl app -am

# ── Stage 2: RUNTIME ──────────────────────────────────────────────────────────
# eclipse-temurin:21-jre-alpine is ~170MB vs ~500MB for JDK.
# No compiler, no Maven, no source code — only the JVM and the fat JAR.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the fat JAR from builder stage
# app-0.0.1-SNAPSHOT.jar is the Spring Boot repackaged fat JAR (includes all deps)
COPY --from=builder /workspace/app/target/app-0.0.1-SNAPSHOT.jar app.jar

# Non-root ownership
RUN chown appuser:appgroup app.jar

USER appuser

# Port the Spring Boot app listens on
EXPOSE 8080

# JVM tuning for containers:
#   -XX:+UseContainerSupport          — respect container CPU/memory limits (default on Java 11+)
#   -XX:MaxRAMPercentage=75.0         — use 75% of container RAM for heap (not a fixed -Xmx)
#   -XX:+ExitOnOutOfMemoryError       — fail fast instead of thrashing
#   -Djava.security.egd=...           — faster startup: use /dev/urandom for SecureRandom
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-docker}", \
  "-jar", "app.jar"]
