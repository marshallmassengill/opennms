# Syslog over TCP: where this stands

Branch `syslog-tcp-ingestion`, based on `upstream/release-36.x` at 485fc29585d, pushed to `origin` (marshallmassengill/opennms). Worktree `/home/marshall/Development/opennms-worktrees/syslog-tcp`. Plan it was built from: repo-root `syslog-tcp-ingestion-plan.md`.

## State

Feature complete and verified end to end on both ingestion paths.

`mvn -o verify -pl features/events/syslog -DskipITs=false` is green: 97 unit tests and 63 integration tests. Note that the DB-backed ITs need a Postgres; with the end-to-end environment up, point them at it with `-Dmock.db.url=jdbc:postgresql://localhost:5436/ -Dmock.db.adminUser=postgres -Dmock.db.adminPassword=postgres`.

`dev/syslog-tcp-test/scripts/run-matrix.sh` reports 20 of 20 cells passing at 25 messages each: rsyslog, syslog-ng and hand-built frames, both RFC 6587 framings, plaintext, TLS and mutual TLS, against both the core and a Minion. `extra-runs.sh` also passes throughout: 20000 messages on one connection with no loss, connection churn accounted for, both transports at once, reload, and the three failure modes.

## The three bugs the end-to-end work found

None of these were reachable from the module tests as they stood, because `MockMessageDispatcherFactory` builds a dispatcher that never blocks, always completes its futures, and hands out a fresh `MetricRegistry` per call. Each now has a regression test that fails on the code that preceded it.

**Two dispatchers for one sink module.** Running the TCP listener as a second `SyslogReceiver` meant two `AsyncDispatcher` instances for the same module id, and the Sink API names its metrics after that id, so the second registration threw `A metric named Syslog.queue-size already exists` and killed whichever listener lost the race, sometimes the UDP one that installs already rely on. The TCP socket is now a `SyslogTcpListener` owned by whichever receiver owns the dispatcher. `SyslogdMultiListenerIT` runs against a factory that shares one registry.

**Dispatching on the Netty event loop.** `AsyncDispatcher.send()` blocks once the sink queue fills and the module asks for `blockWhenFull`, which `SyslogSinkModule` does. Calling it from `channelRead0` blocked the event loop, so a Minion ingested one message per connection and then stopped reading, silently. The dispatch runs on the listener's own pool now. `SyslogTcpListenerDispatchIT` asserts `send()` is never called from a thread named `nioEventLoopGroup`.

**Depending on the dispatch future.** After a Minion configuration reload the sink delivers messages but completes the wrong futures, logging `No future found for message`. Reading resumed on that future, so ingestion stalled again with the message already delivered. The wait is now bounded at thirty seconds, and the first timeout turns confirmation off for the rest of that connection with a warning, because paying the timeout per message delivered only 6 of 25 in two minutes. Ordering still holds whenever the sink confirms dispatches, which is the normal case; when it stops, the log says so.

## Design, settled

The TCP socket is not a `SyslogReceiver`. `SinkDispatchingSyslogReceiver` owns one dispatcher and starts a `SyslogTcpListener` when a TCP port is configured, so both existing receiver implementations get TCP without knowing about it, `Syslogd` is untouched, and a Minion configures TCP on the listener feature it already has rather than installing a second one.

Framing is detected on the first frame of a connection and then latched. Messages from one connection are dispatched one at a time in arrival order. Reads are paused while a dispatch is outstanding, so a slow sink becomes TCP backpressure rather than unbounded buffering. A bad certificate path stops the listener rather than falling back to plaintext.

One Syslogd runs one TCP listener, so plaintext and TLS cannot both be reachable. The verification matrix therefore runs in two passes with a reload between.

## Config surface

Core, `etc/syslogd-configuration.xml`: `syslog-tcp-port` (unset means off), `tcp-listen-address`, `tcp-framing` (`auto`|`octet-counting`|`non-transparent`), `tcp-max-message-size`, `tcp-max-connections`, `tcp-idle-timeout`, `tcp-tls-enabled`, `tcp-tls-cert-filepath`, `tcp-tls-private-key-filepath`, `tcp-tls-trust-cert-filepath`, `tcp-tls-client-auth` (`none`|`optional`|`require`). `syslog.xsd` was updated; the plan wrongly claimed there was no XSD.

Minion, `etc/org.opennms.netmgt.syslog.cfg`: the same as `syslog.tcp.*` keys on the existing listener feature, with `syslog.tcp.listen.port` defaulting to `0`, meaning off, because the .cfg always carries the key.

## Environment notes worth keeping

Documented in `dev/syslog-tcp-test/README.md` and encoded in the scripts, but the ones that cost the most time:

Never publish container port 10514: it is `SyslogClient.PORT` in this module's own ITs, and four unrelated ITs then fail with "Address already in use". The environment uses 10516.

Never probe a published port from the host to decide whether something is listening. docker-proxy answers either way. Two checks reported passes as failures that way, and `set-mode.sh` once let the matrix start its TLS pass while the Minion was six minutes from applying the config.

The Minion must not start before the core's ActiveMQ broker accepts connections, or its sink Camel context stays stopped and every dispatch fails with `CamelContext is stopped` while the syslog listener still binds and decodes correctly. `start-minion.sh` waits for the broker. The shipped broker config only has the vm:// connector, so `stage.sh` uncomments the openwire one.

The `dir` assembly resolves helpers through the absolute paths it was built at, jrrd2 comes from the host, `bin/install` leaves `data/tmp` root-owned which hangs the boot at Eventd, and Syslogd is enabled with `CORE_SERVICE_SYSLOGD_ENABLED`. All handled by `start-core.sh` and `stage.sh`.

Interrupted test runs leave an OpenNMS JVM holding eventd's port 5817, which makes every DB-backed IT fail with "Failed to load ApplicationContext". Check `ss -tlnp | grep 5817` before believing such a failure.

## The smoke test cannot run on this machine

`SyslogTcpIT` compiles, and `mvn -o verify -P smoke.minion -DskipITs=false -Dit.test=SyslogTcpIT` does select and execute it, but it dies before any test logic:

```
UnixSocketClientProviderStrategy: failed with exception BadRequestException
  (Status 400: client version 1.32 is too old. Minimum supported API version is 1.40)
```

The docker-java client bundled with the smoke-test framework on this branch speaks Docker API 1.32 and the daemon here requires 1.40 or newer. `DOCKER_API_VERSION` does not help, the version is fixed by the dependency. This is not specific to the new test: the pre-existing `SyslogIT` fails identically, so no smoke test runs locally until that dependency moves. CI presumably runs an older daemon.

Note that `-P smoke.minion` alone is not enough, the profile leaves `skipITs=true` and the build reports success having run nothing.

The container images the test would need are built and present from this branch as `opennms/horizon:36.0.4-SNAPSHOT` and `opennms/minion:36.0.4-SNAPSHOT`, both verified to carry the final listener fix. `opennms/horizon:latest` was left pointing at the stock 2025 image it pointed at before, because the smoke tests are what wanted `:latest` and they cannot run; retag the branch build if that changes.

## Remaining

Running `SyslogTcpIT` needs either an older Docker daemon or a smoke-test framework with a newer docker-java.

Rebuilding the Minion needs `features/minion/repository` rebuilt first and the assembly built with `clean`, or the tarball ships a stale blueprint from a cached staging directory. The container image builds do not work through `make image` here either: the nested make dies at `check-docker-buildx-default` with exit 255 before the check even prints. Calling `docker buildx build` directly with `--builder=default` works, and the Makefile has already unpacked the tarball into `tarball-root/` by then.
