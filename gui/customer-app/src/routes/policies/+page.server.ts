import type { PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';

export interface PolicyRow {
  policyNumber: string;
  quoteId: number;
  status: string;
  boundAt: string;
}

/** Anonymous browse for the demo — same trade-off as /quote: read paths
 *  are publicly visible so the UI works without forcing sign-in first. A
 *  real insurer would gate this on a customer id and only return their
 *  own policies; that's a one-line filter on `findRecent` once we wire
 *  up X-User-Id-based queries. */
export const load: PageServerLoad = async () => {
  const policies = await libertyJson<PolicyRow[]>('GET', '/api/policies?limit=50');
  return { policies };
};
