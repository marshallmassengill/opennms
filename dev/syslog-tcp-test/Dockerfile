FROM eclipse-temurin:21-jdk-jammy

# Tools the bin/opennms wrapper, the install step, and the verification scripts need.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        procps iproute2 curl jq less postgresql-client rsync rrdtool librrd8 netcat-openbsd openssl \
    && rm -rf /var/lib/apt/lists/*

# bin/opennms drops privileges with `runuser $RUNAS` (default: opennms), so the user has to
# exist. uid 1000 matches the host user that owns the bind-mounted install, so the container
# can write etc/, data/ and logs/ without a chown dance.
RUN useradd --uid 1000 --shell /bin/bash --home-dir /opt/opennms --no-create-home opennms \
    && useradd --uid 1001 --shell /bin/bash --home-dir /opt/minion --no-create-home minion

CMD ["sleep", "infinity"]
