#!/usr/bin/env bash
# G2 postflight — verifies the Hosting release from the outside. Read-only GETs only.
# Run by Lars right after `firebase deploy --only hosting --project optiqon-voice-47498`.
#
# Usage: scripts/gate/g2-postflight.sh [project-id]
set -uo pipefail
PROJECT="${1:-optiqon-voice-47498}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
FAIL=0
check() { if [ "$1" = 0 ]; then echo "OK   $2"; else echo "FAIL $2"; FAIL=1; fi; }

for HOST in "$PROJECT.web.app" "$PROJECT.firebaseapp.com"; do
  URL="https://$HOST/.well-known/assetlinks.json"
  HDR="$(curl -sS -D - -o /tmp/assetlinks.$$ "$URL" 2>&1)"
  echo "$HDR" | head -1 | grep -q ' 200' ; check $? "$URL -> 200"
  echo "$HDR" | grep -qi '^content-type: application/json' ; check $? "$URL content-type application/json"
  node -e '
    const a = JSON.stringify(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")));
    const b = JSON.stringify(JSON.parse(require("fs").readFileSync(process.argv[2],"utf8")));
    process.exit(a === b ? 0 : 1)' /tmp/assetlinks.$$ "$REPO/public/.well-known/assetlinks.json"
  check $? "$URL content equals repo public/.well-known/assetlinks.json"
  rm -f /tmp/assetlinks.$$

  CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$HOST/signin")"
  [ "$CODE" = 200 ]; check $? "https://$HOST/signin -> $CODE (want 200)"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "https://$HOST/__/auth/links")"
  [ "$CODE" = 200 ] || [ "$CODE" = 400 ]; check $? "https://$HOST/__/auth/links -> $CODE (auth helper reachable)"
done

# Google's own view of the statement list (what App Links verification will see).
DAL="https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://$PROJECT.web.app&relation=delegate_permission/common.handle_all_urls"
curl -s "$DAL" | grep -q '"se.optiqon.voice"' ; check $? "Digital Asset Links API lists se.optiqon.voice for $PROJECT.web.app"

echo
[ $FAIL = 0 ] && echo "G2 postflight: PASS" || echo "G2 postflight: FAIL"
echo "Reminder (manual, needs firebase login): confirm the active Firestore ruleset id is unchanged:"
echo "  npx --no-install firebase firestore:rules:list --project $PROJECT   # or console > Firestore > Rules > history"
exit $FAIL
