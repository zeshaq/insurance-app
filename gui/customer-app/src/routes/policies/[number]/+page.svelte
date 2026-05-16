<script lang="ts">
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm">
  <p class="text-xs uppercase tracking-wide text-slate-500">Policy</p>
  <h1 class="text-3xl font-semibold font-mono text-slate-900">{data.policy.policyNumber}</h1>

  <dl class="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
    <dt class="font-medium text-slate-500">Status</dt>
    <dd>
      <span class="inline-block px-2 py-0.5 rounded text-xs bg-emerald-50 text-emerald-800 border border-emerald-200">
        {data.policy.status}
      </span>
    </dd>
    <dt class="font-medium text-slate-500">From quote</dt>
    <dd class="font-mono">{data.policy.quoteId}</dd>
    <dt class="font-medium text-slate-500">Bound at</dt>
    <dd>{new Date(data.policy.boundAt).toLocaleString()}</dd>
  </dl>

  <div class="mt-8 flex gap-3 flex-wrap">
    <a href={`/policies/${data.policy.policyNumber}/pay`}
       class="bg-[var(--brand)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90">
      Make a payment
    </a>
    <a href={`/claims/file?policy=${data.policy.policyNumber}`} class="px-5 py-2.5 rounded font-medium border border-slate-300 text-slate-700 hover:bg-slate-50">
      File a claim
    </a>
    <a href="/policies" class="px-5 py-2.5 rounded font-medium border border-slate-300 text-slate-700 hover:bg-slate-50">
      Back to policies
    </a>
  </div>
</section>
