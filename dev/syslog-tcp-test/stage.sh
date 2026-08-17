#!/usr/bin/env bash
#
# Stages an OpenNMS core and a Minion from built assemblies, then writes the syslog TCP and
# TLS configuration for both.
#
# Usage: ./stage.sh <assembled-opennms-dir> <minion-tar.gz>
#
#   the core dir comes from:  cd opennms-full-assembly && mvn install -P dir -DskipTests
#                             -> opennms-full-assembly/target/opennms-<version>
#   the minion tarball from:  cd opennms-assemblies/minion && mvn install -DskipTests
#                             -> opennms-assemblies/minion/target/*-minion.tar.gz
#
set -euo pipefail

CORE_SRC="${1:?usage: stage.sh <assembled-opennms-dir> <minion-tar.gz>}"
MINION_TGZ="${2:?usage: stage.sh <assembled-opennms-dir> <minion-tar.gz>}"

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
. ./env.sh

[ -x "${CORE_SRC}/bin/opennms" ] || { echo "not an assembled OpenNMS dir: ${CORE_SRC}" >&2; exit 1; }
[ -f "${MINION_TGZ}" ] || { echo "not a file: ${MINION_TGZ}" >&2; exit 1; }

if [ ! -f certs/server.crt ]; then
    echo "==> generating certificates"
    ./gen-certs.sh
fi

echo "==> staging core"
rm -rf core
mkdir -p core
# -a keeps modes; the assembly's bin/ scripts have to stay executable.
rsync -a --exclude 'data/' --exclude 'logs/' "${CORE_SRC}/" core/
mkdir -p core/data core/logs

cat > core/etc/opennms-datasources.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<datasource-configuration xmlns:this="http://xmlns.opennms.org/xsd/config/opennms-datasources"
  xmlns:xs="http://www.w3.org/2001/XMLSchema-instance"
  xs:schemaLocation="http://xmlns.opennms.org/xsd/config/opennms-datasources
  http://www.opennms.org/xsd/config/opennms-datasources.xsd ">

  <connection-pool factory="org.opennms.core.db.HikariCPConnectionFactory"
                   idleTimeout="600" loginTimeout="3" minPool="5" maxPool="20" maxSize="50" />

  <jdbc-data-source name="opennms" database-name="opennms"
                    class-name="org.postgresql.Driver"
                    url="jdbc:postgresql://syslog-tcp-postgres:5432/opennms"
                    user-name="opennms" password="opennms" />

  <jdbc-data-source name="opennms-admin" database-name="template1"
                    class-name="org.postgresql.Driver"
                    url="jdbc:postgresql://syslog-tcp-postgres:5432/template1"
                    user-name="postgres" password="postgres" />
</datasource-configuration>
EOF

# Syslogd is enabled through CORE_SERVICE_SYSLOGD_ENABLED in docker-compose.yml, which is
# what service-configuration.xml gates the entry on.

# A Syslogd instance has one TCP listener, so plaintext and TLS cannot be reachable at the
# same time. Both configurations are written here and set-mode.sh swaps between them with a
# reload, which is why the matrix runs in two passes.
cat > core/etc/syslogd-configuration.xml.plain <<EOF
<?xml version="1.0"?>
<syslogd-configuration>
    <configuration
            syslog-port="${ONMS_SYSLOG_UDP}"
            syslog-tcp-port="${ONMS_SYSLOG_TCP}"
            tcp-framing="auto"
            tcp-max-message-size="65536"
            tcp-max-connections="256"
            new-suspect-on-message="false"
            parser="org.opennms.netmgt.syslogd.RadixTreeSyslogParser"
            forwarding-regexp="^.*\s(19|20)\d\d([-/.])(0[1-9]|1[012])\2(0[1-9]|[12][0-9]|3[01])(\s+)(\S+)(\s)(\S.+)"
            matching-group-host="6"
            matching-group-message="8"
            discard-uei="DISCARD-MATCHING-MESSAGES"
            batch-size="1"
            batch-interval="100"
            />
    <import-file>syslog/ApacheHTTPD.syslog.xml</import-file>
    <import-file>syslog/LinuxKernel.syslog.xml</import-file>
    <import-file>syslog/OpenSSH.syslog.xml</import-file>
</syslogd-configuration>
EOF

sed -e "s|syslog-tcp-port=\"${ONMS_SYSLOG_TCP}\"|syslog-tcp-port=\"${ONMS_SYSLOG_TLS}\"|" \
    -e "s|tcp-framing=\"auto\"|tcp-framing=\"auto\"\n            tcp-tls-enabled=\"true\"\n            tcp-tls-cert-filepath=\"/opt/opennms/syslog-certs/server.crt\"\n            tcp-tls-private-key-filepath=\"/opt/opennms/syslog-certs/server.key\"\n            tcp-tls-trust-cert-filepath=\"/opt/opennms/syslog-certs/ca.crt\"\n            tcp-tls-client-auth=\"optional\"|" \
    core/etc/syslogd-configuration.xml.plain > core/etc/syslogd-configuration.xml.tls

cp core/etc/syslogd-configuration.xml.plain core/etc/syslogd-configuration.xml

# The Minion reaches the core over ActiveMQ, and the shipped broker config has only the
# vm:// connector, so the openwire TCP one has to be uncommented or the Minion sits in a
# "Failed to connect ... Connection refused" retry loop with no other clue.
echo "==> enabling the openwire transport connector"
python3 - core/etc/opennms-activemq.xml <<'ACTIVEMQ'
import sys
path = sys.argv[1]
with open(path) as fh:
    xml = fh.read()
commented = '<!-- <transportConnector name="openwire" uri="tcp://0.0.0.0:61616?useJmx=false&amp;maximumConnections=1000&amp;wireformat.maxFrameSize=104857600"/> -->'
enabled = '<transportConnector name="openwire" uri="tcp://0.0.0.0:61616?useJmx=false&amp;maximumConnections=1000&amp;wireformat.maxFrameSize=104857600"/>'
if commented in xml:
    xml = xml.replace(commented, enabled, 1)
    with open(path, 'w') as fh:
        fh.write(xml)
    print("openwire connector enabled")
else:
    print("openwire connector already enabled or not found")
ACTIVEMQ

echo "==> staging minion"
rm -rf minion
mkdir -p minion
tar -xzf "${MINION_TGZ}" -C minion --strip-components=1
mkdir -p minion/data minion/logs

cat > minion/etc/org.opennms.minion.controller.cfg <<EOF
id = syslog-tcp-test-minion
location = ${MINION_LOCATION}
broker-url = failover:tcp://syslog-tcp-core:61616
http-url = http://syslog-tcp-core:8980/opennms
EOF

# Same two-pass arrangement as the core: one TCP listener, swapped by set-mode.sh.
cat > minion/etc/org.opennms.netmgt.syslog.cfg.plain <<EOF
syslog.listen.interface = 0.0.0.0
syslog.listen.port = ${MINION_SYSLOG_UDP}
syslog.batch.size = 1
syslog.batch.interval = 100

syslog.tcp.listen.interface = 0.0.0.0
syslog.tcp.listen.port = ${MINION_SYSLOG_TCP}
syslog.tcp.framing = auto
syslog.tcp.max.message.size = 65536
syslog.tcp.max.connections = 256
EOF

cat > minion/etc/org.opennms.netmgt.syslog.cfg.tls <<EOF
syslog.listen.interface = 0.0.0.0
syslog.listen.port = ${MINION_SYSLOG_UDP}
syslog.batch.size = 1
syslog.batch.interval = 100

syslog.tcp.listen.interface = 0.0.0.0
syslog.tcp.listen.port = ${MINION_SYSLOG_TLS}
syslog.tcp.framing = auto
syslog.tcp.max.message.size = 65536
syslog.tcp.max.connections = 256
syslog.tcp.tls.enabled = true
syslog.tcp.tls.cert.filepath = /opt/minion/syslog-certs/server.crt
syslog.tcp.tls.private.key.filepath = /opt/minion/syslog-certs/server.key
syslog.tcp.tls.trust.cert.filepath = /opt/minion/syslog-certs/ca.crt
syslog.tcp.tls.client.auth = optional
EOF

cp minion/etc/org.opennms.netmgt.syslog.cfg.plain minion/etc/org.opennms.netmgt.syslog.cfg


echo
echo "staged. next:"
echo "  docker compose up -d"
echo "  ./scripts/start-core.sh     # waits for the web UI"
echo "  ./scripts/start-minion.sh"
echo "  ./scripts/run-matrix.sh"
