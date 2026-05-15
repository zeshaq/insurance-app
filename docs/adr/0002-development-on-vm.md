# ADR 0002: Development happens on `insurance-app-vm`, not the laptop

- Status: Accepted
- Date: 2026-05-15
- Deciders: ze
- Supersedes: the interim laptop-as-source-of-truth setup established alongside ADR 0001

## Context

When the project was bootstrapped, source lived on the laptop at `/Users/ze/Documents/claude-agent-insurannce-app/` and was rsynced to `/home/ze/insurance-app/` on the VM via `scripts/sync-to-vm.sh`. The VM held the JDK / Maven / podman toolchain and ran every build. The laptop only edited files.

That split was acceptable but creates two real costs:

1. **Two copies of the source, one editable.** The laptop is the editor, the VM is the build host, and `rsync --delete` is the only thing keeping them in agreement. Any edit done on the VM (e.g. a hot-fix from a podman log) is silently destroyed by the next sync.
2. **Tooling lives in one place, source in another.** Compiler errors, debugger breakpoints, `mvn liberty:dev` hot reload, container logs — all are on the VM. Round-tripping every signal back to a laptop editor is friction that compounds over time.

## Decision

**The VM is the canonical workspace for this project.** All editing, building, running, and git operations happen on `insurance-app-vm`.

Concretely:

- The git working tree lives at `/home/ze/insurance-app/` on the VM, with `origin = git@github.com:zeshaq/insurance-app.git`.
- The laptop directory `/Users/ze/Documents/claude-agent-insurannce-app/` is no longer the source of truth. It can stay around as a read-only browsing mirror (`git pull` only) or be deleted.
- The previous laptop→VM rsync helper (`scripts/sync-to-vm.sh`) is removed — running it would clobber edits made on the VM.
- New ADRs and code edits are authored on the VM and pushed from there.

## Rationale

- **Eliminates the rsync hazard.** With only one writable copy, there is no direction-of-truth ambiguity and no `--delete` foot-gun.
- **Aligns with where everything else already is.** Toolchain, container engine, target runtime, logs, and the network the app talks to are all on the VM. The editor should sit next to them.
- **Doesn't preclude laptop browsing.** A laptop clone that only ever does `git pull` is welcome; what changed is that it has no special status.

## Consequences

Positive:
- One workspace, one history. `git status` on the VM is authoritative.
- `mvn liberty:dev` (hot reload) becomes practical without sync gymnastics.
- Future automation (CI, build agents, image registries) targets the VM naturally.

Negative / accepted trade-offs:
- The VM needs an SSH key on GitHub to push (one-time setup, generated on the VM, public key added to the user's GitHub account or as a repo deploy key).
- Editing requires either an SSH-friendly editor on the VM (`vim`, `nvim`, `nano`) or a remote-editing setup pointed at the VM (VS Code Remote-SSH via ProxyJump, JetBrains Gateway). These all work; the user's existing `ssh -J ze@dl385-2 ze@30.30.26.1` is the same connection they use.
- A laptop mirror, if kept, can fall behind. That's acceptable because nothing on the laptop is depended on by the build.

## Alternatives considered

1. **Mutagen / Unison two-way sync.** Solves the "edit anywhere" problem but adds a moving part and a new failure mode (conflict resolution). Not worth it for a one-developer project.
2. **VS Code Remote-SSH with source still on the laptop.** Same one-copy problem; Remote-SSH itself doesn't change where files live.
3. **Status quo (laptop edits, rsync to VM).** Rejected for the reasons above.

## Revisit when

- The team grows past one developer and concurrent editing becomes an issue.
- A real CI runner takes over building, at which point neither the laptop nor the VM is canonical anymore.
- The VM stops being long-lived (e.g. moved to ephemeral OpenShift pods) — at that point the git remote is the source of truth and the VM is just one of several workspaces.
