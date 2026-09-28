### steady

Ramp to 200 req/s over 30s, hold for 3m, across 2 merchant(s); unique Idempotency-Key per request.

Environment: unspecified environment (set ENV_LABEL). Finished 2026-09-28T20:37:03.473Z, 210 s.

| Metric | Value |
|---|---|
| Payment requests | 38999 (185.5 req/s average over the run) |
| Accepted (201/202) | 38999 (185.5/s) |
| Latency of accepted, p50 / p95 / p99 | 69 ms / 108 ms / 174 ms (max 606 ms) |
| Error rate (429 and declines excluded) | 0.00% |
| 429 rate limited | 0 |
| Captured / declined / unknown (bank) | 34943 / 4056 / 0 |
| Dropped iterations (k6 couldn't start them on time) | 0 |
| Ledger after the run (`/admin/ledger/verify`) | all entries balanced: true, global net: 0 |

**Thresholds**

- PASS `ledger_net_zero`: `rate==1`
- PASS `ledger_balanced`: `rate==1`
- PASS `accepted_latency`: `p(99)<300`
- PASS `errors`: `rate<0.01`
