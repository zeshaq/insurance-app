<script lang="ts">
  import { page } from '$app/state';
  const session = $derived(page.data.session);
</script>

<h1 class="text-2xl font-semibold text-slate-900">Account</h1>

{#if session?.user}
  <div class="mt-6 bg-white border border-slate-200 rounded-lg p-6">
    <dl class="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
      <dt class="font-medium text-slate-500">Name</dt>
      <dd class="text-slate-900">{session.user.name ?? '(no name in token)'}</dd>
      <dt class="font-medium text-slate-500">Email</dt>
      <dd class="text-slate-900">{session.user.email ?? '(no email)'}</dd>
      <dt class="font-medium text-slate-500">User id</dt>
      <dd class="text-slate-900 font-mono text-xs">{session.user.id ?? '—'}</dd>
    </dl>
    <p class="mt-6 text-xs text-slate-500">
      This page is server-rendered after a successful OIDC code-flow against
      WSO2 IS. The session cookie is HttpOnly + signed by Auth.js; your browser
      never sees a JWT.
    </p>
  </div>
{:else}
  <p class="mt-4 text-slate-600">You're signed out.</p>
  <form action="/auth/signin/wso2is" method="POST" class="mt-3">
    <button class="text-[var(--brand)] underline">Sign in</button>
  </form>
{/if}
