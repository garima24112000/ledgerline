# Load tests (k6)

Three scenarios against `POST /v1/payments`:

| Script | Load | Question it answers |
|---|---|---|
| `steady.js` | Ramp to 200 req/s over 30 s, hold 3 min | At a normal, sustained rate, how fast is a payment and does anything fail? |
| `spike.js` | 50 → 800 → 50 req/s, 1 min each | What happens when traffic jumps far above the rate limits, and does latency come back afterwards? |
| `duplicate_storm.js` | 200 VUs; each Idempotency-Key sent by 10 VUs at the same instant | Does a burst of identical retries still create exactly one payment per key? |

After the load, every scenario's `teardown()` calls `GET /admin/ledger/verify`. The run fails
unless every journal entry is balanced (`allEntriesBalanced: true`) and the global net of all
balances is 0 (`globalNet: 0`).

`lib/common.js` holds what they share: configuration, the request, classifying responses, metrics,
the ledger check, and the summary. `run-all.sh` runs all three. Representative results are in
[`results/`](results/).

Tested with k6 v2.3.0 (`brew install k6`).

## Configuration

Everything comes from environment variables. The scripts contain no addresses, keys or passwords.

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `BASE_URL` | yes | — | Gateway base URL, e.g. `http://localhost:8080` or an in-cluster service URL |
| `API_KEYS` | yes | — | Comma-separated merchant API keys. Use **PRO** merchants (200 req/s, burst 400 each) |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | yes | — | Operator credentials (HTTP Basic) for `/admin/ledger/verify`, called in every `teardown()`. Locally: the gateway's `ADMIN_USERNAME`/`ADMIN_PASSWORD`, default `admin` / `admin-dev-password` |
| `CARD_TOKEN` | no | `tok_visa_4242` | Sent to the bank. Random outcome by default; `tok_approve` / `tok_decline` / `tok_timeout` force one |
| `ENV_LABEL` | no | — | Free text describing where the test ran; printed in the summary |
| `RESULTS_DIR` | no | `results/latest` | Summaries go to `$RESULTS_DIR/<scenario>.md` and `.json`. The directory must exist; `run-all.sh` creates its own |
| `DEBUG` | no | — | Log the body of unexpected responses |
| `RATE`, `RAMP`, `HOLD` | no | `200`, `30s`, `3m` | `steady.js` |
| `BASE_RATE`, `SPIKE_RATE`, `SPIKE`, `MAX_VUS` | no | `50`, `800`, `1m`, `2 × SPIKE_RATE` | `spike.js` |
| `VUS`, `KEY_REUSE`, `ROUNDS`, `ROUND_MS` | no | `200`, `10`, `30`, `1000` | `duplicate_storm.js` |
| `COOLDOWN` | no | `30` | `run-all.sh`: seconds to wait between scenarios |

k6 reads variables from the shell environment and from `-e NAME=value`.

## Running locally

1. Start the infrastructure and the apps (see the project README):

   ```bash
   make up
   make build
   ```

2. Start **mock-bank with its synthetic timeouts disabled** (`BANK_TIMEOUTS_ENABLED=false`) and
   fast responses. From the repository root:

   ```bash
   # terminal 1
   BANK_TIMEOUTS_ENABLED=false BANK_LATENCY_MIN=20ms BANK_LATENCY_MAX=100ms \
     java -jar mock-bank/target/mock-bank.jar
   # terminal 2
   java -jar gateway-api/target/gateway-api.jar
   ```

   - **What `BANK_TIMEOUTS_ENABLED=false` does.** It defaults to `true`. When false, a charge that
     rolls into the timeout bucket (5% by default) is answered on time. Its outcome is drawn with the
     usual approve:decline odds (85:10), so the mix becomes about 89.5% approved and 10.5% declined,
     all at normal latency. The rates don't need retuning. Only the magic card token `tok_timeout`
     still times out.
   - **Why disable timeouts.** With mock-bank's defaults, 5% of charges "time out", and each one
     takes the gateway's full 2 s bank timeout (202 UNKNOWN). Then p99 is about 2 s by construction
     and says nothing about the gateway. The exact `captured == approved` check in
     `duplicate_storm` also needs every approval to reach a client (see below).
   - **Why the latency settings.** The default random latency goes up to 300 ms, which alone uses
     the whole 300 ms budget.
   - Run with the defaults when you *want* to see timeout handling under load.

   Run the gateway from the jar, not from an IDE: IDE run configurations often add
   `-XX:TieredStopAtLevel=1`, which disables the optimising JIT.

3. **Check that every merchant in `API_KEYS` is PRO.**
   - The V5 migration makes Chai Point and Pixel Prints PRO and leaves Book Nook FREE. A tier
     changed by hand stays changed.
   - **Why it matters:** one FREE merchant (20 req/s) in the list quietly turns a capacity test into a
     rate-limit test. That is what invalidated an earlier run: see
     [results/steady.md](results/steady.md#invalid-earlier-run-not-a-capacity-measurement).
   - The final measurements used Book Nook and Pixel Prints, with Book Nook set to PRO.

   ```bash
   docker compose -f infra/docker-compose.yml exec postgres \
     psql -U ledgerline -d ledgerline -c "SELECT id, name, rate_limit_tier FROM merchants"
   # local dev database only: make a demo merchant PRO
   docker compose -f infra/docker-compose.yml exec postgres \
     psql -U ledgerline -d ledgerline -c "UPDATE merchants SET rate_limit_tier = 'PRO' WHERE name = 'Book Nook'"
   ```

   Before trusting a `steady` or `duplicate_storm` result, check its `429 rate limited` row. It
   must be 0.

4. Run from this directory:

   ```bash
   cd infra/k6
   export BASE_URL=http://localhost:8080
   # two PRO merchants' keys (Book Nook + Pixel Prints were used for the results in results/)
   export API_KEYS=sk_test_booknook_3Hd8wN5tYc1F,sk_test_pixelprints_9Ze6bJ2rVs7M
   export ADMIN_USERNAME=admin ADMIN_PASSWORD=admin-dev-password   # the gateway's dev defaults
   export ENV_LABEL="laptop, docker compose"

   ./run-all.sh                               # all three, summaries in results/<timestamp>/

   k6 run steady.js                           # or one at a time, summaries in results/latest/
   k6 run spike.js
   k6 run duplicate_storm.js
   k6 run -e RATE=100 -e HOLD=1m steady.js   # override anything
   ```

   k6 exits non-zero when a threshold fails (99), so the scripts can gate a CI job.

   **`run-all.sh`**:
   - runs `steady.js`, `spike.js` and `duplicate_storm.js` in that order, with `COOLDOWN` seconds
     in between so one scenario's backlog doesn't spill into the next;
   - saves the results in `results/<UTC timestamp>/`:
     - `<scenario>.md`: the Markdown summary;
     - `<scenario>.json`: k6's full summary data;
     - `<scenario>.log`: the console output;
     - `summary.md`: the three summaries in one file.
   - If a scenario fails its thresholds, the others still run. The script exits 1 at the end if any
     scenario failed. Scenario variables (`RATE`, `SPIKE_RATE`, ...) pass through from the
     environment.

## Running against Kubernetes

Two options.

**Port-forward (quick check only).** `kubectl port-forward` sends everything through one tunnel
via the API server. It becomes the bottleneck well below 800 req/s, so use it for smoke runs, not
for numbers:

```bash
kubectl -n <namespace> port-forward svc/<gateway-service> 8080:8080
BASE_URL=http://localhost:8080 API_KEYS=... ADMIN_USERNAME=... ADMIN_PASSWORD=... \
  k6 run -e RATE=20 -e HOLD=30s steady.js
```

**k6 as a Job inside the cluster (real numbers).** k6 then calls the gateway's Service directly,
like another pod would, and the network path matches production traffic from inside the cluster.

```bash
kubectl -n <namespace> create configmap k6-scripts \
  --from-file=steady.js --from-file=spike.js --from-file=duplicate_storm.js --from-file=lib/common.js
kubectl -n <namespace> create secret generic k6-credentials \
  --from-literal=API_KEYS='<pro key 1>,<pro key 2>' \
  --from-literal=ADMIN_USERNAME='<operator user>' --from-literal=ADMIN_PASSWORD='<operator password>'
```

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: k6-steady
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest   # pin to the version you use locally
          args: ["run", "/scripts/steady.js"]
          envFrom:
            - secretRef: { name: k6-credentials }   # API_KEYS, ADMIN_USERNAME, ADMIN_PASSWORD
          env:
            - name: BASE_URL
              value: http://<gateway-service>.<namespace>.svc.cluster.local:8080
            - name: ENV_LABEL
              value: "EKS, in-cluster k6 Job"
            - name: RESULTS_DIR
              value: /out
          resources:
            requests: { cpu: "2", memory: 2Gi }   # 800 req/s with up to 1600 VUs needs room
          volumeMounts:
            - { name: scripts, mountPath: /scripts }
            - { name: out, mountPath: /out }
      volumes:
        - name: scripts
          configMap:
            name: k6-scripts
            items:   # the scripts import ./lib/common.js
              - { key: steady.js, path: steady.js }
              - { key: spike.js, path: spike.js }
              - { key: duplicate_storm.js, path: duplicate_storm.js }
              - { key: common.js, path: lib/common.js }
        - name: out
          emptyDir: {}
```

```bash
kubectl -n <namespace> apply -f k6-steady.yaml
kubectl -n <namespace> logs -f job/k6-steady   # the Markdown summary is printed at the end
```

Things to set on the cluster side:
- Set `BANK_TIMEOUTS_ENABLED=false` (and the latency settings) in mock-bank's Deployment env.
- Note the gateway replica count in `ENV_LABEL`. The rate limit is shared through Redis, so adding
  replicas does not raise a merchant's limit.
- Watch the gateway's `/actuator/prometheus` while the test runs, especially
  `hikaricp_connections_pending` and `rate_limited_requests_total`.
- On EKS with RDS, the first limit to show up was row locks in the database, not the load
  generator or the pods ([results/aws-diagnostic-steady-50.md](results/aws-diagnostic-steady-50.md)).
  To see what the sessions are waiting on, use
  [RUNBOOK: Lock contention on the ledger](../../docs/RUNBOOK.md#8-lock-contention-on-the-ledger).

## What each scenario measures

### steady.js
A **constant arrival rate**: k6 starts `RATE` requests per second whatever the response time, the
way independent customers arrive. (A fixed number of VUs in a loop would instead slow down with
the server and hide the problem.) Every request has a new Idempotency-Key, so each one is a full
payment: idempotency claim, bank call, ledger entry and outbox row in one transaction. The requests
alternate between the merchants in `API_KEYS`, so at 200 req/s two PRO merchants get 100 req/s each,
half their limit. No 429s are expected.

Thresholds:
- `accepted_latency` p99 < 300 ms;
- `errors` rate < 1%;
- the ledger check (below).

### spike.js
A quiet minute, a jump to 16× the baseline, then quiet again. At 800 req/s over two PRO
merchants, each merchant gets 400 req/s, twice its limit. The intended picture is:
- the excess comes back as fast 429s, while accepted requests stay reasonably quick;
- latency in the recovery minute returns to the baseline.

Latency and 429s are reported per phase (`baseline`, `spike`, `recovery`).

Thresholds:
- `errors` < 1%;
- accepted p99 < 300 ms at baseline and in recovery;
- accepted p99 < 1000 ms during the spike;
- the ledger check.

### duplicate_storm.js
VUs are split into groups of `KEY_REUSE`. Before each round, every VU sleeps until the same
wall-clock instant, so a group's identical requests (same key, same body) arrive together. The
gateway must let one request through and answer every other one with either:
- **409 `IDEMPOTENCY_KEY_IN_USE`**, when the first request is still running, or
- **a replay** of the first response (`Idempotent-Replayed: true`), when it has finished.

`teardown()` runs the ledger check. It then pages through `GET /v1/payments` for each merchant and
looks at the payments whose `merchantOrderId` is one of this run's keys.

**The exact invariant: captured payments == unique keys that were approved.**
- **"Approved keys"** is counted by the VUs: every first-hand (not replayed) 201 with
  `status: CAPTURED`. There is at most one per key. Once a payment is captured, its key is
  COMPLETED, and every later request gets a replay.
- **"Captured payments"** is counted by `teardown()`: this run's payments whose status is
  `CAPTURED`.
- **How the two are compared.** k6 VUs share no memory, and a threshold can't compare two metrics.
  So both sides add to one counter, `idem_captured_minus_approved`: the VUs add −1 per approved key,
  and teardown adds +1 per captured payment. It must end at exactly 0.
- **What a mismatch means:**
  - more captured than approved: a key was charged twice, or captured without any client being told;
  - more approved than captured: an approval the client saw was lost.
- **It needs `BANK_TIMEOUTS_ENABLED=false`.** With timeouts, a payment can be captured later by the
  reconciler, after every client got 202 UNKNOWN. That payment is captured but was never seen as
  approved, so the check would fail even though nothing is wrong.

Thresholds:
- `idem_captured_minus_approved == 0`, and `idem_approved_keys > 0` (so the check compares
  something);
- `idem_payments_found == keys sent`;
- `idem_duplicate_payments == 0`;
- `idem_key_reused_422 == 0`;
- `idem_listing_ok == 100%`: the listing itself worked;
- `errors` < 1%;
- the ledger check.

### The ledger check (all scenarios)
`teardown()` calls `GET /admin/ledger/verify` with the admin credentials. The run fails if:
- `allEntriesBalanced` is not `true` (threshold `ledger_balanced: rate==1`), or
- `globalNet` is not `0` (threshold `ledger_net_zero: rate==1`).

A failed call (wrong credentials, a timeout) counts as a failed check too.
- **Why this matters.** k6 *passes* a threshold whose metric received no samples, so a check that
  silently didn't run would look green. The endpoint's full report is logged whenever it is not
  `consistent`.
- **Why check after load.** Every entry is written in one transaction, and a deferred trigger
  rejects unbalanced ones. So this confirms, under concurrency, what the database already enforces.
- **Cost.** It scans the whole ledger, so teardown gets up to 3 minutes.

## Reading the results

**Latency: p50 / p95 / p99.** "p95 = 120 ms" means 95% of requests took 120 ms or less.
- **p50 (median):** the typical request.
- **p95 and p99:** the slowest 5% and 1%. These are what a busy merchant notices. At 200 req/s, a
  bad p99 hits two customers every second.
- **How they relate tells you something:**
  - A high p50 means everything is slow: the bank, CPU, or a slow query.
  - A normal p50 with a very high p99 means occasional stalls or queueing: lock waits, a full
    connection pool, GC pauses.
- The scripts report `accepted_latency`, which covers only 201/202 responses. `http_req_duration`
  also includes 429s, which take about a millisecond. The more traffic is rejected, the better
  they make the latency look.
- Latency here includes the bank's own 20–100 ms (with the load profile). The gateway's share is
  what's left.

**Throughput.** Three numbers:
- `payment_requests`: requests sent;
- `Accepted`: 201/202 responses, i.e. payments that actually happened;
- `dropped_iterations`: requests k6 wanted to start but couldn't, because every VU was still
  waiting for a response.

With an arrival-rate executor, dropped iterations mean the **server** fell behind: the load you
asked for was not the load that was sent. Check this before believing any "it handled X req/s"
claim. The summary's averages are over the whole run, including ramps.

**Error rate.** `errors` counts responses that mean something went wrong: 5xx, timeouts, and 4xx
other than the expected ones. These are **not** errors:
- a **decline** (201 with `status: FAILED`): the gateway did its job; the card was declined;
- **202 UNKNOWN**: the bank didn't answer in 2 s, and the reconciler will settle it;
- **429**: the rate limiter working as designed.

Declines and UNKNOWNs are counted separately (`payments_declined`, `payments_unknown`), and so are
429s. `http_req_failed` uses the same definition (201/202/429 are "expected"; the storm adds 409).

**429s.**
- In `steady`, any 429 means the merchant's traffic exceeded its tier. Either the tier in the
  database isn't PRO, or the rate is set above 200 per merchant.
- In `spike`, 429s are the point: excess traffic should be rejected in about 1 ms, with
  `Retry-After`. Every 429 is checked for that header.
- **No 429s during a spike is a finding, not a pass.** It means the gateway slowed down before
  any merchant reached its limit, so overload queued instead of being rejected.
  [results/spike.md](results/spike.md) shows exactly this.

**Idempotency** (`duplicate_storm`). Healthy looks like:
- `First responses` = keys sent;
- `409 IN_USE` + `Replayed` = everything else;
- `422` = 0;
- payments found = keys, and duplicates = 0;
- unique keys approved = CAPTURED payments found.

Mostly 409s means the duplicates landed while the first request was still waiting on the bank,
which is the hard case. Mostly replays means they arrived after it finished. One edge case: after
a **202 UNKNOWN**, the key's lock is released, and a later duplicate *takes over* and asks the bank
again. That response is not a replay, so with bank timeouts on, `First responses` can exceed the
key count while payments found still equals it. The payment count is what proves idempotency.

## Results

### Local benchmark (the capacity numbers)

Final local measurements (MacBook, whole stack and k6 on one machine, two PRO merchants, mock-bank
without timeouts):

| Scenario | Result |
|---|---|
| `steady`, 200 req/s | p50/p95/p99 69/108/174 ms, 0 errors, 0 × 429, ledger balanced (net 0): **pass** |
| `steady`, 231 req/s | p50/p95/p99 71/118/176 ms, 0 errors, 0 × 429, 0 dropped, ledger balanced: **pass (healthy boundary)** |
| `steady`, 232 req/s | p50/p95/p99 73/155/360 ms, 0 errors, 0 × 429, 19 dropped, ledger balanced: **fail (p99)** |
| `spike`, 50 → 800 → 50 req/s | spike p99 6957 ms, 25 378 dropped iterations, 111 × 429, ledger still balanced: **fail (latency)** |
| `duplicate_storm` | 600 keys → 600 payments, 0 duplicates, 527 approved = 527 captured, ledger balanced: **pass** |

Details and what each shows:
- [results/steady.md](results/steady.md), which covers the capacity boundary and the invalid
  earlier run;
- [results/spike.md](results/spike.md);
- [results/duplicate_storm.md](results/duplicate_storm.md).

The failures are measurements, not bugs in the tests. They mark where this build of the gateway
stops meeting its latency target, and correctness holds on both sides of that point.

Where new runs go:
- `run-all.sh` writes each run to `results/<UTC timestamp>/`.
- A single `k6 run` writes to `results/latest/`, which is gitignored.

When a run is worth keeping, copy its `.md` into `results/` and add what it shows.

### AWS diagnostic run (not a benchmark)

One run on EKS (us-east-1, 3 × c7i-flex.large, RDS PostgreSQL 16 db.t4g.micro) is kept as a
**diagnostic**, not as a capacity number:

| Scenario | Result |
|---|---|
| `steady`, 50 req/s | p50/p95/p99 129/2325/7656 ms, 2.61% errors, 948 dropped, 0 × 429, ledger balanced (net 0): **fail (latency, errors)** |

- It failed because of a design limit, not because of the load generator: every capture queues on the
  shared platform account rows. See
  [Known scaling limits](../../docs/DESIGN.md#known-scaling-limits).
- The observed max was approximately the configured 10 s client timeout, so client-side timeouts may
  have contributed to the error rate; the saved result does not provide an error-type breakdown.
- No threshold was changed. Details: [results/aws-diagnostic-steady-50.md](results/aws-diagnostic-steady-50.md).
