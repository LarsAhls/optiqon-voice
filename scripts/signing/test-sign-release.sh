#!/usr/bin/env bash
# Exercises scripts/signing/sign-release.sh and .github/scripts/verify-apk-signer.sh with
# throw-away keys, so the deny-list and the lineage check are proven rather than assumed
# (Mission L1, definition of done item 4). Nothing here touches a real key.
#
# Usage: scripts/signing/test-sign-release.sh <unsigned-release.apk> [work-dir]
#
# Build the input with: ./gradlew assembleRelease -PunsignedRelease=true
# Needs keytool (JDK) and apksigner ($ANDROID_HOME/build-tools).
set -euo pipefail

APK="${1:-}"
WORK="${2:-$(mktemp -d)}"
[ -f "$APK" ] || { echo "usage: $0 <unsigned-release.apk> [work-dir]" >&2; exit 2; }
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SIGN="$REPO/scripts/signing/sign-release.sh"
VERIFY="$REPO/.github/scripts/verify-apk-signer.sh"
DEBUG_KS="$REPO/debug.keystore"
DEBUG_SHA="234E2833E3E74D721B316C0DB5E87E112B3F74ED5F76D34E5D3208C504429EED"

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
BT="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n1)"
APKSIGNER="$ANDROID_HOME/build-tools/$BT/apksigner"
[ -x "$APKSIGNER" ] || APKSIGNER="$APKSIGNER.bat"

mkdir -p "$WORK"
export RELEASE_STORE_PASSWORD=throwaway RELEASE_KEY_PASSWORD=throwaway
export OLD_STORE_PASSWORD=throwaway OLD_KEY_PASSWORD=throwaway

mk_key() { # name
  [ -f "$WORK/$1.jks" ] || "$KEYTOOL" -genkeypair -v -keystore "$WORK/$1.jks" -alias "$1" \
    -keyalg RSA -keysize 2048 -validity 30 -storepass throwaway -keypass throwaway \
    -dname "CN=throwaway $1, O=L1 test" >/dev/null 2>&1
}
sha_of() { # keystore alias storepass -> SHA-256 of the cert, normalised
  "$KEYTOOL" -list -v -keystore "$1" -alias "$2" -storepass "$3" 2>/dev/null \
    | grep -m1 'SHA256:' | sed -E 's/.*SHA256:[[:space:]]*//' | tr -d ':' | tr '[:lower:]' '[:upper:]'
}

mk_key A; mk_key B
SHA_A="$(sha_of "$WORK/A.jks" A throwaway)"
SHA_B="$(sha_of "$WORK/B.jks" B throwaway)"
DBG_CHECK="$(sha_of "$DEBUG_KS" androiddebugkey android)"
[ "$DBG_CHECK" = "$DEBUG_SHA" ] || { echo "debug.keystore SHA is $DBG_CHECK, expected $DEBUG_SHA" >&2; exit 2; }

PASS=0; FAIL=0
expect() { # expected-exit description -- command...
  local want="$1" desc="$2"; shift 3
  local got=0
  "$@" >"$WORK/last.log" 2>&1 || got=$?
  if [ "$got" = "$want" ]; then
    echo "PASS (exit $got)  $desc"; PASS=$((PASS+1))
  else
    echo "FAIL (exit $got, wanted $want)  $desc"; FAIL=$((FAIL+1)); sed 's/^/    | /' "$WORK/last.log" | tail -15
  fi
}

# --- sign-release.sh --------------------------------------------------------------------
expect 0 "correct key A, expected A -> signed" -- \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_a.apk" --ks "$WORK/A.jks" --alias A --expected-sha256 "$SHA_A"
[ -f "$WORK/out_a.apk" ] && echo "      output exists" || { echo "      output MISSING"; FAIL=$((FAIL+1)); }

expect 1 "debug key with debug SHA as expected -> refused by deny-list" -- \
  env RELEASE_STORE_PASSWORD=android RELEASE_KEY_PASSWORD=android \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_dbg.apk" --ks "$DEBUG_KS" --alias androiddebugkey --expected-sha256 "$DEBUG_SHA"
[ ! -f "$WORK/out_dbg.apk" ] && echo "      no output left behind" || { echo "      output LEFT BEHIND"; FAIL=$((FAIL+1)); }

expect 1 "debug key with A as expected -> signed output detected as debug and removed" -- \
  env RELEASE_STORE_PASSWORD=android RELEASE_KEY_PASSWORD=android \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_dbg2.apk" --ks "$DEBUG_KS" --alias androiddebugkey --expected-sha256 "$SHA_A"
[ ! -f "$WORK/out_dbg2.apk" ] && echo "      no output left behind" || { echo "      output LEFT BEHIND"; FAIL=$((FAIL+1)); }

expect 1 "key A but expected B -> mismatch, output removed" -- \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_mismatch.apk" --ks "$WORK/A.jks" --alias A --expected-sha256 "$SHA_B"
[ ! -f "$WORK/out_mismatch.apk" ] && echo "      no output left behind" || { echo "      output LEFT BEHIND"; FAIL=$((FAIL+1)); }

expect 1 "lineage requested but file missing -> refused" -- \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_nolin.apk" --ks "$WORK/B.jks" --alias B --expected-sha256 "$SHA_B" \
    --lineage "$WORK/does-not-exist.lineage" --old-ks "$WORK/A.jks" --old-alias A

expect 1 "already-signed input -> refused" -- \
  bash "$SIGN" --apk "$WORK/out_a.apk" --out "$WORK/out_resign.apk" --ks "$WORK/A.jks" --alias A --expected-sha256 "$SHA_A"

# Rotation A -> B with apksigner, then sign with lineage (both variants).
"$APKSIGNER" rotate --out "$WORK/ab.lineage" \
  --old-signer --ks "$WORK/A.jks" --ks-key-alias A --ks-pass pass:throwaway --key-pass pass:throwaway \
  --new-signer --ks "$WORK/B.jks" --ks-key-alias B --ks-pass pass:throwaway --key-pass pass:throwaway
expect 0 "lineage A->B, default rotation-min-sdk, expected B -> signed" -- \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_lin_default.apk" --ks "$WORK/B.jks" --alias B --expected-sha256 "$SHA_B" \
    --lineage "$WORK/ab.lineage" --old-ks "$WORK/A.jks" --old-alias A
expect 0 "lineage A->B, rotation-min-sdk 28, expected B -> signed" -- \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_lin_28.apk" --ks "$WORK/B.jks" --alias B --expected-sha256 "$SHA_B" \
    --lineage "$WORK/ab.lineage" --old-ks "$WORK/A.jks" --old-alias A --rotation-min-sdk-version 28
expect 1 "lineage A->B but expected A (the old key) -> refused" -- \
  bash "$SIGN" --apk "$APK" --out "$WORK/out_lin_old.apk" --ks "$WORK/B.jks" --alias B --expected-sha256 "$SHA_A" \
    --lineage "$WORK/ab.lineage" --old-ks "$WORK/A.jks" --old-alias A

# --- verify-apk-signer.sh (CI) -----------------------------------------------------------
"$APKSIGNER" sign --out "$WORK/debug_signed.apk" --ks "$DEBUG_KS" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android "$APK"
expect 1 "CI verifier: debug-signed APK with debug SHA as expected -> still rejected" -- \
  bash "$VERIFY" "$WORK/debug_signed.apk" "$DEBUG_SHA"
expect 1 "CI verifier: debug-signed APK with A as expected -> rejected" -- \
  bash "$VERIFY" "$WORK/debug_signed.apk" "$SHA_A"
expect 0 "CI verifier: A-signed APK with A as expected -> accepted" -- \
  bash "$VERIFY" "$WORK/out_a.apk" "$SHA_A"
expect 1 "CI verifier: A-signed APK with B as expected -> rejected" -- \
  bash "$VERIFY" "$WORK/out_a.apk" "$SHA_B"

echo
echo "sign-release tests: $PASS passed, $FAIL failed (work dir: $WORK)"
[ "$FAIL" -eq 0 ]
