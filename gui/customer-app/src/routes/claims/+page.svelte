<script lang="ts">
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();

  function statusClass(s: string): string {
    if (s === 'APPROVED') return 'bg-emerald-50 text-emerald-800 border-emerald-200';
    if (s === 'REJECTED') return 'bg-red-50 text-red-800 border-red-200';
    return 'bg-amber-50 text-amber-800 border-amber-200';
  }
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm">
  <div class="flex items-baseline justify-between flex-wrap gap-3">
    <div>
      <h1 class="text-2xl font-semibold text-slate-900">Claims</h1>
      <p class="mt-1 text-sm text-slate-600">
        Recently filed claims. Each upload is OCR'd against the partner
        adjuster service and parked at <code>FILED</code> until an operator
        approves it.
      </p>
    </div>
    <a href="/claims/file"
       class="bg-[var(--accent)] text-white px-4 py-2 rounded font-medium hover:opacity-90 text-sm">
      File a new claim
    </a>
  </div>

  {#if data.claims.length === 0}
    <p class="mt-8 text-slate-500 text-sm">No claims filed yet.</p>
  {:else}
    <div class="mt-6 overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="text-left text-xs uppercase tracking-wide text-slate-500 border-b border-slate-200">
            <th class="py-2 pr-4">Claim id</th>
            <th class="py-2 pr-4">Policy</th>
            <th class="py-2 pr-4">Description</th>
            <th class="py-2 pr-4">Status</th>
            <th class="py-2 pr-4">Filed at</th>
          </tr>
        </thead>
        <tbody>
          {#each data.claims as c}
            <tr class="border-b border-slate-100 hover:bg-slate-50">
              <td class="py-2 pr-4 font-mono">
                <a href={`/claims/${c.id}`} class="text-[var(--brand)] hover:underline">#{c.id}</a>
              </td>
              <td class="py-2 pr-4 font-mono text-slate-600">
                <a href={`/policies/${c.policyNumber}`} class="hover:underline">{c.policyNumber}</a>
              </td>
              <td class="py-2 pr-4 text-slate-700 max-w-md truncate">{c.description ?? '—'}</td>
              <td class="py-2 pr-4">
                <span class={`inline-block px-2 py-0.5 rounded text-xs border ${statusClass(c.status)}`}>{c.status}</span>
              </td>
              <td class="py-2 pr-4 text-slate-600">{new Date(c.filedAt).toLocaleString()}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</section>
