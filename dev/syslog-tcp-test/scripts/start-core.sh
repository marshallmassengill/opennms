#!/usr/bin/env bash
# Initialises the database if needed, starts OpenNMS in the core container and waits for the
# web UI. Mirrors dev/ha-test: the container idles and OpenNMS is driven by docker exec.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./env.sh

echo "==> installing schema"
docker exec syslog-tcp-core /opt/opennms/bin/install -dis

echo "==> starting opennms"
docker exec -d syslog-tcp-core /opt/opennms/bin/opennms -f start

echo "==> waiting for the web UI on $ONMS_BASE"
for i in $(seq 1 120); do
    if curl -sf -u "$ONMS_AUTH" "$ONMS_BASE/opennms/rest/info" >/dev/null 2>&1; then
        echo "  up after ${i}0s"
        exit 0
    fi
    sleep 10
done
echo "  never came up" >&2
exit 1
