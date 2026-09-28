// Shared by every scenario: configuration from environment variables, the create-payment call,
// response classification, custom metrics, the ledger check run in teardown(), and the
// Markdown/JSON summary written at the end.
import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------------------------
// Configuration. Nothing here knows where the gateway runs or its credentials: BASE_URL, API_KEYS,
// ADMIN_USERNAME and ADMIN_PASSWORD are required.
// ---------------------------------------------------------------------------------------------

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is required (see infra/k6/README.md)`);
  }
  return value;
}

export function numberEnv(name, fallback) {
  const value = __ENV[name];
  if (value === undefined || value === '') {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive number, got "${value}"`);
  }
  return parsed;
}

export function stringEnv(name, fallback) {
  return __ENV[name] || fallback;
}

export const BASE_URL = required('BASE_URL').replace(/\/+$/, '');

// Comma-separated merchant API keys. The scenarios assume PRO merchants (200 req/s, burst 400).
export const API_KEYS = required('API_KEYS').split(',').map((k) => k.trim()).filter((k) => k.length > 0);
if (API_KEYS.length === 0) {
  throw new Error('API_KEYS must contain at least one key');
}

// Operator credentials for GET /admin/ledger/verify (HTTP Basic), which every scenario calls in teardown().
const ADMIN_AUTHORIZATION = `Basic ${encoding.b64encode(`${required('ADMIN_USERNAME')}:${required('ADMIN_PASSWORD')}`)}`;

// Card token sent to the bank. The default lets mock-bank pick approve/decline/timeout at random.
const CARD_TOKEN = stringEnv('CARD_TOKEN', 'tok_visa_4242');

// Unique per run, so idempotency keys never collide with an earlier run against the same database.
export function newRunId() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
}

// 201, 202 and 429 are the API working as designed; everything else counts in http_req_failed.
// Idempotency 409s are expected only in duplicate_storm, which sets its own callback.
http.setResponseCallback(http.expectedStatuses(201, 202, 429));

// ---------------------------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------------------------

// Latency of requests the gateway actually processed (201/202). 429s are excluded: they return in
// about a millisecond and would make p99 look better the more traffic is rejected.
export const paymentRequests = new Counter('payment_requests');
export const acceptedLatency = new Trend('accepted_latency', true);
export const errorRate = new Rate('errors');
export const captured = new Counter('payments_captured');
export const declined = new Counter('payments_declined');
export const unknown = new Counter('payments_unknown');
export const rateLimited = new Counter('rate_limited');
export const unexpected = new Counter('unexpected_responses');

// Ledger audit after the load (see verifyLedger). Rates, not plain values, so that a verify call
// that fails counts as a failed check: k6 passes a threshold whose metric has no samples at all.
const ledgerBalanced = new Rate('ledger_balanced');
const ledgerNetZero = new Rate('ledger_net_zero');
const ledgerGlobalNet = new Gauge('ledger_global_net');

// Every scenario spreads these into its thresholds, so an inconsistent ledger fails the run.
export const LEDGER_THRESHOLDS = {
  ledger_balanced: ['rate==1'],
  ledger_net_zero: ['rate==1'],
};

// /admin/ledger/verify scans the whole ledger, which takes a while once it holds millions of rows.
export const TEARDOWN_TIMEOUT = '3m';

// ---------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------

export function paymentBody(amount, merchantOrderId) {
  return JSON.stringify({ amount, currency: 'INR', cardToken: CARD_TOKEN, merchantOrderId });
}

export function createPayment(apiKey, idempotencyKey, body, tags = {}) {
  paymentRequests.add(1, tags);
  return http.post(`${BASE_URL}/v1/payments`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Api-Key': apiKey,
      'Idempotency-Key': idempotencyKey,
    },
    tags: { name: 'POST /v1/payments', ...tags },
    timeout: '10s',
  });
}

// The parsed body, or one field of it (a k6 GJSON path such as "error.code"); undefined if the body isn't JSON.
export function jsonField(res, field) {
  try {
    return field === undefined ? res.json() : res.json(field);
  } catch (e) {
    return undefined;
  }
}

/**
 * Records one create-payment response in the shared metrics and returns its outcome:
 * captured | declined | unknown | rate_limited | error.
 *
 * Not errors: a bank decline (201 FAILED), a bank timeout (202 UNKNOWN: the gateway answered
 * correctly and the reconciler will settle it) and a 429 (the rate limiter doing its job).
 */
export function recordPayment(res, tags = {}) {
  let outcome = 'error';
  if (res.status === 201 || res.status === 202) {
    const status = jsonField(res, 'status');
    outcome = { CAPTURED: 'captured', FAILED: 'declined', UNKNOWN: 'unknown' }[status] || 'error';
    acceptedLatency.add(res.timings.duration, tags);
  } else if (res.status === 429) {
    outcome = 'rate_limited';
    check(res, { '429 has Retry-After': (r) => Number(r.headers['Retry-After']) >= 1 });
  }

  switch (outcome) {
    case 'captured': captured.add(1, tags); break;
    case 'declined': declined.add(1, tags); break;
    case 'unknown': unknown.add(1, tags); break;
    case 'rate_limited': rateLimited.add(1, tags); break;
    default:
      unexpected.add(1, { status: String(res.status), ...tags });
      if (__ENV.DEBUG) {
        console.warn(`unexpected ${res.status}: ${String(res.body).slice(0, 200)}`);
      }
  }
  errorRate.add(outcome === 'error', tags);
  return outcome;
}

// ---------------------------------------------------------------------------------------------
// Ledger check
// ---------------------------------------------------------------------------------------------

/**
 * Calls GET /admin/ledger/verify and records the result. Call it from teardown(), after the load.
 * The run fails (through LEDGER_THRESHOLDS) unless `allEntriesBalanced` is true and `globalNet` is
 * 0. Never throws: a failed call is recorded as a failed check, not as a missing sample.
 */
export function verifyLedger() {
  const res = http.get(`${BASE_URL}/admin/ledger/verify`, {
    headers: { Authorization: ADMIN_AUTHORIZATION },
    tags: { name: 'GET /admin/ledger/verify' },
    timeout: '150s',
    responseCallback: http.expectedStatuses(200),
  });
  const report = res.status === 200 ? jsonField(res) : undefined;
  if (!report) {
    console.error(`ledger verify failed: HTTP ${res.status} ${String(res.body).slice(0, 200)}`);
    ledgerBalanced.add(false);
    ledgerNetZero.add(false);
    return undefined;
  }

  const balanced = report.allEntriesBalanced === true;
  const netZero = report.globalNet === 0;
  ledgerBalanced.add(balanced);
  ledgerNetZero.add(netZero);
  ledgerGlobalNet.add(Number(report.globalNet) || 0);
  if (!balanced || !netZero || report.consistent !== true) {
    console.error(`ledger verify: ${JSON.stringify(report)}`);
  }
  return report;
}

// ---------------------------------------------------------------------------------------------
// Summary: a Markdown table meant to be pasted into the project README, plus k6's full JSON.
// Written to $RESULTS_DIR/<name>.md|.json (default results/latest, relative to the working
// directory; run-all.sh points it at a new results/<timestamp>/ directory). The curated, committed
// examples live in results/*.md.
// ---------------------------------------------------------------------------------------------

export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'];

function metric(data, name) {
  return data.metrics[name] ? data.metrics[name].values : undefined;
}

function count(data, name) {
  const values = metric(data, name);
  return values ? values.count : 0;
}

function ms(value) {
  return value === undefined ? 'n/a' : `${Math.round(value)} ms`;
}

function percent(value) {
  return value === undefined ? 'n/a' : `${(value * 100).toFixed(2)}%`;
}

export function latencyRow(label, values) {
  if (!values) {
    return `| ${label} | n/a |`;
  }
  return `| ${label} | ${ms(values.med)} / ${ms(values['p(95)'])} / ${ms(values['p(99)'])} (max ${ms(values.max)}) |`;
}

function thresholdLines(data) {
  const lines = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    for (const [expr, result] of Object.entries(m.thresholds || {})) {
      // Thresholds like count>=0 exist only to make k6 report a submetric; skip them.
      if (expr === 'count>=0') continue;
      lines.push(`- ${result.ok ? 'PASS' : 'FAIL'} \`${name}\`: \`${expr}\``);
    }
  }
  return lines;
}

function ledgerRow(data) {
  const balanced = metric(data, 'ledger_balanced');
  const net = metric(data, 'ledger_global_net');
  if (!balanced) {
    return '| Ledger after the run (`/admin/ledger/verify`) | not checked |';
  }
  if (!net) {
    return '| Ledger after the run (`/admin/ledger/verify`) | verify call failed |';
  }
  return `| Ledger after the run (\`/admin/ledger/verify\`) | all entries balanced: ${balanced.rate === 1}, global net: ${net.value} |`;
}

/**
 * @param name        file name and heading, e.g. "steady"
 * @param description one line saying what was run
 * @param extraRows   more Markdown table rows specific to the scenario
 */
export function buildSummary(data, name, description, extraRows = []) {
  const durationS = data.state.testRunDurationMs / 1000;
  const requests = count(data, 'payment_requests');
  const accepted = count(data, 'accepted_latency');
  const env = stringEnv('ENV_LABEL', 'unspecified environment (set ENV_LABEL)');

  const lines = [
    `### ${name}`,
    '',
    `${description}`,
    '',
    `Environment: ${env}. Finished ${new Date().toISOString()}, ${durationS.toFixed(0)} s.`,
    '',
    '| Metric | Value |',
    '|---|---|',
    `| Payment requests | ${requests} (${(requests / durationS).toFixed(1)} req/s average over the run) |`,
    `| Accepted (201/202) | ${accepted} (${(accepted / durationS).toFixed(1)}/s) |`,
    latencyRow('Latency of accepted, p50 / p95 / p99', metric(data, 'accepted_latency')),
    `| Error rate (429 and declines excluded) | ${percent(metric(data, 'errors') && metric(data, 'errors').rate)} |`,
    `| 429 rate limited | ${count(data, 'rate_limited')} |`,
    `| Captured / declined / unknown (bank) | ${count(data, 'payments_captured')} / ${count(data, 'payments_declined')} / ${count(data, 'payments_unknown')} |`,
    `| Dropped iterations (k6 couldn't start them on time) | ${count(data, 'dropped_iterations')} |`,
    ...extraRows,
    ledgerRow(data),
    '',
    '**Thresholds**',
    '',
    ...thresholdLines(data),
    '',
  ];
  const markdown = lines.join('\n');

  const dir = stringEnv('RESULTS_DIR', 'results/latest');
  return {
    stdout: `\n${markdown}\n`,
    [`${dir}/${name}.md`]: markdown,
    [`${dir}/${name}.json`]: JSON.stringify(data, null, 2),
  };
}
