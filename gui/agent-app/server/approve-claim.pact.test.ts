/**
 * Pact consumer contract test for agent-app <-> Liberty.
 *
 * Scope: one endpoint, `POST /api/claims/{id}/approve`. Phase 2's goal is
 * to prove the pattern; future PRs widen coverage to /api/claims (list),
 * GET /api/policies/{n}, etc.
 *
 * The agent-app BFF (server/index.ts) is a transparent proxy: it attaches
 * Authorization: Bearer <svc-jwt>, X-User-Id (from the session), and
 * optionally X-User-Email, then forwards the verb + path + body to
 * Liberty. We don't import server/index.ts here (it has top-level Redis +
 * OIDC bootstrap that's expensive to mock in a contract test). Instead we
 * replicate the exact request shape it emits — a fetch with the three
 * headers and a JSON body — directly against the Pact mock. This makes
 * the consumer test deliberately decoupled from the BFF's wiring while
 * still pinning the *wire contract* that the BFF promises Liberty.
 *
 * What this test asserts the agent-app sends:
 *   method   POST
 *   path     /api/claims/42/approve
 *   headers  Authorization: Bearer <jwt>
 *            X-User-Id: <agent-uuid>
 *            Content-Type: application/json   (proxy sets this when body present)
 *
 * What Liberty must reply:
 *   status   200
 *   body     a Claim entity with status flipped to APPROVED
 *
 * Output: pacts/agent-app-liberty.json (committed to the repo).
 */
import path from 'node:path';
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { PactV3, MatchersV3 } from '@pact-foundation/pact';

const { like, integer, datetime, regex } = MatchersV3;
const ISO8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

// Don't pin a port — the VM has Kafka on 9092, Liberty on 9080, etc.
// Pact picks a free ephemeral port and exposes it via mockServer.url.
const pact = new PactV3({
  consumer: 'agent-app',
  provider: 'liberty',
  dir: path.resolve(__dirname, '../../../pacts'),
  logLevel: 'warn',
});

const SERVICE_JWT = 'pact-fixed-svc-jwt';
const AGENT_USER_ID = 'agent-test-uuid';

describe('agent-app <-> Liberty contract', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('POST /api/claims/{id}/approve -> 200 Claim(status=APPROVED)', async () => {
    await pact.addInteraction({
      states: [
        { description: 'a claim with id 42 exists in FILED status against policy POL-100' },
      ],
      uponReceiving: 'an approve-claim request from the agent-app BFF',
      withRequest: {
        method: 'POST',
        path: '/api/claims/42/approve',
        headers: {
          Authorization: regex(/^Bearer .+$/, `Bearer ${SERVICE_JWT}`),
          'X-User-Id': regex(/.+/, AGENT_USER_ID),
          'Content-Type': 'application/json',
        },
        // The Express proxy stringifies req.body || {} so an empty
        // JSON object is always on the wire even on a no-body POST.
        body: {},
      },
      willRespondWith: {
        status: 200,
        headers: { 'Content-Type': regex(/application\/json.*/, 'application/json') },
        body: {
          id: integer(42),
          policyNumber: like('POL-100'),
          status: regex(/^APPROVED$/, 'APPROVED'),
          filedAt: datetime(ISO8601, '2026-05-17T11:00:00.000Z'),
          description: like('rear bumper scrape'),
        },
      },
    });

    await pact.executeTest(async (mockServer) => {
      // Re-create the exact wire format the agent-app's Express /api
      // proxy emits. See gui/agent-app/server/index.ts: it attaches
      // Authorization, X-User-Id, optional X-User-Email, sets
      // Content-Type when req.body is present, and always serialises
      // req.body ?? {} for non-GET/HEAD methods.
      const res = await fetch(`${mockServer.url}/api/claims/42/approve`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${SERVICE_JWT}`,
          'X-User-Id': AGENT_USER_ID,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({}),
      });
      expect(res.status).toBe(200);
      const claim = (await res.json()) as { id: number; status: string; policyNumber: string };
      expect(claim.id).toBe(42);
      expect(claim.status).toBe('APPROVED');
      expect(claim.policyNumber).toMatch(/.+/);
    });
  });

  afterAll(() => {
    vi.restoreAllMocks();
  });
});
