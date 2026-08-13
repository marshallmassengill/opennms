#!/usr/bin/env bash
# Initialises the database if needed, starts OpenNMS in the core container and waits for the
# web UI. Mirrors dev/ha-test: the container idles and OpenNMS is driven by docker exec.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./env.sh

# The dir assembly hardcodes the paths it was built at, and bin/install plus the installer
# both resolve helpers through them. Pointing those paths at the mount is less invasive than
# mounting the install at its build-time path.
echo "==> linking the assembly's build-time paths"
WT="$(cd ../.. && pwd)"
for p in "$WT/opennms-base-assembly/target/opennms-36.0.4-SNAPSHOT" \
         "$WT/opennms-install/target/opennms-36.0.4-SNAPSHOT" \
         "$WT/opennms-full-assembly/target/opennms-36.0.4-SNAPSHOT"; do
    docker exec -u root syslog-tcp-core sh -c \
        "mkdir -p \"\$(dirname '$p')\" && rm -rf '$p' && ln -s /opt/opennms '$p'"
done

echo "==> selecting a JVM"
docker exec syslog-tcp-core /opt/opennms/bin/runjava -s

echo "==> installing schema"
docker exec syslog-tcp-core /opt/opennms/bin/install -dis

# bin/install runs as root here and leaves data/tmp root-owned, which stops ActiveMQ
# from creating its kahadb directory and hangs the boot at Eventd.
echo "==> fixing ownership under data/ and logs/"
docker exec -u root syslog-tcp-core chown -R opennms:opennms /opt/opennms/data /opt/opennms/logs

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
