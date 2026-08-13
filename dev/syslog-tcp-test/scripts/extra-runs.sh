#!/usr/bin/env bash
#
# The checks that run once rather than per matrix cell.
#
set -uo pipefail

cd "$(dirname "$0")/.."
. ./env.sh
SCRIPTS=./scripts

status=0
note() { printf '\n########## %s\n' "$1"; }
check() { if [ "$1" -eq 0 ]; then echo "PASS: $2"; else echo "FAIL: $2"; status=1; fi }

note "sustained load over one long-lived connection"
# This is the only check that exercises the setAutoRead backpressure. A blocked Netty worker
# shows up here and nowhere else: one connection keeps going while the others stall.
MARKER="onmstcp-load-$RANDOM"
COUNT=20000
python3 - "$MARKER" "$COUNT" "$ONMS_SYSLOG_TCP" <<'PY'
import socket, sys
marker, count, port = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
s = socket.create_connection(("127.0.0.1", port), timeout=30)
for i in range(count):
    msg = f"<34>Oct 11 22:14:15 loadhost app: {marker} seq {i}".encode()
    s.sendall(f"{len(msg)} ".encode() + msg)
s.close()
print(f"sent {count}")
PY
VERIFY_TIMEOUT=300 "$SCRIPTS/verify.sh" "$ONMS_BASE" "$MARKER" "$COUNT"
check $? "no loss under sustained load"

note "connection churn"
# SyslogSinkModule keys its batches on the source address including the ephemeral port, so
# every short-lived connection forms its own batch. This measures what that costs.
MARKER="onmstcp-churn-$RANDOM"
CHURN=500
start=$(date +%s)
python3 - "$MARKER" "$CHURN" "$ONMS_SYSLOG_TCP" <<'PY'
import socket, sys
marker, count, port = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
for i in range(count):
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    msg = f"<34>Oct 11 22:14:15 churnhost app: {marker} seq {i}".encode()
    s.sendall(f"{len(msg)} ".encode() + msg)
    s.close()
print(f"sent {count} over {count} connections")
PY
elapsed=$(( $(date +%s) - start ))
VERIFY_TIMEOUT=180 "$SCRIPTS/verify.sh" "$ONMS_BASE" "$MARKER" "$CHURN"
check $? "no loss under connection churn"
echo "  $CHURN connections in ${elapsed}s"

note "udp and tcp at once"
MARKER="onmstcp-both-$RANDOM"
printf '<34>Oct 11 22:14:15 udphost app: %s udp\n' "$MARKER" \
    | timeout 5 nc -u -w1 127.0.0.1 "$ONMS_SYSLOG_UDP" || true
printf '<34>Oct 11 22:14:15 tcphost app: %s tcp\n' "$MARKER" \
    | timeout 5 nc -q1 127.0.0.1 "$ONMS_SYSLOG_TCP" || true
"$SCRIPTS/verify.sh" "$ONMS_BASE" "$MARKER" 2
check $? "both transports deliver simultaneously"

note "reload brings both listeners back"
docker exec syslog-tcp-core /opt/opennms/bin/send-event.pl \
    uei.opennms.org/internal/reloadDaemonConfig --parm 'daemonName syslogd' >/dev/null
sleep 20
MARKER="onmstcp-reload-$RANDOM"
printf '<34>Oct 11 22:14:15 udphost app: %s udp\n' "$MARKER" \
    | timeout 5 nc -u -w1 127.0.0.1 "$ONMS_SYSLOG_UDP" || true
printf '<34>Oct 11 22:14:15 tcphost app: %s tcp\n' "$MARKER" \
    | timeout 5 nc -q1 127.0.0.1 "$ONMS_SYSLOG_TCP" || true
"$SCRIPTS/verify.sh" "$ONMS_BASE" "$MARKER" 2
check $? "both listeners rebound after reload"

note "framing mismatch is diagnosable"
# Forced non-transparent against an octet-counting sender. The length prefix should end up
# in the event body rather than being silently dropped, and verify.sh flags exactly that.
sed -i 's/tcp-framing="auto"/tcp-framing="non-transparent"/' core/etc/syslogd-configuration.xml
docker exec syslog-tcp-core /opt/opennms/bin/send-event.pl \
    uei.opennms.org/internal/reloadDaemonConfig --parm 'daemonName syslogd' >/dev/null
sleep 20
MARKER="onmstcp-mismatch-$RANDOM"
"$SCRIPTS/send.sh" raw octet-counted plain 127.0.0.1 "$ONMS_SYSLOG_TCP" 3 "$MARKER"
out="$("$SCRIPTS/verify.sh" "$ONMS_BASE" "$MARKER" 3 2>&1 || true)"
echo "$out" | sed 's/^/  /'
if echo "$out" | grep -q "leaked length prefix"; then
    echo "PASS: mismatch surfaces as a leaked length prefix, as documented"
else
    echo "FAIL: mismatch did not surface the way the docs describe"
    status=1
fi
sed -i 's/tcp-framing="non-transparent"/tcp-framing="auto"/' core/etc/syslogd-configuration.xml
docker exec syslog-tcp-core /opt/opennms/bin/send-event.pl \
    uei.opennms.org/internal/reloadDaemonConfig --parm 'daemonName syslogd' >/dev/null
sleep 20

note "oversize message logs once and closes"
MARKER="onmstcp-oversize-$RANDOM"
python3 - "$ONMS_SYSLOG_TCP" <<'PY'
import socket, sys
port = int(sys.argv[1])
s = socket.create_connection(("127.0.0.1", port), timeout=10)
# One frame well past the 65536 default.
s.sendall(b"200000 " + b"x" * 1000)
try:
    s.settimeout(10)
    print("server closed the connection" if s.recv(1) == b"" else "server kept it open")
except OSError as e:
    print(f"connection dropped: {e}")
s.close()
PY
warns="$(docker exec syslog-tcp-core sh -c 'grep -c "exceeds the" /opt/opennms/logs/syslogd.log' 2>/dev/null || echo 0)"
echo "  syslogd.log oversize warnings: $warns"
if [ "$warns" -ge 1 ] && [ "$warns" -le 5 ]; then
    echo "PASS: oversize logged without flooding"
else
    echo "FAIL: expected a small number of oversize warnings, found $warns"
    status=1
fi

note "bad certificate path stops the TLS listener"
cp core/etc/syslogd-configuration.xml core/etc/syslogd-configuration.xml.bak
cp core/etc/syslogd-configuration.xml.tls core/etc/syslogd-configuration.xml
sed -i 's|tcp-tls-cert-filepath="[^"]*"|tcp-tls-cert-filepath="/nope/missing.crt"|' core/etc/syslogd-configuration.xml
docker exec syslog-tcp-core /opt/opennms/bin/send-event.pl \
    uei.opennms.org/internal/reloadDaemonConfig --parm 'daemonName syslogd' >/dev/null
sleep 20
if timeout 3 bash -c "</dev/tcp/127.0.0.1/$ONMS_SYSLOG_TLS" 2>/dev/null; then
    echo "FAIL: the TLS port accepted a connection despite an unusable certificate"
    status=1
else
    echo "PASS: the TLS port is not listening"
fi
docker exec syslog-tcp-core sh -c 'grep -c "Not starting the syslog TCP listener" /opt/opennms/logs/syslogd.log' 2>/dev/null | sed 's/^/  refusal log lines: /'
mv core/etc/syslogd-configuration.xml.bak core/etc/syslogd-configuration.xml
docker exec syslog-tcp-core /opt/opennms/bin/send-event.pl \
    uei.opennms.org/internal/reloadDaemonConfig --parm 'daemonName syslogd' >/dev/null

echo
if [ "$status" -eq 0 ]; then echo "all extra runs passed"; else echo "some extra runs failed"; fi
exit "$status"
