#!/usr/bin/env bash
#
# Generates a CA, a server certificate and key for the syslog TCP listeners, and a client
# certificate and key for the mutual TLS cell. PEM throughout, because that is what
# SslContextBuilder takes.
#
set -euo pipefail

cd "$(dirname "$0")"
OUT=certs
mkdir -p "$OUT"

# The listeners are reached over the loopback address, so the server certificate needs a
# SAN for it. Without one a sender that verifies hostnames rejects the handshake.
SAN="subjectAltName=DNS:localhost,IP:127.0.0.1"

echo "== CA =="
openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$OUT/ca.key" -out "$OUT/ca.crt" \
    -subj "/CN=syslog-tcp-test-ca" 2>/dev/null

echo "== server =="
openssl req -newkey rsa:2048 -nodes \
    -keyout "$OUT/server.key" -out "$OUT/server.csr" \
    -subj "/CN=localhost" 2>/dev/null
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
    -CAcreateserial -days 3650 -sha256 \
    -extfile <(printf '%s\n' "$SAN") \
    -out "$OUT/server.crt" 2>/dev/null

echo "== client =="
openssl req -newkey rsa:2048 -nodes \
    -keyout "$OUT/client.key" -out "$OUT/client.csr" \
    -subj "/CN=syslog-tcp-test-client" 2>/dev/null
openssl x509 -req -in "$OUT/client.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
    -CAcreateserial -days 3650 -sha256 \
    -out "$OUT/client.crt" 2>/dev/null

rm -f "$OUT"/*.csr "$OUT"/*.srl

# rsyslog's gtls driver runs as a non-root user in the container and reads the key
chmod 644 "$OUT"/*.key

echo
echo "wrote:"
ls -1 "$OUT"
