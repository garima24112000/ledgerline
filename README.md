# Ledgerline

A mini payment gateway for small merchants: an idempotent payments API on a double-entry ledger,
with a fake card processor that randomly declines, is slow, or times out, and signed webhooks
delivered through a transactional outbox and Kafka. It's a personal learning
project, and the design decisions and measurements are in [docs/DESIGN.md](docs/DESIGN.md).

| Module | Port | What it does |
|---|---|---|
| `gateway-api` | 8080 | Payments REST API, API-key security, idempotency, double-entry ledger, reconciler, outbox relay |
| `mock-bank` | 8082 | Fake card processor: approves, declines or times out, idempotent on `paymentId` |
| `webhook-dispatcher` | 8081 | Delivers signed webhooks from Kafka, with retry topics, a per-merchant bulkhead and a DLT |
| `demo-merchant` | 8083 | Receives webhooks, verifies the signature, dedupes, can be told to fail |

## Run it

Needs Java 21, Maven, and Docker.

```bash
make up        # Postgres, Redis, Kafka, Prometheus, Grafana (docker compose), waits until healthy
make run-all   # builds and starts all four apps; logs in logs/<app>.log
make test      # unit + integration tests (Testcontainers)
```

## Run it on Kubernetes (kind)

Needs Docker, [kind](https://kind.sigs.k8s.io/), kubectl and Helm. Ports 80 and 443 must be free, and
Docker needs ~6 GB of free memory (stop the compose stack with `make down` first).

```bash
make kind-up     # kind cluster (1 control plane + 2 workers), images, ingress-nginx, metrics-server,
                 # kube-prometheus-stack and the ledgerline chart (infra/helm/ledgerline); ~10 min the first time
make kind-down   # delete the cluster
```

- Gateway API: <http://api.localtest.me/swagger-ui.html> (`*.localtest.me` resolves to 127.0.0.1)
- Grafana: <http://grafana.localtest.me> (Ledgerline dashboard is the home page; `admin`/`admin` to edit)
- `kubectl -n ledgerline get pods,hpa`, and `kubectl -n ledgerline logs deploy/ledgerline-demo-merchant -f` for webhooks

The `curl` examples below work against the cluster too, if you replace `localhost:8080` with `api.localtest.me`.
Debugging commands (logs, exec, jcmd, network tools, psql): [docs/RUNBOOK.md](docs/RUNBOOK.md).

## Run it on AWS (EKS + RDS)

Terraform in `infra/terraform` creates a VPC, EKS (3 × c7i-flex.large), RDS PostgreSQL 16, ECR, an S3 bucket,
the nightly ledger-audit Lambda and a GitHub OIDC deploy role in us-east-1. GitHub Actions
(`.github/workflows/deploy.yml`) builds linux/amd64 images and deploys with Helm.
**It costs about $0.21 per hour while it exists, plus the EC2 cost of the 3 nodes** (see
[DEPLOY.md](docs/DEPLOY.md#costs); free-tier usage or account credits may cover part of the EC2 portion).

```bash
make aws-up      # scripts/aws-up.sh: terraform apply + cluster add-ons + secrets (asks first)
make aws-down    # scripts/aws-down.sh: destroy everything, then verify nothing chargeable is left
```

Prerequisites, GitHub variables, first deploy, redeploy, teardown and common failures:
[docs/DEPLOY.md](docs/DEPLOY.md).

## Observability

- **Grafana:** <http://localhost:3000/d/ledgerline>. The Prometheus datasource and the Ledgerline
  dashboard are provisioned from `infra/grafana/` and `infra/helm/ledgerline/dashboards/`. Open it anonymously, or log in as `admin`/`admin`.
  It shows request rate, p50/p95/p99 latency, 4xx/5xx, 429s per tier, payment/bank/ledger timings,
  outbox lag, webhook outcomes, JVM heap and the Hikari pool.
- **Prometheus:** <http://localhost:9090/targets>. It scrapes all four apps on the host every 5 s.
- **Logs** are one JSON object per line, with `traceId`, `spanId` and, where it applies,
  `paymentId` / `eventId`:

  Each log file starts with Spring's text banner, so keep only the JSON lines before `jq`:

  ```bash
  grep '^{' logs/gateway-api.log | jq .                                      # pretty-print one app
  grep -h '^{' logs/*.log | jq -c 'select(.paymentId == "<id>")'             # one payment across every app
  SPRING_PROFILES_ACTIVE=plain-logs java -jar gateway-api/target/gateway-api.jar   # plain text instead
  ```

Swagger UI: <http://localhost:8080/swagger-ui.html>. Click **Authorize**, paste a demo key into
`ApiKey`, and every endpoint can be tried from the browser.

## Demo credentials

These are for local development only. The migration stores only the SHA-256 hashes of the API keys.

| Merchant | API key (`X-Api-Key`) |
|---|---|
| Chai Point | `sk_test_chaipoint_7Qm2xK9vLp4R` |
| Book Nook | `sk_test_booknook_3Hd8wN5tYc1F` |
| Pixel Prints | `sk_test_pixelprints_9Ze6bJ2rVs7M` |

| Who | Credential | Default | Override with |
|---|---|---|---|
| Operator (`/admin/**`) | HTTP Basic | `admin` / `admin-dev-password` | `ADMIN_USERNAME`, `ADMIN_PASSWORD` |
| Internal services (`/internal/**`) | `X-Service-Token` header | `dev-internal-service-token` | `INTERNAL_SERVICE_TOKEN` |

## Try it

```bash
KEY=sk_test_chaipoint_7Qm2xK9vLp4R

# Charge a card. Amounts are integers in paise: 49900 = ₹499.00
curl -i -X POST localhost:8080/v1/payments \
  -H "X-Api-Key: $KEY" -H "Idempotency-Key: order-1042-attempt" -H "Content-Type: application/json" \
  -d '{"amount": 49900, "currency": "INR", "cardToken": "tok_visa_4242", "merchantOrderId": "order-1042"}'

# Send the same request again: same response, plus "Idempotent-Replayed: true", and no second charge.

# Force an outcome with a magic card token: tok_approve, tok_decline, tok_timeout.
# tok_timeout returns 202 UNKNOWN after 2s; the reconciler resolves it within ~15s.

curl -H "X-Api-Key: $KEY" "localhost:8080/v1/payments?limit=20"            # newest first
curl -H "X-Api-Key: $KEY" "localhost:8080/v1/payments?cursor=<nextCursor>"  # next page

curl -X POST localhost:8080/v1/payments/<id>/refunds \
  -H "X-Api-Key: $KEY" -H "Idempotency-Key: refund-1" -H "Content-Type: application/json" \
  -d '{"amount": 10000}'

curl -u admin:admin-dev-password localhost:8080/admin/ledger/verify       # ledger audit
```

mock-bank's behaviour is configurable with environment variables: `BANK_APPROVE_RATE` (0.85),
`BANK_DECLINE_RATE` (0.10), `BANK_TIMEOUT_RATE` (0.05), `BANK_LATENCY_MIN`/`MAX` (50ms/300ms), and
`BANK_TIMEOUT_SLEEP` (5s).

## Webhooks

Every captured, failed or refunded payment is sent to the merchant's webhook URL (the demo merchants
all point at demo-merchant, whose log shows each event). The body is the event JSON:

```json
{"id": "<event id>", "type": "payment.captured", "merchantId": 1, "createdAt": "…", "data": { …same as the API's payment… }}
```

Types: `payment.captured`, `payment.failed`, `refund.created`. Delivery is **at-least-once**: dedupe
on `id` (also sent as `X-Ledgerline-Event-Id`).

**Verifying the signature** (`X-Ledgerline-Signature: t=<unix seconds>,v1=<hex>`):
1. Split the header into `t` and `v1`.
2. Compute `HMAC-SHA256(webhook secret, t + "." + raw request body)` as lowercase hex.
3. Compare it with `v1` in constant time, and reject `t` more than 5 minutes from now.

Always use the raw body bytes; parsing and re-serializing the JSON changes them.
[`SignatureVerifier`](demo-merchant/src/main/java/com/ledgerline/merchant/SignatureVerifier.java) is a
complete example.

Failed deliveries (non-2xx or no answer within 5 s) are retried after 10 s, 1 m, 6 m and 30 m, then
parked in the dead-letter topic. To see it happen, simulate a flaky merchant:

```bash
FAIL_RATE=0.5 java -jar demo-merchant/target/demo-merchant.jar   # half of all webhooks get a 500
curl -s localhost:8081/actuator/prometheus | grep -E '^(webhook_|dlq_size)'
curl -u admin:admin-dev-password -X POST localhost:8081/admin/dlq/replay   # send dead letters again
```

Swagger UI for the replay endpoint: <http://localhost:8081/swagger-ui.html>.

## Measuring the list query

```bash
make seed-payments      # 1M payments for the demo merchants (~15 s)
make explain-payments   # EXPLAIN ANALYZE with and without the (merchant_id, created_at, id) index
```

## Measuring the outbox relay query

```bash
make explain-outbox     # seeds 1M published + 1k unpublished events in a transaction, EXPLAINs, rolls back
```
