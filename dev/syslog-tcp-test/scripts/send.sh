#!/usr/bin/env bash
#
# Sends a known number of marked syslog messages to a listener, using a chosen sender,
# framing and transport. Every message carries a unique marker so that verify.sh can count
# exactly what arrived.
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

case "$SENDER" in
rsyslog)
    CONF=$(mktemp /tmp/rsyslog-send-XXXXXX.conf)
    SPOOL=$(mktemp -d /tmp/rsyslog-spool-XXXXXX)
    trap 'rm -rf "$CONF" "$SPOOL"' EXIT

    TLS_LINES=""
    if [ "$TRANSPORT" != "plain" ]; then
        TLS_LINES="
global(
  defaultNetstreamDriver=\"gtls\"
  defaultNetstreamDriverCAFile=\"/certs/ca.crt\""
        if [ "$TRANSPORT" = "mtls" ]; then
            TLS_LINES="$TLS_LINES
  defaultNetstreamDriverCertFile=\"/certs/client.crt\"
  defaultNetstreamDriverKeyFile=\"/certs/client.key\""
        fi
        TLS_LINES="$TLS_LINES
)"
    fi

    ACTION_TLS=""
    if [ "$TRANSPORT" != "plain" ]; then
        # StreamDriverMode 1 is TLS; anon would skip certificate checks, so the name is
        # pinned to what the server certificate carries.
        ACTION_TLS='StreamDriver="gtls" StreamDriverMode="1" StreamDriverAuthMode="x509/name" StreamDriverPermittedPeers="localhost"'
    fi

    cat > "$CONF" <<EOF
module(load="imfile")
$TLS_LINES
\$WorkDirectory $SPOOL

template(name="passthru" type="string" string="%rawmsg%\n")

ruleset(name="fwd") {
  action(type="omfwd"
         target="$HOST" port="$PORT" protocol="tcp"
         TCP_Framing="$FRAMING"
         $ACTION_TLS
         template="passthru"
         action.resumeRetryCount="3")
}

module(load="imtcp" ruleset="fwd")
input(type="imtcp" port="5514")
EOF

    docker run --rm --network host \
        -v "$CONF:/etc/rsyslog.conf:ro" \
        -v "$CERTS:/certs:ro" \
        -v "$SPOOL:$SPOOL" \
        --name "syslog-tcp-test-rsyslog-$$" \
        -d rsyslog/syslog_appliance_alpine:latest \
        rsyslogd -n -f /etc/rsyslog.conf >/dev/null

    sleep 3
    for i in $(seq 1 "$COUNT"); do
        printf '<34>Oct 11 22:14:15 sender%s app: %s seq %s\n' "$i" "$MARKER" "$i" \
            | timeout 5 nc -q1 127.0.0.1 5514 || true
    done
    sleep 3
    docker rm -f "syslog-tcp-test-rsyslog-$$" >/dev/null 2>&1 || true
    ;;

syslog-ng)
    CONF=$(mktemp /tmp/syslog-ng-send-XXXXXX.conf)
    trap 'rm -f "$CONF"' EXIT

    if [ "$FRAMING" = "octet-counted" ]; then
        DEST_OPTS="transport(\"tcp\") flags(syslog-protocol)"
        PROTO="syslog"
    else
        DEST_OPTS="transport(\"tcp\")"
        PROTO="network"
    fi

    if [ "$TRANSPORT" != "plain" ]; then
        TLS_BLOCK="transport(\"tls\") tls( ca-file(\"/certs/ca.crt\")"
        if [ "$TRANSPORT" = "mtls" ]; then
            TLS_BLOCK="$TLS_BLOCK cert-file(\"/certs/client.crt\") key-file(\"/certs/client.key\")"
        fi
        TLS_BLOCK="$TLS_BLOCK peer-verify(optional-untrusted) )"
        DEST_OPTS="$TLS_BLOCK"
    fi

    cat > "$CONF" <<EOF
@version: 4.0
source s_net { network(ip("127.0.0.1") port(5515) transport("tcp")); };
destination d_onms { $PROTO("$HOST" port($PORT) $DEST_OPTS template("\${MSG}\n")); };
log { source(s_net); destination(d_onms); };
EOF

    docker run --rm --network host \
        -v "$CONF:/etc/syslog-ng/syslog-ng.conf:ro" \
        -v "$CERTS:/certs:ro" \
        --name "syslog-tcp-test-syslogng-$$" \
        -d balabit/syslog-ng:latest \
        -F -f /etc/syslog-ng/syslog-ng.conf >/dev/null

    sleep 3
    for i in $(seq 1 "$COUNT"); do
        printf '<34>Oct 11 22:14:15 sender%s app: %s seq %s\n' "$i" "$MARKER" "$i" \
            | timeout 5 nc -q1 127.0.0.1 5515 || true
    done
    sleep 3
    docker rm -f "syslog-tcp-test-syslogng-$$" >/dev/null 2>&1 || true
    ;;

raw)
    # Hand-built frames, so the exact bytes on the wire are known. Used for the edge cases
    # a real daemon will not produce.
    payload=""
    for i in $(seq 1 "$COUNT"); do
        msg="<34>Oct 11 22:14:15 rawhost app: $MARKER seq $i"
        if [ "$FRAMING" = "octet-counted" ]; then
            payload="$payload${#msg} $msg"
        else
            payload="$payload$msg
"
        fi
    done

    if [ "$TRANSPORT" = "plain" ]; then
        printf '%s' "$payload" | timeout 10 nc -q1 "$HOST" "$PORT"
    else
        CLIENT_ARGS=()
        if [ "$TRANSPORT" = "mtls" ]; then
            CLIENT_ARGS=(-cert "$CERTS/client.crt" -key "$CERTS/client.key")
        fi
        printf '%s' "$payload" | timeout 10 openssl s_client -quiet -verify_quiet \
            -connect "$HOST:$PORT" -CAfile "$CERTS/ca.crt" "${CLIENT_ARGS[@]}" >/dev/null 2>&1
    fi
    ;;

*)
    echo "unknown sender '$SENDER'" >&2
    exit 2
    ;;
esac
