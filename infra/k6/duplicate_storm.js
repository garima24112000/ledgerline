// Duplicate storm: many clients retry the same payment at the same instant. VUs are split into
// groups of KEY_REUSE; in every round, all VUs in a group send the same Idempotency-Key with the
// same body at the same moment. The gateway must create exactly one payment per key: one request
// wins, the rest get 409 IDEMPOTENCY_KEY_IN_USE (still running) or a replay of the winner's
// response (Idempotent-Replayed: true).
//
//   BASE_URL=... API_KEYS=key1,key2 ADMIN_USERNAME=... ADMIN_PASSWORD=... k6 run duplicate_storm.js
//
// Optional: VUS (200), KEY_REUSE (10), ROUNDS (30), ROUND_MS (1000), ENV_LABEL, RESULTS_DIR.
// Run it against mock-bank with BANK_TIMEOUTS_ENABLED=false (see the README): the exact
// captured == approved check below needs every approval to reach a client.
//
// After the storm, teardown():
//   1. calls /admin/ledger/verify: all entries balanced, global net 0;
//   2. lists each merchant's payments through the API and checks
//      - exactly one payment per key: idem_payments_found == keys sent, idem_duplicate_payments == 0;
//      - captured payments == unique keys approved: the number of this run's CAPTURED payments
//        equals the number of keys for which a client got a first-hand (not replayed) CAPTURED
//        response. VUs can't share memory, so both sides add to one counter,
//        idem_captured_minus_approved: -1 per approved key in the VUs, +1 per captured payment in
//        teardown. It must end at exactly 0.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import {
  API_KEYS, BASE_URL, LEDGER_THRESHOLDS, SUMMARY_TREND_STATS, TEARDOWN_TIMEOUT, acceptedLatency, buildSummary,
  captured, createPayment, declined, errorRate, jsonField, newRunId, numberEnv, paymentBody, rateLimited,
  unexpected, unknown, verifyLedger,
} from './lib/common.js';

const VUS = numberEnv('VUS', 200);
const KEY_REUSE = numberEnv('KEY_REUSE', 10);
const ROUNDS = numberEnv('ROUNDS', 30);
const ROUND_MS = numberEnv('ROUND_MS', 1000);
if (VUS % KEY_REUSE !== 0) {
  throw new Error('VUS must be a multiple of KEY_REUSE');
}
const GROUPS = VUS / KEY_REUSE;
const EXPECTED_KEYS = GROUPS * ROUNDS;

// Here a 409 is the expected answer for a duplicate that arrives while the first is running.
http.setResponseCallback(http.expectedStatuses(201, 202, 409, 429));

const winners = new Counter('idem_first_responses'); // 201/202 without Idempotent-Replayed
const replays = new Counter('idem_replayed'); // 201/202 with Idempotent-Replayed: true
const inProgress = new Counter('idem_in_progress_409');
const keyReused = new Counter('idem_key_reused_422'); // must stay 0: every duplicate has the same body
const paymentsFound = new Counter('idem_payments_found');
const duplicatePayments = new Counter('idem_duplicate_payments');
// A Rate, so a failed listing fails the run: thresholds on counters with no samples pass.
const listingOk = new Rate('idem_listing_ok');
// Exact invariant: captured payments == unique keys approved.
const approvedKeys = new Counter('idem_approved_keys'); // first-hand CAPTURED responses, one per key
const capturedPayments = new Counter('idem_captured_payments'); // this run's CAPTURED payments, from the API
const capturedMinusApproved = new Counter('idem_captured_minus_approved'); // must end at exactly 0

export const options = {
  scenarios: {
    storm: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ROUNDS,
      maxDuration: `${Math.ceil((ROUNDS * ROUND_MS) / 1000) + 60}s`,
    },
  },
  // teardown verifies the ledger and pages through the list endpoint; give it room.
  teardownTimeout: TEARDOWN_TIMEOUT,
  thresholds: {
    // teardown(): /admin/ledger/verify must report every entry balanced and a global net of 0.
    ...LEDGER_THRESHOLDS,
    idem_listing_ok: ['rate==1'],
    idem_duplicate_payments: ['count==0'],
    idem_payments_found: [`count==${EXPECTED_KEYS}`],
    idem_captured_minus_approved: ['count==0'],
    idem_approved_keys: ['count>0'], // the check above must compare something
    idem_key_reused_422: ['count==0'],
    errors: ['rate<0.01'],
    // Force k6 to report these even when they are zero.
    idem_first_responses: ['count>=0'],
    idem_replayed: ['count>=0'],
    idem_in_progress_409: ['count>=0'],
    idem_captured_payments: ['count>=0'],
    rate_limited: ['count>=0'],
    unexpected_responses: ['count>=0'],
  },
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  // Every VU fires round r at startAt + r * ROUND_MS. The 3 s head start lets all 200 VUs
  // initialise before the first round.
  return { runId: newRunId(), startAt: Date.now() + 3000 };
}

export default function (data) {
  const round = exec.vu.iterationInScenario;
  const group = Math.floor((exec.vu.idInTest - 1) / KEY_REUSE);
  // A key belongs to one merchant, so every VU in a group uses the group's merchant.
  const apiKey = API_KEYS[group % API_KEYS.length];
  const key = `storm-${data.runId}-r${round}-g${group}`;
  const body = paymentBody(10000 + group * 100 + round, key); // identical for the whole group

  // Barrier: wait for this round's start time, so the group's requests arrive together. A VU that
  // is still busy from the previous round sends late; the gateway should then replay.
  const waitMs = data.startAt + round * ROUND_MS - Date.now();
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }

  const res = createPayment(apiKey, key, body);
  let ok = true;
  if (res.status === 201 || res.status === 202) {
    acceptedLatency.add(res.timings.duration);
    if (res.headers['Idempotent-Replayed'] === 'true') {
      replays.add(1);
    } else {
      winners.add(1);
      // Only the winner's bank outcome counts; replays repeat it.
      const status = jsonField(res, 'status');
      const outcome = { CAPTURED: captured, FAILED: declined, UNKNOWN: unknown }[status];
      if (outcome) {
        outcome.add(1);
      }
      // At most one first-hand CAPTURED per key: once a payment is captured its key is COMPLETED,
      // and every later request with that key gets a replay. (A first-hand response can repeat only
      // after a 202 UNKNOWN, when a retry takes the released key over.)
      if (status === 'CAPTURED') {
        approvedKeys.add(1);
        capturedMinusApproved.add(-1);
      }
    }
    ok = check(res, { 'payment carries the key as merchantOrderId': (r) => jsonField(r, 'merchantOrderId') === key });
  } else if (res.status === 409) {
    inProgress.add(1);
    ok = check(res, { '409 is IDEMPOTENCY_KEY_IN_USE': (r) => jsonField(r, 'error.code') === 'IDEMPOTENCY_KEY_IN_USE' });
  } else if (res.status === 422) {
    keyReused.add(1);
    ok = false;
  } else if (res.status === 429) {
    rateLimited.add(1);
  } else {
    unexpected.add(1, { status: String(res.status) });
    ok = false;
  }
  errorRate.add(!ok);
}

// Pages through one merchant's payments created since the storm started (newest first).
// Returns [{merchantOrderId, status}] for this run's keys; throws if the API fails.
function stormPayments(apiKey, runId, since) {
  const found = [];
  let cursor = null;
  do {
    let url = `${BASE_URL}/v1/payments?limit=100&from=${encodeURIComponent(since)}`;
    if (cursor) {
      url += `&cursor=${encodeURIComponent(cursor)}`;
    }
    const res = http.get(url, { headers: { 'X-Api-Key': apiKey }, tags: { name: 'GET /v1/payments (verify)' } });
    if (res.status === 429) {
      sleep(Number(res.headers['Retry-After']) || 1);
      continue;
    }
    if (res.status !== 200) {
      throw new Error(`listing payments failed: ${res.status} ${res.body}`);
    }
    for (const payment of res.json('data')) {
      if (payment.merchantOrderId.startsWith(`storm-${runId}-`)) {
        found.push(payment);
      }
    }
    cursor = res.json('nextCursor');
  } while (cursor);
  return found;
}

export function teardown(data) {
  verifyLedger();

  const since = new Date(data.startAt - 5000).toISOString();
  const seen = new Set();
  try {
    for (const apiKey of API_KEYS.slice(0, GROUPS)) {
      for (const payment of stormPayments(apiKey, data.runId, since)) {
        if (seen.has(payment.merchantOrderId)) {
          duplicatePayments.add(1);
          continue;
        }
        seen.add(payment.merchantOrderId);
        paymentsFound.add(1);
        if (payment.status === 'CAPTURED') {
          capturedPayments.add(1);
          capturedMinusApproved.add(1);
        }
      }
    }
    listingOk.add(true);
  } catch (e) {
    console.error(String(e));
    listingOk.add(false);
  }
}

export function handleSummary(data) {
  const c = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  return buildSummary(data, 'duplicate_storm',
    `${VUS} VUs in groups of ${KEY_REUSE}; every round each group sends one Idempotency-Key from all its VUs `
    + `at the same instant. ${ROUNDS} rounds, ${ROUND_MS} ms apart: ${EXPECTED_KEYS} keys, ${VUS * ROUNDS} requests.`,
    [
      `| Keys sent | ${EXPECTED_KEYS} (each by ${KEY_REUSE} VUs at once) |`,
      `| First responses (not replayed) | ${c('idem_first_responses')} |`,
      `| Replayed responses (\`Idempotent-Replayed: true\`) | ${c('idem_replayed')} |`,
      `| 409 IDEMPOTENCY_KEY_IN_USE | ${c('idem_in_progress_409')} |`,
      `| 422 IDEMPOTENCY_KEY_REUSED (must be 0) | ${c('idem_key_reused_422')} |`,
      `| Payments found afterwards via GET /v1/payments | ${c('idem_payments_found')} |`,
      `| Duplicate payments for one key (must be 0) | ${c('idem_duplicate_payments')} |`,
      `| Unique keys approved (first-hand CAPTURED responses) | ${c('idem_approved_keys')} |`,
      `| CAPTURED payments found via GET /v1/payments (must equal the line above) | ${c('idem_captured_payments')} |`,
      `| Other unexpected responses | ${c('unexpected_responses')} |`,
    ]);
}
