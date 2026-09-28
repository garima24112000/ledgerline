### Duplicate storm: 10 concurrent requests per Idempotency-Key

`duplicate_storm.js`: 200 VUs in 20 groups of 10. In each of 30 rounds (1 s apart), all 10 VUs of a
group send the same Idempotency-Key and body at the same instant. That is 600 keys and 6000
requests, split across two PRO merchants (Book Nook, Pixel Prints). `teardown()` then:
- checks the ledger;
- lists every payment through `GET /v1/payments`;
- compares captured payments with the keys clients saw approved.

Environment: same as [steady.md](steady.md). From `run-all.sh`, results/2026-09-28T20-33-33Z,
finished 2026-09-28T20:41:47Z, 33 s.

| Metric | Value |
|---|---|
| Payment requests | 6000 (182.0 req/s average over the run) |
| Accepted (201/202) | 620 (18.8/s) |
| Latency of accepted, p50 / p95 / p99 | 148 ms / 284 ms / 535 ms (max 624 ms) |
| Error rate (429 and declines excluded) | 0.00% |
| 429 rate limited | 0 |
| Captured / declined / unknown (bank, first responses only) | 527 / 73 / 0 |
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

- PASS `idem_payments_found`: `count==600`
- PASS `idem_duplicate_payments`: `count==0`
- PASS `idem_captured_minus_approved`: `count==0`
- PASS `idem_approved_keys`: `count>0`
- PASS `idem_key_reused_422`: `count==0`
- PASS `idem_listing_ok`: `rate==1`
- PASS `errors`: `rate<0.01`
- PASS `ledger_balanced`: `rate==1`
- PASS `ledger_net_zero`: `rate==1`

#### What it shows

- **Exactly one payment per key.** 600 keys gave 600 first responses and 600 payments, with no
  duplicates. The `INSERT ... ON CONFLICT DO NOTHING` on `idempotency_keys` picks a single winner
  even when 10 requests arrive within milliseconds of each other.
- **Captured payments == unique keys approved: 527 = 527.** Every key a client saw approved has
  exactly one CAPTURED payment, and no payment was captured without a client being told. The
  other 73 keys were declined by the bank, each as exactly one FAILED payment.
- **The ledger balanced afterwards**, with a global net of 0.
- **Losers are told the truth.**
  - 5380 of the 5400 duplicates arrived while the winner was still talking to the bank. They got
    409 `IDEMPOTENCY_KEY_IN_USE` ("retry later"), with no wait and no second charge.
  - The other 20 arrived after the winner had finished, and got its stored response replayed with
    `Idempotent-Replayed: true`.
- **Duplicates are cheap.** Only 620 requests did real work. A 409 costs a no-op indexed
  `INSERT` plus a `SELECT`: no bank call, no ledger lock.

The invalid run with Book Nook still FREE (results/2026-09-28T20-13-23Z) failed this scenario:
- **429s:** 2380 of its requests were rejected with 429.
- **Missing payments:** 31 keys had every request rejected, so only 569 of 600 keys became
  payments, and `idem_payments_found` failed.
- **Still correct:** it had no duplicates, 498 approved = 498 captured, and a balanced ledger. That
  shows the checks tell "rejected by the rate limit" apart from "charged twice".
