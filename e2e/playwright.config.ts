import { defineConfig, devices } from '@playwright/test';

// Two baseURLs because the customer portal and agent dashboard are
// separate origins. Each spec picks the right project via test.use({ ... })
// or via the project name when running `playwright test --project=...`.
//
// Defaults point at the public staging deployment. Override with env vars
// for local runs (e.g. against a VPN-only environment).
const CUSTOMER_BASE_URL =
  process.env.CUSTOMER_BASE_URL ?? 'https://my.insurance-app.comptech-lab.com';
const AGENT_BASE_URL =
  process.env.AGENT_BASE_URL ?? 'https://agent.insurance-app.comptech-lab.com';

const isCI = !!process.env.CI;

export default defineConfig({
  testDir: './tests',
  // Each spec talks to a real, shared staging environment. Running them in
  // parallel produces noisy concurrent quote/bind rows but is otherwise
  // safe; serial is friendlier for log readability when debugging.
  fullyParallel: false,
  workers: 1,
  // Two retries in CI to absorb transient IS / network blips; zero locally
  // so failures are immediately actionable.
  retries: isCI ? 2 : 0,
  forbidOnly: isCI,
  reporter: isCI
    ? [['html', { open: 'never' }], ['list']]
    : [['html', { open: 'never' }], ['list']],
  timeout: 90_000,
  expect: {
    timeout: 15_000,
  },
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // Some browsers are aggressive about cross-origin cookies on
    // first-party redirects; the BFFs use lax cookies, but ignoring
    // HTTPS errors keeps the run resilient if staging certs ever blip.
    ignoreHTTPSErrors: false,
    navigationTimeout: 30_000,
    actionTimeout: 15_000,
  },
  projects: [
    {
      name: 'customer-chromium',
      testMatch: /customer\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: CUSTOMER_BASE_URL,
      },
    },
    {
      name: 'agent-chromium',
      testMatch: /agent\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: AGENT_BASE_URL,
      },
    },
  ],
});
