#!/usr/bin/env bash
# Mission L1, B.1 — proves apksigner 0.9 signer-rotation semantics on a real Android
# platform instead of trusting the help text. Runs the synthetic package
# tools/rotationtest (Room row + EncryptedSharedPreferences value) through:
#
#   positive  v1 signed A  ->  v2 signed with lineage A->B  : data + platform-reported signer
#   neg i     ... then v3 signed ONLY with A                  : must be refused where B is effective
#   neg ii    ... then v3 with a competing lineage A->C       : must be refused where B is effective
#   neg iii   like positive but the lineage grants A rollback -> v3 signed only with A is accepted
#             (control: proves the rollback capability, not the platform, decides neg i)
#
# for both `--rotation-min-sdk-version` variants (default = 33, and 28), against ONE
# connected device/emulator. Run it once per API level (28, 32, 36).
#
# Usage:
#   scripts/signing/rotation-matrix.sh --work <dir> --serial emulator-5554 --api 28
#
# <dir> must contain rot_v1-unsigned.apk, rot_v2-unsigned.apk, rot_v3-unsigned.apk, built with
#   for v in 1 2 3; do ./gradlew -p tools/rotationtest assembleRelease -ProtVersionCode=$v; done
# Throw-away keys A/B/C and the lineages are generated into <dir> on first use.
# Evidence (install output, ROTTEST log lines, dumpsys excerpts) goes to <dir>/evidence/api<N>/
# and a summary table to <dir>/evidence/api<N>/SUMMARY.md. Nothing here touches a real key.
set -euo pipefail

WORK="" SERIAL="" API=""
while [ $# -gt 0 ]; do
  case "$1" in
    --work) WORK="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$WORK" ] && [ -n "$SERIAL" ] && [ -n "$API" ] || { sed -n '2,22p' "$0"; exit 2; }
[ -n "${ANDROID_HOME:-}" ] || { echo "ANDROID_HOME not set" >&2; exit 2; }

PKG=se.optiqon.rotationtest
ADB="$ANDROID_HOME/platform-tools/adb"; [ -x "$ADB" ] || ADB="$ADB.exe"
BT="$(ls -1 "$ANDROID_HOME/build-tools" | sort -V | tail -n1)"
APKSIGNER="$ANDROID_HOME/build-tools/$BT/apksigner"; [ -x "$APKSIGNER" ] || APKSIGNER="$APKSIGNER.bat"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
adb() { "$ADB" -s "$SERIAL" "$@"; }

EV="$WORK/evidence/api$API"; mkdir -p "$EV"
for v in 1 2 3; do [ -f "$WORK/rot_v$v-unsigned.apk" ] || { echo "missing $WORK/rot_v$v-unsigned.apk" >&2; exit 2; }; done

# --- throw-away keys and lineages ------------------------------------------------------
PW=throwaway
mk_key() { [ -f "$WORK/$1.jks" ] || "$KEYTOOL" -genkeypair -keystore "$WORK/$1.jks" -alias "$1" -keyalg RSA -keysize 2048 \
  -validity 30 -storepass $PW -keypass $PW -dname "CN=throwaway $1, O=L1 rotation test" >/dev/null 2>&1; }
mk_key A; mk_key B; mk_key C
sha_of() { "$KEYTOOL" -list -v -keystore "$WORK/$1.jks" -alias "$1" -storepass $PW 2>/dev/null \
  | grep -m1 'SHA256:' | sed -E 's/.*SHA256:[[:space:]]*//' | tr -d ':' | tr '[:lower:]' '[:upper:]'; }
SHA_A="$(sha_of A)"; SHA_B="$(sha_of B)"; SHA_C="$(sha_of C)"
signer_args() { echo --ks "$WORK/$1.jks" --ks-key-alias "$1" --ks-pass pass:$PW --key-pass pass:$PW; }

# ab: normal rotation (old signer keeps NO rollback capability — apksigner default)
[ -f "$WORK/ab.lineage" ] || "$APKSIGNER" rotate --out "$WORK/ab.lineage" --old-signer $(signer_args A) --new-signer $(signer_args B)
# ab_rb: same rotation but the old signer is explicitly granted rollback
[ -f "$WORK/ab_rb.lineage" ] || "$APKSIGNER" rotate --out "$WORK/ab_rb.lineage" --old-signer $(signer_args A) --set-rollback true --new-signer $(signer_args B)
# ac: a competing rotation made with A's key, as an attacker holding only A could
[ -f "$WORK/ac.lineage" ] || "$APKSIGNER" rotate --out "$WORK/ac.lineage" --old-signer $(signer_args A) --new-signer $(signer_args C)
"$APKSIGNER" lineage --print-certs -v --in "$WORK/ab.lineage" > "$EV/lineage_ab.txt"
"$APKSIGNER" lineage --print-certs -v --in "$WORK/ab_rb.lineage" > "$EV/lineage_ab_rb.txt"
"$APKSIGNER" lineage --print-certs -v --in "$WORK/ac.lineage" > "$EV/lineage_ac.txt"

# --- signed artefacts (shared across API levels) ---------------------------------------
sign() { # out unsigned-version signer [lineage old-signer] [rotation-min]
  local out="$1" ver="$2" signer="$3" lineage="${4:-}" old="${5:-}" rmin="${6:-}"
  [ -f "$WORK/$out" ] && return 0
  local cmd=("$APKSIGNER" sign --out "$WORK/$out")
  if [ -n "$lineage" ]; then
    cmd+=(--lineage "$WORK/$lineage"); [ -n "$rmin" ] && cmd+=(--rotation-min-sdk-version "$rmin")
    cmd+=($(signer_args "$old") --next-signer $(signer_args "$signer"))
  else
    cmd+=($(signer_args "$signer"))
  fi
  cmd+=("$WORK/rot_v$ver-unsigned.apk")
  "${cmd[@]}"
}
sign v1_A.apk 1 A
sign v3_A.apk 3 A
for VAR in default 28; do
  RMIN=""; [ "$VAR" = 28 ] && RMIN=28
  sign "v2_B_ab_$VAR.apk"    2 B ab.lineage    A "$RMIN"
  sign "v2_B_abrb_$VAR.apk"  2 B ab_rb.lineage A "$RMIN"
  sign "v3_C_ac_$VAR.apk"    3 C ac.lineage    A "$RMIN"
done

# --- device helpers --------------------------------------------------------------------
launch() { # -> ROTTEST line
  adb logcat -c >/dev/null 2>&1 || true
  adb shell am start -n "$PKG/.MainActivity" >/dev/null  # no -W: the NoDisplay activity finishes in onCreate and -W would wait forever
  local i line=""
  for i in 1 2 3 4 5 6 7 8 9 10; do
    line="$(adb logcat -d -s ROTTEST:I 2>/dev/null | grep -m1 'ROTTEST ' | sed -E 's/.*ROTTEST /ROTTEST /' | tr -d '\r')"
    [ -n "$line" ] && break; sleep 1
  done
  echo "$line"
}
field() { printf '%s' "$1" | sed -nE "s/.* $2=([^ ]*).*/\1/p"; }
log() { echo "$2" >> "$EV/$1.log"; }
install() { # apk -> "Success" or the INSTALL_FAILED_* code; full adb output goes to install.log
  local out; out="$(adb install -r "$WORK/$1" 2>&1 | tr -d '\r')"
  log install "$1: $out"
  local code; code="$(printf '%s' "$out" | grep -oE 'Success|INSTALL_FAILED_[A-Z_]+' | tail -n1)"
  echo "${code:-UNKNOWN}"
}
dumpsys_excerpt() { adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' \
  | grep -E 'versionCode=|signatures=|past signers|capabilities|flags=' | head -8; }
reset_to() { # v2 apk name -> installs v1_A then the given v2
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  [ "$(install v1_A.apk)" = "Success" ] || { echo "cannot install v1_A" >&2; exit 1; }
  local l1; l1="$(launch)"
  local r; r="$(install "$1")"
  [ "$r" = "Success" ] || { echo "cannot install $1: $r" >&2; exit 1; }
  echo "$l1"
}
label() { # sha -> A/B/C/?
  case "$1" in "$SHA_A") echo A ;; "$SHA_B") echo B ;; "$SHA_C") echo C ;; "") echo "-" ;; *) echo "?" ;; esac
}
labels() { local IFS=,; local out=(); for s in $1; do out+=("$(label "$s")"); done; IFS=+; echo "${out[*]:--}"; }

DEV_API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[ "$DEV_API" = "$API" ] || { echo "device $SERIAL reports API $DEV_API, not $API" >&2; exit 2; }
adb uninstall "$PKG" >/dev/null 2>&1 || true

SUM="$EV/SUMMARY.md"
{
  echo "# Rotation matrix — API $API ($(adb shell getprop ro.build.version.release | tr -d '\r'))"
  echo
  echo "Keys: A=$SHA_A B=$SHA_B C=$SHA_C (throw-away)"
  echo
  echo "| rotation-min-sdk | case | install result | platform signer | history | data kept | note |"
  echo "|---|---|---|---|---|---|---|"
} > "$SUM"
row() { echo "| $1 | $2 | $3 | $4 | $5 | $6 | $7 |" >> "$SUM"; echo "  [$1/$2] install=$3 signer=$4 history=$5 data=$6 $7"; }

for VAR in default 28; do
  echo "== API $API, rotation-min-sdk $VAR =="
  # positive
  L1="$(reset_to "v2_B_ab_$VAR.apk")"; log "$VAR" "v1: $L1"
  L2="$(launch)"; log "$VAR" "v2 (B, lineage ab): $L2"; dumpsys_excerpt > "$EV/dumpsys_${VAR}_positive.txt"
  KEPT=no
  [ "$(field "$L1" marker)" = "$(field "$L2" marker)" ] && [ "$(field "$L1" secretDigest)" = "$(field "$L2" secretDigest)" ] \
    && [ "$(field "$L2" created)" = "false" ] && [ -n "$(field "$L2" marker)" ] && KEPT=yes
  row "$VAR" "positive A->B" "Success" "$(labels "$(field "$L2" signer)")" "$(labels "$(field "$L2" history)")" "$KEPT" "v2 versionCode=$(field "$L2" versionCode)"

  # neg i: old key only
  R="$(install v3_A.apk)"; log "$VAR" "neg i install: $R"
  L3="$(launch)"; log "$VAR" "neg i state: $L3"
  KEPT=no; [ "$(field "$L1" marker)" = "$(field "$L3" marker)" ] && KEPT=yes
  row "$VAR" "neg i: v3 signed A only" "$R" "$(labels "$(field "$L3" signer)")" "$(labels "$(field "$L3" history)")" "$KEPT" "$( [ "$R" = Success ] && echo 'ACCEPTED: the old key alone still updates' || echo refused )"

  # neg ii: competing lineage A->C
  L1="$(reset_to "v2_B_ab_$VAR.apk")"
  R="$(install "v3_C_ac_$VAR.apk")"; log "$VAR" "neg ii install: $R"
  L3="$(launch)"; log "$VAR" "neg ii state: $L3"
  KEPT=no; [ "$(field "$L1" marker)" = "$(field "$L3" marker)" ] && KEPT=yes
  row "$VAR" "neg ii: v3 lineage A->C" "$R" "$(labels "$(field "$L3" signer)")" "$(labels "$(field "$L3" history)")" "$KEPT" "$( [ "$R" = Success ] && echo 'ACCEPTED: A alone can rotate to any key' || echo refused )"

  # neg iii: lineage with rollback granted to A, then old key only
  L1="$(reset_to "v2_B_abrb_$VAR.apk")"
  R="$(install v3_A.apk)"; log "$VAR" "neg iii install: $R"
  L3="$(launch)"; log "$VAR" "neg iii state: $L3"
  KEPT=no; [ "$(field "$L1" marker)" = "$(field "$L3" marker)" ] && KEPT=yes
  row "$VAR" "neg iii: rollback=true, v3 signed A only" "$R" "$(labels "$(field "$L3" signer)")" "$(labels "$(field "$L3" history)")" "$KEPT" "control for neg i: the rollback capability decides"
done

adb uninstall "$PKG" >/dev/null 2>&1 || true
echo; cat "$SUM"
