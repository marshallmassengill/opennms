#!/usr/bin/env bash
# Starts the Minion and waits for the TCP syslog listener to accept a connection.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./env.sh

# The Minion must not start before the core's ActiveMQ broker is accepting connections. If
# it does, the failover transport reconnects later but the sink's Camel context stays
# stopped, and every dispatch then fails with "RejectedExecutionException: CamelContext is
# stopped". The syslog listener still binds and still decodes correctly, so the only symptom
# is that nothing reaches the core, which looks like a listener bug and is not one.
echo "==> waiting for the core broker on 61616"
for i in $(seq 1 60); do
    if docker exec syslog-tcp-core sh -c 'timeout 2 bash -c "</dev/tcp/127.0.0.1/61616"' 2>/dev/null; then
        echo "  broker is up"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "  the core broker never came up; is the openwire connector enabled in etc/opennms-activemq.xml?" >&2
        exit 1
    fi
    sleep 5
done

echo "==> starting minion"
docker exec -d syslog-tcp-minion /opt/minion/bin/karaf server

echo "==> waiting for the syslog TCP listener on $MINION_SYSLOG_TCP"
# Probed inside the container: docker publishes the port on the host, so a connect from the
# host succeeds against docker-proxy even when nothing is listening behind it.
for i in $(seq 1 60); do
    if docker exec syslog-tcp-minion sh -c "timeout 2 bash -c '</dev/tcp/127.0.0.1/$MINION_SYSLOG_TCP'" 2>/dev/null; then
        echo "  up"
        exit 0
    fi
    sleep 10
done
echo "  never came up; check minion/data/log/karaf.log" >&2
exit 1
