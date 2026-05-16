<script lang="ts">
  import { enhance } from '$app/forms';
  import type { ActionData, PageData } from './$types';
  let { data, form }: { data: PageData; form: ActionData } = $props();
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm max-w-2xl">
  <h1 class="text-2xl font-semibold text-slate-900">File a claim</h1>
  <p class="mt-2 text-slate-600">
    Upload a photo or PDF of the damage. We stream the attachment straight
    to object storage and the OCR partner runs against it before an operator
    reviews.
  </p>

  <form method="POST" use:enhance enctype="multipart/form-data" class="mt-6 grid gap-4 max-w-xl">
    <label class="block">
      <span class="text-sm font-medium text-slate-700">Policy number</span>
      <input name="policyNumber" type="text" required
        value={form?.values?.policyNumber ?? data.prefillPolicy}
        placeholder="POL-XXXXXXXX"
        class="mt-1 w-full border border-slate-300 rounded px-3 py-2 font-mono focus:outline-none focus:ring-2 focus:ring-[var(--brand)]" />
    </label>

    <label class="block">
      <span class="text-sm font-medium text-slate-700">What happened? (optional)</span>
      <textarea name="description" rows="3"
        value={form?.values?.description ?? ''}
        class="mt-1 w-full border border-slate-300 rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[var(--brand)]"></textarea>
    </label>

    <label class="block">
      <span class="text-sm font-medium text-slate-700">Other party VIN (optional)</span>
      <input name="otherPartyVin" type="text"
        value={form?.values?.otherPartyVin ?? ''}
        placeholder="e.g. 1HGBH41JXMN109186"
        class="mt-1 w-full border border-slate-300 rounded px-3 py-2 font-mono focus:outline-none focus:ring-2 focus:ring-[var(--brand)]" />
    </label>

    <label class="block">
      <span class="text-sm font-medium text-slate-700">Attachment (optional)</span>
      <input name="attachment" type="file" accept="image/*,.pdf"
        class="mt-1 block w-full text-sm text-slate-600 file:mr-3 file:py-2 file:px-3 file:rounded file:border-0 file:bg-[var(--brand)] file:text-white file:cursor-pointer" />
    </label>

    <div>
      <button class="bg-[var(--accent)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90">
        File claim
      </button>
      <a href="/claims" class="ml-3 text-sm text-slate-500 hover:underline">Cancel</a>
    </div>
  </form>

  {#if form?.error}
    <div class="mt-6 bg-red-50 border-l-4 border-red-600 p-4 text-red-900 text-sm">
      {form.error}
    </div>
  {/if}

  <p class="mt-8 text-xs text-slate-500">
    Signed in as <span class="font-mono">{data.user.email ?? data.user.id}</span>.
    The upload streams through the SvelteKit BFF to Liberty as
    <code>multipart/form-data</code>; large files never have to fit in
    memory.
  </p>
</section>
