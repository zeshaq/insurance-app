// Phase 3 / Issue #16 — baseline load test of the canonical money chain.
//
// Three stages of increasing load against quote → bind → pay:
//   Stage 1 — 1 VU / 30s   (smoke; sanity-check that the chain works)
//   Stage 2 — 10 VUs / 60s (typical-traffic baseline)
//   Stage 3 — 100 VUs / 60s (peak; see what breaks first)
//
// CI can override stage targets via env vars (CI_VUS_SMOKE, CI_VUS_TYPICAL,
// CI_VUS_PEAK) so the PR-blocking run completes in well under three minutes
// — the workflow caps them at 1/5/10 for fast feedback. Manual runs against
// staging use the defaults below.
//
// Thresholds:
//   http_req_failed{status!=429}: rate < 0.01  (under 1% non-rate-limit failures)
//   http_req_duration:            p95 < 2000ms, p99 < 5000ms
//
// 429 is intentionally excluded from the error rate. QuoteResource enforces
// 5 requests/min/VIN (ADR-0005); even with fresh VINs per iteration, at peak
// VU counts the occasional collision with another scenario's run-on traffic
// will fire 429s. That is the rate limiter working as designed, not a
// regression.

import { sleep } from 'k6';
import { getToken } from './lib/auth.js';
import { runChain } from './lib/chain.js';

const SMOKE_VUS   = parseInt(__ENV.CI_VUS_SMOKE   || '1',   10);
const TYPICAL_VUS = parseInt(__ENV.CI_VUS_TYPICAL || '10',  10);
const PEAK_VUS    = parseInt(__ENV.CI_VUS_PEAK    || '100', 10);

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: SMOKE_VUS },   // smoke
        { duration: '60s', target: TYPICAL_VUS }, // typical
        { duration: '60s', target: PEAK_VUS },    // peak
        { duration: '5s',  target: 0 },           // graceful ramp-down
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // Non-429 failures must stay under 1% across the whole run.
    // Uses the custom `errors_non_429` Rate metric defined in
    // load/lib/metrics.js — k6's threshold tag-filter syntax has no
    // not-equals operator, so a `{status:!429}` selector on the
    // built-in http_req_failed would silently match nothing.
    'errors_non_429':    ['rate<0.01'],
    'http_req_duration': ['p(95)<2000', 'p(99)<5000'],
    // Per-endpoint p95 — useful for the Phase 6 SLO doc; non-breaking
    // (no threshold abort) so we still get the data even if peak blows
    // the global p95.
    'http_req_duration{endpoint:quote}':   ['p(95)<2000'],
    'http_req_duration{endpoint:policy}':  ['p(95)<2000'],
    'http_req_duration{endpoint:payment}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const jwt = getToken();        // module-cached after the first call
  runChain(jwt, 'BL');
  sleep(0.1);                    // tiny pacing; keeps a single VU under
                                 // the 5/min/VIN rate-limit by construction
                                 // (VINs are unique anyway).
}

export function handleSummary(data) {
  return {
    'baseline-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// Minimal text summary so CI logs stay readable. k6's built-in
// `textSummary` lives in jslib.k6.io — we inline a stub to avoid the
// extra fetch in air-gapped CI.
function textSummary(data) {
  const m = data.metrics;
  const fmt = (n) => (n == null ? 'n/a' : Number(n).toFixed(2));
  const lines = [
    '',
    '=== baseline summary ===',
    `total HTTP requests:        ${m.http_reqs ? m.http_reqs.values.count : 0}`,
    `failures (all):             ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) + '%' : 'n/a'}`,
    `http_req_duration p50:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(50)'])} ms`,
    `http_req_duration p95:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(95)'])} ms`,
    `http_req_duration p99:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(99)'])} ms`,
    '',
  ];
  return lines.join('\n');
}
