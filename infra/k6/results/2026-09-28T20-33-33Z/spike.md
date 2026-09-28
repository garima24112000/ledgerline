### spike

50 req/s for 1m, jump to 800 req/s for 1m, back to 50 req/s for 1m, across 2 merchant(s).

Environment: unspecified environment (set ENV_LABEL). Finished 2026-09-28T20:40:44.318Z, 190 s.

| Metric | Value |
|---|---|
| Payment requests | 32871 (172.7 req/s average over the run) |
| Accepted (201/202) | 32758 (172.1/s) |
| Latency of accepted, p50 / p95 / p99 | 3722 ms / 5797 ms / 6868 ms (max 9820 ms) |
| Error rate (429 and declines excluded) | 0.01% |
| 429 rate limited | 111 |
| Captured / declined / unknown (bank) | 29264 / 3494 / 0 |
| Dropped iterations (k6 couldn't start them on time) | 25378 |
| Baseline: accepted p50 / p95 / p99 | 73 ms / 111 ms / 131 ms (max 232 ms) |
| Spike: accepted p50 / p95 / p99 | 3959 ms / 6038 ms / 6957 ms (max 9820 ms) |
| Recovery: accepted p50 / p95 / p99 | 80 ms / 217 ms / 2094 ms (max 2740 ms) |
| 429s: baseline / spike / recovery | 0 / 111 / 0 |
| Ledger after the run (`/admin/ledger/verify`) | all entries balanced: true, global net: 0 |

**Thresholds**

- FAIL `accepted_latency{phase:spike}`: `p(99)<1000`
- FAIL `accepted_latency{phase:recovery}`: `p(99)<300`
- PASS `ledger_balanced`: `rate==1`
- PASS `errors`: `rate<0.01`
- PASS `ledger_net_zero`: `rate==1`
- PASS `accepted_latency{phase:baseline}`: `p(99)<300`
