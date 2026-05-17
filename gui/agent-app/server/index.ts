/**
 * Express BFF for the agent dashboard.
 *
 * Same architecture as the customer-app's SvelteKit BFF: the browser
 * has only an HttpOnly session cookie; the access token from WSO2 IS
 * stays server-side, and Liberty calls use the service-account
 * client_credentials JWT.
 *
 * Responsibilities:
 *   - OIDC authorization-code + PKCE handshake with WSO2 IS
 *   - Session cookie (HttpOnly, Secure, SameSite=Lax)
 *   - GET /auth/session, POST /auth/signin, POST /auth/signout
 *   - /api/* proxy to Liberty with a cached service-account JWT
 *   - Static serve of dist/ui with SPA fallback to index.html
 *
 * Library notes:
 *   - openid-client v6: top-level `discovery()` + `authorizationCodeGrant()`
 *     replace the v5 `Issuer`/`Client` class pair. PKCE + state generators
 *     moved to `randomPKCECodeVerifier`, `calculatePKCECodeChallenge`,
 *     `randomState`.
 *   - connect-redis v9: same `new RedisStore({ client, prefix })` shape as
 *     v8, but the peer pin moved to `redis >= 5` (matches what we already
 *     have here).
 */
import express, { type Request, type Response, type NextFunction } from 'express';
import cookieParser from 'cookie-parser';
import session from 'express-session';
import { RedisStore } from 'connect-redis';
import { createClient } from 'redis';
import * as oidc from 'openid-client';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = parseInt(process.env.PORT ?? '3001', 10);
const ORIGIN = process.env.ORIGIN ?? `http://localhost:${PORT}`;
const SESSION_SECRET = required('AUTH_SECRET');
const OIDC_CLIENT_ID = required('AGENT_OIDC_CLIENT_ID');
const OIDC_CLIENT_SECRET = required('AGENT_OIDC_CLIENT_SECRET');
const OIDC_ISSUER = process.env.AGENT_OIDC_ISSUER ?? 'https://is.insurance-app.comptech-lab.com/oauth2/token';
const REDIRECT_URI = process.env.AGENT_OIDC_REDIRECT_URI ?? `${ORIGIN}/auth/callback/wso2is`;

const WSO2IS_TOKEN_URL = process.env.WSO2IS_TOKEN_URL_INTERNAL ?? 'http://wso2is:9763/oauth2/token';
const WSO2IS_CLIENT_ID = required('WSO2IS_CLIENT_ID');
const WSO2IS_CLIENT_SECRET = required('WSO2IS_CLIENT_SECRET');
const LIBERTY_BASE = process.env.LIBERTY_BASE ?? 'http://insurance-app:9080';
const REDIS_URL    = process.env.REDIS_URL    ?? 'redis://redis:6379';

function required(k: string): string {
  const v = process.env[k];
  if (!v) throw new Error(`${k} env var is required`);
  return v;
}

// ---------- OIDC configuration (lazy-init, cached) ----------
// openid-client v6 replaces the v5 `new Issuer.Client(...)` flow with a
// top-level `discovery()` call that returns a `Configuration` value. The
// Configuration is then passed into the per-request helpers
// (`buildAuthorizationUrl`, `authorizationCodeGrant`, ...).
let _config: oidc.Configuration | null = null;
async function oidcConfig(): Promise<oidc.Configuration> {
  if (_config) return _config;
  _config = await oidc.discovery(
    new URL(OIDC_ISSUER),
    OIDC_CLIENT_ID,
    { client_secret: OIDC_CLIENT_SECRET },
    oidc.ClientSecretBasic(OIDC_CLIENT_SECRET),
  );
  return _config;
}

// ---------- service-account JWT cache (for Liberty calls) ----------
let cachedJwt: string | null = null;
let cachedExpiry = 0;
const JWT_REFRESH_BEFORE_MS = 5 * 60 * 1000;

async function svcToken(): Promise<string> {
  if (cachedJwt && Date.now() < cachedExpiry - JWT_REFRESH_BEFORE_MS) return cachedJwt;
  const basic = Buffer.from(`${WSO2IS_CLIENT_ID}:${WSO2IS_CLIENT_SECRET}`).toString('base64');
  const res = await fetch(WSO2IS_TOKEN_URL, {
    method: 'POST',
    headers: { Authorization: `Basic ${basic}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=client_credentials',
  });
  if (!res.ok) throw new Error(`svc token mint failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { access_token: string; expires_in: number };
  cachedJwt = body.access_token;
  cachedExpiry = Date.now() + body.expires_in * 1000;
  return cachedJwt;
}

// ---------- Redis-backed session store ----------
// connect-redis v9 keeps the v8 constructor shape: `new RedisStore({
// client, prefix })`. We connect() eagerly at process start so a Redis
// outage surfaces in the logs immediately rather than on the first
// signin attempt.
const redisClient = createClient({ url: REDIS_URL });
redisClient.on('error', (err) => console.error('redis error', err));
await redisClient.connect();
const redisStore = new RedisStore({ client: redisClient, prefix: 'agent:sess:' });

// ---------- Express setup ----------
const app = express();
app.set('trust proxy', 1); // honor X-Forwarded-Proto from HAProxy
app.use(cookieParser());
app.use(session({
  store: redisStore,
  name: 'agent_sid',
  secret: SESSION_SECRET,
  resave: false,
  saveUninitialized: false,
  cookie: {
    httpOnly: true,
    sameSite: 'lax',
    secure: ORIGIN.startsWith('https://'),
    maxAge: 8 * 60 * 60 * 1000,  // 8h
  },
}));

declare module 'express-session' {
  interface SessionData {
    user?: { id: string; name?: string; email?: string };
    codeVerifier?: string;
    state?: string;
  }
}

// ---------- /auth routes ----------
app.get('/auth/session', (req, res) => {
  res.json({ user: req.session.user });
});

app.post('/auth/signin', async (_req, res, next) => {
  try {
    const cfg = await oidcConfig();
    const code_verifier = oidc.randomPKCECodeVerifier();
    const code_challenge = await oidc.calculatePKCECodeChallenge(code_verifier);
    const state = oidc.randomState();
    (_req.session as any).codeVerifier = code_verifier;
    (_req.session as any).state = state;
    const url = oidc.buildAuthorizationUrl(cfg, {
      redirect_uri: REDIRECT_URI,
      scope: 'openid profile email',
      code_challenge,
      code_challenge_method: 'S256',
      state,
    });
    res.redirect(url.href);
  } catch (e) { next(e); }
});

app.get('/auth/callback/wso2is', async (req, res, next) => {
  try {
    const cfg = await oidcConfig();
    // openid-client v6 wants the full current request URL (including the
    // query string carrying `code` + `state`) so it can validate the
    // response itself. We reconstruct it from ORIGIN + req.originalUrl
    // because the BFF sits behind HAProxy and Express's req.protocol
    // alone is not always trustworthy.
    const currentUrl = new URL(req.originalUrl, ORIGIN);
    const tokens = await oidc.authorizationCodeGrant(cfg, currentUrl, {
      pkceCodeVerifier: (req.session as any).codeVerifier,
      expectedState: (req.session as any).state,
    });
    // v6 types `claims()` as `IDToken | undefined`. IDToken is a sealed
    // shape that does NOT index by arbitrary string, so we widen to a
    // record before plucking custom claims (`name`, `given_name`,
    // `email`) which the spec marks as optional and WSO2 IS may or may
    // not return depending on the configured scopes.
    const claims = (tokens.claims() ?? {}) as Record<string, unknown>;
    req.session.user = {
      id: String(claims.sub ?? ""),
      name: (claims.name as string | undefined) ?? (claims.given_name as string | undefined),
      email: claims.email as string | undefined,
    };
    delete (req.session as any).codeVerifier;
    delete (req.session as any).state;
    res.redirect('/');
  } catch (e) { next(e); }
});

app.post('/auth/signout', (req, res) => {
  req.session.destroy(() => res.redirect('/login'));
});

// ---------- /api/* proxy to Liberty ----------
function requireUser(req: Request, res: Response, next: NextFunction) {
  if (!req.session.user) return res.status(401).json({ error: 'not signed in' });
  next();
}

app.use('/api', requireUser, express.json(), async (req, res) => {
  try {
    const token = await svcToken();
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
      'X-User-Id': req.session.user!.id,
    };
    if (req.session.user!.email) headers['X-User-Email'] = req.session.user!.email;
    if (req.is('application/json') && req.body && Object.keys(req.body).length > 0) {
      headers['Content-Type'] = 'application/json';
    }
    const upstreamUrl = `${LIBERTY_BASE}/api${req.url}`;
    const upstream = await fetch(upstreamUrl, {
      method: req.method,
      headers,
      body: ['GET', 'HEAD'].includes(req.method) ? undefined : JSON.stringify(req.body ?? {}),
    });
    res.status(upstream.status);
    upstream.headers.forEach((v, k) => {
      if (k.toLowerCase() !== 'transfer-encoding') res.setHeader(k, v);
    });
    const buf = Buffer.from(await upstream.arrayBuffer());
    res.send(buf);
  } catch (e) {
    res.status(502).json({ error: (e as Error).message });
  }
});

// ---------- Static + SPA fallback ----------
const uiDir = path.resolve(__dirname, '../ui');
if (fs.existsSync(uiDir)) {
  app.use(express.static(uiDir));
  app.get('*', (_req, res) => res.sendFile(path.join(uiDir, 'index.html')));
} else {
  app.get('*', (_req, res) => res.status(503).send('UI build missing — run `npm run build:ui` first'));
}

app.listen(PORT, () => {
  console.log(`agent-app BFF listening on :${PORT} (origin ${ORIGIN})`);
});
