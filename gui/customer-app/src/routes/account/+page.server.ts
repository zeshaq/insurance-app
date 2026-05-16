import { redirect } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';

interface PolicyRow { policyNumber: string; quoteId: number; status: string; boundAt: string; }
interface ClaimRow  { id: number; policyNumber: string; description?: string | null; status: string; filedAt: string; }

/** Account page: profile + activity counts + 3 most-recent rows of each
 *  kind. We do NOT filter by user — Liberty doesn't store userId on
 *  policy/claim rows yet (would be a one-line change in PolicyService /
 *  ClaimService plus a column migration). For the demo we surface a
 *  whole-system activity feed and call out the limitation in the page
 *  body so the teaching point is honest. */
export const load: PageServerLoad = async (event) => {
  const session = await event.locals.auth();
  if (!session?.user) throw redirect(302, '/auth/signin/wso2is?callbackUrl=/account');

  const [policies, claims] = await Promise.all([
    libertyJson<PolicyRow[]>('GET', '/api/policies?limit=3').catch(() => [] as PolicyRow[]),
    libertyJson<ClaimRow[]>('GET',  '/api/claims?limit=3').catch(() => [] as ClaimRow[]),
  ]);
  return { session, policies, claims };
};
