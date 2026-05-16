import { fail, redirect } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';
import { liberty } from '$lib/server/liberty';

interface Claim {
  id: number;
  policyNumber: string;
  status: string;
  ocrText?: string | null;
  ocrConfidence?: string | null;
  photoKey?: string | null;
}

export const load: PageServerLoad = async ({ locals, url }) => {
  const session = await locals.auth();
  if (!session?.user) {
    throw redirect(302, `/auth/signin/wso2is?callbackUrl=${encodeURIComponent(url.pathname)}`);
  }
  return { user: session.user, prefillPolicy: url.searchParams.get('policy') ?? '' };
};

export const actions: Actions = {
  default: async ({ request, locals }) => {
    const session = await locals.auth();
    if (!session?.user) throw redirect(302, '/auth/signin/wso2is');

    const incoming = await request.formData();
    const policyNumber = String(incoming.get('policyNumber') ?? '').trim();
    const description  = String(incoming.get('description') ?? '').trim();
    const otherPartyVin = String(incoming.get('otherPartyVin') ?? '').trim();
    const file = incoming.get('attachment');

    if (!policyNumber) {
      return fail(400, { values: { policyNumber, description, otherPartyVin }, error: 'Policy number is required.' });
    }

    // Build the outgoing FormData so we control exactly which parts Liberty
    // sees. Keys must match the part names the ClaimResource looks for.
    const outgoing = new FormData();
    outgoing.append('policyNumber', policyNumber);
    if (description)   outgoing.append('description', description);
    if (otherPartyVin) outgoing.append('otherPartyVin', otherPartyVin);
    if (file instanceof File && file.size > 0) {
      outgoing.append('attachment', file, file.name);
    }

    const res = await liberty('POST', '/api/claims', {
      body: outgoing,
      userId:    session.user.id    ?? undefined,
      userEmail: session.user.email ?? undefined,
    });

    if (!res.ok) {
      const text = await res.text();
      if (res.status === 404) {
        return fail(404, { values: { policyNumber, description, otherPartyVin }, error: `Policy ${policyNumber} not found.` });
      }
      return fail(res.status, { values: { policyNumber, description, otherPartyVin }, error: `Liberty rejected the claim: ${text}` });
    }
    const claim = (await res.json()) as Claim;
    throw redirect(303, `/claims/${claim.id}`);
  },
};
