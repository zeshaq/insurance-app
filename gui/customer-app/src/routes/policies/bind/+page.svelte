<script lang="ts">
  import { enhance } from '$app/forms';
  import type { PageData } from './$types';
  let { data }: { data: PageData } = $props();
</script>

<section class="bg-white border border-slate-200 rounded-lg p-8 shadow-sm max-w-xl">
  <h1 class="text-2xl font-semibold text-slate-900">Confirm bind</h1>
  <p class="mt-2 text-slate-600">
    You're about to bind <strong class="font-mono">quote #{data.quoteId}</strong> into a policy.
    Once bound, the rate is locked and we'll generate a policy number you can
    pay against.
  </p>

  <form method="POST" use:enhance class="mt-6">
    <input type="hidden" name="quoteId" value={data.quoteId} />
    <button class="bg-[var(--brand)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90">
      Bind policy
    </button>
    <a href="/quote" class="ml-3 text-sm text-slate-500 hover:underline">Cancel</a>
  </form>

  <p class="mt-6 text-xs text-slate-500">
    Signed in as <span class="font-mono">{data.user.email ?? data.user.name ?? data.user.id}</span>.
    The bind call propagates your identity to Liberty as
    <code>X-User-Id</code> / <code>X-User-Email</code>.
  </p>
</section>
