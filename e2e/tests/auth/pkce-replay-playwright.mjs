// pkce-replay-playwright.mjs — drives the OIDC authorization endpoint
// click-through and captures the `code` query param IS emits to our
// redirect_uri, before the BFF successfully exchanges it.
//
// Capture strategy: listen on `page.on('request')` for any navigation
// whose URL starts with the redirect_uri. The IS 302 to that URL fires
// such a request even if the BFF's response is an error — we read
// `code` from the URL string. The BFF's own attempt to exchange (which
// will fail with "Configuration" error because we didn't go through
// /auth/signin to stash state+verifier) is harmless: the code is still
// fresh because the BFF couldn't exchange it.

import { writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 2) {
    out[argv[i].replace(/^--/, '')] = argv[i + 1];
  }
  return out;
}

async function main() {
  const a = parseArgs(process.argv);
  for (const r of ['authorize-url','redirect-uri','client-id','code-challenge','state','username','password','out']) {
    if (!a[r]) { console.error(`missing --${r}`); process.exit(2); }
  }

  const url = new URL(a['authorize-url']);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', a['client-id']);
  url.searchParams.set('redirect_uri', a['redirect-uri']);
  url.searchParams.set('scope', 'openid');
  url.searchParams.set('state', a.state);
  url.searchParams.set('code_challenge', a['code-challenge']);
  url.searchParams.set('code_challenge_method', 'S256');

  const callbackPrefix = a['redirect-uri'];
  let captured = null;

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: false });
  const page = await ctx.newPage();
  page.on('request', (req) => {
    if (!captured && req.url().startsWith(callbackPrefix)) {
      try { captured = new URL(req.url()).searchParams.get('code'); } catch { /* noop */ }
    }
  });

  await page.goto(url.toString(), { waitUntil: 'domcontentloaded' });

  await page.getByPlaceholder(/enter your username/i).fill(a.username);
  await page.getByPlaceholder(/enter your password/i).fill(a.password);
  await page
    .getByRole('button', { name: /^(sign in|log ?in|continue)$/i })
    .first()
    .click();

  try {
    const approve = page.getByRole('button', { name: /approve|continue|allow|yes/i });
    await approve.waitFor({ timeout: 5_000 });
    await approve.click();
  } catch { /* skipped */ }

  for (let i = 0; i < 60 && !captured; i++) {
    await new Promise((r) => setTimeout(r, 500));
  }
  await browser.close();

  if (!captured) {
    console.error('did not capture an authorization code');
    process.exit(1);
  }
  await writeFile(a.out, captured);
  console.log(`captured code -> ${a.out}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
