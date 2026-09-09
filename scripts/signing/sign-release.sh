#!/usr/bin/env bash
# Signs an *unsigned* release APK offline and refuses to hand back anything that is not
# provably signed by the key it was told to expect.
#
# This is the local counterpart of .github/scripts/verify-apk-signer.sh: CI is already
# fail-closed (no RELEASE_* secrets, no release job), but a plain `./gradlew assembleRelease`
# on a developer machine silently signs with the committed, public debug key. The Gradle
# build now labels that APK "-devsigned"; this script is the guard. It only accepts an
# unsigned input (build with `./gradlew assembleRelease -PunsignedRelease=true`), signs it
# with apksigner, and then verifies the result before it is allowed to exist:
#
#   1. the effective signer certificate SHA-256 equals --expected-sha256,
#   2. no signer certificate is on the deny-list (the debug key is, permanently),
#   3. when --lineage was given, the APK actually carries that lineage and the expected
#      key is its newest certificate.
#
# Any failure removes the output file and exits 1.
#
# Usage:
#   scripts/signing/sign-release.sh \
#     --apk app/build/outputs/apk/release/app-release-unsigned.apk \
#     --out dist/optiqon-voice-<version>.apk \
#     --ks /secure/optiqon-voice-beta.jks --alias optiqon-voice-beta \
#     --expected-sha256 <beta certificate SHA-256> \
#     [--lineage /secure/beta.lineage --old-ks debug.keystore --old-alias androiddebugkey] \
#     [--rotation-min-sdk-version 28]
#
# Passwords are never given on the command line. apksigner reads them from the
# environment when these are set, and prompts on the terminal when they are not:
#   RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD   the signing key (--ks/--alias)
#   OLD_STORE_PASSWORD / OLD_KEY_PASSWORD           the previous signer (--old-ks/--old-alias)
#
# Requires apksigner (Android build-tools; 0.9 or later for --rotation-min-sdk-version)
# under $ANDROID_HOME.
set -euo pipefail

# Certificate SHA-256 digests that must never sign a distributable APK.
# 1. debug.keystore / androiddebugkey, committed at the repository root.
DENIED_SHA256=(
  "234E2833E3E74D721B316C0DB5E87E112B3F74ED5F76D34E5D3208C504429EED"
)

APK="" OUT="" KS="" ALIAS="" EXPECTED="" LINEAGE="" OLD_KS="" OLD_ALIAS="" ROTATION_MIN_SDK=""

fail() { echo "sign-release: ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --ks) KS="$2"; shift 2 ;;
    --alias) ALIAS="$2"; shift 2 ;;
    --expected-sha256) EXPECTED="$2"; shift 2 ;;
    --lineage) LINEAGE="$2"; shift 2 ;;
    --old-ks) OLD_KS="$2"; shift 2 ;;
    --old-alias) OLD_ALIAS="$2"; shift 2 ;;
    --rotation-min-sdk-version) ROTATION_MIN_SDK="$2"; shift 2 ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[ -n "$APK" ] && [ -f "$APK" ] || fail "--apk <unsigned.apk> is required and must exist"
[ -n "$OUT" ] || fail "--out <signed.apk> is required"
[ -n "$KS" ] && [ -f "$KS" ] || fail "--ks <keystore> is required and must exist"
[ -n "$ALIAS" ] || fail "--alias <key alias> is required"
[ -n "$EXPECTED" ] || fail "--expected-sha256 <certificate digest> is required"
[ -n "${ANDROID_HOME:-}" ] || fail "ANDROID_HOME is not set; cannot locate apksigner"

normalize() { printf '%s' "$1" | tr -d ': ' | tr '[:lower:]' '[:upper:]'; }
EXPECTED_N="$(normalize "$EXPECTED")"
[ ${#EXPECTED_N} -eq 64 ] || fail "--expected-sha256 must be a 64-hex-digit SHA-256 digest"

for denied in "${DENIED_SHA256[@]}"; do
  [ "$EXPECTED_N" != "$denied" ] || fail "the expected certificate is deny-listed (public debug key); refusing"
done

if [ -n "$LINEAGE" ]; then
  [ -f "$LINEAGE" ] || fail "--lineage was given but the file does not exist: $LINEAGE"
  [ -n "$OLD_KS" ] && [ -f "$OLD_KS" ] || fail "--lineage requires --old-ks <previous keystore> (must exist)"
  [ -n "$OLD_ALIAS" ] || fail "--lineage requires --old-alias <previous key alias>"
fi
if [ -n "$ROTATION_MIN_SDK" ]; then
  [ -n "$LINEAGE" ] || fail "--rotation-min-sdk-version only makes sense together with --lineage"
fi

BUILD_TOOLS_VERSION="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n1)"
[ -n "$BUILD_TOOLS_VERSION" ] || fail "no build-tools under $ANDROID_HOME/build-tools"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
if [ ! -x "$APKSIGNER" ] && [ -f "$APKSIGNER.bat" ]; then APKSIGNER="$APKSIGNER.bat"; fi
[ -f "$APKSIGNER" ] || fail "apksigner not found at $APKSIGNER"
echo "sign-release: apksigner from build-tools $BUILD_TOOLS_VERSION"

# 0. The input must be unsigned. A signed input means somebody is re-signing an artefact
#    that already carries an identity (typically the -devsigned Gradle fallback); the
#    distribution chain builds unsigned on purpose so that this script is the only signer.
if "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
  fail "input APK is already signed; build it with ./gradlew assembleRelease -PunsignedRelease=true"
fi

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT" "$OUT.idsig"
# Anything but a clean finish removes the output: a half-verified APK must not exist.
SIGNED_OK=0
trap '[ "$SIGNED_OK" = 1 ] || rm -f "$OUT" "$OUT.idsig"' EXIT

pass_args() {
  # $1 = env var name for the store password, $2 = env var for the key password.
  # When unset, apksigner prompts; the password is never on the command line.
  local out=()
  [ -n "${!1:-}" ] && out+=(--ks-pass "env:$1")
  [ -n "${!2:-}" ] && out+=(--key-pass "env:$2")
  printf '%s\n' "${out[@]}"
}

# 1. Sign.
SIGN_CMD=("$APKSIGNER" sign --out "$OUT")
if [ -n "$LINEAGE" ]; then
  # With a lineage the *previous* signer produces the v1/v2 (and, below the rotation
  # minimum, v3) signatures, and the new key signs for platforms that honour rotation.
  # That is how apksigner is meant to be called and it is why both keystores are needed.
  SIGN_CMD+=(--lineage "$LINEAGE")
  [ -n "$ROTATION_MIN_SDK" ] && SIGN_CMD+=(--rotation-min-sdk-version "$ROTATION_MIN_SDK")
  SIGN_CMD+=(--ks "$OLD_KS" --ks-key-alias "$OLD_ALIAS")
  while IFS= read -r a; do [ -n "$a" ] && SIGN_CMD+=("$a"); done < <(pass_args OLD_STORE_PASSWORD OLD_KEY_PASSWORD)
  SIGN_CMD+=(--next-signer --ks "$KS" --ks-key-alias "$ALIAS")
  while IFS= read -r a; do [ -n "$a" ] && SIGN_CMD+=("$a"); done < <(pass_args RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD)
else
  SIGN_CMD+=(--ks "$KS" --ks-key-alias "$ALIAS")
  while IFS= read -r a; do [ -n "$a" ] && SIGN_CMD+=("$a"); done < <(pass_args RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD)
fi
SIGN_CMD+=("$APK")
"${SIGN_CMD[@]}"

# 2. Verify the effective signer. With a lineage, the key that matters is the one a
#    platform at or above the rotation minimum will see, so verification is pinned there;
#    older platforms are, by design, still signed by the previous key.
VERIFY_ARGS=(verify --print-certs)
if [ -n "$LINEAGE" ]; then
  VERIFY_ARGS+=(--min-sdk-version "${ROTATION_MIN_SDK:-33}")
fi
VERIFY_OUT="$("$APKSIGNER" "${VERIFY_ARGS[@]}" "$OUT")"
echo "$VERIFY_OUT"

mapfile -t ACTUAL_DIGESTS < <(printf '%s\n' "$VERIFY_OUT" \
  | grep -i 'certificate SHA-256 digest' \
  | sed -E 's/.*digest:[[:space:]]*//I' | tr -d ':' | tr '[:lower:]' '[:upper:]')
[ ${#ACTUAL_DIGESTS[@]} -gt 0 ] || fail "could not read any signer certificate from apksigner"

for d in "${ACTUAL_DIGESTS[@]}"; do
  for denied in "${DENIED_SHA256[@]}"; do
    [ "$d" != "$denied" ] || fail "output is signed with a deny-listed certificate ($d); removed"
  done
done
[ "${ACTUAL_DIGESTS[0]}" = "$EXPECTED_N" ] \
  || fail "effective signer ${ACTUAL_DIGESTS[0]} != expected $EXPECTED_N; output removed"

# 3. When a lineage was requested it must be present in the APK, and the expected key must
#    be the newest certificate in it.
if [ -n "$LINEAGE" ]; then
  LINEAGE_OUT="$("$APKSIGNER" lineage --print-certs --in "$OUT" 2>&1)" \
    || fail "output carries no signing lineage although one was requested; removed"
  mapfile -t LINEAGE_DIGESTS < <(printf '%s\n' "$LINEAGE_OUT" \
    | grep -i 'certificate SHA-256 digest' \
    | sed -E 's/.*digest:[[:space:]]*//I' | tr -d ':' | tr '[:lower:]' '[:upper:]')
  [ ${#LINEAGE_DIGESTS[@]} -ge 2 ] || fail "lineage in output has fewer than two certificates; removed"
  LAST="${LINEAGE_DIGESTS[${#LINEAGE_DIGESTS[@]}-1]}"
  [ "$LAST" = "$EXPECTED_N" ] || fail "newest lineage certificate $LAST != expected $EXPECTED_N; removed"
  echo "sign-release: lineage present with ${#LINEAGE_DIGESTS[@]} certificates; newest = expected"
fi

SIGNED_OK=1
echo "sign-release: OK -> $OUT (signer $EXPECTED_N)"
