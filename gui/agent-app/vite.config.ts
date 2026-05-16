import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The BFF (server/index.ts) serves the built SPA from dist/ui in
// production. In dev, run `npm run dev:ui` separately and proxy /api
// + /auth to the BFF on :3001.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'dist/ui',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api':  'http://localhost:3001',
      '/auth': 'http://localhost:3001',
    },
  },
});
