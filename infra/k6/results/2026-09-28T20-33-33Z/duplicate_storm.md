### duplicate_storm

200 VUs in groups of 10; every round each group sends one Idempotency-Key from all its VUs at the same instant. 30 rounds, 1000 ms apart: 600 keys, 6000 requests.

Environment: unspecified environment (set ENV_LABEL). Finished 2026-09-28T20:41:47.642Z, 33 s.

| Metric | Value |
|---|---|
| Payment requests | 6000 (182.0 req/s average over the run) |
| Accepted (201/202) | 620 (18.8/s) |
| Latency of accepted, p50 / p95 / p99 | 148 ms / 284 ms / 535 ms (max 624 ms) |
| Error rate (429 and declines excluded) | 0.00% |
| 429 rate limited | 0 |
| Captured / declined / unknown (bank) | 527 / 73 / 0 |
| Dropped iterations (k6 couldn't start them on time) | 0 |
| Keys sent | 600 (each by 10 VUs at once) |
| First responses (not replayed) | 600 |
| Replayed responses (`Idempotent-Replayed: true`) | 20 |
| 409 IDEMPOTENCY_KEY_IN_USE | 5380 |
| 422 IDEMPOTENCY_KEY_REUSED (must be 0) | 0 |
| Payments found afterwards via GET /v1/payments | 600 |
| Duplicate payments for one key (must be 0) | 0 |
| Unique keys approved (first-hand CAPTURED responses) | 527 |
| CAPTURED payments found via GET /v1/payments (must equal the line above) | 527 |
| Other unexpected responses | 0 |
| Ledger after the run (`/admin/ledger/verify`) | all entries balanced: true, global net: 0 |

**Thresholds**

- PASS `ledger_balanced`: `rate==1`
- PASS `idem_captured_minus_approved`: `count==0`
- PASS `idem_duplicate_payments`: `count==0`
- PASS `ledger_net_zero`: `rate==1`
- PASS `idem_approved_keys`: `count>0`
- PASS `errors`: `rate<0.01`
- PASS `idem_listing_ok`: `rate==1`
- PASS `idem_payments_found`: `count==600`
- PASS `idem_key_reused_422`: `count==0`
