// session-fixation-playwright.mjs — drives the OIDC click-through using
// a *prepopulated* cookie jar so the SID we capture pre-login is the
// same one Playwright carries into the WSO2 IS dance. After the flow,
// we dump the resulting agent_sid back to the same jar file so the
// shell script can compare PRE vs POST.
//
// Imports: only @playwright/test (already a peer of the e2e package)
// and node built-ins.
//
// Usage:
//   node session-fixation-playwright.mjs \
//     --jar <cookies.txt> \
//     --base-url https://agent... \
//     --username student@comptech.com \
//     --password Student@1234

import { readFile, writeFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 2) {
    const k = argv[i].replace(/^--/, '');
    out[k] = argv[i + 1];
  }
  return out;
}

// Netscape cookie-jar parser (just the fields we need).
async function loadJar(path, defaultDomain) {
  const text = await readFile(path, 'utf8').catch(() => '');
  const cookies = [];
  for (const line of text.split('\n')) {
    if (!line || line.startsWith('#')) continue;
    // Netscape format: domain  flag  path  secure  expiration  name  value
    const parts = line.split(/\t/);
    if (parts.length < 7) continue;
    const [domain, _flag, path, secureStr, expiry, name, value] = parts;
    cookies.push({
      name,
      value,
      domain: domain.replace(/^#HttpOnly_/, '').replace(/^\./, ''),
      path,
      expires: Number(expiry) || -1,
      httpOnly: domain.startsWith('#HttpOnly_'),
      secure: secureStr === 'TRUE',
      sameSite: 'Lax',
    });
  }
  return cookies;
}

async function saveJar(path, cookies, domain) {
  let out = '# Netscape HTTP Cookie File\n';
  for (const c of cookies) {
    const d = c.domain || domain;
    const flag = d.startsWith('.') ? 'TRUE' : 'FALSE';
    const secure = c.secure ? 'TRUE' : 'FALSE';
    const expiry = Math.floor(c.expires) > 0 ? Math.floor(c.expires) : 0;
    const hostHeader = c.httpOnly ? `#HttpOnly_${d}` : d;
    out += `${hostHeader}\t${flag}\t${c.path}\t${secure}\t${expiry}\t${c.name}\t${c.value}\n`;
  }
  await writeFile(path, out);
}

async function main() {
  const args = parseArgs(process.argv);
  const baseURL = args['base-url'];
  const jarPath = args.jar;
  const username = args.username;
  const password = args.password;
  if (!baseURL || !jarPath || !username || !password) {
    console.error('Usage: --jar <path> --base-url <url> --username <u> --password <p>');
    process.exit(2);
  }
  const host = new URL(baseURL).host;
  const seed = await loadJar(jarPath, host);

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: false });
  if (seed.length) await ctx.addCookies(seed);
  const page = await ctx.newPage();

  // Drive the same click-through customer.spec.ts uses, against the
  // agent-app login page.
  await page.goto(`${baseURL}/login`);
  // Agent-app /login renders a "Sign in with WSO2 IS" button that
  // POSTs to /auth/signin. The button labels vary; broaden the match.
  const signIn = page.getByRole('button', { name: /sign in/i }).first();
  await signIn.click();

  await page.waitForURL(/\/authenticationendpoint\/login\.do|\/oauth2\/authorize/i, { timeout: 30_000 });

  await page.getByPlaceholder(/enter your username/i).fill(username);
  await page.getByPlaceholder(/enter your password/i).fill(password);
  await page
    .getByRole('button', { name: /^(sign in|log ?in|continue)$/i })
    .first()
    .click();

  // Optional consent screen.
  try {
    const approve = page.getByRole('button', { name: /approve|continue|allow|yes/i });
    await approve.waitFor({ timeout: 5_000 });
    await approve.click();
  } catch { /* consent skipped */ }

  // Wait until we're back on the agent origin.
  await page.waitForURL(new RegExp(`${host.replace('.', '\\.')}/`), { timeout: 30_000 });
  await page.waitForLoadState('networkidle');

  const finalCookies = await ctx.cookies(baseURL);
  await saveJar(jarPath, finalCookies, host);
  await browser.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
