/**
 * Unit tests for the agent-app Express BFF (server/index.ts).
 *
 * Test strategy
 * -------------
 * server/index.ts is structured as one self-bootstrapping file: it
 * reads env vars at top level, connects to Redis with a top-level
 * `await`, and binds an HTTP port via `app.listen()`. Its helpers
 * (`svcToken`, `requireUser`, the `/api/*` proxy handler) are not
 * exported. We work around that without modifying the source by:
 *
 *   1. Setting every required env var in `beforeAll` *before* the
 *      module is imported.
 *   2. Mocking `redis` and `connect-redis` so `createClient(...).connect()`
 *      and the session store are inert.
 *   3. Mocking `openid-client` so the OIDC issuer discovery never runs.
 *   4. Wrapping `express` itself in `vi.mock`: the wrapper still returns
 *      a real Express app, but we capture the instance into a module
 *      variable and override `app.listen()` so no port is bound.
 *   5. Spying on `global.fetch` so the only outbound call the BFF would
 *      make (the WSO2 IS token mint) is intercepted.
 *
 * Once the module finishes its top-level execution, `capturedApp` is
 * the real, fully-wired Express app. We exercise it by dispatching
 * synthetic requests through `app.handle(req, res)` against tiny
 * `IncomingMessage` / `ServerResponse` stubs — no real socket, no
 * port, no supertest dependency.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { EventEmitter } from 'node:events';
import type { Express, Request, Response, NextFunction } from 'express';

// ---- Test env (set before any import that reads it) ------------------------
const ENV = {
  AUTH_SECRET: 'test-session-secret',
  AGENT_OIDC_CLIENT_ID: 'agent-client',
  AGENT_OIDC_CLIENT_SECRET: 'agent-client-secret',
  WSO2IS_CLIENT_ID: 'svc-client',
  WSO2IS_CLIENT_SECRET: 'svc-secret',
  WSO2IS_TOKEN_URL_INTERNAL: 'http://wso2is.test/oauth2/token',
  LIBERTY_BASE: 'http://liberty.test:9080',
  PORT: '0', // never actually bound, but harmless
  ORIGIN: 'http://localhost:3001',
  REDIS_URL: 'redis://redis.test:6379',
};
for (const [k, v] of Object.entries(ENV)) process.env[k] = v;

// ---- Module-level captures (populated by mocks during import) --------------
let capturedApp: Express | null = null;

// ---- vi.mock declarations (hoisted to the top of the file by vitest) -------
vi.mock('redis', () => ({
  createClient: () => {
    const ee = new EventEmitter();
    return Object.assign(ee, {
      connect: vi.fn().mockResolvedValue(undefined),
      disconnect: vi.fn().mockResolvedValue(undefined),
    });
  },
}));

vi.mock('connect-redis', async () => {
  // The class must behave enough like a session store for express-session
  // to accept it: it needs `.on(...)` (EventEmitter shape) plus the
  // standard CRUD callbacks. We extend EventEmitter so `store.on('error',
  // ...)` registration inside express-session works.
  const { EventEmitter: EE } = await import('node:events');
  class RedisStore extends EE {
    constructor(_opts: unknown) { super(); }
    get(_sid: string, cb: (err: unknown, sess: unknown) => void) { cb(null, null); }
    set(_sid: string, _sess: unknown, cb: (err?: unknown) => void) { cb(); }
    destroy(_sid: string, cb: (err?: unknown) => void) { cb(); }
    touch(_sid: string, _sess: unknown, cb: (err?: unknown) => void) { cb(); }
  }
  return { RedisStore };
});

// We replace express-session with a passthrough so we can control
// `req.session` directly from each test. Without this, every request
// would get a fresh empty session and our injected `.session.user`
// would be clobbered. The trade-off: this test file does NOT exercise
// the real session store wiring (cookie sign/verify, Redis CRUD); that
// belongs in an integration test against a real Redis.
vi.mock('express-session', () => {
  const middleware = (_req: unknown, _res: unknown, next: () => void) => next();
  // express-session is a function that returns a middleware, and also
  // exposes some properties (Store, MemoryStore). The BFF only uses the
  // function form, so a plain factory suffices.
  return { default: () => middleware };
});

vi.mock('openid-client', () => ({
  Issuer: {
    discover: vi.fn().mockResolvedValue({
      Client: class {
        constructor(_opts: unknown) {}
        authorizationUrl(_opts: unknown) { return 'http://issuer.test/authorize?stub'; }
        callbackParams(_req: unknown) { return {}; }
        async callback() { return { claims: () => ({ sub: 'u1', email: 'u1@x' }) }; }
      },
    }),
  },
  generators: {
    codeVerifier: () => 'cv',
    codeChallenge: (_v: string) => 'cc',
    state: () => 'st',
  },
}));

vi.mock('express', async () => {
  const actual = await vi.importActual<typeof import('express')>('express');
  const realExpress = actual.default;
  const wrapped = function expressWrapper(...args: unknown[]) {
    // Cast through unknown so TS does not balk at the dynamic signature.
    const app = (realExpress as unknown as (...a: unknown[]) => Express)(...args);
    capturedApp = app;
    // Prevent the real app.listen() from binding to a port during tests.
    app.listen = ((..._a: unknown[]) => {
      const ee = new EventEmitter() as EventEmitter & { close: (cb?: () => void) => void };
      ee.close = (cb?: () => void) => cb?.();
      return ee;
    }) as unknown as Express['listen'];
    return app;
  };
  // Copy static helpers (express.json, express.static, etc.) onto the wrapper.
  Object.assign(wrapped, realExpress);
  return { ...actual, default: wrapped };
});

// ---- Synthetic req/res helpers --------------------------------------------
/**
 * Build a minimal IncomingMessage-shaped object that Express will accept.
 * Express only touches a handful of properties on the raw request, so we
 * extend a real EventEmitter (Node socket-stream shape) and tack on the
 * fields the framework needs.
 */
function makeReq(method: string, url: string, opts: {
  headers?: Record<string, string>;
  body?: string;
  session?: Record<string, unknown>;
} = {}): EventEmitter & Record<string, unknown> {
  const req = new EventEmitter() as EventEmitter & Record<string, unknown>;
  req.method = method;
  req.url = url;
  req.headers = { host: 'localhost:3001', ...(opts.headers ?? {}) };
  req.httpVersion = '1.1';
  req.socket = { remoteAddress: '127.0.0.1' } as unknown;
  req.connection = req.socket;
  // Stream-ish methods Express may inspect.
  (req as unknown as { resume: () => void }).resume = () => {};
  (req as unknown as { setEncoding: (e: string) => void }).setEncoding = () => {};
  (req as unknown as { unpipe: () => void }).unpipe = () => {};
  (req as unknown as { readable: boolean }).readable = true;
  // Pre-attach a `session` so the BFF's `req.session.user` access never
  // hits an undefined parent. Tests that need an authenticated user pass
  // `opts.session`; tests covering the unauthenticated branch leave it
  // empty (default `{}`), which still satisfies `requireUser`'s shape
  // contract — `req.session.user` is undefined → 401.
  (req as Record<string, unknown>).session = opts.session ?? {};
  // If a body is provided, defer firing 'data'/'end' until the next tick so
  // the handler has time to attach listeners.
  if (opts.body !== undefined) {
    setImmediate(() => {
      req.emit('data', Buffer.from(opts.body!));
      req.emit('end');
    });
  } else {
    setImmediate(() => req.emit('end'));
  }
  return req;
}

/**
 * Build a writable response stub that mirrors enough of http.ServerResponse
 * for Express + json/send to work. Resolves a promise when `end()` runs.
 */
interface CapturedRes {
  statusCode: number;
  headers: Record<string, string>;
  body: Buffer;
  done: Promise<void>;
}
function makeRes(): { res: unknown; captured: CapturedRes } {
  const chunks: Buffer[] = [];
  let resolve: () => void;
  const captured: CapturedRes = {
    statusCode: 200,
    headers: {},
    body: Buffer.alloc(0),
    done: new Promise<void>((r) => { resolve = r; }),
  };
  const res = new EventEmitter() as EventEmitter & Record<string, unknown>;
  res.statusCode = 200;
  res.setHeader = (k: string, v: string | number | string[]) => {
    captured.headers[k.toLowerCase()] = String(v);
  };
  res.getHeader = (k: string) => captured.headers[k.toLowerCase()];
  res.removeHeader = (k: string) => { delete captured.headers[k.toLowerCase()]; };
  res.hasHeader = (k: string) => k.toLowerCase() in captured.headers;
  res.getHeaderNames = () => Object.keys(captured.headers);
  res.getHeaders = () => ({ ...captured.headers });
  res.writeHead = (code: number, headers?: Record<string, string>) => {
    res.statusCode = code;
    captured.statusCode = code;
    if (headers) for (const [k, v] of Object.entries(headers)) (res.setHeader as Function)(k, v);
    return res;
  };
  res.write = (chunk: string | Buffer) => {
    chunks.push(typeof chunk === 'string' ? Buffer.from(chunk) : chunk);
    return true;
  };
  res.end = (chunk?: string | Buffer) => {
    if (chunk !== undefined) (res.write as Function)(chunk);
    captured.statusCode = res.statusCode as number;
    captured.body = Buffer.concat(chunks);
    resolve();
    res.emit('finish');
    return res;
  };
  res.on = EventEmitter.prototype.on.bind(res);
  res.once = EventEmitter.prototype.once.bind(res);
  res.emit = EventEmitter.prototype.emit.bind(res);
  res.removeListener = EventEmitter.prototype.removeListener.bind(res);
  // Boolean flags Express probes
  (res as Record<string, unknown>).headersSent = false;
  (res as Record<string, unknown>).finished = false;
  return { res, captured };
}

beforeAll(async () => {
  // Import the BFF *once*. Its top-level code wires `app.use(...)` etc.
  // We capture the resulting Express instance via the `express` mock above.
  await import('./index');
  if (!capturedApp) throw new Error('Expected express() to have been called by server/index.ts');
});

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ===========================================================================
// requireUser middleware
// ===========================================================================
//
// We can't import `requireUser` directly (server/index.ts doesn't export it).
// What we *can* do is exercise the same behavior by routing a synthetic
// request through the captured Express app: `/api/*` is gated by
// requireUser, so a request with no session must come back 401 and a
// request with a session must reach the proxy (which we intercept via
// the fetch spy).

describe('requireUser middleware (via /api/*)', () => {
  it('returns 401 with {error:"not signed in"} when no session.user is set', async () => {
    const { res, captured } = makeRes();
    const req = makeReq('GET', '/api/health'); // no session attached
    capturedApp!.handle(req as unknown as Request, res as unknown as Response, () => {});
    await captured.done;
    expect(captured.statusCode).toBe(401);
    expect(JSON.parse(captured.body.toString())).toEqual({ error: 'not signed in' });
  });

  it('calls next() and proxies upstream when session.user is set', async () => {
    vi.mocked(globalThis.fetch)
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: 't', expires_in: 3600 }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response('{"ok":true}', {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }));

    const { res, captured } = makeRes();
    // express-session is mocked to a passthrough (see vi.mock above), so
    // a session pre-attached on the synthetic req survives to the
    // requireUser middleware.
    const req = makeReq('GET', '/api/health', {
      session: { user: { id: 'agent-7', email: 'agent7@x' } },
    });
    capturedApp!.handle(req as unknown as Request, res as unknown as Response, () => {});
    await captured.done;
    expect(captured.statusCode).toBe(200);
    expect(captured.body.toString()).toContain('"ok":true');
  });
});

// ===========================================================================
// svcToken cache (exercised via the /api proxy)
// ===========================================================================
describe('svcToken cache', () => {
  // The svc-token cache lives in module scope inside server/index.ts and
  // we cannot reset it from a test (the module is imported once for the
  // whole file). So we drive both tests' assertions off the *delta* in
  // mint calls between the two /api requests, not the absolute count.
  // A working cache means: 0 OR 1 mints during the first request, then
  // exactly 0 mints during the second.
  it('does NOT re-mint the WSO2 IS service token between two close-together /api calls', async () => {
    // Use a URL-aware impl so we tolerate either cache state (hot or cold)
    // entering this test.
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === ENV.WSO2IS_TOKEN_URL_INTERNAL) {
        return new Response(JSON.stringify({ access_token: 'svc-jwt', expires_in: 3600 }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200 });
    });

    const sess = { user: { id: 'agent-7', email: 'agent7@x' } };

    // First /api call (may or may not mint, depending on prior cache state)
    {
      const { res, captured } = makeRes();
      const req = makeReq('GET', '/api/policies', { session: sess });
      capturedApp!.handle(req as unknown as Request, res as unknown as Response, () => {});
      await captured.done;
    }
    const mintsAfterFirst = fetchSpy.mock.calls.filter(
      ([url]) => String(url) === ENV.WSO2IS_TOKEN_URL_INTERNAL,
    ).length;

    // Second /api call — the cache MUST suppress any further mint.
    {
      const { res, captured } = makeRes();
      const req = makeReq('GET', '/api/claims', { session: sess });
      capturedApp!.handle(req as unknown as Request, res as unknown as Response, () => {});
      await captured.done;
    }
    const mintsAfterSecond = fetchSpy.mock.calls.filter(
      ([url]) => String(url) === ENV.WSO2IS_TOKEN_URL_INTERNAL,
    ).length;

    // The second /api call adds zero mints.
    expect(mintsAfterSecond - mintsAfterFirst).toBe(0);
    // Either 0 mints (cache was already hot from a prior test) or 1 mint
    // (cold cache, this test minted it).
    expect(mintsAfterFirst).toBeLessThanOrEqual(1);
  });
});

// ===========================================================================
// /api/* proxy upstream URL + header construction
// ===========================================================================
describe('/api/* proxy', () => {
  it('constructs upstream URL as `${LIBERTY_BASE}/api${req.url}` and forwards X-User-* headers', async () => {
    // URL-aware mock so we are robust to cache state (the file shares one
    // imported module).
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === ENV.WSO2IS_TOKEN_URL_INTERNAL) {
        return new Response(JSON.stringify({ access_token: 'svc-jwt-2', expires_in: 3600 }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('upstream-body', {
        status: 201, headers: { 'X-Upstream-Marker': 'yes' },
      });
    });

    const { res, captured } = makeRes();
    const req = makeReq('GET', '/api/policies/POL-1', {
      session: { user: { id: 'agent-7', email: 'agent7@x' } },
    });
    capturedApp!.handle(req as unknown as Request, res as unknown as Response, () => {});
    await captured.done;

    // Find the Liberty call (the one whose URL starts with LIBERTY_BASE).
    const libertyCall = fetchSpy.mock.calls.find(
      ([url]) => String(url).startsWith(ENV.LIBERTY_BASE),
    ) as [string, RequestInit] | undefined;
    expect(libertyCall, 'expected a fetch call to the Liberty backend').toBeDefined();
    expect(libertyCall![0]).toBe(`${ENV.LIBERTY_BASE}/api/policies/POL-1`);
    const headers = libertyCall![1].headers as Record<string, string>;
    expect(headers.Authorization).toMatch(/^Bearer /);
    expect(headers['X-User-Id']).toBe('agent-7');
    expect(headers['X-User-Email']).toBe('agent7@x');

    // Upstream status + body are passed through.
    expect(captured.statusCode).toBe(201);
    expect(captured.body.toString()).toBe('upstream-body');
    expect(captured.headers['x-upstream-marker']).toBe('yes');
  });

  it('omits X-User-Email when the session user has no email', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url === ENV.WSO2IS_TOKEN_URL_INTERNAL) {
        return new Response(JSON.stringify({ access_token: 'svc-jwt-3', expires_in: 3600 }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200 });
    });

    const { res, captured } = makeRes();
    const req = makeReq('GET', '/api/me', {
      session: { user: { id: 'agent-7' } }, // no email
    });
    capturedApp!.handle(req as unknown as Request, res as unknown as Response, () => {});
    await captured.done;

    const libertyCall = fetchSpy.mock.calls.find(
      ([url]) => String(url).startsWith(ENV.LIBERTY_BASE),
    ) as [string, RequestInit] | undefined;
    expect(libertyCall).toBeDefined();
    const headers = libertyCall![1].headers as Record<string, string>;
    expect(headers['X-User-Id']).toBe('agent-7');
    expect(headers['X-User-Email']).toBeUndefined();
  });
});

// Reference the unused Express type so eslint/tsc don't complain — this
// also documents that NextFunction is part of the contract surface we
// would be testing if `requireUser` were exported directly.
type _UnusedContract = (req: Request, res: Response, next: NextFunction) => void;
