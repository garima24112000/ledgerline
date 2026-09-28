// Spike: a quiet baseline, a sudden jump far above the merchants' rate limits, then back to the
// baseline. Shows that the rate limiter sheds the excess as fast 429s instead of letting the
// gateway fall over, and that latency recovers once the spike ends.
//
//   BASE_URL=... API_KEYS=key1,key2 ADMIN_USERNAME=... ADMIN_PASSWORD=... k6 run spike.js
//
// Optional: BASE_RATE (50), SPIKE_RATE (800), SPIKE (1m), MAX_VUS (2 x SPIKE_RATE), ENV_LABEL, RESULTS_DIR.
import exec from 'k6/execution';
import {
  API_KEYS, LEDGER_THRESHOLDS, SUMMARY_TREND_STATS, TEARDOWN_TIMEOUT, buildSummary, createPayment, latencyRow,
  newRunId, numberEnv, paymentBody, recordPayment, stringEnv, verifyLedger,
} from './lib/common.js';

const BASE_RATE = numberEnv('BASE_RATE', 50);
const SPIKE_RATE = numberEnv('SPIKE_RATE', 800);
const SPIKE = stringEnv('SPIKE', '1m');
// Each VU waits for its response, so offered load = VUs / latency. If the gateway slows to seconds,
// k6 runs out of VUs, drops iterations and sends less than SPIKE_RATE; raise MAX_VUS to keep pushing.
const MAX_VUS = numberEnv('MAX_VUS', SPIKE_RATE * 2);
const RUN_ID = newRunId();

const STAGES = [
  { target: BASE_RATE, duration: '1m', phase: 'baseline' },
  { target: SPIKE_RATE, duration: '5s', phase: 'spike' }, // the jump
  { target: SPIKE_RATE, duration: SPIKE, phase: 'spike' },
  { target: BASE_RATE, duration: '5s', phase: 'spike' }, // and back
  { target: BASE_RATE, duration: '1m', phase: 'recovery' },
];

function seconds(duration) {
  const match = /^(\d+)(s|m)$/.exec(duration);
  if (!match) {
    throw new Error(`durations must look like 30s or 2m, got "${duration}"`);
  }
  return Number(match[1]) * (match[2] === 'm' ? 60 : 1);
}

// Which phase a moment in the test belongs to, from the stage durations.
const PHASE_ENDS = [];
let elapsed = 0;
for (const stage of STAGES) {
  elapsed += seconds(stage.duration);
  PHASE_ENDS.push({ endS: elapsed, phase: stage.phase });
}

function currentPhase() {
  const elapsedS = (Date.now() - exec.scenario.startTime) / 1000;
  const found = PHASE_ENDS.find((p) => elapsedS < p.endS);
  return found ? found.phase : 'recovery';
}

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: BASE_RATE,
      timeUnit: '1s',
      preAllocatedVUs: SPIKE_RATE,
      maxVUs: MAX_VUS,
      stages: STAGES.map(({ target, duration }) => ({ target, duration })),
    },
  },
  thresholds: {
    // teardown(): /admin/ledger/verify must report every entry balanced and a global net of 0.
    ...LEDGER_THRESHOLDS,
    // During the spike most of the excess should be 429s, not 5xx or timeouts.
    errors: ['rate<0.01'],
    // The spike itself may be slower; what matters is that latency comes back afterwards.
    'accepted_latency{phase:baseline}': ['p(99)<300'],
    'accepted_latency{phase:spike}': ['p(99)<1000'],
    'accepted_latency{phase:recovery}': ['p(99)<300'],
    // Report 429s per phase (the threshold always passes; it only makes k6 keep the submetric).
    'rate_limited{phase:baseline}': ['count>=0'],
    'rate_limited{phase:spike}': ['count>=0'],
    'rate_limited{phase:recovery}': ['count>=0'],
  },
  summaryTrendStats: SUMMARY_TREND_STATS,
  teardownTimeout: TEARDOWN_TIMEOUT,
};

export default function () {
  const phase = currentPhase();
  const iteration = exec.scenario.iterationInTest;
  const apiKey = API_KEYS[iteration % API_KEYS.length];
  const key = `spike-${RUN_ID}-${iteration}`;
  const amount = 10000 + (iteration % 90000);

  const res = createPayment(apiKey, key, paymentBody(amount, key), { phase });
  recordPayment(res, { phase });
}

// After the load: the ledger must still balance.
export function teardown() {
  verifyLedger();
}

export function handleSummary(data) {
  const values = (name) => (data.metrics[name] ? data.metrics[name].values : undefined);
  const limited = (phase) => (values(`rate_limited{phase:${phase}}`) || { count: 0 }).count;
  return buildSummary(data, 'spike',
    `${BASE_RATE} req/s for 1m, jump to ${SPIKE_RATE} req/s for ${SPIKE}, back to ${BASE_RATE} req/s for 1m, `
    + `across ${API_KEYS.length} merchant(s).`,
    [
      latencyRow('Baseline: accepted p50 / p95 / p99', values('accepted_latency{phase:baseline}')),
      latencyRow('Spike: accepted p50 / p95 / p99', values('accepted_latency{phase:spike}')),
      latencyRow('Recovery: accepted p50 / p95 / p99', values('accepted_latency{phase:recovery}')),
      `| 429s: baseline / spike / recovery | ${limited('baseline')} / ${limited('spike')} / ${limited('recovery')} |`,
    ]);
}
