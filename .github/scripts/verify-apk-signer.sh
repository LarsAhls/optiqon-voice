#!/usr/bin/env bash
# Verifies that an APK's signer certificate SHA-256 fingerprint matches an expected
# value. Shared by the manual signed-smoke build and the public release workflow so
# that neither can treat a debug-signed (or otherwise wrongly-signed) APK as a valid
# release build.
#
# Usage: verify-apk-signer.sh <apk-path> <expected-sha256>
#
# <expected-sha256> is the release certificate's SHA-256 digest, with or without
# colon separators (case-insensitive). No fingerprint is hardcoded here: callers pass
# it in from a secret/variable, and the real value is provisioned only by a future
# Gate mission.
set -euo pipefail

APK="${1:-}"
EXPECTED_SHA256="${2:-}"

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "::error::verify-apk-signer.sh: APK not found: $APK"
  exit 1
fi

if [ -z "$EXPECTED_SHA256" ]; then
  echo "::error::No expected release certificate SHA-256 fingerprint was provided."
  echo "::error::Refusing to verify signer identity without a known-good fingerprint to compare against."
  exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "::error::ANDROID_HOME is not set; cannot locate apksigner."
  exit 1
fi

# Deterministic build-tools selection: pick the highest installed version by
# version-sort rather than relying on a shell glob, which could silently match
# whichever build-tools directory happens to sort first.
BUILD_TOOLS_VERSION="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n1)"
if [ -z "$BUILD_TOOLS_VERSION" ]; then
  echo "::error::No Android build-tools versions found under $ANDROID_HOME/build-tools"
  exit 1
fi

APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
if [ ! -x "$APKSIGNER" ]; then
  echo "::error::apksigner not found or not executable at $APKSIGNER"
  exit 1
fi

echo "Using apksigner from build-tools $BUILD_TOOLS_VERSION"

SIGNER_OUTPUT="$("$APKSIGNER" verify --print-certs "$APK")"
echo "$SIGNER_OUTPUT"

ACTUAL_SHA256="$(printf '%s\n' "$SIGNER_OUTPUT" \
  | grep -i 'certificate SHA-256 digest' \
  | head -n1 \
  | sed -E 's/.*digest:[[:space:]]*//I' \
  | tr -d ':' \
  | tr '[:lower:]' '[:upper:]')"

NORMALIZED_EXPECTED="$(printf '%s' "$EXPECTED_SHA256" | tr -d ':' | tr '[:lower:]' '[:upper:]')"

if [ -z "$ACTUAL_SHA256" ]; then
  echo "::error::Could not extract a signer certificate SHA-256 digest from apksigner output."
  exit 1
fi

if [ "$ACTUAL_SHA256" != "$NORMALIZED_EXPECTED" ]; then
  echo "::error::APK signer certificate SHA-256 does not match the expected release certificate."
  echo "::error::Expected: $NORMALIZED_EXPECTED"
  echo "::error::Actual:   $ACTUAL_SHA256"
  exit 1
fi

echo "Signer certificate SHA-256 matches the expected release certificate."
