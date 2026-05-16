<script lang="ts">
  import { page } from '$app/state';
  const session = $derived(page.data.session);
</script>

<section class="bg-white border border-slate-200 rounded-lg p-10 shadow-sm">
  <h1 class="text-3xl font-semibold text-slate-900">Get an insurance quote in 60 seconds.</h1>
  <p class="mt-3 text-slate-600 max-w-2xl">
    Tell us about your vehicle and your driver profile, and we'll calculate a premium
    against our policy engine. Coverage starts the day you bind.
  </p>
  <div class="mt-6 flex flex-wrap gap-3">
    <a href="/quote" class="inline-block bg-[var(--accent)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90">
      Start a quote
    </a>
    {#if !session?.user}
      <form action="/auth/signin/wso2is" method="POST" class="inline">
        <button class="inline-block border border-slate-300 px-5 py-2.5 rounded font-medium text-slate-700 hover:bg-slate-50">
          Sign in
        </button>
      </form>
    {/if}
  </div>
</section>

<section class="mt-10 grid grid-cols-1 md:grid-cols-3 gap-4">
  <div class="bg-white border border-slate-200 rounded-lg p-5">
    <h3 class="font-semibold text-slate-900">Live pricing</h3>
    <p class="mt-2 text-sm text-slate-600">Premium calculation accounts for vehicle, driver, and credit factors in real time.</p>
  </div>
  <div class="bg-white border border-slate-200 rounded-lg p-5">
    <h3 class="font-semibold text-slate-900">Bind same-day</h3>
    <p class="mt-2 text-sm text-slate-600">Accept a quote and we issue a policy number on the spot. No callbacks, no waiting.</p>
  </div>
  <div class="bg-white border border-slate-200 rounded-lg p-5">
    <h3 class="font-semibold text-slate-900">File a claim from your phone</h3>
    <p class="mt-2 text-sm text-slate-600">Upload a photo of the damage. OCR pulls out the details automatically.</p>
  </div>
</section>
