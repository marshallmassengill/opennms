#!/usr/bin/env bash
#
# Sends a known number of marked syslog messages through a real syslog daemon, using a
# chosen framing and transport. Every message carries a unique marker so verify.sh can
# count exactly what arrived.
#
# The daemons are run as relays: they accept the messages on a local TCP port and forward
# them to the listener under test. That keeps the message content exactly what this script
# chose while still putting the daemon's own framing on the wire, which is the thing being
# tested. Injecting through the daemon's own log socket would let it rewrite the content.
#
# usage: send.sh <sender> <framing> <transport> <host> <port> <count> <marker>
#   sender     rsyslog | syslog-ng | raw
#   framing    traditional | octet-counted
#   transport  plain | tls | mtls
#
set -euo pipefail

SENDER="$1"
FRAMING="$2"
TRANSPORT="$3"
HOST="$4"
PORT="$5"
COUNT="$6"
MARKER="$7"

cd "$(dirname "$0")/.."
CERTS="$PWD/certs"

RELAY_PORT=5514
CONTAINER="syslog-tcp-test-sender-$$"

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "${CONF:-}" "${SPOOL:-}"
}
trap cleanup EXIT

# Feeds the relay. Content is fixed here so the expected count and body are known.
inject() {
    for i in $(seq 1 "$COUNT"); do
        printf '<190>Mar 11 08:35:17 %shost%s 30128311: %%SEC-6-IPACCESSLOGP: list in110 denied tcp 192.168.10.100(63923) -> 192.168.11.128(1521), %s seq%s packet\n' \
            "$1" "$i" "$MARKER" "$i" \
            | timeout 5 nc -N 127.0.0.1 "$RELAY_PORT" || true
    done
}

case "$SENDER" in
rsyslog)
    # Built locally rather than using rsyslog/syslog_appliance_alpine: that image ships
    # without lmnsd_gtls.so so it cannot do TLS, and its entrypoint ignores -f.
    if ! docker image inspect syslog-tcp-test-rsyslog:latest >/dev/null 2>&1; then
        echo "building the rsyslog sender image"
        docker build -q -t syslog-tcp-test-rsyslog:latest senders/rsyslog >/dev/null
    fi

    CONF=$(mktemp /tmp/rsyslog-send-XXXXXX.conf)
    SPOOL=$(mktemp -d /tmp/rsyslog-spool-XXXXXX)
    # The container runs rsyslog as root, but keep both readable regardless of umask.
    chmod 644 "$CONF"
    chmod 777 "$SPOOL"

    GLOBAL_TLS=""
    ACTION_TLS=""
    if [ "$TRANSPORT" != "plain" ]; then
        GLOBAL_TLS='
       defaultNetstreamDriver="gtls"
       defaultNetstreamDriverCAFile="/certs/ca.crt"'
        # x509/name rather than anon: this verifies the listener's certificate against the
        # CA and matches the name, so a broken TLS setup fails instead of silently passing.
        ACTION_TLS='StreamDriver="gtls" StreamDriverMode="1"
         StreamDriverAuthMode="x509/name" StreamDriverPermittedPeers="localhost"'
        if [ "$TRANSPORT" = "mtls" ]; then
            GLOBAL_TLS="$GLOBAL_TLS"'
       defaultNetstreamDriverCertFile="/certs/client.crt"
       defaultNetstreamDriverKeyFile="/certs/client.key"'
        fi
    fi

    cat > "$CONF" <<EOF
global(workDirectory="/spool"$GLOBAL_TLS)
module(load="imtcp")
template(name="passthru" type="string" string="%rawmsg%")
ruleset(name="fwd") {
  action(type="omfwd" target="$HOST" port="$PORT" protocol="tcp"
         TCP_Framing="$FRAMING"
         $ACTION_TLS
         template="passthru"
         action.resumeRetryCount="3")
}
input(type="imtcp" port="$RELAY_PORT" ruleset="fwd")
EOF

    docker run -d --name "$CONTAINER" --network host \
        -v "$CONF:/etc/rsyslog.conf:ro" \
        -v "$CERTS:/certs:ro" \
        -v "$SPOOL:/spool" \
        syslog-tcp-test-rsyslog:latest >/dev/null
    sleep 6
    inject rsys
    sleep 5
    ;;

syslog-ng)
    RELAY_PORT=5515
    CONF=$(mktemp /tmp/syslog-ng-send-XXXXXX.conf)
    chmod 644 "$CONF"

    # The driver choice is the framing: network() emits newline-delimited RFC3164, syslog()
    # emits octet-counted RFC5424. syslog-ng has no TCP_Framing knob.
    if [ "$FRAMING" = "octet-counted" ]; then
        DRIVER="syslog"
    else
        DRIVER="network"
    fi

    if [ "$TRANSPORT" = "plain" ]; then
        DEST_OPTS='transport("tcp")'
    else
        DEST_OPTS='transport("tls") tls( ca-file("/certs/ca.crt") peer-verify(required-trusted)'
        if [ "$TRANSPORT" = "mtls" ]; then
            DEST_OPTS="$DEST_OPTS"' cert-file("/certs/client.crt") key-file("/certs/client.key")'
        fi
        DEST_OPTS="$DEST_OPTS )"
    fi

    cat > "$CONF" <<EOF
@version: 4.2
source s_in { network(ip("0.0.0.0") port($RELAY_PORT) transport("tcp")); };
destination d_onms { ${DRIVER}("$HOST" port($PORT) $DEST_OPTS); };
log { source(s_in); destination(d_onms); };
EOF

    # --no-caps: the image warns and degrades without it when not running with capabilities.
    docker run -d --name "$CONTAINER" --network host \
        -v "$CONF:/etc/syslog-ng/syslog-ng.conf:ro" \
        -v "$CERTS:/certs:ro" \
        balabit/syslog-ng:latest \
        -F -f /etc/syslog-ng/syslog-ng.conf --no-caps >/dev/null
    sleep 6
    inject sng
    sleep 5
    ;;

raw)
    # Hand-built frames, so the exact bytes on the wire are known. Used for the edge cases
    # a real daemon will not produce.
    payload=""
    for i in $(seq 1 "$COUNT"); do
        msg="<190>Mar 11 08:35:17 rawhost$i 30128311: %SEC-6-IPACCESSLOGP: list in110 denied tcp 192.168.10.100(63923) -> 192.168.11.128(1521), $MARKER seq$i packet"
        if [ "$FRAMING" = "octet-counted" ]; then
            payload="$payload${#msg} $msg"
        else
            payload="$payload$msg
"
        fi
    done

    if [ "$TRANSPORT" = "plain" ]; then
        # -N and a tolerated exit status for the same reason as the TLS branch below: the
        # listener does not close the connection, so nc's exit says nothing about delivery.
        printf '%s' "$payload" | timeout 10 nc -N "$HOST" "$PORT" || true
    else
        CLIENT_ARGS=()
        if [ "$TRANSPORT" = "mtls" ]; then
            CLIENT_ARGS=(-cert "$CERTS/client.crt" -key "$CERTS/client.key")
        fi
        # s_client exits nonzero when the peer closes a connection it considers finished,
        # which is the normal ending here, so the exit status says nothing useful. What
        # arrived is asserted by verify.sh.
        printf '%s' "$payload" | timeout 10 openssl s_client -quiet -verify_quiet \
            -connect "$HOST:$PORT" -CAfile "$CERTS/ca.crt" "${CLIENT_ARGS[@]}" >/dev/null 2>&1 || true
    fi
    ;;

*)
    echo "unknown sender '$SENDER'" >&2
    exit 2
    ;;
esac
