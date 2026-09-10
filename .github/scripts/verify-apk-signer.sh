#!/usr/bin/env bash
# Verifies that an APK's signer certificate SHA-256 fingerprint matches an expected
# value. Shared by the manual signed-smoke build and the public release workflow so
# that neither can treat a debug-signed (or otherwise wrongly-signed) APK as a valid
# release build.
#
# Usage: verify-apk-signer.sh <apk-path> <expected-sha256>
#
# <expected-sha256> is the release certificate's SHA-256 digest, with or without
# colon separators (case-insensitive). The expected fingerprint is not hardcoded here:
# callers pass it in from a secret/variable, and the real value is provisioned only by a
# future Gate mission.
#
# What *is* hardcoded is a deny-list. The repository's committed debug key is public, so
# an APK signed with it can never be a release, no matter what the caller says the
# expected fingerprint is. If RELEASE_CERT_SHA256 were ever set to the debug fingerprint
# by mistake, the match below would succeed and this script would still fail — on purpose.
set -euo pipefail

# Certificate SHA-256 digests that are never acceptable as a release signer.
# 1. debug.keystore / androiddebugkey, committed at the repository root.
DENIED_SHA256=(
  "234E2833E3E74D721B316C0DB5E87E112B3F74ED5F76D34E5D3208C504429EED"
)

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
if [ ! -x "$APKSIGNER" ] && [ -f "$APKSIGNER.bat" ]; then
  # A Windows SDK ships only the .bat launcher; the extensionless wrapper is absent.
  # Without this the script cannot run on the machine that holds the physical device.
  APKSIGNER="$APKSIGNER.bat"
fi
if [ ! -f "$APKSIGNER" ]; then
  echo "::error::apksigner not found at $APKSIGNER"
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

# Deny-list first, and against both values: a denied *expected* fingerprint is a
# misconfiguration that must not be allowed to validate anything.
for denied in "${DENIED_SHA256[@]}"; do
  if [ "$NORMALIZED_EXPECTED" = "$denied" ]; then
    echo "::error::The expected fingerprint is on the deny-list (this is the public debug key)."
    echo "::error::RELEASE_CERT_SHA256 must be the release certificate, never the debug certificate."
    exit 1
  fi
  if [ "$ACTUAL_SHA256" = "$denied" ]; then
    echo "::error::APK is signed with a deny-listed certificate (the public debug key): $ACTUAL_SHA256"
    echo "::error::This APK must not be released or distributed."
    exit 1
  fi
done

if [ "$ACTUAL_SHA256" != "$NORMALIZED_EXPECTED" ]; then
  echo "::error::APK signer certificate SHA-256 does not match the expected release certificate."
  echo "::error::Expected: $NORMALIZED_EXPECTED"
  echo "::error::Actual:   $ACTUAL_SHA256"
  exit 1
fi

echo "Signer certificate SHA-256 matches the expected release certificate."
