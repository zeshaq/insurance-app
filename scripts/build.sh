#!/usr/bin/env bash
# Canonical build for the insurance-app Liberty image.
#
# - `mvn clean package` (not `mvn package`) — without `clean`, deleted source
#   files leave stale .class artifacts in target/classes/ that get bundled into
#   the WAR. Liberty then tries to wire those ghost classes and fails with
#   confusing errors (e.g. "SubscriberMethod ... has no upstream" for a
#   consumer whose source you deleted). See build_gotchas memory item 11.
# - `--network=host` on `podman build` because rootless podman's default build
#   network can't always reach Maven Central / icr.io reliably on this VM.
#   See build_gotchas memory item ... (the original network-host pin).
#
# Use this instead of bare `mvn package && podman build ...` so the cleanup
# isn't skipped.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "==> mvn clean package"
mvn -B clean package

echo "==> podman build insurance-app:dev"
podman build --network=host -t insurance-app:dev -f Containerfile .

echo "==> done. To restart the running container:"
echo "    source ~/insurance-app/.wso2is-creds && \\"
echo "    podman run -d --replace --name insurance-app --network insurance-net \\"
echo "      -v \$HOME/insurance-app/compose/certs:/config/partner-certs:ro \\"
echo "      -e WSO2IS_CLIENT_ID=$WSO2IS_CLIENT_ID -e WSO2IS_CLIENT_SECRET=$WSO2IS_CLIENT_SECRET \\"
echo "      -p 9080:9080 -p 9443:9443 insurance-app:dev"
