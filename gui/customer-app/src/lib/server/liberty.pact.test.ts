/**
 * Pact consumer contract test for customer-app <-> Liberty.
 *
 * Scope: one endpoint, `POST /api/quotes`. Phase 2's goal is to prove the
 * pattern; future PRs widen coverage to /api/policies, /api/payments etc.
 *
 * What this test asserts:
 *   - The customer-app BFF helper (`libertyJson<Quote>`) sends:
 *       method   POST
 *       path     /api/quotes
 *       headers  Authorization: Bearer <jwt>     (set by the helper)
 *                Content-Type: application/json  (auto-set when body is an obj)
 *       body     { vehicleVin, driverAge, coverageType }
 *   - Liberty replies with:
 *       status   201
 *       headers  Content-Type: application/json
 *       body     { id, vehicleVin, driverAge, coverageType, premium, status, validUntil, createdAt }
 *
 * The Pact mock server stands in for Liberty for the duration of the test;
 * the helper never touches a real backend. The JWT is a fixed string —
 * Pact does not (and should not) validate JWT signatures at the consumer
 * tier; that's the provider's job.
 *
 * Output: pacts/customer-app-liberty.json (committed to the repo).
 */
import path from 'node:path';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { PactV3, MatchersV3 } from '@pact-foundation/pact';

const { like, integer, decimal, datetime, regex } = MatchersV3;
const ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

// ---- SvelteKit env shim ----------------------------------------------------
// The helper reads from `$env/dynamic/private`. Point it at the Pact mock.
const PACT_MOCK_PORT = 9091;
const TEST_ENV = {
  WSO2IS_TOKEN_URL_INTERNAL: 'http://wso2is.test/oauth2/token',
  LIBERTY_BASE: `http://127.0.0.1:${PACT_MOCK_PORT}`,
  WSO2IS_CLIENT_ID: 'pact-test-client',
  WSO2IS_CLIENT_SECRET: 'pact-test-secret',
};
vi.mock('$env/dynamic/private', () => ({ env: TEST_ENV }));

// ---- Pact provider declaration --------------------------------------------
const pact = new PactV3({
  consumer: 'customer-app',
  provider: 'liberty',
  dir: path.resolve(__dirname, '../../../../../pacts'),
  port: PACT_MOCK_PORT,
  logLevel: 'warn',
});

describe('customer-app <-> Liberty contract', () => {
  beforeEach(() => {
    // Reset the helper's module-scoped token cache + stub the
    // WSO2 IS token endpoint so the helper doesn't try to hit the real
    // IS when ensureToken() runs. Returning a fixed Bearer keeps the
    // recorded Pact deterministic.
    vi.resetModules();
    const realFetch = globalThis.fetch;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as URL | Request).toString();
      if (url.startsWith('http://wso2is.test')) {
        return new Response(
          JSON.stringify({ access_token: 'pact-fixed-jwt-token', expires_in: 3600 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      // Anything else goes to the real fetch (which Pact's mock server
      // is listening on).
      return realFetch(input, init);
    });
  });

  it('POST /api/quotes -> 201 with the calculated Quote', async () => {
    await pact.addInteraction({
      states: [{ description: 'rate limit window is open for this vehicleVin' }],
      uponReceiving: 'a quote creation request from the customer-app BFF',
      withRequest: {
        method: 'POST',
        path: '/api/quotes',
        headers: {
          'Content-Type': 'application/json',
          Authorization: regex(/^Bearer .+$/, 'Bearer pact-fixed-jwt-token'),
        },
        body: {
          vehicleVin: '1HGBH41JXMN109186',
          driverAge: 35,
          coverageType: 'STANDARD',
        },
      },
      willRespondWith: {
        status: 201,
        headers: { 'Content-Type': regex(/application\/json.*/, 'application/json') },
        body: {
          id: integer(42),
          vehicleVin: like('1HGBH41JXMN109186'),
          driverAge: integer(35),
          coverageType: like('STANDARD'),
          premium: decimal(750.0),
          status: regex(/^(CALCULATED|EXPIRED|BOUND)$/, 'CALCULATED'),
          createdAt: datetime(ISO8601, '2026-05-17T12:00:00.000Z'),
          validUntil: datetime(ISO8601, '2026-06-16T12:00:00.000Z'),
        },
      },
    });

    await pact.executeTest(async () => {
      const { libertyJson } = await import('./liberty');
      const quote = await libertyJson<{
        id: number;
        vehicleVin: string;
        driverAge: number;
        coverageType: string;
        premium: number;
        status: string;
        createdAt: string;
        validUntil: string;
      }>('POST', '/api/quotes', {
        body: {
          vehicleVin: '1HGBH41JXMN109186',
          driverAge: 35,
          coverageType: 'STANDARD',
        },
      });
      expect(quote.id).toBeTypeOf('number');
      expect(quote.vehicleVin).toBe('1HGBH41JXMN109186');
      expect(quote.coverageType).toBe('STANDARD');
      expect(quote.status).toBe('CALCULATED');
      expect(typeof quote.premium === 'number' || typeof quote.premium === 'string').toBe(true);
    });
  });

  afterAll(() => {
    vi.restoreAllMocks();
  });
});
