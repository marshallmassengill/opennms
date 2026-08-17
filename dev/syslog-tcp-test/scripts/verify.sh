#!/usr/bin/env bash
#
# Counts the events a marker produced and checks their content.
#
# The count assertion is exact on purpose. Under-delivery means messages were dropped or
# mis-framed, and over-delivery means one message was split into several events, which is
# what a framing bug actually produces. An "at least N" assertion would pass straight over
# the second case.
#
# The count comes from the v2 API's totalCount, which the server computes, rather than from
# counting a page client-side. An earlier version fetched limit=2000 and counted what came
# back, so it could never see more than 2000 events and reported a clean 20000-message run
# as losing 90% of them.
#
# usage: verify.sh <base-url> <marker> <expected-count>
#
set -euo pipefail

BASE="$1"
MARKER="$2"
EXPECTED="$3"

AUTH="${ONMS_AUTH:-admin:admin}"
DEADLINE=$(( $(date +%s) + ${VERIFY_TIMEOUT:-90} ))

# Matches either field: the syslog text lands in the description as parameters, and in
# logMessage when a ueiMatch rewrites it.
FIQL="eventDescr==*${MARKER}*,eventLogMsg==*${MARKER}*"

# Normalised to an integer: with no matches the endpoint answers 204 with no body, so both
# curl and jq yield nothing rather than a zero.
total() {
    local n
    n="$(curl -sf -u "$AUTH" -H 'Accept: application/json' \
        --get --data-urlencode "_s=$FIQL" --data-urlencode "limit=1" \
        "$BASE/opennms/api/v2/events" 2>/dev/null \
        | jq -r '.totalCount // 0' 2>/dev/null | tr -cd '0-9')"
    echo "${n:-0}"
}

# A page of matches, for the content checks. Sampled rather than exhaustive: the faults it
# looks for are properties of how a run was framed, so they affect every message in it.
sample() {
    curl -sf -u "$AUTH" -H 'Accept: application/json' \
        --get --data-urlencode "_s=$FIQL" --data-urlencode "limit=200" \
        "$BASE/opennms/api/v2/events" 2>/dev/null \
        | jq '[ (.event? // []) | .[] ]' 2>/dev/null || echo '[]'
}

count=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    count="$(total)"
    if [ "${count:-0}" -ge "$EXPECTED" ]; then
        break
    fi
    sleep 2
done

# Give a late duplicate a chance to show up, otherwise an over-delivery bug looks like a
# pass because the loop exited as soon as it saw enough.
sleep 3
count="$(total)"
events="$(sample)"

status=0

if [ "${count:-0}" -ne "$EXPECTED" ]; then
    echo "FAIL count: expected exactly $EXPECTED events for $MARKER, found ${count:-0}"
    status=1
else
    echo "ok count: $count"
fi

# A leading length prefix in the body is the classic octet-counting bug: the listener
# treated the frame header as message content.
leaked="$(echo "$events" | jq -r '[ .[] | select(((.logMessage // "") + (.description // "")) | test("[^0-9]?[0-9]+ +<[0-9]+>")) ] | length' 2>/dev/null || echo 0)"
if [ "${leaked:-0}" != "0" ]; then
    echo "FAIL framing: $leaked sampled events carry a leaked length prefix"
    echo "$events" | jq -r '.[] | (.logMessage // .description // "") | .[0:120]' | head -3
    status=1
else
    echo "ok no leaked length prefix"
fi

# Nothing downstream strips a CR, so a CRLF sender would leave one in the body.
cr="$(echo "$events" | jq -r '[ .[] | select(((.logMessage // "") + (.description // "")) | test("\r")) ] | length' 2>/dev/null || echo 0)"
if [ "${cr:-0}" != "0" ]; then
    echo "FAIL framing: $cr sampled events carry a trailing carriage return"
    status=1
else
    echo "ok no stray carriage return"
fi

exit "$status"
