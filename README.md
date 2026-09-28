# Ledgerline

A mini payment gateway for small merchants: an idempotent payments API on a double-entry ledger,
with a fake card processor that randomly declines, is slow, or times out. It's a personal learning
project, and the design decisions and measurements are in [docs/DESIGN.md](docs/DESIGN.md).

| Module | Port | What it does |
|---|---|---|
| `gateway-api` | 8080 | Payments REST API, API-key security, idempotency, double-entry ledger, reconciler |
| `mock-bank` | 8082 | Fake card processor: approves, declines or times out, idempotent on `paymentId` |
| `webhook-dispatcher` | 8081 | (in progress) Delivers signed webhooks from Kafka |
| `demo-merchant` | 8083 | (in progress) Receives and verifies webhooks |

## Run it

Needs Java 21, Maven, and Docker.

```bash
make up        # Postgres, Redis, Kafka (docker compose), waits until healthy
make run-all   # builds and starts all four apps; logs in logs/<app>.log
make test      # unit + integration tests (Testcontainers)
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

## Measuring the list query

```bash
make seed-payments      # 1M payments for the demo merchants (~15 s)
make explain-payments   # EXPLAIN ANALYZE with and without the (merchant_id, created_at, id) index
```
