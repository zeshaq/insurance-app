import { defineConfig } from 'vitest/config';

/**
 * Phase 1 coverage gate for customer-app (issue #10).
 *
 * Ratchet floor pegged to current coverage of the BFF helper:
 *   src/lib/server/liberty.ts — 97.3 % stmt, 87.9 % branch, 100 % funcs.
 *
 * A PR that drops liberty.ts below this floor fails CI. Component / route
 * tests aren't included in the gate yet — when they land, extend the
 * thresholds object below with per-file entries.
 */
export default defineConfig({
  test: {
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/lib/server/liberty.ts'],
      thresholds: {
        'src/lib/server/liberty.ts': {
          lines: 90,
          statements: 90,
          branches: 80,
          functions: 90,
        },
      },
    },
  },
});
