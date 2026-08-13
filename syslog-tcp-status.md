# Syslog over TCP: where this stands

Paused 2026-08-13. Branch `syslog-tcp-ingestion`, 15 commits, pushed to `origin` (marshallmassengill/opennms). Worktree `/home/marshall/Development/opennms-worktrees/syslog-tcp`, based on `upstream/release-36.x` at 485fc29585d. Plan it was built from: repo-root `syslog-tcp-ingestion-plan.md`.

## What works and is verified

`mvn -o verify -pl features/events/syslog -DskipITs=false` is green: 97 unit tests (2 pre-existing skips) and every IT, including the pre-existing `SyslogdIT` 17/17, `SyslogdLoadIT`, `SyslogdImplementationsIT`, `SyslogReloadDaemonIT` and both existing blueprint ITs.

New coverage: 25 decoder unit tests, 10 config tests, 6 TLS-context tests, 9 TCP ITs, 7 TLS ITs, 3 multi-listener ITs.

Verified end to end against a real OpenNMS built from this branch (`opennms-full-assembly -P dir` plus the Minion tarball, staged into `dev/syslog-tcp-test/`):

- UDP and TCP listeners both up under one Syslogd.
- LF-framed messages over TCP: exact event count, correct bodies.
- Octet-counted messages over TCP: exact event count, `hostname`, `severity`, `timestamp`, `process` all parsed, no leaked length prefix.
- TLS context builds and the `reloadDaemonConfig` path picks it up on a new port.

The senders used were a Python socket client, `nc`, `openssl s_client`, and Java.

## The open gap

**rsyslog and syslog-ng have never actually run.** They are scripted in `dev/syslog-tcp-test/scripts/send.sh` but that script is unexecuted, so the generated rsyslog and syslog-ng configs in it are unproven. This matters more than anything else outstanding, because the whole framing auto-detect design exists precisely because those two daemons disagree on defaults. Treat `send.sh` as a draft.

Also unrun: `scripts/run-matrix.sh` (the 8-cell matrix across both ingestion paths), `scripts/extra-runs.sh` (sustained load, connection churn, reload, and the failure modes), and the `SyslogTcpIT` smoke test.

## Design, and the one thing that changed mid-flight

The TCP socket is **not** a `SyslogReceiver`. It is a `SyslogTcpListener` component that whichever receiver owns the sink dispatcher starts and stops.

That was not the original shape. The first implementation ran it as a second `SyslogReceiver` under a `Syslogd` that held a list. A real instance rejected that:

```
IllegalArgumentException: A metric named Syslog.queue-size already exists
    at org.opennms.core.ipc.sink.common.AsyncDispatcherImpl.<init>
```

Two receivers each create a dispatcher for the same sink module, the Sink API names its metrics after the module id, and the second registration throws and kills its listener. Nondeterministic: on one start the loser was the UDP listener that installs already depend on. The ITs could not catch it because `MockMessageDispatcherFactory` returns a fresh `MetricRegistry` per call while the production factory shares one. `SyslogdMultiListenerIT` now runs against a factory that shares one registry, which is what makes it a regression test rather than a test that cannot fail.

Consequences of the one-receiver shape: `Syslogd` is untouched, both existing receiver implementations get TCP without knowing about it, and on a Minion TCP is configuration on the listener feature already installed rather than a second feature that would have collided the same way inside one Karaf.

Deliberate and settled: framing is auto-detected on the first frame of a connection then latched; messages from one connection are dispatched one at a time in arrival order; reads are switched off while a dispatch is outstanding so a full sink queue never blocks a Netty worker; a bad certificate path stops the listener rather than falling back to plaintext.

One Syslogd runs one TCP listener, so plaintext and TLS cannot be reachable at once. That is why the verification matrix runs in two passes with a reload between, and it is recorded in the docs.

## Config surface

Core, `etc/syslogd-configuration.xml`: `syslog-tcp-port` (unset means off), `tcp-listen-address`, `tcp-framing` (`auto`|`octet-counting`|`non-transparent`), `tcp-max-message-size`, `tcp-max-connections`, `tcp-idle-timeout`, `tcp-tls-enabled`, `tcp-tls-cert-filepath`, `tcp-tls-private-key-filepath`, `tcp-tls-trust-cert-filepath`, `tcp-tls-client-auth` (`none`|`optional`|`require`). The XSD `syslog.xsd` was updated; the plan wrongly claimed there was none.

Minion, `etc/org.opennms.netmgt.syslog.cfg`: the same as `syslog.tcp.*` keys, on the existing listener feature. `syslog.tcp.listen.port` defaults to `0`, which means off, because the .cfg always carries the key.

## Environment notes worth keeping

The `dir` assembly resolves helpers through the absolute paths it was built at, so `start-core.sh` symlinks those to the mount. jrrd2 comes from the host (`/usr/lib/jni`, `/usr/share/java`) because the `dir` profile does not ship it. Syslogd is enabled with `CORE_SERVICE_SYSLOGD_ENABLED=true`, not by editing service-configuration.xml. `bin/install` runs as root and leaves `data/tmp` root-owned, which hangs the boot at Eventd with only a repeating kahadb lock line; `start-core.sh` now chowns after install.

The environment's UDP port was moved from 10514 to 10516. 10514 is `SyslogClient.PORT` in the syslog module's own tests, and publishing it from the container made four unrelated ITs fail with "Address already in use" whenever the environment was up. That cost about an hour of chasing a phantom regression, so do not move it back.

`core/`, `minion/` and `certs/` under `dev/syslog-tcp-test/` are gitignored: staged from built assemblies and generated. An earlier commit swallowed 10,986 of those files and was rewritten out.

## Picking it back up

The containers are still running. `docker compose stop` in `dev/syslog-tcp-test/` preserves everything; `docker compose down` deletes the Postgres container and the schema has to be reinstalled with `docker exec syslog-tcp-core /opt/opennms/bin/install -dis`.

The immediate next step, interrupted mid-command, is redeploying the post-refactor jars into the running install, because it still has pre-refactor ones and therefore no TCP listener:

```sh
cd /home/marshall/Development/opennms-worktrees/syslog-tcp
D=dev/syslog-tcp-test/core
cp features/events/syslog/target/org.opennms.features.events.syslog-36.0.4-SNAPSHOT.jar $D/lib/
cp opennms-config-model/target/opennms-config-model-36.0.4-SNAPSHOT.jar $D/lib/
cp features/events/syslog/target/org.opennms.features.events.syslog-36.0.4-SNAPSHOT.jar \
   $D/system/org/opennms/features/events/org.opennms.features.events.syslog/36.0.4-SNAPSHOT/
find $D/system -path "*config-model*" -name "*.jar" \
   -exec cp opennms-config-model/target/opennms-config-model-36.0.4-SNAPSHOT.jar {} \;
```

Then restart the core and confirm `Listening for syslog messages over TCP` appears in `core/logs/syslogd.log`. A cleaner alternative is to rebuild the assembly and re-run `stage.sh`, which avoids hand-patching an install.

After that, in order: get rsyslog working through `send.sh` and check the count, then syslog-ng, then the matrix, then `extra-runs.sh`, then the `SyslogTcpIT` smoke test.

Note that `core/logs/syslogd.log` had grown to 96MB from repeated DEBUG restarts, so truncate it before reading.
