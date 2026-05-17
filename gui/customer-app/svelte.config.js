import adapter from '@sveltejs/adapter-node';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    // Node adapter so the SvelteKit server can run inside a podman
    // container alongside Liberty + WSO2 IS on insurance-net.
    adapter: adapter({ out: 'build' }),

    // ---- CSRF cross-origin check (issue #20, Phase 4) ----
    //
    // The cross-origin check is ON in production. SvelteKit's default
    // (kit.csrf.checkOrigin = true) rejects POST/PUT/PATCH/DELETE form
    // submissions whose Origin header does not match the request's own
    // host. This blocks the classic "evil.com submits a form action to
    // my.insurance-app.comptech-lab.com using the victim's cookie"
    // attack on the BFF's /auth/* and form-action endpoints.
    //
    // We do NOT set `csrf: { checkOrigin: false }` (the teaching-time
    // shortcut that let curl drive /auth/signin/wso2is from the smoke
    // script). The Playwright e2e (e2e/tests/customer.spec.ts) drives a
    // real Chromium against this origin and therefore carries the right
    // Origin header automatically — it does not need an allow-list
    // entry.
    //
    // `trustedOrigins` is left at its default (empty). Add an origin
    // here ONLY if you intentionally cross-submit from another host
    // you control (e.g. a corporate intranet portal that wraps the
    // app). Do not add wildcards.
    //
    // Teaching-vs-prod toggle for instructors:
    //   The smoke script (scripts/smoke.sh) used to POST to
    //   /auth/signin/wso2is via curl from the VM. With the cross-origin
    //   check back on, that curl-driven smoke step returns 403. This is
    //   expected and acceptable — the smoke is testing routing, not the
    //   BFF's form-action security. The load-bearing post-issue-#20
    //   test for the OIDC click-through is `playwright test --project=
    //   customer-chromium` (see e2e/tests/customer.spec.ts).
    //
    //   If you absolutely need curl-from-the-VM to keep posting against
    //   /auth/signin/wso2is in a teaching session, the right knob is to
    //   ship `--header "Origin: https://my.insurance-app.comptech-lab.com"`
    //   on each curl, NOT to disable the check.
  },
};

export default config;
