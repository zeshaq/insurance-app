import { defineConfig } from 'vitest/config';

/**
 * Phase 1 coverage gate for agent-app (issue #10).
 *
 * Ratchet floor pegged to current coverage of the BFF entry point:
 *   server/index.ts — 63.75 % stmt / 48 % branch / 75 % funcs.
 *
 * The OIDC + static-serve branches are intentionally untested for now
 * (they need a test IdP and a real-build artifact respectively).
 * Tightening these thresholds is a follow-up that needs source-level
 * refactoring of server/index.ts to export the testable helpers
 * (svcToken, requireUser, the /api proxy) as separate modules.
 */
export default defineConfig({
  test: {
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['server/index.ts'],
      thresholds: {
        'server/index.ts': {
          lines: 55,
          statements: 55,
          branches: 40,
          functions: 70,
        },
      },
    },
  },
});
