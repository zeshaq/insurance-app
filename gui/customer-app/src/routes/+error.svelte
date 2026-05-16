<script lang="ts">
  import { page } from '$app/state';
  const status  = $derived(page.status);
  const message = $derived(page.error?.message ?? 'Something went wrong.');
</script>

<section class="bg-white border border-slate-200 rounded-lg p-10 shadow-sm text-center max-w-xl mx-auto">
  <p class="text-6xl font-bold text-slate-300 font-mono">{status}</p>
  <h1 class="mt-4 text-2xl font-semibold text-slate-900">
    {#if status === 404}
      Page not found
    {:else if status >= 500}
      Something broke on our end
    {:else}
      {message}
    {/if}
  </h1>
  <p class="mt-2 text-slate-600">{message}</p>
  <div class="mt-8 flex gap-3 justify-center">
    <a href="/" class="bg-[var(--brand)] text-white px-5 py-2 rounded font-medium hover:opacity-90">Home</a>
    <a href="/quote" class="px-5 py-2 rounded font-medium border border-slate-300 text-slate-700 hover:bg-slate-50">Get a quote</a>
  </div>
</section>
