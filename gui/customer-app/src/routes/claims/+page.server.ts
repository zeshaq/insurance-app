import type { PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';

export interface ClaimRow {
  id: number;
  policyNumber: string;
  description?: string | null;
  status: string;
  filedAt: string;
  photoKey?: string | null;
  photoContentType?: string | null;
  ocrConfidence?: string | null;
}

export const load: PageServerLoad = async () => {
  const claims = await libertyJson<ClaimRow[]>('GET', '/api/claims?limit=50');
  return { claims };
};
