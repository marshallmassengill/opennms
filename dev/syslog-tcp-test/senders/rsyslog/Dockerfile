# rsyslog with the gnutls netstream driver. The rsyslog/syslog_appliance_alpine image
# ships without lmnsd_gtls.so, so it cannot send over TLS at all.
FROM debian:12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends rsyslog rsyslog-gnutls netcat-openbsd ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENTRYPOINT ["/usr/sbin/rsyslogd"]
CMD ["-n", "-f", "/etc/rsyslog.conf"]
