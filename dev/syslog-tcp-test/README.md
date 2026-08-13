# Syslog over TCP end-to-end environment

Verifies syslog ingestion over TCP and TLS against a real OpenNMS core and a real Minion, using rsyslog and syslog-ng as senders rather than a hand-written client, so that the framing on the wire is whatever those daemons actually produce.

## Layout

| Path | What it is |
| --- | --- |
| `env.sh` | Ports and counts. Chosen to stay clear of the other `dev/` environments. |
| `gen-certs.sh` | CA, server and client certificates as PEM. The server certificate carries a SAN for `127.0.0.1`. |
| `stage.sh` | Copies a built core assembly and Minion tarball into `core/` and `minion/`, then writes the syslog configuration for both. |
| `docker-compose.yml` | Postgres plus two containers that idle on `sleep infinity`; OpenNMS and the Minion are started by `docker exec`, following `dev/ha-test`. |
| `scripts/send.sh` | Sends marked messages via rsyslog, syslog-ng, or hand-built frames. |
| `scripts/verify.sh` | Counts the resulting events and checks their content. |
| `scripts/set-mode.sh` | Switches both sides between the plaintext and TLS listener, then reloads. |
| `scripts/run-matrix.sh` | The sender / framing / transport matrix, against both ingestion paths. |
| `scripts/extra-runs.sh` | The checks that run once: load, churn, reload, and the failure modes. |

## Running it

```sh
cd opennms-full-assembly && mvn install -P dir -DskipTests
cd opennms-assemblies/minion && mvn install -DskipTests

cd dev/syslog-tcp-test
./gen-certs.sh
./stage.sh ../../opennms-full-assembly/target/opennms-36.0.4-SNAPSHOT \
           ../../opennms-assemblies/minion/target/opennms-minion-36.0.4-SNAPSHOT-minion.tar.gz
docker compose up -d
./scripts/start-core.sh
./scripts/start-minion.sh
./scripts/run-matrix.sh
./scripts/extra-runs.sh
```

## Why two passes

A Syslogd instance runs one TCP listener, so plaintext and TLS cannot be reachable at the same time. `run-matrix.sh` therefore runs the plaintext cells, switches both sides with `set-mode.sh`, and runs the TLS cells. The switch goes through `reloadDaemonConfig` rather than a restart, so each pass also exercises the reload path.

## What the assertions actually catch

Counts are asserted exactly rather than as a lower bound. Under-delivery means messages were dropped or mis-framed, and over-delivery means one message became several events, which is what a framing bug produces. A `>=` assertion would pass straight over the second case, which is the more likely one.

`verify.sh` also checks that no event body starts with a length prefix and that none carries a stray carriage return. The first is the classic octet-counting bug, where the frame header is treated as content. The second matters because nothing downstream strips a CR, so a CRLF sender would leave one in the event.

The sustained-load run is the only check that exercises the `setAutoRead(false)` backpressure in the listener. A blocked Netty worker thread is invisible everywhere else: it needs a saturated dispatcher and several connections on the same worker before one connection keeps going while the others stall.

The churn run exists because `SyslogSinkModule` keys its batches on the source address including the ephemeral port, so every short-lived connection forms its own batch. It reports how long the connections took so the cost is recorded rather than assumed.

## Results

`run-matrix.sh` appends a table to `results.md` on each run.
