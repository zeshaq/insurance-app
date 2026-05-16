<script lang="ts">
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm">
  <div class="flex items-baseline justify-between flex-wrap gap-3">
    <div>
      <h1 class="text-2xl font-semibold text-slate-900">Policies</h1>
      <p class="mt-1 text-sm text-slate-600">
        Recently bound policies — most recent first. Click a policy number to
        open it and record a payment.
      </p>
    </div>
    <a href="/quote"
       class="bg-[var(--accent)] text-white px-4 py-2 rounded font-medium hover:opacity-90 text-sm">
      Start a new quote
    </a>
  </div>

  {#if data.policies.length === 0}
    <p class="mt-8 text-slate-500 text-sm">
      No policies yet. <a class="underline" href="/quote">Get a quote</a> and bind it to see one here.
    </p>
  {:else}
    <div class="mt-6 overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="text-left text-xs uppercase tracking-wide text-slate-500 border-b border-slate-200">
            <th class="py-2 pr-4">Policy number</th>
            <th class="py-2 pr-4">From quote</th>
            <th class="py-2 pr-4">Status</th>
            <th class="py-2 pr-4">Bound at</th>
            <th class="py-2"></th>
          </tr>
        </thead>
        <tbody>
          {#each data.policies as p}
            <tr class="border-b border-slate-100 hover:bg-slate-50">
              <td class="py-2 pr-4 font-mono">
                <a href={`/policies/${p.policyNumber}`} class="text-[var(--brand)] hover:underline">
                  {p.policyNumber}
                </a>
              </td>
              <td class="py-2 pr-4 font-mono text-slate-600">{p.quoteId}</td>
              <td class="py-2 pr-4">
                <span class="inline-block px-2 py-0.5 rounded text-xs bg-emerald-50 text-emerald-800 border border-emerald-200">
                  {p.status}
                </span>
              </td>
              <td class="py-2 pr-4 text-slate-600">{new Date(p.boundAt).toLocaleString()}</td>
              <td class="py-2 text-right">
                <a href={`/policies/${p.policyNumber}/pay`} class="text-xs text-[var(--brand)] hover:underline">
                  Pay →
                </a>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</section>
