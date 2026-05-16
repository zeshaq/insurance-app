<script lang="ts">
  import { enhance } from '$app/forms';
  import type { ActionData, PageData } from './$types';

  let { data, form }: { data: PageData; form: ActionData } = $props();
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm">
  <h1 class="text-2xl font-semibold text-slate-900">Get a quote</h1>
  <p class="mt-2 text-slate-600">
    Three fields, real-time pricing against our policy engine. Premium is
    indicative — bind in the next step to lock the rate.
  </p>

  <form method="POST" use:enhance class="mt-6 grid grid-cols-1 sm:grid-cols-[max-content_1fr] gap-x-6 gap-y-3 items-center max-w-xl">
    <label class="text-sm font-medium text-slate-700" for="vin">Vehicle VIN</label>
    <input id="vin" name="vehicleVin" type="text" required maxlength="17" minlength="3"
      placeholder="e.g. 1HGBH41JXMN109186"
      value={form?.values?.vehicleVin ?? ''}
      class="border border-slate-300 rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[var(--brand)]">

    <label class="text-sm font-medium text-slate-700" for="age">Driver age</label>
    <input id="age" name="driverAge" type="number" required min="16" max="99"
      value={form?.values?.driverAge ?? '35'}
      class="border border-slate-300 rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[var(--brand)] sm:w-32">

    <label class="text-sm font-medium text-slate-700" for="cov">Coverage</label>
    <select id="cov" name="coverageType"
      class="border border-slate-300 rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[var(--brand)] sm:w-fit">
      <option value="BASIC"    selected={form?.values?.coverageType === 'BASIC'}>BASIC</option>
      <option value="STANDARD" selected={(form?.values?.coverageType ?? 'STANDARD') === 'STANDARD'}>STANDARD (most popular)</option>
      <option value="PREMIUM"  selected={form?.values?.coverageType === 'PREMIUM'}>PREMIUM</option>
    </select>

    <div class="sm:col-start-2">
      <button class="bg-[var(--accent)] text-white px-5 py-2 rounded font-medium hover:opacity-90">
        Calculate premium
      </button>
    </div>
  </form>

  {#if form?.error}
    <div class="mt-6 bg-red-50 border-l-4 border-red-600 p-4 text-red-900 text-sm">
      <strong>Couldn't calculate a quote.</strong> {form.error}
    </div>
  {/if}

  {#if form?.quote}
    <div class="mt-8 bg-emerald-50 border border-emerald-200 rounded-lg p-6">
      <div class="flex items-baseline justify-between gap-4 flex-wrap">
        <div>
          <p class="text-sm uppercase tracking-wide text-emerald-700">Your premium</p>
          <p class="text-4xl font-semibold text-emerald-900 mt-1">${form.quote.premium}</p>
          <p class="text-xs text-emerald-700 mt-1">per period · valid until {form.quote.validUntil}</p>
        </div>
        <a href={`/policies/bind?quoteId=${form.quote.id}`}
           class="bg-[var(--brand)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90">
          Accept and bind
        </a>
      </div>
      <dl class="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm text-slate-700">
        <dt class="font-medium text-slate-500">Quote id</dt> <dd class="font-mono">{form.quote.id}</dd>
        <dt class="font-medium text-slate-500">VIN</dt>      <dd>{form.quote.vehicleVin}</dd>
        <dt class="font-medium text-slate-500">Driver age</dt><dd>{form.quote.driverAge}</dd>
        <dt class="font-medium text-slate-500">Coverage</dt> <dd>{form.quote.coverageType}</dd>
        <dt class="font-medium text-slate-500">Status</dt>   <dd>{form.quote.status}</dd>
      </dl>
    </div>
  {/if}
</section>

<section class="mt-8 text-sm text-slate-500">
  <p>
    <strong class="text-slate-700">What's happening behind the scenes:</strong>
    your VIN goes through a credit-bureau lookup (via MI) and the result is
    cached in Redis for an hour. Same VIN twice? Cached premium, instant
    response. Six times in a row from the same VIN? You'll see a 429 — the
    sliding-window rate-limit is working. This is the slice-1-through-5
    Quote feature riding underneath.
  </p>
</section>
