#!/usr/bin/env bash
#
# Runs the acceptance matrix: every sender, framing and transport combination, against both
# ingestion paths (straight into the core, and via the Minion so the sink hop is exercised).
#
# Results are printed as a table and appended to results.md.
#
set -uo pipefail

cd "$(dirname "$0")/.."
. ./env.sh

SCRIPTS=./scripts
RESULTS=results.md
STAMP="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

pass=0
fail=0
declare -a rows

run_cell() {
    local sender="$1" framing="$2" transport="$3" path="$4" host="$5" port="$6"
    local marker="onmstcp-$(echo "$sender$framing$transport$path" | tr -cd 'a-z0-9')-$RANDOM"

    printf '\n=== %-9s %-14s %-5s via %-6s -> %s:%s\n' \
        "$sender" "$framing" "$transport" "$path" "$host" "$port"

    if ! "$SCRIPTS/send.sh" "$sender" "$framing" "$transport" "$host" "$port" "$MATRIX_COUNT" "$marker"; then
        echo "  sender failed"
        rows+=("| $sender | $framing | $transport | $path | FAIL (sender) | 0/$MATRIX_COUNT |")
        fail=$((fail + 1))
        return
    fi

    local out
    out="$("$SCRIPTS/verify.sh" "$ONMS_BASE" "$marker" "$MATRIX_COUNT" 2>&1)"
    echo "$out" | sed 's/^/  /'

    local got
    got="$(echo "$out" | sed -n 's/^ok count: \([0-9]*\)$/\1/p;s/^FAIL count: .*found \([0-9]*\)$/\1/p' | head -1)"
    got="${got:-0}"

    if echo "$out" | grep -q '^FAIL'; then
        rows+=("| $sender | $framing | $transport | $path | FAIL | $got/$MATRIX_COUNT |")
        fail=$((fail + 1))
    else
        rows+=("| $sender | $framing | $transport | $path | pass | $got/$MATRIX_COUNT |")
        pass=$((pass + 1))
    fi
}

# sender framing transport
CELLS=(
    "rsyslog traditional plain"
    "rsyslog octet-counted plain"
    "rsyslog octet-counted tls"
    "rsyslog octet-counted mtls"
    "syslog-ng traditional plain"
    "syslog-ng octet-counted plain"
    "syslog-ng octet-counted tls"
    "raw traditional plain"
    "raw octet-counted plain"
    "raw octet-counted tls"
)

# Two passes, because a Syslogd instance has one TCP listener and so plaintext and TLS
# cannot be reachable at the same time. set-mode.sh swaps them with a reload, which also
# exercises the reload path once per pass.
for mode in plain tls; do
    echo
    echo "############ $mode pass ############"
    "$SCRIPTS/set-mode.sh" "$mode" || { echo "could not switch to $mode"; exit 1; }

    if [ "$mode" = "plain" ]; then
        core_port="$ONMS_SYSLOG_TCP"; minion_port="$MINION_SYSLOG_TCP"
    else
        core_port="$ONMS_SYSLOG_TLS"; minion_port="$MINION_SYSLOG_TLS"
    fi

    for cell in "${CELLS[@]}"; do
        read -r sender framing transport <<< "$cell"

        # plain cells belong to the plain pass, tls and mtls to the tls pass
        if [ "$mode" = "plain" ] && [ "$transport" != "plain" ]; then continue; fi
        if [ "$mode" = "tls" ] && [ "$transport" = "plain" ]; then continue; fi

        run_cell "$sender" "$framing" "$transport" "core" 127.0.0.1 "$core_port"
        run_cell "$sender" "$framing" "$transport" "minion" 127.0.0.1 "$minion_port"
    done
done

{
    echo
    echo "### Matrix run $STAMP"
    echo
    echo "| Sender | Framing | Transport | Path | Result | Events |"
    echo "| --- | --- | --- | --- | --- | --- |"
    for row in "${rows[@]}"; do
        echo "$row"
    done
    echo
    echo "$pass passed, $fail failed."
} | tee -a "$RESULTS"

echo
echo "$pass passed, $fail failed. Appended to $RESULTS"
[ "$fail" -eq 0 ]
