// Phase 3 — shared k6 auth helper.
//
// Mints a JWT from Liberty's DevTokenResource (GET /api/auth/token) and
// caches it per-VU on the first iteration. Reused by load/baseline.js,
// load/soak.js, load/spike.js so each scenario does not duplicate the
// token-mint logic.
//
// DevTokenResource returns {"jwt":"<token>","expiresIn":<seconds>}. The
// field is `jwt`, NOT `accessToken` — easy gotcha if you copy from
// other OIDC code.
//
// We cache by VU because k6 init context (top-of-file code) runs once
// per process at startup, but BASE_URL only resolves at runtime. The
// safe place to mint is the per-VU `setup`-like pattern: call
// `getToken()` from the default export and let the module-level cache
// hold it for the rest of that VU's iterations.
//
// One subtle thing: each VU mints its own token. We could share a
// single token across VUs via k6's `setup()` -> `data` mechanism, but
// for a teaching artifact the per-VU mint is honest (real clients each
// have their own credential), and the mint endpoint itself caches
// upstream so we are not hammering WSO2 IS.

import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'https://app.insurance-app.comptech-lab.com';

let cachedJwt = null;

export function getToken() {
  if (cachedJwt) return cachedJwt;

  const res = http.get(`${BASE_URL}/api/auth/token`, {
    tags: { name: 'GET /api/auth/token' },
  });

  const ok = check(res, {
    'auth: status is 200': (r) => r.status === 200,
    'auth: body has jwt': (r) => {
      try { return !!r.json('jwt'); } catch (_) { return false; }
    },
  });
  if (!ok) {
    fail(`auth/token failed: status=${res.status} body=${res.body && res.body.substring(0, 200)}`);
  }

  cachedJwt = res.json('jwt');
  return cachedJwt;
}

export function authHeaders(jwt) {
  return {
    'Authorization': `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  };
}

export { BASE_URL };
