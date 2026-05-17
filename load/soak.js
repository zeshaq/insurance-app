// Phase 3 / Issue #17 — soak test.
//
// INTENT: 10 VUs (10% of peak) sustained for 24h. Watches for:
//   - JVM memory leaks (heap growth across hours)
//   - Connection-pool exhaustion (Liberty's JDBC datasource, HTTP keep-alive)
//   - Liberty managed-executor thread starvation
//   - Kafka consumer lag on the audit-events topic
//   - Slow-leak metric trends (latency creep, GC pause growth)
//
// The full 24h soak is a MANUAL run from the VM:
//   DURATION=24h k6 run load/soak.js
//
// CI does not run the 24h soak — GHA's max job time is 6h, and a 24h soak
// pinning a runner is not free. The workflow runs DURATION=5m as a smoke
// to verify the scenario still composes; the real soak is operator-driven
// on the VM with `nohup` so SSH disconnects don't kill it (see runbook in
// docs/qa-roadmap.md Phase 3).
//
// Thresholds:
//   http_req_failed{status!=429}: rate < 0.01
//   http_req_duration:            p95 < 2000ms (held across the run, not
//                                  just averaged — k6's threshold checks
//                                  apply to the whole-run aggregate)
//
// Note on k6 'vus_max' / 'vus' stability: k6 does NOT expose a built-in
// threshold for VU-count drift (VUs are driven by the executor, not a
// metric you can threshold). The constant-vus executor pins it; if the
// system ever stops the runtime mid-soak, the run aborts and that itself
// is the signal.

import { sleep } from 'k6';
import { getToken } from './lib/auth.js';
import { runChain } from './lib/chain.js';

const DURATION = __ENV.DURATION || '5m';   // CI default; override for full 24h.
const VUS      = parseInt(__ENV.VUS || '10', 10);

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '30s',
    },
  },
  thresholds: {
    // See load/lib/metrics.js for why we use a custom Rate instead of
    // a tag-filtered http_req_failed sub-metric.
    'errors_non_429':    ['rate<0.01'],
    'http_req_duration': ['p(95)<2000'],
    'http_req_duration{endpoint:quote}':   ['p(95)<2000'],
    'http_req_duration{endpoint:policy}':  ['p(95)<2000'],
    'http_req_duration{endpoint:payment}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const jwt = getToken();
  runChain(jwt, 'SK');
  // Pace VUs: each VU does ~1 chain/sec. At 10 VUs that's ~10 chains/sec =
  // ~864,000 chains over 24h. Cuts the data volume per VIN to well under
  // the rate-limit ceiling and gives Liberty time to recycle threads
  // between iterations.
  sleep(1);
}

export function handleSummary(data) {
  return {
    'soak-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const fmt = (n) => (n == null ? 'n/a' : Number(n).toFixed(2));
  return [
    '',
    '=== soak summary ===',
    `duration:                   ${DURATION}`,
    `total HTTP requests:        ${m.http_reqs ? m.http_reqs.values.count : 0}`,
    `failures (all):             ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) + '%' : 'n/a'}`,
    `http_req_duration p50:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(50)'])} ms`,
    `http_req_duration p95:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(95)'])} ms`,
    `http_req_duration p99:      ${fmt(m.http_req_duration && m.http_req_duration.values['p(99)'])} ms`,
    '',
  ].join('\n');
}
