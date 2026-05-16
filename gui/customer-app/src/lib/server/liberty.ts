/**
 * BFF helper: server-side fetch wrapper that talks to Liberty using
 * the existing client_credentials JWT (slice 15's DevTokenResource).
 *
 * Used by SvelteKit server-side load() functions and form actions in
 * upcoming slices. NEVER imported into client code — the service-account
 * secret is process.env and must not leak to the browser.
 *
 * Strategy: cache the JWT in-process for ~55 min, refresh on expiry.
 * One round trip to WSO2 IS per hour at idle, more if traffic exceeds
 * the cache's freshness window.
 */
import { env } from '$env/dynamic/private';

const WSO2IS_TOKEN_URL =
  env.WSO2IS_TOKEN_URL_INTERNAL || 'http://wso2is:9763/oauth2/token';
const LIBERTY_BASE =
  env.LIBERTY_BASE || 'http://insurance-app:9080';
const REFRESH_BEFORE_EXPIRY_MS = 5 * 60 * 1000;

let cachedJwt: string | null = null;
let cachedExpiry = 0;

async function ensureToken(): Promise<string> {
  if (cachedJwt && Date.now() < cachedExpiry - REFRESH_BEFORE_EXPIRY_MS) return cachedJwt;

  const id = env.WSO2IS_CLIENT_ID;
  const secret = env.WSO2IS_CLIENT_SECRET;
  if (!id || !secret) {
    throw new Error('WSO2IS_CLIENT_ID / WSO2IS_CLIENT_SECRET must be set on the customer-app container');
  }
  const basic = Buffer.from(`${id}:${secret}`).toString('base64');
  const res = await fetch(WSO2IS_TOKEN_URL, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${basic}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  if (!res.ok) throw new Error(`WSO2 IS token mint failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { access_token: string; expires_in: number };
  cachedJwt = body.access_token;
  cachedExpiry = Date.now() + body.expires_in * 1000;
  return cachedJwt;
}

export interface LibertyOpts {
  userId?: string;
  userEmail?: string;
}

/** GET, POST, etc. against Liberty, with the service JWT attached. */
export async function liberty(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  init: RequestInit & LibertyOpts = {}
): Promise<Response> {
  const token = await ensureToken();
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${token}`);
  if (init.userId)    headers.set('X-User-Id',    init.userId);
  if (init.userEmail) headers.set('X-User-Email', init.userEmail);
  // Auto-JSON: if body is a plain object, stringify + set Content-Type.
  // Caller can still pass body as a string/FormData/Buffer for non-JSON.
  let body = init.body as BodyInit | object | undefined;
  if (
    body &&
    typeof body === 'object' &&
    !(body instanceof FormData) &&
    !(body instanceof URLSearchParams) &&
    !(body instanceof Blob) &&
    !(body instanceof ArrayBuffer) &&
    !(body instanceof ReadableStream)
  ) {
    body = JSON.stringify(body);
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  }
  return fetch(`${LIBERTY_BASE}${path}`, { ...init, method, headers, body: body as BodyInit });
}

/** Convenience: parse JSON and throw on non-2xx. */
export async function libertyJson<T = unknown>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  init: RequestInit & LibertyOpts = {}
): Promise<T> {
  const r = await liberty(method, path, init);
  if (!r.ok) {
    const body = await r.text();
    throw new Error(`Liberty ${method} ${path} → ${r.status}: ${body}`);
  }
  return (await r.json()) as T;
}
