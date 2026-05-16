// insurance-app teaching GUI — shared client helpers.
//
// One JWT for the page session. Fetched server-side from /api/auth/token
// on page load via client_credentials, refreshed when expired. Students
// open DevTools and see (1) the bootstrap token call, (2) Bearer headers
// on every subsequent fetch. Same auth shape real apps use, no login screen.

const App = (() => {
  let jwt = null;
  let jwtExpiresAt = 0;

  /** Page-load bootstrap: mint a JWT and stash it. */
  async function ensureToken() {
    const now = Date.now() / 1000;
    if (jwt && now < jwtExpiresAt - 60) return jwt;
    const r = await fetch('/api/auth/token');
    if (!r.ok) throw new Error('Could not mint dev token: ' + r.status);
    const body = await r.json();
    jwt = body.jwt;
    jwtExpiresAt = now + (body.expiresIn || 3600);
    return jwt;
  }

  /** Fetch wrapper that automatically adds the Bearer header. */
  async function api(method, path, { body, headers, raw } = {}) {
    const token = await ensureToken();
    const opts = {
      method,
      headers: {
        Authorization: 'Bearer ' + token,
        ...(headers || {}),
      },
    };
    if (body !== undefined) {
      if (body instanceof FormData) {
        opts.body = body;                 // browser sets multipart boundary
      } else {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = typeof body === 'string' ? body : JSON.stringify(body);
      }
    }
    const r = await fetch(path, opts);
    const text = await r.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* keep null */ }
    return { ok: r.ok, status: r.status, body: json, raw: text };
  }

  /** Toast (1.8s auto-dismiss). */
  function toast(msg, kind = 'info') {
    const t = document.createElement('div');
    t.className = 'toast' + (kind === 'error' ? ' error' : '');
    t.textContent = msg;
    document.body.appendChild(t);
    requestAnimationFrame(() => t.classList.add('show'));
    setTimeout(() => {
      t.classList.remove('show');
      setTimeout(() => t.remove(), 250);
    }, 1800);
  }

  /** Pretty-print a JSON response into a <pre>, with status badge. */
  function showResponse(target, result) {
    const el = typeof target === 'string' ? document.querySelector(target) : target;
    el.classList.remove('error');
    if (!result.ok) el.classList.add('error');
    const head = `HTTP ${result.status}\n`;
    el.textContent = head + (result.body !== null
        ? JSON.stringify(result.body, null, 2)
        : result.raw || '(no body)');
  }

  /** Generate a fresh idempotency key for payment-form demos. */
  function idemKey(prefix = 'idem') {
    return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
  }

  return { ensureToken, api, toast, showResponse, idemKey };
})();

// Eagerly mint the token on every page load so the first user action is fast.
window.addEventListener('DOMContentLoaded', () => {
  App.ensureToken().catch(e => App.toast(e.message, 'error'));
});
