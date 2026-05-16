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
  <p class="text-xs uppercase tracking-wide text-slate-500">Claim</p>
  <div class="flex items-baseline justify-between flex-wrap gap-3">
    <h1 class="text-3xl font-semibold font-mono text-slate-900">#{data.claim.id}</h1>
    <span class={`inline-block px-3 py-1 rounded text-sm border ${statusClass(data.claim.status)}`}>{data.claim.status}</span>
  </div>

  <dl class="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
    <dt class="font-medium text-slate-500">Policy</dt>
    <dd class="font-mono">
      <a href={`/policies/${data.claim.policyNumber}`} class="text-[var(--brand)] hover:underline">{data.claim.policyNumber}</a>
    </dd>
    <dt class="font-medium text-slate-500">Filed at</dt>
    <dd>{new Date(data.claim.filedAt).toLocaleString()}</dd>
    {#if data.claim.description}
      <dt class="font-medium text-slate-500">Description</dt>
      <dd>{data.claim.description}</dd>
    {/if}
    {#if data.claim.photoKey}
      <dt class="font-medium text-slate-500">Attachment</dt>
      <dd class="font-mono text-xs text-slate-600">
        {data.claim.photoKey}
        {#if data.claim.photoContentType}<span class="ml-2 text-slate-400">({data.claim.photoContentType})</span>{/if}
      </dd>
    {/if}
    {#if data.claim.otherPartyVin}
      <dt class="font-medium text-slate-500">Other party</dt>
      <dd>
        <span class="font-mono">{data.claim.otherPartyVin}</span>
        {#if data.claim.otherPartyCarrier}
          <span class="ml-2 text-slate-600">— {data.claim.otherPartyCarrier}</span>
        {/if}
        {#if data.claim.otherPartyPolicy}
          <span class="ml-2 font-mono text-slate-500">{data.claim.otherPartyPolicy}</span>
        {/if}
      </dd>
    {/if}
  </dl>

  {#if data.claim.ocrText}
    <div class="mt-8">
      <p class="text-sm font-medium text-slate-700">
        OCR result
        {#if data.claim.ocrConfidence}
          <span class="ml-2 text-xs text-slate-500">confidence {data.claim.ocrConfidence}</span>
        {/if}
      </p>
      <pre class="mt-2 bg-slate-50 border border-slate-200 rounded p-3 text-xs whitespace-pre-wrap text-slate-700">{data.claim.ocrText}</pre>
    </div>
  {/if}

  <div class="mt-8 flex gap-3 flex-wrap">
    <a href="/claims" class="px-5 py-2 rounded font-medium border border-slate-300 text-slate-700 hover:bg-slate-50">
      Back to claims
    </a>
  </div>
</section>
