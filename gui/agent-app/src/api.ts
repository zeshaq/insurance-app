// Client-side fetch helpers. All calls go through the BFF (same-origin),
// so cookies travel automatically and no token leaves the browser memory.

export interface SessionUser {
  id?: string;
  name?: string;
  email?: string;
}
export interface Session {
  user?: SessionUser;
}

export interface PolicyRow {
  policyNumber: string;
  quoteId: number;
  status: string;
  boundAt: string;
}

export interface ClaimRow {
  id: number;
  policyNumber: string;
  description?: string | null;
  status: string;
  filedAt: string;
  ocrText?: string | null;
  ocrConfidence?: string | null;
}

async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(path, { credentials: 'include' });
  if (!r.ok) throw new Error(`${path} -> ${r.status}`);
  return (await r.json()) as T;
}

export async function fetchSession(): Promise<Session | null> {
  try { return await getJson<Session>('/auth/session'); }
  catch { return null; }
}

export const fetchPolicies = () => getJson<PolicyRow[]>('/api/policies?limit=200');
export const fetchClaims   = () => getJson<ClaimRow[]>('/api/claims?limit=200');

export async function approveClaim(id: number): Promise<ClaimRow> {
  const r = await fetch(`/api/claims/${id}/approve`, {
    method: 'POST',
    credentials: 'include',
  });
  if (!r.ok) throw new Error(`approve claim ${id} -> ${r.status}: ${await r.text()}`);
  return (await r.json()) as ClaimRow;
}
