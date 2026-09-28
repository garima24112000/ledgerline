### Steady load and the capacity boundary

`steady.js`: ramp to `RATE` req/s over 30 s, hold for 3 min. Requests alternate between two PRO
merchants, **Book Nook and Pixel Prints** (200 req/s, burst 400 each). Every request has a unique
Idempotency-Key, so each one is a full payment: bank call, ledger entry, outbox row. After the load,
`teardown()` checks `/admin/ledger/verify`.

Environment: local MacBook, the whole stack and k6 on one machine. mock-bank ran without
synthetic timeouts (0 UNKNOWN responses in every run).

| RATE | Actual average* | p50 | p95 | p99 | Errors | 429s | Dropped iterations | Ledger | p99 < 300 ms |
|---|---|---|---|---|---|---|---|---|---|
| 200 | 185.5 req/s | 69 ms | 108 ms | 174 ms | 0.00% | 0 | 0 | balanced, net 0 | PASS |
| **231** | 213.9 req/s | 71 ms | 118 ms | **176 ms** | 0.00% | 0 | 0 | balanced, net 0 | **PASS** |
| **232** | 214.5 req/s | 73 ms | 155 ms | **360 ms** | 0.00% | 0 | 19 | balanced, net 0 | **FAIL** |

\* The average is over the whole run, *including the 30 s ramp*. A run that delivers everything it
was asked for averages `RATE × 195 / 210`: 185.7 at 200, 214.5 at 231, 215.4 at 232. So the averages
above mean the full rate was delivered. Only at 232 were a few requests dropped.

**The healthy boundary on this machine is 231 req/s. 232 req/s is the first point that fails.**

#### Full summaries

RATE=200 (`run-all.sh`, results/2026-09-28T20-33-33Z), finished 2026-09-28T20:37:03Z, 210 s:

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

- PASS `accepted_latency`: `p(99)<300`
- PASS `errors`: `rate<0.01`
- PASS `ledger_balanced`: `rate==1`
- PASS `ledger_net_zero`: `rate==1`

RATE=231: target 231 req/s, actual average 213.9 req/s. p50 / p95 / p99 = 71 / 118 / 176 ms.
0.00% errors, 0 rate-limited, 0 dropped iterations. Ledger balanced, global net 0. All thresholds
passed. (The raw k6 output was not kept: the 232 run overwrote `results/latest/`. These numbers are
as recorded from that run.)

RATE=232 (`k6 run -e RATE=232 steady.js`), finished 2026-09-28T21:53:35Z, 211 s:

| Metric | Value |
|---|---|
| Payment requests | 45220 (214.5 req/s average over the run) |
| Accepted (201/202) | 45220 (214.5/s) |
| Latency of accepted, p50 / p95 / p99 | 73 ms / 155 ms / 360 ms (max 815 ms) |
| Error rate (429 and declines excluded) | 0.00% |
| 429 rate limited | 0 |
| Captured / declined / unknown (bank) | 40459 / 4761 / 0 |
| Dropped iterations (k6 couldn't start them on time) | 19 |
| Ledger after the run (`/admin/ledger/verify`) | all entries balanced: true, global net: 0 |

- FAIL `accepted_latency`: `p(99)<300`
- PASS `errors`: `rate<0.01`
- PASS `ledger_balanced`: `rate==1`
- PASS `ledger_net_zero`: `rate==1`

#### What it shows

- **A knee, not a slope.** From 200 to 231 req/s, p99 barely moves (174 → 176 ms). One more
  request per second doubles it (360 ms), and k6 starts dropping iterations: requests are
  beginning to queue.
- **Latency degrades before correctness.** At every rate, there were no errors, no 429s, and the
  ledger balanced to 0.
- **The median hardly changes** (69–73 ms, about the bank's own 20–100 ms). The degradation is
  entirely in the tail, which is the signature of queueing on a shared resource rather than slow
  code. Exploratory runs pointed at the ledger's hot accounts: every capture locks the same
  platform-wide `CUSTOMER_FUNDS` and `PLATFORM_FEES` rows. (Postgres sessions were waiting on
  `SELECT ... FROM accounts ... FOR UPDATE`, and Hikari's pool was full.) That profiling was not
  repeated at 231/232 req/s.
- **Read the boundary as "about 230 req/s on this laptop".** Each point is a single run, and a
  1 req/s step is within run-to-run noise. The machine also ran k6 and every dependency, so a real
  deployment would have a different number.

#### Invalid earlier run (not a capacity measurement)

`results/2026-09-28T20-13-23Z` was run while **Book Nook was still a FREE merchant** (20 req/s,
burst 40). At RATE=200 it received 100 req/s. Of 38 999 requests, **15 321 (39%) were rejected
with 429**, so only 112.6 req/s reached the payment path. Its p99 of 137 ms therefore describes
about half the load. It says nothing about capacity at 200 req/s.
- The same run's `spike` was dominated by 429s: 37 425.
- Its `duplicate_storm` lost whole keys to 429s: 2380 rejections, and only 569 of 600 keys became
  payments, so `idem_payments_found` failed.
- After Book Nook was made PRO, the final measurements above used Book Nook and Pixel Prints. None
  of the final steady or duplicate-storm runs had a single 429.

Earlier exploratory runs (before the teardown checks existed, on a throwaway stack with other
containers and an IDE-run gateway also on the machine) gave p99 of 2.2 s at 200 req/s. They are
superseded by the runs above and not comparable.
