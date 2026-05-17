import { test, expect, type Page } from '@playwright/test';

/**
 * Customer portal end-to-end (issue #14).
 *
 * Drives the full OIDC click-through that today's smoke can't:
 *   landing -> POST /auth/signin/wso2is -> WSO2 IS authenticationendpoint
 *   -> credentials -> (optional consent) -> /account
 *
 * Then exercises one business flow: quote -> bind -> policy detail.
 * Each run uses a timestamped VIN so reruns don't collide on the
 * sliding-window rate-limit in the Quote service.
 */

const USERNAME = process.env.PORTAL_TEST_USER ?? 'student@comptech.com';
const PASSWORD = process.env.PORTAL_TEST_PASSWORD ?? 'Student@1234';

// VIN must be 3-17 chars (matches the quote form constraint). Date.now()
// gives a 13-digit millis stamp, so "PW-<ms>" is 16 chars, comfortably
// inside the cap.
const VIN = `PW-${Date.now()}`;

async function signInViaWSO2IS(page: Page): Promise<void> {
  // Landing page exposes a Sign in button that POSTs /auth/signin/wso2is.
  // Click it the same way a user would, rather than firing the form
  // directly, so we exercise the same redirect chain.
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /get an insurance quote/i })).toBeVisible();

  // Two Sign in surfaces on the landing page (hero + header). Use the
  // first visible one to start the flow.
  const signInButton = page.getByRole('button', { name: /^sign in$/i }).first();
  await signInButton.click();

  // We should be redirected to the WSO2 IS authenticationendpoint. The
  // hostname is is.insurance-app.comptech-lab.com; assert on the path
  // pattern so we don't hard-couple to that hostname.
  await page.waitForURL(/\/authenticationendpoint\/login\.do|\/oauth2\/authorize/i, {
    timeout: 30_000,
  });

  // WSO2 IS's default template carries a hidden input[name="username"]
  // that JS hydrates from the visible textbox; targeting the placeholder
  // gives us the user-facing field directly. If your tenant ships a
  // custom template, update these selectors.
  const usernameField = page.getByPlaceholder(/enter your username/i);
  const passwordField = page.getByPlaceholder(/enter your password/i);
  await expect(usernameField).toBeVisible({ timeout: 15_000 });
  await usernameField.fill(USERNAME);
  await passwordField.fill(PASSWORD);

  // Submit. Button label is "Sign In" in stock IS; broaden the match in
  // case a tenant rebrands it ("Continue", "Log in", ...).
  await page
    .getByRole('button', { name: /^(sign in|log ?in|continue)$/i })
    .first()
    .click();

  // Consent screen (if shown) carries an "Approve" / "Continue" /
  // "Allow" button. Click it if present; otherwise fall through.
  try {
    const approve = page.getByRole('button', { name: /approve|continue|allow|yes/i });
    await approve.waitFor({ timeout: 5_000 });
    await approve.click();
  } catch {
    // No consent step — user has already approved this client. Carry on.
  }

  // Final destination depends on the original callbackUrl. For a fresh
  // session started from `/`, the BFF lands the user on `/` or `/account`.
  // Just wait until we're back on the customer-portal origin.
  await page.waitForURL(/my\.insurance-app\.comptech-lab\.com\//, { timeout: 30_000 });
  // Pull the cookie-backed session view to confirm we are signed in.
  // The session may not surface the user's email (Liberty mpJwt only
  // ships a sub claim today), so we assert on header signals:
  //   - the header navigation now shows "My policies" / "Claims" (only
  //     rendered when session.user is truthy),
  //   - the /account page renders the "Signed in as" panel,
  //   - the Sign out form is present.
  await page.goto('/account');
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByRole('heading', { name: /^account$/i })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(/signed in as/i)).toBeVisible();
  // Multiple Sign out buttons render (header + main); .first() is fine.
  await expect(page.getByRole('button', { name: /sign out/i }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: /my policies/i })).toBeVisible();
}

test.describe('customer portal e2e', () => {
  test('sign in via WSO2 IS, get a quote, bind a policy', async ({ page }) => {
    test.info().annotations.push({ type: 'test-vin', description: VIN });

    await signInViaWSO2IS(page);

    // ---- Quote ----
    await page.goto('/quote');
    await expect(page.getByRole('heading', { name: /get a quote/i })).toBeVisible();

    await page.locator('input[name="vehicleVin"]').fill(VIN);
    await page.locator('input[name="driverAge"]').fill('35');
    await page.locator('select[name="coverageType"]').selectOption('STANDARD');
    await page.getByRole('button', { name: /calculate premium/i }).click();

    // Premium tile renders a $-prefixed number when the quote succeeds.
    const premiumTile = page.locator('text=Your premium').locator('..');
    await expect(premiumTile).toBeVisible({ timeout: 20_000 });
    const premiumText = await premiumTile.locator('p.text-4xl').innerText();
    expect(premiumText).toMatch(/^\$\d+(\.\d+)?$/);
    test.info().annotations.push({
      type: 'premium',
      description: `${VIN} -> ${premiumText}`,
    });

    // ---- Bind ----
    await page.getByRole('link', { name: /accept and bind/i }).click();
    await expect(page).toHaveURL(/\/policies\/bind\?quoteId=\d+/);
    await expect(page.getByRole('heading', { name: /confirm bind/i })).toBeVisible();
    await page.getByRole('button', { name: /bind policy/i }).click();

    // The bind action 303s to /policies/<policyNumber>. Wait for that URL,
    // then assert the policy heading is a POL- code.
    await page.waitForURL(/\/policies\/POL-[A-Z0-9-]+/, { timeout: 30_000 });
    const url = new URL(page.url());
    const policyNumber = url.pathname.split('/').pop()!;
    test.info().annotations.push({ type: 'policy-number', description: policyNumber });

    // The policy number renders both in the breadcrumb and as the H1.
    // Assert on the H1 to avoid strict-mode collisions.
    await expect(page.getByRole('heading', { name: policyNumber })).toBeVisible();
    await expect(page.getByText(/^BOUND$/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('link', { name: /make a payment/i })).toBeVisible();
  });
});
