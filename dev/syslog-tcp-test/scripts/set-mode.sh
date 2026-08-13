#!/usr/bin/env bash
#
# Switches both the core and the Minion between the plaintext and TLS TCP listener, then
# reloads. A Syslogd instance has one TCP listener, so the matrix has to run in two passes.
#
# The core is reloaded through the reloadDaemonConfig event rather than restarted, which
# doubles as a check that the reload path brings both listeners back.
#
# usage: set-mode.sh plain|tls
#
set -euo pipefail

MODE="${1:?usage: set-mode.sh plain|tls}"
case "$MODE" in plain|tls) ;; *) echo "mode must be plain or tls" >&2; exit 2 ;; esac

cd "$(dirname "$0")/.."
. ./env.sh

echo "==> core -> $MODE"
cp "core/etc/syslogd-configuration.xml.$MODE" core/etc/syslogd-configuration.xml
docker exec syslog-tcp-core /opt/opennms/bin/send-event.pl \
    uei.opennms.org/internal/reloadDaemonConfig --parm 'daemonName syslogd' >/dev/null

echo "==> minion -> $MODE"
cp "minion/etc/org.opennms.netmgt.syslog.cfg.$MODE" minion/etc/org.opennms.netmgt.syslog.cfg
# The blueprint's property-placeholder has update-strategy="reload", so writing the .cfg is
# enough: config admin picks it up and the bundle restarts with the new values.

echo "==> waiting for the listeners"
if [ "$MODE" = "plain" ]; then
    core_port="$ONMS_SYSLOG_TCP"; minion_port="$MINION_SYSLOG_TCP"
else
    core_port="$ONMS_SYSLOG_TLS"; minion_port="$MINION_SYSLOG_TLS"
fi

for target in "127.0.0.1:$core_port" "127.0.0.1:$minion_port"; do
    host="${target%%:*}"; port="${target##*:}"
    for i in $(seq 1 60); do
        if timeout 2 bash -c "</dev/tcp/$host/$port" 2>/dev/null; then
            echo "  $target is up"
            break
        fi
        if [ "$i" -eq 60 ]; then
            echo "  $target never came up" >&2
            exit 1
        fi
        sleep 2
    done
done
