/**
 * Unit tests for the customer-app BFF Liberty helper.
 *
 * Strategy:
 *   - Mock the SvelteKit `$env/dynamic/private` virtual module so the
 *     helper reads test values for WSO2 IS + Liberty endpoints.
 *   - Spy on global `fetch` for every test; no real network ever leaves
 *     the process.
 *   - Use `vi.resetModules()` between tests to wipe the module-scoped
 *     token cache; this makes each test deterministic regardless of run
 *     order.
 *
 * Covered branches (per Phase 1 acceptance criteria):
 *   - Token cache: first call to a JWT-needing helper mints a token via
 *     WSO2 IS; second call within the refresh window does NOT.
 *   - liberty() auto-JSON body detection:
 *       * plain object → JSON.stringify + Content-Type: application/json
 *       * FormData      → untouched
 *       * URLSearchParams → untouched
 *       * Blob          → untouched
 *   - X-User-Id / X-User-Email header propagation when `userId` /
 *     `userEmail` opts are passed.
 *   - libertyJson() happy path returns parsed JSON.
 *   - libertyJson() throws on non-2xx with the response body included
 *     in the error message.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// ---- SvelteKit env shim ----------------------------------------------------
// The helper reads from `$env/dynamic/private`. SvelteKit normally provides
// this at build time; in tests we mock it before importing the helper.
const TEST_ENV = {
  WSO2IS_TOKEN_URL_INTERNAL: 'http://wso2is.test/oauth2/token',
  LIBERTY_BASE: 'http://liberty.test:9080',
  WSO2IS_CLIENT_ID: 'test-client',
  WSO2IS_CLIENT_SECRET: 'test-secret',
};
vi.mock('$env/dynamic/private', () => ({ env: TEST_ENV }));

type LibertyModule = typeof import('./liberty');

/** Build a stub fetch response. */
function jsonResponse(body: unknown, init: ResponseInit = { status: 200 }): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) },
  });
}

/** Build the WSO2 IS token-mint response. */
function tokenResponse(access_token = 'tok-abc', expires_in = 3600): Response {
  return jsonResponse({ access_token, expires_in });
}

/** Fresh-load the liberty module after wiping its in-process token cache. */
async function loadLiberty(): Promise<LibertyModule> {
  vi.resetModules();
  return import('./liberty');
}

describe('customer-app BFF: liberty helper', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    fetchSpy = vi.spyOn(globalThis, 'fetch');
  });

  afterEach(() => {
    fetchSpy.mockRestore();
    vi.restoreAllMocks();
  });

  describe('ensureToken cache', () => {
    it('mints exactly one WSO2 IS token across two close-together liberty() calls', async () => {
      const { liberty } = await loadLiberty();
      // First fetch call is the token mint, second is the Liberty call.
      // Third fetch call should be only Liberty — the token must be cached.
      fetchSpy
        .mockResolvedValueOnce(tokenResponse('jwt-1', 3600)) // token mint
        .mockResolvedValueOnce(new Response('{}', { status: 200 })) // liberty 1
        .mockResolvedValueOnce(new Response('{}', { status: 200 })); // liberty 2

      await liberty('GET', '/api/policies');
      await liberty('GET', '/api/claims');

      expect(fetchSpy).toHaveBeenCalledTimes(3);
      const tokenCalls = fetchSpy.mock.calls.filter(
        ([url]) => String(url) === TEST_ENV.WSO2IS_TOKEN_URL_INTERNAL,
      );
      expect(tokenCalls).toHaveLength(1);
    });

    it('sends Basic auth + client_credentials grant when minting', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      await liberty('GET', '/api/health');

      const [tokenUrl, tokenInit] = fetchSpy.mock.calls[0] as [string, RequestInit];
      expect(tokenUrl).toBe(TEST_ENV.WSO2IS_TOKEN_URL_INTERNAL);
      expect(tokenInit.method).toBe('POST');
      expect(tokenInit.body).toBe('grant_type=client_credentials');
      const headers = new Headers(tokenInit.headers);
      const expectedBasic = Buffer.from(
        `${TEST_ENV.WSO2IS_CLIENT_ID}:${TEST_ENV.WSO2IS_CLIENT_SECRET}`,
      ).toString('base64');
      expect(headers.get('Authorization')).toBe(`Basic ${expectedBasic}`);
      expect(headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    });

    it('throws if the WSO2 IS token mint fails (non-2xx)', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy.mockResolvedValueOnce(
        new Response('boom', { status: 500 }),
      );
      await expect(liberty('GET', '/api/x')).rejects.toThrow(/WSO2 IS token mint failed: 500/);
    });
  });

  describe('liberty() auto-JSON body branch', () => {
    it('plain object → JSON.stringify + sets Content-Type: application/json', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      const payload = { policyId: 'POL-1', amount: 42 };
      await liberty('POST', '/api/policies', { body: payload as unknown as BodyInit });

      const [url, init] = fetchSpy.mock.calls[1] as [string, RequestInit];
      expect(url).toBe(`${TEST_ENV.LIBERTY_BASE}/api/policies`);
      expect(init.body).toBe(JSON.stringify(payload));
      const headers = new Headers(init.headers);
      expect(headers.get('Content-Type')).toBe('application/json');
      expect(headers.get('Authorization')).toMatch(/^Bearer /);
    });

    it('FormData body is passed through untouched (no JSON.stringify)', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      const fd = new FormData();
      fd.append('field', 'value');
      await liberty('POST', '/api/upload', { body: fd });

      const [, init] = fetchSpy.mock.calls[1] as [string, RequestInit];
      expect(init.body).toBe(fd); // same reference, not stringified
      const headers = new Headers(init.headers);
      // The helper must NOT have forced JSON content-type for multipart.
      expect(headers.get('Content-Type')).not.toBe('application/json');
    });

    it('URLSearchParams body is passed through untouched', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      const usp = new URLSearchParams({ a: '1', b: '2' });
      await liberty('POST', '/api/form', { body: usp });

      const [, init] = fetchSpy.mock.calls[1] as [string, RequestInit];
      expect(init.body).toBe(usp);
      const headers = new Headers(init.headers);
      expect(headers.get('Content-Type')).not.toBe('application/json');
    });

    it('Blob body is passed through untouched', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      const blob = new Blob(['hello'], { type: 'text/plain' });
      await liberty('POST', '/api/blob', { body: blob });

      const [, init] = fetchSpy.mock.calls[1] as [string, RequestInit];
      expect(init.body).toBe(blob);
      const headers = new Headers(init.headers);
      expect(headers.get('Content-Type')).not.toBe('application/json');
    });
  });

  describe('user header propagation', () => {
    it('forwards X-User-Id and X-User-Email when both opts are provided', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      await liberty('GET', '/api/me', {
        userId: 'user-42',
        userEmail: 'jane@example.com',
      });

      const [, init] = fetchSpy.mock.calls[1] as [string, RequestInit];
      const headers = new Headers(init.headers);
      expect(headers.get('X-User-Id')).toBe('user-42');
      expect(headers.get('X-User-Email')).toBe('jane@example.com');
    });

    it('omits user headers when no opts are provided', async () => {
      const { liberty } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('{}', { status: 200 }));

      await liberty('GET', '/api/health');

      const [, init] = fetchSpy.mock.calls[1] as [string, RequestInit];
      const headers = new Headers(init.headers);
      expect(headers.get('X-User-Id')).toBeNull();
      expect(headers.get('X-User-Email')).toBeNull();
    });
  });

  describe('libertyJson()', () => {
    it('returns parsed JSON on a 2xx response', async () => {
      const { libertyJson } = await loadLiberty();
      const payload = { policies: [{ id: 'POL-1' }] };
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(jsonResponse(payload));

      const result = await libertyJson<typeof payload>('GET', '/api/policies');
      expect(result).toEqual(payload);
    });

    it('throws on non-2xx with status + response body in the message', async () => {
      const { libertyJson } = await loadLiberty();
      fetchSpy
        .mockResolvedValueOnce(tokenResponse())
        .mockResolvedValueOnce(new Response('policy not found', { status: 404 }));

      await expect(libertyJson('GET', '/api/policies/nope')).rejects.toThrow(
        /Liberty GET \/api\/policies\/nope → 404: policy not found/,
      );
    });
  });
});
