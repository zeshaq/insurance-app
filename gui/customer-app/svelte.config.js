import adapter from '@sveltejs/adapter-node';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    // Node adapter so the SvelteKit server can run inside a podman
    // container alongside Liberty + WSO2 IS on insurance-net.
    adapter: adapter({ out: 'build' }),
    // CSRF cross-origin check off for the teaching artifact — smoke
    // tests POST via curl from a different host header than the served
    // one, and turning this off lets the smoke pass without forcing a
    // real-browser harness. Production would keep this on and test
    // through Playwright.
    csrf: { checkOrigin: false },
  },
};

export default config;
