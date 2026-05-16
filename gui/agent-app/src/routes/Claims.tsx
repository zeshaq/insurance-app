import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { approveClaim, fetchClaims, type ClaimRow } from '../api';

function statusClass(s: string): string {
  if (s === 'APPROVED') return 'bg-emerald-50 text-emerald-800 border-emerald-200';
  if (s === 'REJECTED') return 'bg-red-50 text-red-800 border-red-200';
  return 'bg-amber-50 text-amber-800 border-amber-200';
}

export default function Claims() {
  const [filter, setFilter] = useState<'ALL' | 'FILED' | 'APPROVED'>('FILED');
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({ queryKey: ['claims'], queryFn: fetchClaims });

  const approve = useMutation({
    mutationFn: approveClaim,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['claims'] }),
  });

  const filtered: ClaimRow[] = (data ?? []).filter((c) => filter === 'ALL' || c.status === filter);

  return (
    <section className="bg-white border border-slate-200 rounded-lg p-6">
      <div className="flex items-baseline justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Claims queue</h1>
          <p className="mt-1 text-sm text-slate-600">
            FILED claims are waiting on agent review. Click Approve to fire
            Liberty's <code>POST /api/claims/{'{'}id{'}'}/approve</code>.
          </p>
        </div>
        <div className="text-sm flex gap-1.5">
          {(['FILED', 'APPROVED', 'ALL'] as const).map((f) => (
            <button key={f}
              onClick={() => setFilter(f)}
              className={`px-3 py-1 rounded border text-xs ${
                filter === f
                  ? 'bg-[var(--color-brand)] text-white border-[var(--color-brand)]'
                  : 'border-slate-300 text-slate-700 hover:bg-slate-50'
              }`}>{f}</button>
          ))}
        </div>
      </div>

      {isLoading && <p className="mt-4 text-sm text-slate-500">Loading…</p>}
      {error && <p className="mt-4 text-sm text-red-700">Failed to load claims.</p>}

      {data && (
        <div className="mt-5 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-slate-500 border-b border-slate-200">
                <th className="py-2 pr-4">Claim</th>
                <th className="py-2 pr-4">Policy</th>
                <th className="py-2 pr-4">Description</th>
                <th className="py-2 pr-4">Status</th>
                <th className="py-2 pr-4">Filed at</th>
                <th className="py-2"></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((c) => (
                <tr key={c.id} className="border-b border-slate-100 align-top">
                  <td className="py-2 pr-4 font-mono">#{c.id}</td>
                  <td className="py-2 pr-4 font-mono text-slate-600">{c.policyNumber}</td>
                  <td className="py-2 pr-4 text-slate-700 max-w-sm truncate">{c.description ?? '—'}</td>
                  <td className="py-2 pr-4">
                    <span className={`inline-block px-2 py-0.5 rounded text-xs border ${statusClass(c.status)}`}>
                      {c.status}
                    </span>
                  </td>
                  <td className="py-2 pr-4 text-slate-600">{new Date(c.filedAt).toLocaleString()}</td>
                  <td className="py-2 text-right">
                    {c.status === 'FILED' && (
                      <button
                        disabled={approve.isPending && approve.variables === c.id}
                        onClick={() => approve.mutate(c.id)}
                        className="bg-[var(--color-accent)] text-white px-3 py-1 rounded text-xs hover:opacity-90 disabled:opacity-50">
                        {approve.isPending && approve.variables === c.id ? 'Approving…' : 'Approve'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr><td colSpan={6} className="py-6 text-center text-sm text-slate-500">No claims match the current filter.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {approve.error && (
        <div className="mt-4 bg-red-50 border-l-4 border-red-600 p-3 text-xs text-red-900">
          {(approve.error as Error).message}
        </div>
      )}
    </section>
  );
}
