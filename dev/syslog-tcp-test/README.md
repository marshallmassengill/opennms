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
           ../../opennms-assemblies/minion/target/org.opennms.assemblies.minion-36.0.4-SNAPSHOT-minion.tar.gz
docker compose up -d
./scripts/start-core.sh
./scripts/start-minion.sh
./scripts/run-matrix.sh
./scripts/extra-runs.sh
```

## Why two passes

A Syslogd instance runs one TCP listener, sharing the sink dispatcher with the UDP one, so plaintext and TLS cannot be reachable at the same time. `run-matrix.sh` therefore runs the plaintext cells, switches both sides with `set-mode.sh`, and runs the TLS cells. The switch goes through `reloadDaemonConfig` rather than a restart, so each pass also exercises the reload path.

## What the assertions actually catch

Counts are asserted exactly rather than as a lower bound. Under-delivery means messages were dropped or mis-framed, and over-delivery means one message became several events, which is what a framing bug produces. A `>=` assertion would pass straight over the second case, which is the more likely one.

`verify.sh` also checks that no event body starts with a length prefix and that none carries a stray carriage return. The first is the classic octet-counting bug, where the frame header is treated as content. The second matters because nothing downstream strips a CR, so a CRLF sender would leave one in the event.

The sustained-load run is the only check that exercises the `setAutoRead(false)` backpressure in the listener. A blocked Netty worker thread is invisible everywhere else: it needs a saturated dispatcher and several connections on the same worker before one connection keeps going while the others stall.

The churn run exists because `SyslogSinkModule` keys its batches on the source address including the ephemeral port, so every short-lived connection forms its own batch. It reports how long the connections took so the cost is recorded rather than assumed.

It also asserts that delivered plus refused equals sent, rather than that everything was delivered. The listener counts connections that are still closing towards `tcp-max-connections`, so 500 sequential connections in 0.3s are refused partway through at a limit of 256: the run measured 285 delivered and 215 refused. Nothing was lost, and raising the limit delivers all 500, but a sender that reconnects per message needs that limit set with this in mind.

## Extra runs

All pass against a real instance:

| Check | Result |
| --- | --- |
| 20000 messages on one long-lived connection | 20000 delivered, no loss |
| 500 connections in 0.3s | 285 delivered, 215 refused by the limit, none lost |
| UDP and TCP at the same time | both deliver |
| `reloadDaemonConfig` | both listeners rebind |
| framing pinned to the wrong one | delivers nothing rather than corrupt events |
| frame past `tcp-max-message-size` | connection closed, 2 log lines, no flooding |
| unreadable TLS certificate path | listener refuses to start, port stays closed |

## Senders

`send.sh` runs the daemons as relays: they accept messages on a local TCP port and forward
them to the listener under test. The content therefore stays exactly what the script chose,
while the framing on the wire is the daemon's own, which is the part being tested.

Two things about the images, both learned the hard way. `rsyslog/syslog_appliance_alpine`
ignores `-f` because its entrypoint installs its own config, and it ships without
`lmnsd_gtls.so` so it cannot do TLS at all; `senders/rsyslog/` builds a Debian-based image
instead. syslog-ng has no `TCP_Framing` knob, so the framing is the driver: `network()`
emits newline-delimited RFC 3164 and `syslog()` emits octet-counted RFC 5424.

## Results

Confirmed against a real OpenNMS built from this branch, 5 messages per cell, exact counts,
no leaked length prefix and no stray carriage return in any of them:

| Sender | Framing | Transport | Result |
| --- | --- | --- | --- |
| rsyslog 8.2302 | traditional (LF) | plaintext | 5/5 |
| rsyslog 8.2302 | octet-counted | plaintext | 5/5 |
| rsyslog 8.2302 | octet-counted | TLS, server cert verified `x509/name` | 5/5 |
| rsyslog 8.2302 | octet-counted | mutual TLS | 5/5 |
| syslog-ng 4.12 `network()` | traditional (LF) | plaintext | 5/5 |
| syslog-ng 4.12 `syslog()` | octet-counted | plaintext | 5/5 |
| syslog-ng 4.12 `syslog()` | octet-counted | TLS, `peer-verify(required-trusted)` | 5/5 |
| syslog-ng 4.12 `syslog()` | octet-counted | mutual TLS | 5/5 |
| raw frames | both | plaintext and TLS | 5/5 |

The listener logged `Detected non-transparent framing` for the LF cells and
`Detected octet-counting framing` for the others, so auto-detection was doing the work
rather than both cells happening to land on one default.

The same cells pass through a Minion, with both daemons and both framings, reaching the core
over the sink. Those events are attributed to the Minion's `systemid` and location in the
`events` table rather than to the core, which is what proves they actually took that path.

Mutual TLS was checked against `tcp-tls-client-auth="require"` rather than only `optional`:
rsyslog with a client certificate delivered 5, and the same rsyslog without one delivered 0.
Under `optional` both would have passed and proved nothing.

`run-matrix.sh` appends a table to `results.md` on each run.
