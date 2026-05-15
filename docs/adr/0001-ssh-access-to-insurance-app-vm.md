# ADR 0001: SSH access to `insurance-app-vm` via ProxyJump through `dl385-2`

- Status: Accepted (interim)
- Date: 2026-05-15
- Deciders: ze

## Context

The `insurance-app-vm` KVM guest runs on hypervisor `dl385-2` and is attached to the private bridge `br33` with static IP `30.30.26.1/16` (gateway `30.30.0.1`). The br33 network is not routed to operator workstations.

`dl385-2` already hosts a PowerDNS guest (`gf-ocp-pdns-01`) and an HAProxy guest (`gf-ocp-haproxy-01`) used for the OpenShift cluster on the same bridge. The natural question was whether to reuse them to give `insurance-app-vm` a domain name and a fronted SSH endpoint.

## Decision

For now, operators reach the VM exclusively via SSH ProxyJump through the hypervisor:

```
ssh -J ze@dl385-2 ze@30.30.26.1
```

Equivalent `~/.ssh/config` entry:

```
Host insurance-app-vm
  HostName 30.30.26.1
  User ze
  ProxyJump ze@dl385-2
```

No DNS record, no HAProxy frontend, no public IP is provisioned at this stage.

## Rationale

- **`dl385-2` is already reachable** from operator workstations and is dual-homed onto `br33`, making it a zero-cost bastion. No new service, no new firewall hole.
- **HAProxy cannot route SSH by hostname.** The SSH protocol carries no SNI-equivalent, so HAProxy would have to dedicate one TCP listener per backend (e.g. `haproxy:2222` → `insurance-app-vm:22`). That scales poorly and conflates the OCP load balancer with unrelated admin access.
- **DNS alone does not solve reachability.** Even with an A record on `gf-ocp-pdns-01`, the operator's resolver would need either a `/etc/resolver/<zone>` override (macOS), a public delegation of the zone, or a `/etc/hosts` line. None of these is worth setting up for a single VM that already works via ProxyJump.
- **Keeps the OCP HAProxy single-purpose.** The cluster HAProxy fronts 80/443/6443/22623 for OpenShift; mixing in admin SSH would muddy its configuration and blast radius.

## Consequences

Positive:
- Zero new infrastructure. Works the moment cloud-init finishes seeding `authorized_keys`.
- Auditable: every session traverses `dl385-2`, where shell history and auth logs already live.
- No new attack surface exposed outside the existing hypervisor.

Negative / accepted trade-offs:
- Operators must remember (or `~/.ssh/config`) the jump-host form.
- If `dl385-2` is down, the VM is unreachable for SSH (same as today — the VM cannot run without the hypervisor anyway).
- No friendly hostname; tooling that assumes DNS resolution (e.g. some Ansible inventories) needs the `ansible_ssh_common_args='-o ProxyJump=ze@dl385-2'` workaround.

## Alternatives considered

1. **A record on `gf-ocp-pdns-01` + macOS resolver override.** Rejected for now: solves naming, not reachability, and adds laptop-side config that drifts.
2. **HAProxy TCP listener on a unique port per VM.** Rejected: doesn't scale and pollutes the OCP load balancer.
3. **WireGuard / VPN onto br33.** Defensible long-term answer but disproportionate for a single VM.
4. **Public IP / DNAT on the VM directly.** Rejected: br33 is intentionally private; exposing it bypasses the segmentation that motivated the bridge in the first place.

## Revisit when

- A second or third VM on br33 needs operator SSH — then a real bastion + DNS zone earns its keep.
- The team wants Ansible / IDE remote-dev / SFTP workflows that don't tolerate `ProxyJump` cleanly.
- A VPN onto br33 is rolled out for any other reason.
