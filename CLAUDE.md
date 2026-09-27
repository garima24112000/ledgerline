# Ledgerline

A mini payment gateway for small merchants. Personal learning project.

## Architecture (Maven multi-module, Java 21, Spring Boot 3.3.x)
- `gateway-api`: payments REST API, idempotency, double-entry ledger, outbox relay, Redis rate limiting
- `webhook-dispatcher`: consumes Kafka `payment.events`, delivers signed webhooks with retry topics + DLT
- `mock-bank`: fake card processor that randomly succeeds, fails, is slow, or times out (configurable)
- `demo-merchant`: tiny app that receives webhooks, verifies HMAC, logs them, can be told to fail
- `infra/`: docker-compose, k8s Helm chart, k6 scripts, Grafana dashboards

## Tech
Postgres 16 (Flyway migrations), Kafka (KRaft, apache/kafka image), Redis 7,
Spring Data JPA/Hibernate for entities + JdbcTemplate where locking matters, Spring Security,
springdoc-openapi (Swagger UI), Spring Kafka, Java 21 virtual threads, Micrometer + Prometheus,
JUnit 5 + Mockito for unit tests, Testcontainers for integration tests, AssertJ.
AWS via Terraform: VPC, EKS (EC2 nodes), RDS Postgres, ECR, S3, Lambda (Java 21).

## Rules
- Money is `long` minor units (paise). Never double/float.
- Ledger is append-only. No UPDATE/DELETE on journal_entries or postings. Refunds are reversing entries.
- Every change to ledger + outbox happens in ONE database transaction.
- Invariants are enforced in Postgres (constraints/triggers), not only in Java.
- Every feature gets an integration test using Testcontainers.
- Service-layer logic gets fast unit tests with JUnit 5 + Mockito (mock clients and repositories).
- Every public endpoint is documented with OpenAPI annotations.
- Every query used by an endpoint or job is backed by an index; record EXPLAIN ANALYZE in DESIGN.md.
- Keep code simple and readable; this is a portfolio project that I must explain in interviews.
- After finishing a task, update `docs/DESIGN.md` with decisions and trade-offs.

## Commands
- Build + test: `mvn -q clean verify`
- Local stack: `docker compose -f infra/docker-compose.yml up -d`