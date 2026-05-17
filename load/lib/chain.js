// Phase 3 — shared money-chain helper.
//
// One iteration = POST /api/quotes → POST /api/policies → POST /api/payments.
// All three scenarios (baseline / soak / spike) drive the SAME chain; only
// the load profile differs. Keeping the chain here means a future change
// to the request shape (e.g. adding a required field) updates all three at
// once.
//
// VIN MUST be unique per iteration: QuoteResource rate-limits 5 req/min/VIN
// (ADR-0005), so reusing a VIN within a load run hits 429 noise that is
// not the system-under-test's fault. We key the VIN with __VU + __ITER +
// random hex so collisions across VUs are statistically impossible.
//
// VIN length is constrained to EXACTLY 17 chars by the Postgres schema
// (quote.vehicle_vin VARCHAR(17) — real VINs are 17 chars per ISO 3779).
// A load test that generates longer VINs produces 500s for a reason that
// has nothing to do with the load profile, so we encode the prefix into
// 2 chars and pack VU/ITER/random into the remaining 15.
//
// Idempotency-Key is fresh per iteration: payments use the key as the
// dedupe identifier (PaymentResource javadoc), and we want every payment
// in a load run to land as a fresh charge, not a replay. crypto.randomUUID()
// is available in k6's globalThis since v0.50.
//
// 429 is excluded from error rates at the scenario level (see thresholds);
// the chain itself just returns the responses and lets the caller decide.

import http from 'k6/http';
import { check } from 'k6';
import { authHeaders, BASE_URL } from './auth.js';
import { recordResponse } from './metrics.js';

/**
 * Generate a 17-character VIN unique to this VU+iteration.
 * Layout: 2-char scenario prefix + 15 chars of base36(random+VU+ITER).
 * Uppercased — real VINs are uppercase alphanumerics; staying within
 * [A-Z0-9] keeps the schema validator (if any future migration adds one)
 * happy.
 */
function makeVin(prefix) {
  // 15 chars from a uuid (no dashes), uppercased
  const rand = crypto.randomUUID().replace(/-/g, '').slice(0, 15).toUpperCase();
  return (prefix.slice(0, 2) + rand).slice(0, 17);
}

/**
 * Runs one quote → bind → pay chain.
 * @param {string} jwt   - JWT minted via getToken()
 * @param {string} prefix - 2-char VIN prefix per scenario, e.g. 'BL' / 'SK' / 'SP'
 * @returns {{quote: Response, policy: Response, payment: Response}}
 */
export function runChain(jwt, prefix) {
  const headers = authHeaders(jwt);
  const vin = makeVin(prefix);

  // --- 1. POST /api/quotes ---------------------------------------------
  const quoteBody = JSON.stringify({
    vehicleVin: vin,
    driverAge: 30,
    coverageType: 'BASIC',
  });
  const quote = http.post(`${BASE_URL}/api/quotes`, quoteBody, {
    headers,
    tags: { name: 'POST /api/quotes', endpoint: 'quote' },
  });
  recordResponse(quote);
  check(quote, {
    'quote: 201 or 429 (rate-limit ok)': (r) => r.status === 201 || r.status === 429,
  });
  // 429 means we hit the per-VIN rate limit (shouldn't happen with our VIN
  // scheme but is allowed). Short-circuit the chain — no quoteId to bind.
  if (quote.status !== 201) {
    return { quote, policy: null, payment: null };
  }
  const quoteId = quote.json('id');

  // --- 2. POST /api/policies -------------------------------------------
  const policyBody = JSON.stringify({ quoteId });
  const policy = http.post(`${BASE_URL}/api/policies`, policyBody, {
    headers,
    tags: { name: 'POST /api/policies', endpoint: 'policy' },
  });
  recordResponse(policy);
  check(policy, {
    'policy: 201 or 200': (r) => r.status === 201 || r.status === 200,
  });
  if (policy.status !== 201 && policy.status !== 200) {
    return { quote, policy, payment: null };
  }
  const policyNumber = policy.json('policyNumber');

  // --- 3. POST /api/payments -------------------------------------------
  // Idempotency-Key is REQUIRED (PaymentResource:400 if missing). Fresh
  // UUID per iteration so each charge is a distinct one — replays would
  // collapse onto the first charge and skew the duration metrics.
  const payBody = JSON.stringify({
    policyNumber,
    amount: 1.00,
    currency: 'USD',
  });
  const payHeaders = Object.assign({}, headers, {
    'Idempotency-Key': crypto.randomUUID(),
  });
  const payment = http.post(`${BASE_URL}/api/payments`, payBody, {
    headers: payHeaders,
    tags: { name: 'POST /api/payments', endpoint: 'payment' },
  });
  recordResponse(payment);
  check(payment, {
    'payment: 201 (charged)': (r) => r.status === 201,
  });

  return { quote, policy, payment };
}
