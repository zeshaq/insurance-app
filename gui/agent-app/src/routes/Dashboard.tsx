import { useQuery } from '@tanstack/react-query';
import { fetchPolicies, fetchClaims } from '../api';

function StatCard({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <div className="bg-white border border-slate-200 rounded-lg p-5">
      <p className="text-xs uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-2 text-3xl font-semibold text-slate-900">{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  );
}

export default function Dashboard() {
  const policies = useQuery({ queryKey: ['policies'], queryFn: fetchPolicies });
  const claims   = useQuery({ queryKey: ['claims'],   queryFn: fetchClaims });

  const claimsPending  = claims.data?.filter((c) => c.status === 'FILED').length ?? 0;
  const claimsApproved = claims.data?.filter((c) => c.status === 'APPROVED').length ?? 0;

  return (
    <section>
      <h1 className="text-2xl font-semibold text-slate-900">Dashboard</h1>
      <p className="mt-1 text-sm text-slate-600">Live counts from Liberty via the BFF.</p>

      <div className="mt-6 grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="Total policies"  value={policies.data?.length ?? '—'} hint="BOUND policies, all customers" />
        <StatCard label="Total claims"    value={claims.data?.length ?? '—'} />
        <StatCard label="Pending claims"  value={claimsPending}   hint="status = FILED" />
        <StatCard label="Approved claims" value={claimsApproved} hint="status = APPROVED" />
      </div>

      {(policies.error || claims.error) && (
        <div className="mt-6 bg-red-50 border-l-4 border-red-600 p-4 text-sm text-red-900">
          One or more upstream calls failed. The BFF could not reach Liberty.
        </div>
      )}
    </section>
  );
}
