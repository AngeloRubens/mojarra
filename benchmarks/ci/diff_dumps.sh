#!/usr/bin/env bash
# Diffs two response dump directories after masking the values that legitimately differ per request
# (view state ids, client window ids, CSP nonces). Exits non-zero when any response differs.
set -uo pipefail
a=$1 b=$2
norm() {
    sed -E -e 's/(ViewState[^>]*value=")[^"]*/\1VS/g' -e 's/(ViewState:[0-9]+"><!\[CDATA\[)[^]]*/\1VS/g' \
           -e 's/(ClientWindow[^>]*value=")[^"]*/\1CW/g' -e 's/nonce="[^"]*"/nonce="N"/g' "$1"
}
status=0
for f in "$a"/*; do
    name=$(basename "$f")
    if [ ! -f "$b/$name" ]; then echo "missing in $b: $name"; status=1; continue; fi
    if ! diff -q <(norm "$f") <(norm "$b/$name") >/dev/null; then
        echo "DIFFERS: $name"; diff <(norm "$f") <(norm "$b/$name") | head -20; status=1
    fi
done
echo "compared $(ls "$a" | wc -l) responses"
exit $status
