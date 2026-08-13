#!/usr/bin/env bash
#
# Counts the events a marker produced and checks their content.
#
# The count assertion is exact on purpose. Under-delivery means messages were dropped or
# mis-framed, and over-delivery means one message was split into several events, which is
# what a framing bug actually produces. A "at least N" assertion would pass straight over
# the second case.
#
# usage: verify.sh <base-url> <marker> <expected-count>
#
set -euo pipefail

BASE="$1"
MARKER="$2"
EXPECTED="$3"

AUTH="${ONMS_AUTH:-admin:admin}"
DEADLINE=$(( $(date +%s) + ${VERIFY_TIMEOUT:-90} ))

fetch() {
    curl -sf -u "$AUTH" -H 'Accept: application/json' \
        "$BASE/opennms/rest/events?limit=2000&orderBy=eventTime&order=desc" 2>/dev/null || echo '{}'
}

matching() {
    fetch | jq --arg m "$MARKER" '
        [ (.event? // []) | .[]
          | select(((.logMessage // "") + (.description // "")) | contains($m)) ]'
}

count=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    events="$(matching)"
    count="$(echo "$events" | jq 'length')"
    if [ "$count" -ge "$EXPECTED" ]; then
        break
    fi
    sleep 2
done

# Give a late duplicate a chance to show up, otherwise an over-delivery bug looks like a
# pass because the loop exited as soon as it saw enough.
sleep 3
events="$(matching)"
count="$(echo "$events" | jq 'length')"

status=0

if [ "$count" -ne "$EXPECTED" ]; then
    echo "FAIL count: expected exactly $EXPECTED events for $MARKER, found $count"
    status=1
else
    echo "ok count: $count"
fi

# A leading length prefix in the body is the classic octet-counting bug: the listener
# treated the frame header as message content.
leaked="$(echo "$events" | jq -r '[ .[] | select((.logMessage // "") | test("^[0-9]+ +<")) ] | length')"
if [ "$leaked" != "0" ]; then
    echo "FAIL framing: $leaked events carry a leaked length prefix"
    echo "$events" | jq -r '.[] | select((.logMessage // "") | test("^[0-9]+ +<")) | .logMessage' | head -3
    status=1
else
    echo "ok no leaked length prefix"
fi

# Nothing downstream strips a CR, so a CRLF sender would leave one in the body.
cr="$(echo "$events" | jq -r '[ .[] | select((.logMessage // "") | test("\r")) ] | length')"
if [ "$cr" != "0" ]; then
    echo "FAIL framing: $cr events carry a trailing carriage return"
    status=1
else
    echo "ok no stray carriage return"
fi

if [ -n "${EXPECT_SOURCE:-}" ]; then
    # The event must be attributed to the real sender, not to the Minion or a proxy.
    bad="$(echo "$events" | jq -r --arg s "$EXPECT_SOURCE" \
        '[ .[] | select((.source // "") != "" and (.ipAddress // "") != $s) ] | length')"
    if [ "$bad" != "0" ]; then
        echo "WARN source: $bad events are not attributed to $EXPECT_SOURCE"
        echo "$events" | jq -r '.[] | .ipAddress' | sort -u | head -5
    else
        echo "ok source attribution"
    fi
fi

exit "$status"
