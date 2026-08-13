#!/usr/bin/env bash
# Starts the Minion and waits for the TCP syslog listener to accept a connection.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./env.sh

echo "==> starting minion"
docker exec -d syslog-tcp-minion /opt/minion/bin/karaf server

echo "==> waiting for the syslog TCP listener on $MINION_SYSLOG_TCP"
for i in $(seq 1 60); do
    if timeout 2 bash -c "</dev/tcp/127.0.0.1/$MINION_SYSLOG_TCP" 2>/dev/null; then
        echo "  up"
        exit 0
    fi
    sleep 5
done
echo "  never came up; check minion/logs/karaf.log" >&2
exit 1
