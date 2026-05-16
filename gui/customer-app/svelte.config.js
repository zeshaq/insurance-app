import adapter from '@sveltejs/adapter-node';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    // Node adapter so the SvelteKit server can run inside a podman
    // container alongside Liberty + WSO2 IS on insurance-net.
    adapter: adapter({ out: 'build' }),
  },
};

export default config;
