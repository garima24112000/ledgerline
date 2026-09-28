// Steady load: ramp up to a constant arrival rate and hold it. Every request has a new
// idempotency key, so every request is a real payment (bank call + ledger + outbox).
//
//   BASE_URL=... API_KEYS=key1,key2 ADMIN_USERNAME=... ADMIN_PASSWORD=... k6 run steady.js
//
// Optional: RATE (200 req/s), RAMP (30s), HOLD (3m), ENV_LABEL, RESULTS_DIR.
import exec from 'k6/execution';
import {
  API_KEYS, LEDGER_THRESHOLDS, SUMMARY_TREND_STATS, TEARDOWN_TIMEOUT, buildSummary, createPayment, newRunId,
  numberEnv, paymentBody, recordPayment, stringEnv, verifyLedger,
} from './lib/common.js';

const RATE = numberEnv('RATE', 200);
const RAMP = stringEnv('RAMP', '30s');
const HOLD = stringEnv('HOLD', '3m');
const RUN_ID = newRunId();

export const options = {
  scenarios: {
    steady: {
      // Arrival rate, not a VU count: k6 starts RATE iterations per second whatever the response
      // time, like independent customers would. A slow gateway shows up as rising latency and
      // dropped_iterations, not as a politely lower request rate.
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: Math.ceil(RATE / 2),
      maxVUs: RATE * 3,
      stages: [
        { target: RATE, duration: RAMP },
        { target: RATE, duration: HOLD },
      ],
    },
  },
  thresholds: {
    // teardown(): /admin/ledger/verify must report every entry balanced and a global net of 0.
    ...LEDGER_THRESHOLDS,
    accepted_latency: ['p(99)<300'],
    errors: ['rate<0.01'],
  },
  summaryTrendStats: SUMMARY_TREND_STATS,
  teardownTimeout: TEARDOWN_TIMEOUT,
};

export default function () {
  // Round-robin over the merchants so each one gets RATE / API_KEYS.length requests per second.
  const iteration = exec.scenario.iterationInTest;
  const apiKey = API_KEYS[iteration % API_KEYS.length];
  const key = `steady-${RUN_ID}-${iteration}`;
  const amount = 10000 + (iteration % 90000); // ₹100.00 to ₹999.99, in paise

  const res = createPayment(apiKey, key, paymentBody(amount, key));
  recordPayment(res);
}

// After the load: the ledger must still balance.
export function teardown() {
  verifyLedger();
}

export function handleSummary(data) {
  return buildSummary(data, 'steady',
    `Ramp to ${RATE} req/s over ${RAMP}, hold for ${HOLD}, across ${API_KEYS.length} merchant(s); `
    + 'unique Idempotency-Key per request.');
}
