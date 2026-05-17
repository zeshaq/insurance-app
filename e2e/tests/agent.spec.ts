import { test, expect, type Page } from '@playwright/test';

/**
 * Agent dashboard end-to-end (issue #15).
 *
 * Drives sign-in through WSO2 IS, navigates to the Claims queue with the
 * FILED filter, clicks Approve on the first FILED claim, and asserts that
 * the row's status flips to APPROVED (or that the row drops out of the
 * FILED-filtered view — the UI invalidates the claims query on success,
 * so re-rendering with FILED selected will hide it).
 */

const USERNAME = process.env.PORTAL_TEST_USER ?? 'student@comptech.com';
const PASSWORD = process.env.PORTAL_TEST_PASSWORD ?? 'Student@1234';

async function signInViaWSO2IS(page: Page): Promise<void> {
  await page.goto('/');
  // App.tsx redirects to /login if no session. Wait for that to settle.
  await page.waitForURL(/\/login$/, { timeout: 15_000 });

  await page.getByRole('button', { name: /sign in with wso2 is/i }).click();

  await page.waitForURL(/\/authenticationendpoint\/login\.do|\/oauth2\/authorize/i, {
    timeout: 30_000,
  });

  // IS hides input[name="username"]; target the visible textbox by
  // placeholder. Update if your IS tenant uses a custom template.
  const usernameField = page.getByPlaceholder(/enter your username/i);
  const passwordField = page.getByPlaceholder(/enter your password/i);
  await expect(usernameField).toBeVisible({ timeout: 15_000 });
  await usernameField.fill(USERNAME);
  await passwordField.fill(PASSWORD);
  await page
    .getByRole('button', { name: /^(sign in|log ?in|continue)$/i })
    .first()
    .click();

  try {
    const approve = page.getByRole('button', { name: /approve|continue|allow|yes/i });
    await approve.waitFor({ timeout: 5_000 });
    await approve.click();
  } catch {
    // No consent surface for this user/client — fall through.
  }

  // Back on the agent dashboard origin, App.tsx will gate again on a
  // fresh /auth/session probe.
  await page.waitForURL(/agent\.insurance-app\.comptech-lab\.com\//, { timeout: 30_000 });
  // The header only renders Dashboard/Claims/Policies links after the
  // session probe resolves; once those are present we know we're past
  // the gate. The header's user pill may show id-only if the JWT lacks
  // email/name claims, so assert on stable nav anchors instead.
  await expect(page.getByRole('link', { name: /^dashboard$/i })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('link', { name: /^claims$/i })).toBeVisible();
  await expect(page.getByRole('button', { name: /sign out/i })).toBeVisible();
}

test.describe('agent dashboard e2e', () => {
  test('sign in, filter to FILED, approve a claim, verify APPROVED', async ({ page }) => {
    await signInViaWSO2IS(page);

    // Navigate to the Claims queue. Filter defaults to FILED in the UI,
    // but we click it explicitly so the test is robust to default changes.
    await page.getByRole('link', { name: /^claims$/i }).click();
    await expect(page).toHaveURL(/\/claims$/);
    await expect(page.getByRole('heading', { name: /claims queue/i })).toBeVisible();

    // Click the FILED filter pill.
    await page.getByRole('button', { name: /^FILED$/ }).click();

    // Wait for the table to render. If there are no FILED claims in
    // staging, this test cannot meaningfully run — bail with a clear
    // message rather than a confusing assertion failure.
    const tableBody = page.locator('table tbody');
    await expect(tableBody).toBeVisible({ timeout: 15_000 });

    const approveButtons = page.getByRole('button', { name: /^approve$/i });
    const approveCount = await approveButtons.count();
    test.skip(
      approveCount === 0,
      'No FILED claims available in staging; nothing to approve. Run the customer file-claim flow first to seed one.',
    );

    // Grab the claim id from the first row so we can verify the
    // transition from the API perspective if needed.
    const firstRow = page.locator('table tbody tr').first();
    const claimIdCell = firstRow.locator('td').first();
    const claimId = (await claimIdCell.innerText()).trim();
    test.info().annotations.push({ type: 'claim-id', description: claimId });

    await approveButtons.first().click();

    // The mutation invalidates the claims query and re-renders. Under the
    // FILED filter, the just-approved row should disappear. Verify by
    // confirming approveCount decreased (or by switching filter to ALL
    // and asserting the row now shows APPROVED).
    await expect(approveButtons).toHaveCount(approveCount - 1, { timeout: 30_000 });

    // Switch to ALL to confirm the row is now APPROVED. This makes the
    // success assertion explicit and protects against false positives
    // from a row simply vanishing for an unrelated reason.
    await page.getByRole('button', { name: /^ALL$/ }).click();
    const approvedRow = page
      .locator('table tbody tr', { hasText: claimId.replace(/^#/, '') })
      .first();
    await expect(approvedRow).toBeVisible({ timeout: 15_000 });
    await expect(approvedRow.getByText(/^APPROVED$/)).toBeVisible({ timeout: 15_000 });
  });
});
