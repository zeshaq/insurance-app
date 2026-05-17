// Phase 3 / Issue #18 — spike test.
//
// 0 → 5× peak (500 VUs) in 30s, hold 1m, ramp back down. The current
// single-VM podman setup almost certainly fails here — that is the
// point. We want to know WHERE it fails so we can write a Phase 6
// scaling story.
//
// Thresholds are intentionally tolerant:
//   http_req_failed{status!=429}: rate < 0.10   (allow up to 10% failures)
//
// We don't want this test to abort the moment errors appear — we want
// it to run to completion and capture the full failure profile.
//
// The summary highlights:
//   - 429-onset point (when does the rate limiter kick in?)
//   - 5xx breakdown (which endpoint dies first?)
// Useful for the Phase 6 SLO doc and the "marketing launch survives?"
// question raised in docs/qa-roadmap.md.
//
// Run manually:
//   k6 run load/spike.js
// or via workflow_dispatch in the k6 GH action.

import { sleep } from 'k6';
import { getToken } from './lib/auth.js';
import { runChain } from './lib/chain.js';

const SPIKE_TARGET = parseInt(__ENV.SPIKE_TARGET || '500', 10);

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: SPIKE_TARGET }, // ramp to 5× peak
        { duration: '1m',  target: SPIKE_TARGET }, // hold
        { duration: '30s', target: 0 },            // ramp down
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    // Generous failure budget — the system is expected to be under
    // distress here, we just want < 10% non-rate-limit errors. Uses
    // the `errors_non_429` custom Rate (see load/lib/metrics.js) for
    // the same reason baseline does — k6 thresholds can't express a
    // "status != 429" tag filter natively.
    'errors_non_429': ['rate<0.10'],
    // No hard latency threshold — we EXPECT degradation. Report it
    // in the summary instead so a human can read what happened.
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const jwt = getToken();
  runChain(jwt, 'SP');
  // No sleep — under spike load we want each VU pushing as hard as it
  // can. The chain itself is the natural rate-limiter.
}

export function handleSummary(data) {
  // For the spike test we want a richer summary: bucket every status
  // code we saw, and pull out 429-onset / 5xx counts per endpoint.
  return {
    'spike-summary.json': JSON.stringify(data, null, 2),
    stdout: spikeTextSummary(data),
  };
}

function spikeTextSummary(data) {
  const m = data.metrics;
  const fmt = (n) => (n == null ? 'n/a' : Number(n).toFixed(2));
  const lines = [
    '',
    '=== spike summary ===',
    `spike target VUs:           ${SPIKE_TARGET}`,
    `total HTTP requests:        ${m.http_reqs ? m.http_reqs.values.count : 0}`,
    `failures (all):             ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) + '%' : 'n/a'}`,
    `http_req_duration p50:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(50)'])} ms`,
    `http_req_duration p95:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(95)'])} ms`,
    `http_req_duration p99:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(99)'])} ms`,
    '',
    'Status-code distribution and per-endpoint 5xx counts are in the',
    'spike-summary.json artifact (data.metrics.http_reqs.tags or the',
    'JSON event stream if --out json was passed). Look there for the',
    '429-onset point and the breakdown of where 5xx first appeared.',
    '',
  ];
  return lines.join('\n');
}
