# Setting up the insurance-app environment

This is the **runbook** for getting from "blank Ubuntu 24.04 VM" to "all 144 smoke + e2e tests green." Follow it top to bottom; if something fails at step N, see the **Troubleshooting** section at the bottom before retrying.

The "why" of each decision is captured in ADRs under `docs/adr/`. This file is the imperative companion — commands to run, things to verify, gotchas to avoid.

---

## What you need before you start

- A **Linux host with KVM/libvirt** (Ubuntu 22.04+ recommended), 32 GiB RAM and 300 GB free disk to spare for one VM.
- A **bridge interface** the VM will sit on. Throughout this guide we call it `br0`; substitute your own bridge name.
- **SSH access from your laptop** to the hypervisor host (`ssh ze@<hypervisor>` should work).
- A **GitHub account** with SSH access set up (you'll push from the VM later).
- *Optional, only for public HTTPS exposure*: a **Cloudflare-managed domain** and an API token scoped to `Zone:DNS:Edit`.

This guide assumes Ubuntu 24.04 on the VM. Other distros work but the `apt` commands change.

---

## Phase 0 — Already have a working insurance-app-vm? Skip ahead.

If you're using the lab's existing `insurance-app-vm` (30.30.26.1) and just need to bring its services back up after a reboot:

```bash
ssh -J ze@dl385-2 ze@30.30.26.1
cd ~/insurance-app/compose && COMPOSE_PROFILES=all podman-compose up -d
cd ~/signoz/deploy/docker && podman-compose up -d
podman start insurance-app insurance-mi 2>/dev/null
./scripts/smoke.sh                                   # ensure 144/0 green
```

If that's all you needed, you're done. Otherwise continue with Phase 1 to provision a fresh VM.

---

## Phase 1 — Provision the VM (cloud-init + virt-install)

On the hypervisor, install KVM/libvirt and cloud-image tooling:

```bash
sudo apt-get install -y qemu-kvm libvirt-daemon-system libvirt-clients virt-install cloud-image-utils
```

Pull the Ubuntu 24.04 cloud image, resize a copy for this VM:

```bash
cd /var/lib/libvirt/images
# Re-runnable: only fetch if the base image is missing.
[ -f noble-server-cloudimg-amd64.img ] || \
  sudo wget https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img
# Re-runnable: only copy + resize if the per-VM disk doesn't exist yet.
if [ ! -f insurance-app-vm.qcow2 ]; then
  sudo cp --reflink=auto noble-server-cloudimg-amd64.img insurance-app-vm.qcow2
  sudo qemu-img resize insurance-app-vm.qcow2 300G
fi
```

Write `/tmp/user-data` (paste **your** laptop's `~/.ssh/id_ed25519.pub` into `ssh_authorized_keys`):

```yaml
#cloud-config
hostname: insurance-app-vm
fqdn: insurance-app-vm
manage_etc_hosts: true
users:
  - name: ze
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    lock_passwd: true
    ssh_authorized_keys:
      - ssh-ed25519 AAAA... your-laptop-public-key
ssh_pwauth: false
disable_root: true
package_update: true
growpart:
  mode: auto
  devices: ['/']
resize_rootfs: true
```

Write `/tmp/network-config` (adjust `addresses` + `routes` for your bridge):

```yaml
version: 2
ethernets:
  primary:
    match:
      name: "en*"
    dhcp4: false
    addresses:
      - 10.10.20.10/24
    routes:
      - to: default
        via: 10.10.20.1
    nameservers:
      addresses: [8.8.8.8, 1.1.1.1]
```

Build the seed ISO and provision:

```bash
sudo cloud-localds -N /tmp/network-config \
  /var/lib/libvirt/images/insurance-app-vm-seed.iso /tmp/user-data

# Re-runnable: skip if the domain already exists.
if ! sudo virsh dominfo insurance-app-vm >/dev/null 2>&1; then
  sudo virt-install \
    --name insurance-app-vm \
    --memory 32768 --vcpus 16 --cpu host-passthrough \
    --os-variant ubuntu24.04 \
    --disk path=/var/lib/libvirt/images/insurance-app-vm.qcow2,format=qcow2,bus=virtio \
    --disk path=/var/lib/libvirt/images/insurance-app-vm-seed.iso,device=cdrom \
    --network bridge=br0,model=virtio \
    --graphics none --console pty,target_type=serial \
    --import --noautoconsole
else
  echo "insurance-app-vm already exists — skipping virt-install"
fi
```

**Verify**: roughly 60 seconds after `virt-install`, `sudo virsh dominfo insurance-app-vm` reports `State: running` and you can `ping 10.10.20.10` from the hypervisor.

On your **laptop**, add to `~/.ssh/config`:

```
Host insurance-app-vm
  HostName 10.10.20.10
  User ze
  ProxyJump ze@<hypervisor>
```

**Verify**: `ssh insurance-app-vm 'hostname'` returns `insurance-app-vm`.

---

## Phase 2 — Install the toolchain on the VM

All subsequent steps run **on the VM**. SSH in.

```bash
sudo DEBIAN_FRONTEND=noninteractive apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-21-jdk maven podman buildah podman-compose \
  uidmap slirp4netns fuse-overlayfs ca-certificates curl git jq
```

Enable systemd-logind lingering so rootless containers survive SSH disconnects:

```bash
sudo loginctl enable-linger ze
loginctl show-user ze | grep Linger      # must say Linger=yes
```

**Verify**:

```bash
java -version            # 21.x
mvn -v                   # 3.8.x or 3.9.x
podman --version         # 4.x or 5.x
podman-compose --version # 1.0.6 or newer
```

---

## Phase 3 — Clone the repo

On the VM:

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
git config --global init.defaultBranch main

# Generate an SSH key for GitHub access (the VM pushes from here per ADR 0002).
# Re-runnable: only generate if no key exists.
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N "" -C "ze@insurance-app-vm" -f ~/.ssh/id_ed25519
cat ~/.ssh/id_ed25519.pub
# Add the above output to https://github.com/settings/keys (or as a deploy key on the repo)

ssh-keyscan -t ed25519,rsa github.com >> ~/.ssh/known_hosts 2>/dev/null
ssh -T git@github.com    # "Hi <handle>!"

cd ~
# Re-runnable: clone only on first run.
[ -d insurance-app ] || git clone git@github.com:<your-handle>/insurance-app.git
cd insurance-app
```

---

## Phase 4 — Build and run the application containers

Create the shared podman network and generate the mTLS demo certs that
slice 10's partner-mock + Liberty mount in. Both are safely re-runnable:

```bash
podman network create insurance-net 2>/dev/null || true
./scripts/gen-certs.sh        # writes compose/certs/{ca,server,client}.* + JKS stores
```

Build Liberty (the four load-bearing pins are already in the pom + Containerfile; see **build_gotchas.md** memory for the why):

```bash
cd ~/insurance-app
./scripts/build.sh                                            # mvn clean package + podman build
```

> **Why a wrapper instead of `mvn package && podman build`?** Two reasons baked into one script:
> 1. **`mvn clean package`, not `mvn package`.** Without `clean`, deleting a Java source file leaves the stale `.class` in `target/classes/` and it gets bundled into the next WAR — Liberty then tries to wire ghost classes and fails with errors like "SubscriberMethod ... has no upstream". The wrapper makes the clean unmissable.
> 2. **`--network=host` on `podman build`.** Rootless podman's default build network can't reach Maven Central / `icr.io` reliably on this VM (IPv6-only DNS in slirp4netns). Without this flag, `RUN configure.sh` inside the image fails.
>
> Run the underlying commands directly if you want; `build.sh` just guarantees you don't skip the clean.

Build the MI image:

```bash
podman build --network=host -t insurance-mi:dev -f mi/Containerfile mi/
```

Run both:

```bash
# WSO2IS_CLIENT_ID / SECRET are env vars the GUI's /api/auth/token endpoint
# uses to mint dev JWTs server-side. Without them, the GUI's bootstrap call
# returns 500 and every authed POST fails. Source the file Phase 6.5
# created earlier — it has both vars set.
source $HOME/insurance-app/.wso2is-creds
podman run -d --replace --name insurance-app --network insurance-net \
  -v $HOME/insurance-app/compose/certs:/config/partner-certs:ro \
  -e WSO2IS_CLIENT_ID=$WSO2IS_CLIENT_ID -e WSO2IS_CLIENT_SECRET=$WSO2IS_CLIENT_SECRET \
  -p 9080:9080 -p 9443:9443 insurance-app:dev

podman run -d --replace --name insurance-mi --network insurance-net \
  -p 8290:8290 -p 8253:8253 insurance-mi:dev
```

> The Liberty image has `OTEL_*` env vars baked in (see `Containerfile`) — once SigNoz is up (Phase 6), Liberty automatically exports traces, metrics, and logs to `signoz-otel-collector:4317`. No extra flags on `podman run` needed. Override at run time with `-e OTEL_EXPORTER_OTLP_ENDPOINT=...` if you point at a different collector.

**Verify** Liberty + MI:

```bash
curl http://localhost:9080/api/ping           # {"status":"ok"}
curl http://localhost:8290/insurance/ping     # {"status":"ok"}  (proxied via MI)
```

If either fails, see **Troubleshooting → Liberty / MI** below.

---

## Phase 5 — Bring up the infrastructure stack

```bash
cd ~/insurance-app/compose
COMPOSE_PROFILES=all podman-compose up -d
```

> **Why `COMPOSE_PROFILES=all` not `--profile all`?** podman-compose 1.0.6 (Ubuntu 24.04's apt package) supports compose profiles only via the env var. The `--profile` flag silently misparses as a positional argument.

First run pulls ~10 GB of images (Postgres, Redis, Kafka, MinIO, OpenSearch, WSO2 IS+APIM, etc.) — expect 5-10 minutes. The `kafka-init` one-shot creates the 7 ADR-0005 topics + slice-7's `payment-dlq` and exits.

**Verify** the 16 infra containers + the 2 app containers = 18 running (slice 10's `partner-mock` is included in the compose count). `kafka-init` is a one-shot that exits, so it won't appear in `podman ps`.

```bash
podman ps --format "{{.Names}}" | sort | wc -l                # 18
podman ps --format "{{.Names}}" | sort                         # see the full list
```

If the list shows fewer than 18, run `podman ps -a` to find the failed ones, then `podman logs <name>` to investigate.

---

## Phase 6 — Bring up SigNoz (separate compose)

SigNoz isn't in the main compose because its stack moves quickly (~7 containers; we let upstream own the topology).

```bash
cd ~
# Re-runnable: clone signoz only on first pass.
[ -d signoz ] || git clone https://github.com/SigNoz/signoz.git
cd signoz
git checkout v0.124.0    # pin a release; main is dev-volatile

cd deploy/docker
# Re-runnable: take a pristine copy ONCE; subsequent runs replay sed against
# the pristine version so we don't double-edit.
[ -f docker-compose.yaml.bak ] || cp docker-compose.yaml docker-compose.yaml.bak
cp docker-compose.yaml.bak docker-compose.yaml

# Attach all SigNoz containers to our shared insurance-net so Liberty can reach
# them by container name (otherwise they sit on their own bridge).
sed -i 's|    name: signoz-net$|    name: insurance-net\n    external: true|' docker-compose.yaml

# Prefix short image names with docker.io/ — podman refuses unqualified names
# without a registries.conf entry.
sed -i -E "/^\s+image:\s/ { /image:\s+docker\.io\//b; /image:\s+quay\.io\//b; /image:\s+ghcr\.io\//b; s|(image:\s+)([^/[:space:]]+)/|\1docker.io/\2/| }" docker-compose.yaml

podman-compose up -d
```

Wait for the `signoz` container to report **healthy** (~60-90 seconds):

```bash
while ! podman inspect signoz --format '{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; do sleep 5; done
echo "signoz healthy"
```

### Critical: create the first SigNoz user

**Without this step, SigNoz's OTel collector won't open ports 4317/4318** and Liberty's telemetry won't flow. The error you'd see in `podman logs signoz-otel-collector` is `cannot create agent without orgId` repeating every 30 seconds.

This must run BEFORE Phase 7, so default to localhost; switch to the public URL after Phase 7 establishes it.

```bash
curl -sS -X POST http://localhost:8080/api/v1/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Admin","email":"admin@insurance-app.local","password":"InsuranceLab123!","orgName":"insurance-app"}'
```

`scripts/signoz-init.sh` automates this with idempotent "user already exists" handling.

After this, total running containers = **18 from Phase 5 + 4 SigNoz containers (signoz, signoz-clickhouse, signoz-zookeeper-1, signoz-otel-collector) = 22**.

The response should be a JSON object with a `data.orgId` field. Within ~30 seconds of this, `signoz-otel-collector` opens 4317 (gRPC) and 4318 (HTTP).

**Verify**:

```bash
ss -tln | grep -E ":(4317|4318)\b"            # both ports listening on the host
podman exec insurance-app bash -c "echo > /dev/tcp/signoz-otel-collector/4317 && echo OK"
```

---

## Phase 6.5 — Register a WSO2 IS OAuth client (CRITICAL for Phase 8 smoke)

Without this step, every `POST` smoke check returns 401 because Liberty has no JWT to validate. The compose stack just gave you a clean WSO2 IS at `https://localhost:9444` — register a client_credentials app there, write the credentials to `~/insurance-app/.wso2is-creds` (gitignored), and the smoke will pick them up.

```bash
# Wait for WSO2 IS to finish booting (it's the slowest container — ~60-90s).
until curl -k -sS -o /dev/null -w '%{http_code}' \
  https://localhost:9444/oauth2/token/.well-known/openid-configuration \
  | grep -q '^200$'; do sleep 5; done
echo "wso2is ready"

# Register via the DCR endpoint. Note the path: oauth2/dcr (slash, not dash).
# ext_token_type=JWT is non-negotiable — without it IS issues opaque UUID
# tokens and Liberty's mpJwt rejects them silently. (build_gotchas 19, 20)
DCR=$(curl -k -sS -X POST -u admin:admin \
  https://localhost:9444/api/identity/oauth2/dcr/v1.1/register \
  -H 'Content-Type: application/json' \
  -d '{"client_name":"insurance-app","grant_types":["client_credentials"],"ext_token_type":"JWT"}')
echo "$DCR" | jq

CID=$(echo "$DCR" | jq -r .client_id)
SEC=$(echo "$DCR" | jq -r .client_secret)
cat > ~/insurance-app/.wso2is-creds <<EOF
WSO2IS_CLIENT_ID=$CID
WSO2IS_CLIENT_SECRET=$SEC
WSO2IS_TOKEN_URL=https://localhost:9444/oauth2/token
WSO2IS_INTERNAL_ISSUER=https://is.insurance-app.comptech-lab.com/oauth2/token
WSO2IS_JWKS_URL=https://is.insurance-app.comptech-lab.com/oauth2/jwks
EOF
```

The basicRegistry user in `server.xml` is set to the original DCR client_id; if you regenerate the client the server.xml entry must follow. (See `build_gotchas.md` item 13 for why the basicRegistry has to match the JWT `sub` claim.)

**Verify**: mint a token + decode the iss claim:

```bash
. ~/insurance-app/.wso2is-creds
AT=$(curl -k -sS -X POST -u "$WSO2IS_CLIENT_ID:$WSO2IS_CLIENT_SECRET" \
       "$WSO2IS_TOKEN_URL" -d "grant_type=client_credentials" \
     | jq -r .access_token)
echo "$AT" | cut -d. -f2 | tr "_-" "/+" | base64 -d 2>/dev/null | jq '{iss, sub, aut}'
# Expect: aut="APPLICATION", iss="https://is.insurance-app.comptech-lab.com/oauth2/token"
```

---

## Phase 7 — Public HTTPS exposure (optional, lab-specific)

This phase is for getting `https://app.insurance-app.comptech-lab.com/...` and the other 9 admin-UI subdomains working through Cloudflare + HAProxy. Skip if you only need the env reachable from inside the lab.

**Requires**: a Cloudflare API token at `~/cloudflare-token` (scope: `Zone:DNS:Edit` on your zone), and SSH access to a reverse-proxy host (`gf-ocp-haproxy-01` in the lab).

1. **DNS** (from anywhere, with the token):

   Each DNS record is upserted: list existing records by name, PUT if present,
   POST if absent. Re-running the loop is safe — no duplicate records.

   ```bash
   TOKEN=$(cat ~/cloudflare-token)
   ZONE_ID=<your-zone-id>
   HAPROXY_IP=<haproxy public IP>
   for sub in app signoz minio kafka mail search is apim gateway redis; do
     FQDN="${sub}.insurance-app.comptech-lab.com"
     EXISTING=$(curl -sS -H "Authorization: Bearer $TOKEN" \
       "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/dns_records?type=A&name=$FQDN" \
       | jq -r '.result[0].id // empty')
     BODY="{\"type\":\"A\",\"name\":\"$sub.insurance-app\",\"content\":\"$HAPROXY_IP\",\"ttl\":300,\"proxied\":false}"
     if [ -n "$EXISTING" ]; then
       curl -sS -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
         -d "$BODY" \
         "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/dns_records/$EXISTING" >/dev/null
       echo "$FQDN  updated"
     else
       curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
         -d "$BODY" \
         "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/dns_records" >/dev/null
       echo "$FQDN  created"
     fi
   done
   ```

2. **Cert** (on the HAProxy host):

   ```bash
   echo "dns_cloudflare_api_token = $TOKEN" | sudo tee /etc/letsencrypt/cloudflare-insurance-app.ini
   sudo chmod 600 /etc/letsencrypt/cloudflare-insurance-app.ini

   # --keep-until-expiring: re-runnable. If a valid cert >30d from expiry
   # already exists, certbot is a no-op. Otherwise it (re-)issues.
   sudo certbot certonly --dns-cloudflare \
     --dns-cloudflare-credentials /etc/letsencrypt/cloudflare-insurance-app.ini \
     --dns-cloudflare-propagation-seconds 60 \
     -d "*.insurance-app.comptech-lab.com" -d "insurance-app.comptech-lab.com" \
     --non-interactive --agree-tos --keep-until-expiring -m you@example.com
   ```

   Then a deploy hook that rebuilds the HAProxy PEM and reloads — see `docs/adr/0007-dns-haproxy-tls-public-exposure.md` for the script.

3. **HAProxy backends**: see the actual `haproxy.cfg` shape captured in ADR 0007 + the live config in the lab. Pattern is `acl host_<svc>` + `use_backend be_insurance_<svc>` + `backend be_insurance_<svc>` pointing at `<vm IP>:<service port>`.

**Verify** each subdomain returns `200` or `302`:

```bash
for h in app signoz minio kafka mail search is apim gateway redis; do
  echo -n "$h: "
  curl -sS -o /dev/null -L --max-time 12 -w "%{http_code}\n" "https://$h.insurance-app.comptech-lab.com/"
done
```

---

## Phase 7.5 — Register the Debezium CDC connector (slice 14)

The smoke's section 18 (Search) runs `scripts/register-debezium.sh` itself, but a deliberate run-once-by-hand step here makes the failure mode clearer for first-time setup.

```bash
cd ~/insurance-app
./scripts/register-debezium.sh   # idempotent — PUT /connectors/<name>/config is upsert
```

The connector reads `compose/infra/connectors/debezium-postgres-claim.json` and starts capturing changes on `public.claim`. SearchIndexer (running inside Liberty) consumes the Kafka topic and indexes into OpenSearch.

---

## Phase 8 — Final smoke + e2e

```bash
cd ~/insurance-app
./scripts/smoke.sh
```

Expected: **all checks pass — 144 as of slice 14 (Search)**. The count grew across the curriculum as each slice added its section to the smoke. If any fail, look at the section name and consult **Troubleshooting** below.

---

## Lifecycle commands (after a reboot or fresh SSH)

```bash
# everything back up after a host reboot
cd ~/insurance-app/compose && COMPOSE_PROFILES=all podman-compose up -d
cd ~/signoz/deploy/docker && podman-compose up -d
podman start insurance-app insurance-mi

# rebuild after editing Java/server.xml/Containerfile
cd ~/insurance-app && ./scripts/build.sh && \
  source $HOME/insurance-app/.wso2is-creds && \
  podman run -d --replace --name insurance-app --network insurance-net \
    -v $HOME/insurance-app/compose/certs:/config/partner-certs:ro \
    -e WSO2IS_CLIENT_ID=$WSO2IS_CLIENT_ID -e WSO2IS_CLIENT_SECRET=$WSO2IS_CLIENT_SECRET \
    -p 9080:9080 -p 9443:9443 insurance-app:dev

# tear down between cohorts (keeps SigNoz alive; wipes app data)
cd ~/insurance-app/compose && podman-compose down -v
```

---

## Troubleshooting

### Build

- **App startup fails with "SubscriberMethod ... has no upstream" / "Reactive Messaging validation" / odd CDI wiring errors after you removed a Java source file**: stale `.class` files in `target/classes/` from before the deletion are still in the WAR. Always build with `./scripts/build.sh` (it runs `mvn clean package`) instead of `mvn package` directly.
- **Container image is from yesterday despite a rebuild today**: same cause — `target/insurance-app.war` was not refreshed because `mvn package` skipped recompilation. `mvn clean package` (or `./scripts/build.sh`) forces it.

### Liberty / MI

- **`mvn package` fails with "Cannot access defaults field of Properties"**: Maven 3.8 picked the 2011 vintage `maven-war-plugin:2.2` which crashes on JDK 21. The fix is already in `pom.xml` (3.4.0+ pin); if you removed it, restore.
- **`podman build` fails inside `RUN configure.sh` with "Could not connect"**: the build network can't reach Maven Central. Use `--network=host` on `podman build`.
- **Liberty starts then exits with `CWWKS9660E orb element requires a user registry`**: server.xml has `jakartaee-10.0` (full profile). Switch to `webProfile-10.0`.
- **Liberty returns "Connection refused" on 9080 even though container is up**: `${http.port}` placeholder didn't resolve. server.xml needs `<variable name="http.port" defaultValue="9080"/>` (already in the repo).

### Compose

- **`podman-compose --profile all up -d` reports "invalid choice: 'all'"**: profile flag is not supported in 1.0.6. Use `COMPOSE_PROFILES=all podman-compose up -d`.
- **`Error: short-name "..." did not resolve to an alias"**: an image in the compose is missing the `docker.io/` prefix. Edit the compose file to add it. (Our compose explicitly uses `docker.io/...` everywhere except SigNoz upstream, which we fix with the sed in Phase 6.)
- **`bitnami/kafka:3.8` "manifest unknown"**: Bitnami's docker.io images vanished in the 2025 Broadcom reshuffle. The compose now uses `apache/kafka:4.0.2`. If you find a stale reference, update it.

### SigNoz

- **`signoz-otel-collector` logs flood `cannot create agent without orgId`**: SigNoz needs a registered org/user before its collector binds OTLP ports. Run the `POST /api/v1/register` from Phase 6. `scripts/signoz-init.sh` automates this.
- **`signoz` container restarts in a loop with clickhouse errors**: clickhouse hasn't initialized yet. Wait 60-90 seconds after the first `compose up`.

### Kafka

- **`kafka-console-consumer.sh --from-beginning --timeout-ms` returns 0 messages even though `kafka-get-offsets.sh` shows non-zero offsets**: CLI quirk with timeout-driven termination. Use `--partition N --offset earliest` explicitly. The MicroProfile Reactive Messaging consumer in feature 4 uses a different code path and isn't affected.

### WSO2 APIM

- **`apim.insurance-app.comptech-lab.com/` redirects to `https://localhost:9443/publisher`**: APIM's `deployment.toml` doesn't know its public hostname. Our compose mounts `compose/infra/wso2apim/deployment.toml` with `hostname = "apim.insurance-app.comptech-lab.com"` and `proxyPort = 443` to fix this. If you bypassed the mount, recreate the container.

### Network / DNS

- **Liberty can't resolve `signoz-otel-collector`**: the container isn't on `insurance-net`. Check `podman inspect signoz-otel-collector --format '{{.NetworkSettings.Networks}}'`. SigNoz's compose must have the network-block edit from Phase 6.
- **Container can't reach the internet**: rootless podman + slirp4netns IPv6 routing can be flaky. `--network=host` works around it for builds; for runtime, restart the container.

### WSO2 IS (slice 5+)

- **`POST /api/identity/oauth2-dcr/v1.1/register` returns 401**: IS 7.0 renamed the path — use `/api/identity/oauth2/dcr/v1.1/register` (slash, not dash). See `build_gotchas` 20.
- **Token mint returns 200 but body has a UUID, not a JWT**: the DCR client was registered without `ext_token_type=JWT`. Re-register or PATCH the app.
- **Liberty returns 401 on every authenticated POST despite a freshly minted token**: either (a) the JWT `iss` claim doesn't match Liberty's `mpJwt issuer` (re-mint after the IS hostname swap; both must use `https://is.insurance-app.comptech-lab.com/oauth2/token`), or (b) `<basicRegistry><user name="...">` in `server.xml` is the OLD client_id but the new JWT carries a different `sub`. See `build_gotchas` 13 + 19.
- **Recreating the `wso2is` container wiped my DCR client**: yes — the IS image has no volume mount on its H2 DB. Re-run Phase 6.5 + update the basicRegistry user in `server.xml`, then rebuild Liberty.

### Liberty (slice 7+)

- **`@Retry` doesn't fire / `@Transactional` interceptor NPEs on `UOWCoordinator`**: same-bean self-invocation skips the CDI proxy (build_gotchas 14), and raw `Thread` objects don't carry Liberty's UOW context (build_gotchas 15). Use a separate `@ApplicationScoped` invoker bean for `@Retry`, and `@Resource ManagedExecutorService executor; executor.submit(...)` for background work.

### Liberty mTLS (slice 10)

- **`CWPKI0823E SSL HANDSHAKE FAILURE ... defaultSSLConfig` even though my mpRestClient has `trustStore=...` set**: mpRestClient-3.0 in Liberty 24.0.0.12 ignores per-client trustStore/keyStore for outbound HTTPS. Override `defaultKeyStore` + `defaultTrustStore` in `server.xml` AND declare `<ssl id="defaultSSLConfig" keyStoreRef=... trustStoreRef=.../>` explicitly. (build_gotchas 18)

### Kafka coordinator wedged (slice 13+)

- **Consumer-group describe / kafka-console-consumer hangs / fails after a `kafka` restart, while producers still work**: single-broker KRaft loses `__consumer_offsets` on some restart sequences. Manually recreate: `podman exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic __consumer_offsets --partitions 50 --replication-factor 1 --config cleanup.policy=compact`. (build_gotchas 16)
- **`fork: Resource temporarily unavailable` from `podman exec kafka ...`**: repeated `kafka-console-consumer.sh` JVMs from smoke runs piled PIDs over the container limit. `podman restart kafka` clears it.

---

## Where things live

| Path | What |
| --- | --- |
| `~/insurance-app/` | The repo (canonical workspace per ADR 0002) |
| `~/insurance-app/Containerfile` | Liberty image |
| `~/insurance-app/mi/` | WSO2 MI image + synapse config |
| `~/insurance-app/compose/compose.yaml` | Infrastructure stack |
| `~/insurance-app/compose/infra/wso2apim/deployment.toml` | APIM hostname/proxy override |
| `~/insurance-app/compose/infra/wso2is/deployment.toml` | IS hostname/proxy override (slice 5+) |
| `~/insurance-app/compose/certs/` | mTLS demo certs (slice 10, gitignored — run `scripts/gen-certs.sh`) |
| `~/insurance-app/compose/infra/partner/nginx.conf` | mTLS partner-mock config (slice 10) |
| `~/insurance-app/compose/infra/connectors/` | Debezium CDC connector configs (slice 14) |
| `~/insurance-app/docs/adr/` | ADRs (the "why") |
| `~/insurance-app/scripts/` | smoke.sh, signoz-init.sh, gen-certs.sh, register-debezium.sh, ws-probe.py |
| `~/signoz/` | SigNoz upstream clone (not in our repo) |
| `/var/lib/libvirt/images/insurance-app-vm.qcow2` | (on hypervisor) the VM disk |

URLs to bookmark:

- App (Liberty `/api/ping`): https://app.insurance-app.comptech-lab.com/api/ping
- SigNoz UI: https://signoz.insurance-app.comptech-lab.com/
- Kafka UI: https://kafka.insurance-app.comptech-lab.com/
- MinIO console: https://minio.insurance-app.comptech-lab.com/  *(minioadmin / minioadmin)*
- Mailpit: https://mail.insurance-app.comptech-lab.com/
- OpenSearch Dashboards: https://search.insurance-app.comptech-lab.com/
- WSO2 IS: https://is.insurance-app.comptech-lab.com/  *(admin / admin)*
- WSO2 APIM publisher / dev portal: https://apim.insurance-app.comptech-lab.com/  *(admin / admin)*
- WSO2 APIM gateway: https://gateway.insurance-app.comptech-lab.com/
- RedisInsight: https://redis.insurance-app.comptech-lab.com/  *(then add database: host=redis, port=6379)*
- SigNoz admin: `admin@insurance-app.local` / `InsuranceLab123!`

---

## What this guide deliberately doesn't cover

- **Writing application code** — that's the curriculum's job. Once Phase 8 returns 144/0, you can start with the Open Liberty track on the blog and never look at this guide again.
- **OpenShift deployment** — separate track. The compose-based environment is the dev workspace; OCP is the production target.
- **CI/CD** — separate track. The repo's images are built locally on the VM; pipelines will publish them to a registry later.
- **Real secrets management** — passwords in this guide (`InsuranceLab123!`, `admin/admin`, `minioadmin`, `insurance/insurance` for Postgres) are demo defaults. Never use them outside the lab.
