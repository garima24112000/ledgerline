# syntax=docker/dockerfile:1
# Build from the repo root (the parent pom lists every module):
#   docker build -f gateway-api/Dockerfile -t ledgerline/gateway-api:dev .

# ---- build: compile only this module and the modules it depends on ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Every module's pom, because Maven reads the whole reactor even with -pl.
COPY pom.xml .
COPY gateway-api/pom.xml gateway-api/
COPY webhook-dispatcher/pom.xml webhook-dispatcher/
COPY mock-bank/pom.xml mock-bank/
COPY demo-merchant/pom.xml demo-merchant/
COPY ledger-audit-lambda/pom.xml ledger-audit-lambda/
COPY gateway-api/src gateway-api/src
# The cache mount keeps ~/.m2 between builds, so the four images share one download of dependencies.
# Tests run in `mvn verify` (Testcontainers needs a Docker daemon, which a build doesn't have).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl gateway-api -am package -DskipTests

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/gateway-api/target/gateway-api.jar app.jar
USER 10001
EXPOSE 8080
# Heap sized from the container's memory limit, not the node's RAM. 40% is left for everything else:
# metaspace + code cache (~90-200 MB measured), thread stacks, direct buffers, GC and malloc overhead.
# 75% got gateway-api OOM-killed at 768 Mi. On OutOfMemoryError the JVM exits and Kubernetes restarts it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=40 -XX:+ExitOnOutOfMemoryError"
# Exec form: java is PID 1, receives SIGTERM, and Spring shuts down gracefully.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
