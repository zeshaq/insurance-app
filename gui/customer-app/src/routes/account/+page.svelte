<script lang="ts">
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();

  function statusClass(s: string): string {
    if (s === 'APPROVED' || s === 'BOUND') return 'bg-emerald-50 text-emerald-800 border-emerald-200';
    if (s === 'REJECTED') return 'bg-red-50 text-red-800 border-red-200';
    return 'bg-amber-50 text-amber-800 border-amber-200';
  }
</script>

<h1 class="text-2xl font-semibold text-slate-900">Account</h1>

<div class="mt-6 bg-white border border-slate-200 rounded-lg p-6">
  <p class="text-xs uppercase tracking-wide text-slate-500">Signed in as</p>
  <p class="mt-1 text-lg font-semibold text-slate-900">{data.session.user.name ?? data.session.user.email ?? '(no name)'}</p>
  <dl class="mt-4 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
    <dt class="font-medium text-slate-500">Email</dt>
    <dd class="text-slate-900">{data.session.user.email ?? '—'}</dd>
    <dt class="font-medium text-slate-500">User id</dt>
    <dd class="text-slate-900 font-mono text-xs">{data.session.user.id ?? '—'}</dd>
  </dl>
  <form action="/auth/signout" method="POST" class="mt-6">
    <button formaction="/auth/signout?callbackUrl=/"
      class="text-sm border border-slate-300 text-slate-700 rounded px-4 py-2 hover:bg-slate-50">
      Sign out
    </button>
  </form>
</div>

<div class="mt-8 grid md:grid-cols-2 gap-6">
  <div class="bg-white border border-slate-200 rounded-lg p-6">
    <div class="flex items-baseline justify-between">
      <h2 class="text-lg font-semibold text-slate-900">Recent policies</h2>
      <a href="/policies" class="text-sm text-[var(--brand)] hover:underline">View all →</a>
    </div>
    {#if data.policies.length === 0}
      <p class="mt-4 text-sm text-slate-500">No policies yet. <a class="underline" href="/quote">Get a quote.</a></p>
    {:else}
      <ul class="mt-4 divide-y divide-slate-100">
        {#each data.policies as p}
          <li class="py-2 flex items-baseline justify-between gap-3 text-sm">
            <a href={`/policies/${p.policyNumber}`} class="font-mono text-[var(--brand)] hover:underline">{p.policyNumber}</a>
            <span class={`inline-block px-2 py-0.5 rounded text-xs border ${statusClass(p.status)}`}>{p.status}</span>
          </li>
        {/each}
      </ul>
    {/if}
  </div>

  <div class="bg-white border border-slate-200 rounded-lg p-6">
    <div class="flex items-baseline justify-between">
      <h2 class="text-lg font-semibold text-slate-900">Recent claims</h2>
      <a href="/claims" class="text-sm text-[var(--brand)] hover:underline">View all →</a>
    </div>
    {#if data.claims.length === 0}
      <p class="mt-4 text-sm text-slate-500">No claims yet.</p>
    {:else}
      <ul class="mt-4 divide-y divide-slate-100">
        {#each data.claims as c}
          <li class="py-2 flex items-baseline justify-between gap-3 text-sm">
            <a href={`/claims/${c.id}`} class="font-mono text-[var(--brand)] hover:underline">#{c.id}</a>
            <span class={`inline-block px-2 py-0.5 rounded text-xs border ${statusClass(c.status)}`}>{c.status}</span>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
</div>

<p class="mt-8 text-xs text-slate-500 max-w-2xl">
  The recent-activity panels currently surface system-wide rows because
  Liberty's policy/claim records do not yet store an owning user id —
  adding one is a one-line change in PolicyService / ClaimService plus a
  column migration, after which the BFF can filter by
  <code>X-User-Id</code> before rendering this page. This page is
  server-rendered after a successful OIDC code-flow against WSO2 IS; the
  session cookie is HttpOnly and signed by Auth.js, so your browser
  never sees a JWT.
</p>
