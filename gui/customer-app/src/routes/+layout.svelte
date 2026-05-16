<script lang="ts">
  import '../app.css';
  import { page } from '$app/state';
  const session = $derived(page.data.session);
</script>

<header class="bg-[var(--brand)] text-[var(--brand-fg)]">
  <div class="max-w-5xl mx-auto px-6 py-3 flex items-center gap-6">
    <a href="/" class="font-semibold">insurance-app · customer portal</a>
    <nav class="text-sm flex gap-4 opacity-90">
      <a href="/quote" class="hover:underline">Get a quote</a>
      {#if session?.user}
        <a href="/policies" class="hover:underline">My policies</a>
        <a href="/claims" class="hover:underline">Claims</a>
      {/if}
    </nav>
    <div class="ml-auto text-sm">
      {#if session?.user}
        <a href="/account" class="hover:underline">{session.user.name ?? session.user.email}</a>
        <form action="/auth/signout" method="POST" class="inline">
          <button class="ml-3 underline opacity-90 hover:opacity-100" formaction="/auth/signout?callbackUrl=/">Sign out</button>
        </form>
      {:else}
        <form action="/auth/signin/wso2is" method="POST" class="inline">
          <button class="underline opacity-90 hover:opacity-100">Sign in</button>
        </form>
      {/if}
    </div>
  </div>
</header>

<main class="max-w-5xl mx-auto px-6 py-10">
  <slot/>
</main>

<footer class="text-center text-xs text-slate-500 py-8">
  insurance-app · teaching artifact · slice 18 (auth foundation)
</footer>
