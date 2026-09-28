### Spike: 50 → 800 → 50 req/s

`spike.js`: 50 req/s for 1 min, a jump to 800 req/s (in 5 s) for 1 min, then back to 50 req/s for
1 min. Two PRO merchants (Book Nook, Pixel Prints), so each would get 400 req/s at the peak, twice
its 200/s limit. The default `MAX_VUS` is 1600.

Environment: same as [steady.md](steady.md). From `run-all.sh`, results/2026-09-28T20-33-33Z,
finished 2026-09-28T20:40:44Z, 190 s.

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

- PASS `accepted_latency{phase:baseline}`: `p(99)<300`
- FAIL `accepted_latency{phase:spike}`: `p(99)<1000`
- FAIL `accepted_latency{phase:recovery}`: `p(99)<300`
- PASS `errors`: `rate<0.01`
- PASS `ledger_balanced`: `rate==1`
- PASS `ledger_net_zero`: `rate==1`

#### What it shows

- **800 req/s is far past this machine's ~230 req/s boundary** (see [steady.md](steady.md)).
  During the spike, the p99 of accepted payments was 6957 ms.
- **The load generator could not keep up either.** All 1600 VUs were waiting on slow responses, so
  k6 had to drop 25 378 iterations. The gateway never received the full 800 req/s.
- **The ledger stayed correct under overload.** It was balanced with a global net of 0 afterwards.
  The error rate was 0.01%, and those errors were client-side timeouts, not wrong answers.
- **The rate limiter barely fired: 111 × 429.** Because the gateway slowed down, each merchant's
  *delivered* rate mostly stayed under its 200/s limit. The PRO limits add up to 400 req/s, well
  above the ~230 req/s the payment path handles here. So a per-merchant limit enforces a plan; it
  doesn't protect capacity. Overload queued for seconds instead of being rejected quickly.
- **Recovery is slow.** The median is back to baseline in the recovery minute (80 ms), but p99
  stays at 2 s while the backlog drains.
- **What would change this (not built):**
  - a global concurrency limit or load shedding in front of the payment path;
  - a shorter Hikari `connectionTimeout`, to fail fast instead of queueing;
  - removing the ledger's hot rows.
