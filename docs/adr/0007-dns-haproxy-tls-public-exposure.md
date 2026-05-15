# ADR 0007: DNS, HAProxy and TLS for insurance-app public exposure

- Status: Accepted
- Date: 2026-05-15
- Deciders: ze
- Related: ADR 0005 (system shape — APIM is the designated public surface)

## Context

The insurance-app runs as containers on `insurance-app-vm` (`30.30.26.1`, on the private `br33` bridge). Students and the instructor need to reach the app — and several supporting services (SigNoz UI, WSO2 IS admin, APIM dev portal) — over the public internet from arbitrary networks. The teaching benefit of "real URL, real cert, opens in a normal browser" is high.

The lab already runs `gf-ocp-haproxy-01` (public IP `59.153.29.102`) and `gf-ocp-pdns-01` (public IP `59.153.29.101`) on the same hypervisor (`dl385-2`). Both are dual-homed onto `br33` so they can reach the insurance-app-vm directly. The Cloudflare zone `comptech-lab.com` is in the lab's account.

There are two existing precedents in `comptech-lab.com`:

- **v7 pattern.** NS delegation: `NS v7.comptech-lab.com → ns1.v7 / ns2.v7` (a self-hosted pdns). Used for the OCP cluster's v7 subzone.
- **datastax pattern.** Flat A records in Cloudflare per subdomain, no delegation. Used for the datastax application and its many subdomains.

## Decision

Use the **datastax pattern**. The `insurance-app.comptech-lab.com` subzone is **not delegated**. All records live in Cloudflare.

### Subdomain plan

| Subdomain | Backend (on insurance-app-vm) | When added |
| --- | --- | --- |
| `app.insurance-app.comptech-lab.com` | Liberty 9080 initially; switched to APIM gateway 8243 once APIM lands | from feature 1 |
| `apim.insurance-app.comptech-lab.com` | APIM publisher/portal 9443 (container internal) | when APIM lands (feature 6+) |
| `is.insurance-app.comptech-lab.com` | WSO2 IS console 9443 (container internal) | when WSO2 IS lands (feature 5) |
| `mi.insurance-app.comptech-lab.com` | WSO2 MI management 8290 | already on the VM |
| `signoz.insurance-app.comptech-lab.com` | SigNoz UI 3301 | when SigNoz lands (early — observability module) |

Records are added lazily — only when the corresponding backend container exists. No DNS pointing at dead addresses.

All A records point to `gf-ocp-haproxy-01`'s public IP `59.153.29.102`. Records are **not** Cloudflare-proxied (orange-cloud off) — students should hit the lab's HAProxy directly so they can see latency, traces, and TLS handshakes without Cloudflare's CDN in the way.

### TLS

A single Let's Encrypt **wildcard certificate** for `*.insurance-app.comptech-lab.com`, issued via DNS-01 challenge using the Cloudflare API. Acme.sh runs on `gf-ocp-haproxy-01` (or wherever has both the Cloudflare token and the haproxy.cfg). Auto-renewal via cron / systemd timer, typically every 60 days.

HAProxy terminates TLS on port 443. Traffic between HAProxy and the backends on `insurance-app-vm` is plain HTTP over `br33` — an internal private bridge. Documented as a teaching-environment simplification; not acceptable in a multi-tenant production setting.

### HAProxy routing

A new frontend on port 443 of `gf-ocp-haproxy-01`, SNI-based routing by Host header to per-subdomain backends. The existing OCP frontends are untouched; this is purely additive.

A port 80 frontend redirects to 443 for the same hostnames so curl-without-https Just Works.

### Operational

- **Cloudflare token** lives at `/Users/ze/Desktop/mysecrets/cloud-flare-token` on the laptop and (scoped to `comptech-lab.com:DNS:Edit`) at `/root/.cloudflare-token` on `gf-ocp-haproxy-01` for acme.sh.
- **Cert path:** `/etc/haproxy/certs/insurance-app.pem` (combined fullchain + privkey, HAProxy convention).
- **Renewal:** acme.sh built-in cron; failures alert via syslog.
- **No tour subdomain.** `/tour` is a route under `app.insurance-app.comptech-lab.com` — same Liberty backend.

## Consequences

Positive:
- Adding a new public service = one Cloudflare API call + one HAProxy backend stanza. Roughly 30 seconds.
- Wildcard cert means no per-subdomain cert chore.
- The existing OCP HAProxy keeps its role; the change is additive.
- Students get clean URLs and a real cert — no scary browser warnings during demos.
- DNS is in one place (Cloudflare); pdns stays out of this app's path.

Negative / accepted trade-offs:
- Single point of dependency on Cloudflare for both DNS and ACME challenges.
- HAProxy to backend is unencrypted on `br33`. Acceptable on a private bridge for teaching; would not be in a multi-tenant production setting.
- All public services for this app share one HAProxy instance — capacity is shared with OCP traffic, though for a teaching demo the volume is trivial.
- Diverges from the v7 NS-delegation pattern; future readers should not assume the OCP convention applies here.

## Alternatives considered

- **NS delegation to gf-ocp-pdns-01 (v7 pattern).** Matches the OCP convention but adds pdns zone configuration, pdns API access for the ACME flow, and the CNAME-challenge-delegation trick for the wildcard cert. For a teaching demo, that complexity teaches about itself, not about the app.
- **Cloudflare-proxied (orange-cloud) records.** Cloudflare terminates TLS and caches responses. Rejected: caching can mask app behaviour we want students to see (cache hits, observability traces, real latency).
- **Per-subdomain certs instead of wildcard.** More Let's Encrypt requests, more renewals, no benefit.

## Revisit when

- A second teaching VM exists (per-student environments) — pdns delegation likely earns its keep at that point.
- `gf-ocp-haproxy-01` becomes a bottleneck — split out a dedicated HAProxy for teaching traffic.
- Internal-only services need to be reachable from the lab network but not the public internet — at that point either a separate HAProxy on `br33` or IP allowlisting on the existing one.
