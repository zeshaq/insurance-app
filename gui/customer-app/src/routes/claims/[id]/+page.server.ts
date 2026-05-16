import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';

interface Claim {
  id: number;
  policyNumber: string;
  description?: string | null;
  status: string;
  filedAt: string;
  photoKey?: string | null;
  photoContentType?: string | null;
  ocrText?: string | null;
  ocrConfidence?: string | null;
  otherPartyVin?: string | null;
  otherPartyPolicy?: string | null;
  otherPartyCarrier?: string | null;
}

export const load: PageServerLoad = async ({ params }) => {
  const id = parseInt(params.id, 10);
  if (Number.isNaN(id)) throw error(400, 'Invalid claim id');
  try {
    const claim = await libertyJson<Claim>('GET', `/api/claims/${id}`);
    return { claim };
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.includes('404')) throw error(404, `Claim ${id} not found`);
    throw error(502, msg);
  }
};
