#!/usr/bin/env bash
# Push the project from the laptop to insurance-app-vm via ProxyJump through dl385-2.
# Usage: ./scripts/sync-to-vm.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE=ze@30.30.26.1
JUMP=ze@dl385-2
DEST=/home/ze/insurance-app

rsync -avz --delete \
  --exclude target/ \
  --exclude .git/ \
  --exclude .idea/ \
  --exclude .vscode/ \
  --exclude '*.iml' \
  --exclude .DS_Store \
  -e "ssh -J ${JUMP}" \
  "${PROJECT_ROOT}/" \
  "${REMOTE}:${DEST}/"

echo "synced ${PROJECT_ROOT} -> ${REMOTE}:${DEST}"
