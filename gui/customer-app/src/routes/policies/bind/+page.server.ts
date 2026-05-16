import { error, redirect } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';

interface Policy {
  policyNumber: string;
  quoteId: number;
  status: string;
  boundAt: string;
}

/** GET /policies/bind?quoteId=N renders a one-button confirmation page.
 *  We do not auto-bind from the GET — that would let an attacker bind a
 *  policy by tricking a logged-in user into clicking a link. The POST
 *  action below performs the actual bind. */
export const load: PageServerLoad = async ({ url, locals }) => {
  const quoteId = url.searchParams.get('quoteId');
  if (!quoteId || Number.isNaN(parseInt(quoteId, 10))) {
    throw error(400, 'Missing or invalid quoteId');
  }
  const session = await locals.auth();
  if (!session?.user) {
    throw redirect(302, `/auth/signin/wso2is?callbackUrl=${encodeURIComponent(`/policies/bind?quoteId=${quoteId}`)}`);
  }
  return { quoteId: parseInt(quoteId, 10), user: session.user };
};

export const actions: Actions = {
  default: async ({ request, locals }) => {
    const session = await locals.auth();
    if (!session?.user) throw redirect(302, '/auth/signin/wso2is');

    const data = await request.formData();
    const quoteId = parseInt(String(data.get('quoteId') ?? ''), 10);
    if (Number.isNaN(quoteId)) throw error(400, 'Invalid quoteId');

    const policy = await libertyJson<Policy>('POST', '/api/policies', {
      body: { quoteId },
      userId:    session.user.id    ?? undefined,
      userEmail: session.user.email ?? undefined,
    });
    throw redirect(303, `/policies/${policy.policyNumber}`);
  },
};
