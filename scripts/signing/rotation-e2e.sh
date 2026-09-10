#!/usr/bin/env bash
# Mission L1, B.2 — app-representative rotation test on the real OPTIQON Voice debug build.
#
# Proves, on one emulator, that an in-place update signed with a *new* key plus a v3 lineage
# (old key -> new key) leaves every persisted thing exactly as it was:
#   Room (profiles, replacement rules, dictation history), DataStore settings,
#   API keys in EncryptedSharedPreferences (AndroidKeyStore master key: proven by digest of the
#   decrypted read-back), the claim of the `default` storage root by a synthetic account and
#   its recorded APPROVED access snapshot.
#
# Steps
#   1. install v1 = the debug APK as built (signed with the repo debug key)
#   2. SEED_SYNTHETIC, DUMP_STATE                       -> before.txt
#   3. force-stop, relaunch, DUMP_STATE                 -> restart.txt   (control: dump is stable)
#   4. apksigner rotate debug -> throw-away B, re-sign the same APK with lineage (+rotation-min-sdk)
#   5. adb install -r v2                                -> must be Success
#   6. DUMP_STATE                                       -> after.txt     (must equal before.txt)
#      signer.txt must now show B as signer and debug+B as history (API >= 28)
#   7. negative: adb install -r of v1 again (old key only) -> must be refused where B is effective
#   8. DUMP_STATE                                       -> after_neg.txt (must still equal before.txt)
#
# Usage
#   scripts/signing/rotation-e2e.sh --serial emulator-5558 --work <dir> [--apk app/build/outputs/apk/debug/app-debug.apk]
#                                   [--rotation-min-sdk 28] [--evidence <dir>]
# The old key is the committed debug.keystore (public, password "android"); the new key is a
# throw-away generated into <dir>. Nothing here touches a real key, a real device or a provider.
# The target package is uninstalled first — only ever point this at an emulator.
set -euo pipefail

SERIAL="" WORK="" APK="" RMIN=28 EVID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --rotation-min-sdk) RMIN="$2"; shift 2 ;;
    --evidence) EVID="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERIAL" ] && [ -n "$WORK" ] || { sed -n '2,27p' "$0"; exit 2; }
case "$SERIAL" in emulator-*) ;; *) echo "refusing: $SERIAL is not an emulator serial" >&2; exit 2 ;; esac
[ -n "${ANDROID_HOME:-}" ] || { echo "ANDROID_HOME not set" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK" ] || { echo "missing $APK (run ./gradlew :app:assembleDebug)" >&2; exit 2; }
PKG=se.optiqon.voice
RECEIVER="$PKG/se.optiqon.voice.debug.AccessDebugReceiver"
ADB="$ANDROID_HOME/platform-tools/adb"; [ -x "$ADB" ] || ADB="$ADB.exe"
BT="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n1)"
APKSIGNER="$ANDROID_HOME/build-tools/$BT/apksigner"; [ -x "$APKSIGNER" ] || APKSIGNER="$APKSIGNER.bat"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
adb() { "$ADB" -s "$SERIAL" "$@"; }

API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
EVID="${EVID:-$WORK/evidence/e2e/api$API}"; mkdir -p "$EVID" "$WORK"
LOG="$EVID/run.log"; : > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }
fail() { say "FAIL: $*"; exit 1; }

# --- keys and lineage -----------------------------------------------------------------
OLD_KS="$ROOT/debug.keystore"; OLD_ALIAS=androiddebugkey; OLD_PW=android
PW=throwaway
[ -f "$WORK/B.jks" ] || "$KEYTOOL" -genkeypair -keystore "$WORK/B.jks" -alias B -keyalg RSA -keysize 2048 \
  -validity 30 -storepass $PW -keypass $PW -dname "CN=throwaway B, O=L1 rotation test" >/dev/null 2>&1
sha_of() { "$KEYTOOL" -list -v -keystore "$1" -alias "$2" -storepass "$3" 2>/dev/null \
  | grep -m1 'SHA256:' | sed -E 's/.*SHA256:[[:space:]]*//' | tr -d ':' | tr '[:lower:]' '[:upper:]'; }
SHA_OLD="$(sha_of "$OLD_KS" $OLD_ALIAS $OLD_PW)"; SHA_B="$(sha_of "$WORK/B.jks" B $PW)"
old_args=(--ks "$OLD_KS" --ks-key-alias $OLD_ALIAS --ks-pass pass:$OLD_PW --key-pass pass:$OLD_PW)
new_args=(--ks "$WORK/B.jks" --ks-key-alias B --ks-pass pass:$PW --key-pass pass:$PW)

LINEAGE="$WORK/debug_to_B.lineage"
[ -f "$LINEAGE" ] || "$APKSIGNER" rotate --out "$LINEAGE" --old-signer "${old_args[@]}" --new-signer "${new_args[@]}"
"$APKSIGNER" lineage --print-certs -v --in "$LINEAGE" > "$EVID/lineage.txt"

V2="$WORK/voice_v2_B_rmin$RMIN.apk"
"$APKSIGNER" sign --out "$V2" --lineage "$LINEAGE" --rotation-min-sdk-version "$RMIN" \
  "${old_args[@]}" --next-signer "${new_args[@]}" "$APK"
"$APKSIGNER" verify --print-certs -v "$V2" > "$EVID/v2_verify.txt" 2>&1 || fail "apksigner verify of v2 failed"
"$APKSIGNER" verify --print-certs -v "$APK" > "$EVID/v1_verify.txt" 2>&1 || fail "apksigner verify of v1 failed"

# --- device helpers -------------------------------------------------------------------
install() { local out; out="$(adb install -r "$1" 2>&1 | tr -d '\r')"; echo "install $(basename "$1"): $out" >> "$LOG"
  printf '%s' "$out" | grep -oE 'Success|INSTALL_FAILED_[A-Z_]+' | tail -n1; }
launch() { adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true; sleep 2; }
broadcast() { # action -> result line
  adb shell am broadcast -n "$RECEIVER" -a "se.optiqon.voice.debug.$1" 2>&1 | tr -d '\r' | grep -m1 'Broadcast completed' || echo "no result"; }
pull_state() { adb shell run-as "$PKG" cat files/debug/state.txt | tr -d '\r' > "$EVID/$1.txt"; }
pull_signer() { adb shell run-as "$PKG" cat files/debug/signer.txt 2>/dev/null | tr -d '\r' > "$EVID/$1" || true; }
dump_to() { local r; r="$(broadcast DUMP_STATE)"; say "  DUMP_STATE -> $r"
  case "$r" in *result=0*) ;; *) fail "dump failed: $r" ;; esac; pull_state "$1"; pull_signer "$1.signer.txt"; }
pkg_facts() { adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' \
  | grep -E 'versionCode=|firstInstallTime|lastUpdateTime|signatures=|past signers' | sed -E 's/^ +//' | head -8; }
same() { cmp -s <(grep -v "^#" "$EVID/$1.txt") <(grep -v "^#" "$EVID/$2.txt"); }  # "# ..." lines are context

say "== rotation-e2e: API $API ($(adb shell getprop ro.build.version.release | tr -d '\r')), rotation-min-sdk $RMIN =="
say "old (debug) key SHA-256: $SHA_OLD"
say "new (throw-away B)  SHA-256: $SHA_B"

# 1. clean install of v1
adb uninstall "$PKG" >/dev/null 2>&1 || true
R="$(install "$APK")"; say "1. install v1 (debug key): $R"; [ "$R" = Success ] || fail "v1 install"
launch

# 2. seed + dump
R="$(broadcast SEED_SYNTHETIC)"; say "2. SEED_SYNTHETIC -> $R"; case "$R" in *result=0*) ;; *) fail "seed" ;; esac
dump_to before; pkg_facts > "$EVID/pkg_before.txt"
FIRST_INSTALL="$(grep -m1 firstInstallTime "$EVID/pkg_before.txt" || true)"

# 3. restart control
adb shell am force-stop "$PKG"; launch; dump_to restart
same before restart || { diff "$EVID/before.txt" "$EVID/restart.txt" | tee -a "$LOG"; fail "dump not stable across a plain process restart"; }
say "3. restart control: state identical"

# 5. rotated update
R="$(install "$V2")"; say "5. install v2 (B + lineage, rotation-min-sdk $RMIN): $R"; [ "$R" = Success ] || fail "v2 install"
launch; dump_to after; pkg_facts > "$EVID/pkg_after.txt"
same before after || { diff "$EVID/before.txt" "$EVID/after.txt" | tee -a "$LOG"; fail "state changed across the rotated update"; }
say "6. state after rotated update: identical to before"
[ "$(grep -m1 firstInstallTime "$EVID/pkg_after.txt" || true)" = "$FIRST_INSTALL" ] || fail "firstInstallTime changed (not an in-place update)"

SIGNERS_AFTER="$(grep -m1 '^signers=' "$EVID/after.signer.txt" | cut -d= -f2 || true)"
HISTORY_AFTER="$(grep -m1 '^history=' "$EVID/after.signer.txt" | cut -d= -f2 || true)"
SIGNERS_BEFORE="$(grep -m1 '^signers=' "$EVID/before.signer.txt" | cut -d= -f2 || true)"
say "   platform signer before: $SIGNERS_BEFORE"
say "   platform signer after:  $SIGNERS_AFTER  history: $HISTORY_AFTER"
EXPECT_B=no; [ "$API" -ge "$RMIN" ] && [ "$API" -ge 28 ] && EXPECT_B=yes
if [ "$EXPECT_B" = yes ]; then
  [ "$SIGNERS_BEFORE" = "$SHA_OLD" ] || fail "v1 signer is not the debug key"
  [ "$SIGNERS_AFTER" = "$SHA_B" ] || fail "platform does not report B as signer after rotation"
  [ "$HISTORY_AFTER" = "$SHA_OLD,$SHA_B" ] || fail "signing history is not debug->B"
fi

# 7. negative: old key alone must no longer update
R="$(install "$APK")"; say "7. install v1 again (old key only): $R"
if [ "$EXPECT_B" = yes ]; then
  [ "$R" = INSTALL_FAILED_UPDATE_INCOMPATIBLE ] || fail "old key alone was accepted after rotation ($R)"
fi
launch; dump_to after_neg
same before after_neg || { diff "$EVID/before.txt" "$EVID/after_neg.txt" | tee -a "$LOG"; fail "state changed after the refused install"; }
say "8. state after refused install: identical to before"

adb uninstall "$PKG" >/dev/null 2>&1 || true
{
  echo "# rotation-e2e — API $API, rotation-min-sdk $RMIN — PASS"
  echo
  echo "| step | result |"
  echo "|---|---|"
  echo "| v1 install (debug key) | Success |"
  echo "| seed + dump, restart control | state identical ($(wc -l < "$EVID/before.txt" | tr -d ' ') lines) |"
  echo "| v2 install (B + lineage) | Success, firstInstallTime unchanged |"
  echo "| state after rotation | identical to before |"
  echo "| platform signer after | $( [ "$SIGNERS_AFTER" = "$SHA_B" ] && echo B || echo "$SIGNERS_AFTER" ), history $( [ "$HISTORY_AFTER" = "$SHA_OLD,$SHA_B" ] && echo 'debug+B' || echo "$HISTORY_AFTER" ) |"
  echo "| neg: v1 (old key only) after rotation | $R |"
  echo "| state after refused install | identical to before |"
  echo
  echo "state.txt fields: $(cut -d= -f1 "$EVID/before.txt" | tr '\n' ' ')"
} > "$EVID/SUMMARY.md"
say; cat "$EVID/SUMMARY.md" | tee -a "$LOG" >/dev/null; cat "$EVID/SUMMARY.md"
say "PASS"
