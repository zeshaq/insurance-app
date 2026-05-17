// Phase 3 — custom k6 metrics shared across scenarios.
//
// Why a custom Rate metric: the user-facing acceptance criterion is
// "non-429 failures < 1%" (baseline) and "< 10%" (spike). k6's built-in
// `http_req_failed` cannot be filtered with a not-equals tag selector —
// `http_req_failed{status:!429}` is parsed by k6 as "status equals the
// literal string '!429'", which never matches and always reports rate=0
// (silently passing the threshold). Custom Rate metrics with explicit
// per-request booleans are the supported way to express "everything
// except 429".
//
// The chain pushes one observation per HTTP response into errorsNon429
// — true for a 5xx/timeout (counts as failure), false for a 2xx OR 429
// (counts as success). The threshold then reads off this rate directly.

import { Rate } from 'k6/metrics';

// Per-response error rate, with 429s explicitly NOT counted as errors.
export const errorsNon429 = new Rate('errors_non_429');

/**
 * Record an HTTP response into the errors-excluding-429 rate.
 * Pass a k6 http.Response or any object with a numeric `status`.
 *   - status in [200..399]    : success     -> false
 *   - status == 429           : rate-limited -> false (excluded from errors)
 *   - status >= 400 (incl 5xx): error       -> true
 *   - status == 0 (timeout)   : error       -> true
 */
export function recordResponse(res) {
  const s = res && res.status;
  const isError = !(s >= 200 && s < 400) && s !== 429;
  errorsNon429.add(isError);
}
