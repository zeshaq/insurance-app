# JWT signing-key rotation (WSO2 IS + Liberty mpJwt)

Issue #23 / Phase 4 — Deeper Security.

This runbook covers rotating the RSA key WSO2 IS uses to sign access tokens, with zero-downtime validation on the Liberty side. It assumes the topology described in `docs/adr/0005-system-shape-apis-topics-redis-mi-apim.md`:

* **Issuer:** WSO2 IS at `https://is.insurance-app.comptech-lab.com` (admin console at `/console`, admin/admin in staging).
* **JWKS endpoint:** `https://is.insurance-app.comptech-lab.com/oauth2/jwks`. As of 2026-05-17 it publishes **two** keys (kids `OWRi…` and `Y2Y2…`), both `RS256` with `use: sig`. JWKS-with-multiple-keys is the safety net that lets old tokens keep validating during the grace window.
* **Validator:** Liberty `mpJwt-2.1` in `src/main/liberty/config/server.xml` with `jwksUri="http://wso2is:9763/oauth2/jwks"` (internal HTTP, no TLS hop).

## Concepts

1. **kid header.** Every IS-minted JWT carries a `kid` claim in its header. Liberty resolves `kid` → public key by reading the JWKS document and pinning to the matching entry. As long as the old kid is **still in the JWKS document**, tokens signed by that kid keep validating, even if IS has stopped issuing new tokens with it.
2. **JWKS cache.** Liberty fetches the JWKS lazily and caches it. The Open Liberty default for `mpJwt-2.1` is a 10-minute soft TTL; on cache miss for a given `kid`, Liberty refetches immediately. So in practice the cache is invalidated within seconds of an unknown-kid token arriving, *as long as IS publishes the new kid at JWKS before tokens signed with it land at Liberty*.
3. **Grace window.** Between "IS starts signing with NEW key" and "IS removes the OLD key from JWKS", both old and new tokens validate. Make this window ≥ the longest token lifetime in flight; today access tokens are 1h and refreshes are 24h, so a 25-hour grace is safe.

## Pre-flight

```bash
# From the VM. Confirm what's in JWKS today.
curl -s https://is.insurance-app.comptech-lab.com/oauth2/jwks \
  | python3 -c 'import sys, json; d=json.load(sys.stdin); print(f"keys: {len(d[\"keys\"])}"); [print(k["kid"][:48], k["alg"], k["use"]) for k in d["keys"]]'
# Expected today: 2 keys, both RS256/sig.
```

Take a snapshot of the JWKS so we can compare after rotation:

```bash
curl -s https://is.insurance-app.comptech-lab.com/oauth2/jwks > /tmp/jwks.before.json
```

Confirm the smoke is green so any post-rotation breakage is attributable to the rotation, not something we walked in carrying:

```bash
bash ~/insurance-app/scripts/smoke.sh | tail -3
# expect: pass=205 fail=0
```

## Procedure

### 1. Schedule the grace window

Pick a 25-hour window. During this window both the **old** and **new** signing keys must remain in JWKS. After it closes, the old key is removed and only the new key is trusted.

### 2. Generate the new key in the IS console

1. Sign in to `https://is.insurance-app.comptech-lab.com/console` as `admin/admin`.
2. Navigate to **Console → Manage → Keystores** (path may be `Configurations → Key Stores` depending on IS minor version).
3. Click the keystore IS is using to sign tokens (default in 7.x: `wso2carbon.jks`).
4. **Add Certificate** → **Generate**. Choose alias `wso2is-2026-05` (YYYY-MM convention so the rotation date is visible from the kid). Key size 2048 RSA. Validity 365d.
5. **Add** the new alias to the JWKS-publishing set. In IS 7.x this lives at **Identity Providers → Resident → Inbound Authentication → OAuth/OIDC → "JWKS endpoint" pane**; tick the new alias so it joins the existing one in the JWKS response.

Verify both keys are now in JWKS:

```bash
curl -s https://is.insurance-app.comptech-lab.com/oauth2/jwks \
  | python3 -c 'import sys, json; d=json.load(sys.stdin); print(f"keys: {len(d[\"keys\"])}"); [print(k["kid"][:48]) for k in d["keys"]]'
# Expected: 3 keys now (old 2 + new 1). Liberty will pick the right one
# per-token via the kid header.
```

### 3. Promote the new key to "active signer"

In the same OAuth/OIDC pane, change the **token signing key alias** from the old `wso2carbon` to `wso2is-2026-05`. Apply.

From this point, every freshly-minted access token / id_token carries the new `kid`. Tokens issued seconds earlier still carry the old `kid` and continue to validate against the still-published old key.

### 4. Verify Liberty validates both

```bash
# Mint a fresh JWT via the service account — uses the NEW key.
source ~/insurance-app/.wso2is-creds
NEW_AT=$(curl -k -sS -X POST -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
  "https://is.insurance-app.comptech-lab.com/oauth2/token" \
  -d "grant_type=client_credentials" | jq -r .access_token)

# Print its kid:
python3 -c "import sys, base64, json; h=sys.argv[1].split('.')[0]; h+='='*((4-len(h)%4)%4); print(json.loads(base64.urlsafe_b64decode(h))['kid'][:48])" "$NEW_AT"
# -> should be the NEW kid (wso2is-2026-05 -> base64 -> "MzZi…")

# Validate it through Liberty (a 200 means mpJwt accepted the signature):
curl -sS -H "Authorization: Bearer $NEW_AT" \
  https://my.insurance-app.comptech-lab.com/api/quotes \
  -w "\nHTTP %{http_code}\n" | tail -3
```

If you have an old token lying around (e.g. one you minted 30 minutes ago and stashed), repeat the curl with it — it must also come back 200/401-with-mpJwt-recognising-the-kid. *Both kids resolve.*

### 5. Hold the grace window

Wait at least 25 hours. During this window, run `scripts/smoke.sh` at least every 6 hours to confirm nothing is breaking.

### 6. Remove the old key from JWKS

In the IS console, untick the old alias from the JWKS-publishing set so the JWKS document only includes the new key.

```bash
curl -s https://is.insurance-app.comptech-lab.com/oauth2/jwks \
  | python3 -c 'import sys, json; d=json.load(sys.stdin); print(f"keys: {len(d[\"keys\"])}"); [print(k["kid"][:48]) for k in d["keys"]]'
# Expected: 1 key (just the new one).
```

Liberty will refetch the JWKS within its TTL (default 10 minutes). After that, any token signed by the now-removed kid will fail validation. Verify by minting a fresh token (signed by the new key) and confirming it still works:

```bash
NEW_AT=$(curl -k -sS -X POST -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
  "https://is.insurance-app.comptech-lab.com/oauth2/token" \
  -d "grant_type=client_credentials" | jq -r .access_token)
curl -sS -H "Authorization: Bearer $NEW_AT" \
  https://my.insurance-app.comptech-lab.com/api/quotes \
  -w "\nHTTP %{http_code}\n" | tail -1
# expect HTTP 200
```

### 7. Forced cache flush (if needed)

Liberty's mpJwt JWKS cache TTL is set by the Open Liberty default. To shorten or lengthen it, add the following to `<mpJwt>` in `src/main/liberty/config/server.xml`:

```xml
<mpJwt id="defaultMpJwt"
       jwksUri="http://wso2is:9763/oauth2/jwks"
       issuer="https://is.insurance-app.comptech-lab.com/oauth2/token"
       userNameAttribute="sub"
       groupNameAttribute="aut"
       ignoreApplicationAuthMethod="false"
       mapToUserRegistry="false"
       <!-- Refresh JWKS at most every 60s. Tighten for faster rotation
            response; loosen to reduce load on IS. -->
       jwksUriCacheRefreshInterval="60s"
/>
```

To force an immediate flush without restarting Liberty, restart just the `insurance-app` container — the next request fetches a fresh JWKS:

```bash
podman restart insurance-app
```

## Verification at every stage

The companion script `e2e/tests/auth/jwt-rotation-dryrun.sh` exercises the steady-state behavior end-to-end (mint → validate → wait → re-mint → validate). Run it before, during the grace window, and after key removal to confirm Liberty is healthy at each step.

```bash
bash ~/insurance-app/e2e/tests/auth/jwt-rotation-dryrun.sh
```

## Rollback

If something breaks during step 3 (new key promoted but Liberty starts 401-ing):

1. In the IS console, change the active signing alias **back** to the old one. New tokens revert.
2. Old key is still in JWKS (you haven't removed it yet), so old tokens still validate.
3. Investigate Liberty logs — usually a clock-skew issue, not a key issue:

```bash
podman logs insurance-app --since 10m | grep -iE "mpjwt|jwt|cwwks|cwiml"
```

If the JWKS cache is stuck on a stale view (you removed the old key in step 6 but old tokens are still in flight), `podman restart insurance-app` forces a fresh JWKS fetch.

## When to rotate

* **Calendar:** at least every 12 months — the JWKS at staging shows certs with 2024-05-12 → 2024-08-12 validity, which is past expiry already; this runbook is part of bringing that current.
* **On incident:** if a key is ever exposed (leaked from a backup, exfiltrated from the IS host filesystem, etc.), rotate **immediately** and shorten the grace window to the shortest token lifetime in production.
* **On staff change:** when an admin with IS console access leaves the team.

## Drill schedule

Per Phase 4 acceptance criteria, the rotation drill is executed at least once before go-live, with the post-rotation `jwt-rotation-dryrun.sh` run going green at every checkpoint. Log the dates of each drill in `docs/security-baseline.md` under "Key rotation history".
