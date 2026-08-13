# Shared settings for the syslog TCP end-to-end environment.
#
# Ports are picked to stay clear of the other dev environments in this tree: 5460 is the HA
# test's Postgres, 5462 the clean dev instance, 5434 the Kafka demo and 5435 the Postgres
# flows instance. 8980 is the Kafka demo's OpenNMS.

# Postgres
export PGPORT_HOST=5436

# OpenNMS core
export ONMS_HTTP=8985
export ONMS_KARAF=8106
export ONMS_SYSLOG_UDP=10514
export ONMS_SYSLOG_TCP=10601
export ONMS_SYSLOG_TLS=10614
export ONMS_BASE="http://localhost:${ONMS_HTTP}"
export ONMS_AUTH="admin:admin"

# Minion. Its listeners are separate ports on the same host, and messages reach the core
# through the sink rather than by being forwarded as syslog.
export MINION_KARAF=8206
export MINION_SYSLOG_UDP=11514
export MINION_SYSLOG_TCP=11601
export MINION_SYSLOG_TLS=11614
export MINION_LOCATION="syslog-tcp-test"

# How many messages each matrix cell sends.
export MATRIX_COUNT=25
