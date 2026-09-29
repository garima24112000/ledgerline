### AWS diagnostic run: steady at 50 req/s on EKS (not a benchmark)

**This is a diagnostic run, not a capacity or throughput result.** It failed its latency and error
thresholds. It is kept because it shows where the design limits scaling:
[Known scaling limits](../../../docs/DESIGN.md#known-scaling-limits). The capacity benchmark is the
local run in [steady.md](steady.md) (healthy up to about 230 req/s on one MacBook).

- **Environment:** EKS in us-east-1 on 3 × c7i-flex.large nodes, with RDS for PostgreSQL 16
  (db.t4g.micro). This is the `ENV_LABEL` recorded by the run. The file does not record how many
  gateway replicas there were.
- **Client:** MacBook → public AWS ALB (not an in-cluster k6 Job).
- **Thresholds failed:** `accepted_latency` (p99 < 300 ms) and `errors` (< 1%). No threshold was
  changed for this run or afterwards.
- **What it showed:**
  - Captures queued on the shared `CUSTOMER_FUNDS` / `PLATFORM_FEES` account rows. In Postgres,
    sessions waited in `Lock:tuple` / `Lock:transactionid` on
    `SELECT id FROM accounts WHERE id IN (...) ORDER BY id FOR UPDATE`.
  - During the AWS diagnostic runs, which include this one, Grafana showed ledger-post p99 of several
    seconds (up to about 8 s), and the gateway's Hikari "pending" count peaked at about 1,250. See the screenshots in the [README](../../../README.md#screenshots).
  - Each statement's round trip to RDS happens while the locks are held, which makes the queue worse
    than on a laptop.
- **Errors:** The observed max was approximately the configured 10 s client timeout, so client-side
  timeouts may have contributed to the error rate; the saved result does not provide an error-type
  breakdown.
- **Correctness held:** after the run, all entries were balanced and the global net was 0.

The k6 summary, unchanged from `results/latest/steady.md`:

#### k6 summary

Ramp to 50 req/s over 30s, hold for 3m, across 2 merchant(s); unique Idempotency-Key per request.

Environment: AWS EKS us-east-1, 3 x c7i-flex.large. Finished 2026-09-29T19:35:49.880Z, 213 s.

| Metric | Value |
|---|---|
| Payment requests | 8802 (41.3 req/s average over the run) |
| Accepted (201/202) | 8572 (40.2/s) |
| Latency of accepted, p50 / p95 / p99 | 129 ms / 2325 ms / 7656 ms (max 9999 ms) |
| Error rate (429 and declines excluded) | 2.61% |
| 429 rate limited | 0 |
| Captured / declined / unknown (bank) | 7632 / 940 / 0 |
| Dropped iterations (k6 couldn't start them on time) | 948 |
| Ledger after the run (`/admin/ledger/verify`) | all entries balanced: true, global net: 0 |

**Thresholds**

- FAIL `accepted_latency`: `p(99)<300`
- PASS `ledger_balanced`: `rate==1`
- FAIL `errors`: `rate<0.01`
- PASS `ledger_net_zero`: `rate==1`
