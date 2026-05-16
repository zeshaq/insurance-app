import { useQuery } from '@tanstack/react-query';
import { fetchPolicies } from '../api';

export default function Policies() {
  const { data, isLoading, error } = useQuery({ queryKey: ['policies'], queryFn: fetchPolicies });

  return (
    <section className="bg-white border border-slate-200 rounded-lg p-6">
      <h1 className="text-xl font-semibold text-slate-900">Policies</h1>
      <p className="mt-1 text-sm text-slate-600">Whole-system policy list, ordered by bind time.</p>

      {isLoading && <p className="mt-4 text-sm text-slate-500">Loading…</p>}
      {error && <p className="mt-4 text-sm text-red-700">Failed to load policies.</p>}

      {data && data.length > 0 && (
        <div className="mt-5 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-slate-500 border-b border-slate-200">
                <th className="py-2 pr-4">Policy number</th>
                <th className="py-2 pr-4">From quote</th>
                <th className="py-2 pr-4">Status</th>
                <th className="py-2 pr-4">Bound at</th>
              </tr>
            </thead>
            <tbody>
              {data.map((p) => (
                <tr key={p.policyNumber} className="border-b border-slate-100">
                  <td className="py-2 pr-4 font-mono">{p.policyNumber}</td>
                  <td className="py-2 pr-4 font-mono text-slate-600">{p.quoteId}</td>
                  <td className="py-2 pr-4">
                    <span className="inline-block px-2 py-0.5 rounded text-xs bg-emerald-50 text-emerald-800 border border-emerald-200">
                      {p.status}
                    </span>
                  </td>
                  <td className="py-2 pr-4 text-slate-600">{new Date(p.boundAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
