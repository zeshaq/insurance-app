<script lang="ts">
  import { enhance } from '$app/forms';
  import type { ActionData, PageData } from './$types';
  let { data, form }: { data: PageData; form: ActionData } = $props();
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm max-w-2xl">
  <p class="text-xs uppercase tracking-wide text-slate-500">Pay policy</p>
  <h1 class="text-2xl font-semibold font-mono text-slate-900">{data.policyNumber}</h1>

  <form method="POST" use:enhance class="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-3 items-center">
    <label class="text-sm font-medium text-slate-700" for="amount">Amount</label>
    <div class="flex items-center gap-2">
      <span class="text-slate-500">$</span>
      <input id="amount" name="amount" type="number" required min="0.01" step="0.01"
        value={form?.values?.amount ?? '750.00'}
        class="border border-slate-300 rounded px-3 py-2 w-40 focus:outline-none focus:ring-2 focus:ring-[var(--brand)]" />
      <input type="hidden" name="currency" value="USD" />
      <span class="text-sm text-slate-500">USD</span>
    </div>

    <label class="text-sm font-medium text-slate-700" for="idem">Idempotency-Key</label>
    <input id="idem" name="idempotencyKey" type="text"
      value={form?.values?.idempotencyKey ?? data.idempotencyKey}
      class="border border-slate-300 rounded px-3 py-2 font-mono text-xs focus:outline-none focus:ring-2 focus:ring-[var(--brand)]" />

    <div class="sm:col-start-2">
      <button class="bg-[var(--brand)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90">
        Charge
      </button>
      <a href={`/policies/${data.policyNumber}`} class="ml-3 text-sm text-slate-500 hover:underline">Cancel</a>
    </div>
  </form>

  {#if form?.error}
    <div class="mt-6 bg-amber-50 border-l-4 border-amber-600 p-4 text-amber-900 text-sm">
      {form.error}
    </div>
  {/if}

  {#if form?.payment}
    <div class="mt-8 bg-emerald-50 border border-emerald-200 rounded-lg p-6">
      <p class="text-sm uppercase tracking-wide text-emerald-700">Payment recorded</p>
      <p class="text-3xl font-semibold text-emerald-900 mt-1">
        ${form.payment.amount} <span class="text-base font-normal text-emerald-700">{form.payment.currency}</span>
      </p>
      <dl class="mt-4 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm text-slate-700">
        <dt class="font-medium text-slate-500">Payment id</dt><dd class="font-mono">{form.payment.id}</dd>
        <dt class="font-medium text-slate-500">Status</dt>    <dd>{form.payment.status}</dd>
        {#if form.payment.externalRef}
          <dt class="font-medium text-slate-500">External ref</dt><dd class="font-mono">{form.payment.externalRef}</dd>
        {/if}
      </dl>
    </div>
  {/if}

  <details class="mt-8 text-sm text-slate-600">
    <summary class="cursor-pointer text-slate-700 font-medium">What's the Idempotency-Key for?</summary>
    <p class="mt-3">
      The key uniquely identifies <em>this charge attempt</em>. If your network
      drops and you click "Charge" again with the same key, Liberty returns
      the same Payment row instead of billing you twice. Try it: charge, then
      submit the form again without changing the key — you'll get an HTTP 200
      replay instead of a 201 new charge. Now change the key and submit
      again — you'll see a brand-new payment recorded.
    </p>
    <p class="mt-3">
      Special demo amount: <code>9999.00</code> always fails at the gateway
      (DLQ demo). Retrying with the same key returns the same FAILED row;
      changing the key creates a new FAILED row in the database.
    </p>
  </details>
</section>
