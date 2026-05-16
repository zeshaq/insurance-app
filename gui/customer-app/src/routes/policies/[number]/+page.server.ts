import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';

interface Policy {
  policyNumber: string;
  quoteId: number;
  status: string;
  boundAt: string;
}

export const load: PageServerLoad = async ({ params }) => {
  try {
    const policy = await libertyJson<Policy>('GET', `/api/policies/${params.number}`);
    return { policy };
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.includes('404')) throw error(404, `Policy ${params.number} not found`);
    throw error(502, msg);
  }
};
