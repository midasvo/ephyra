#!/usr/bin/env bash
# Press D-pad keys on an Android TV emulator the way a remote does.
#
#   scripts/tv-dpad.sh <avd name|adb serial> <key>...
#   keys: up down left right ok back home, and wait (one second)
#   scripts/tv-dpad.sh ce_tv_1080p down down ok
#
# Why not `adb shell input keyevent`: those keys come from a virtual device, and Compose text
# fields move focus only on D-pad keys from a real one, so focus looks stuck on the login, search
# and PIN fields. A key sent as down and up in one go also hides bugs that need a real press.
# This registers a D-pad through uinput and holds every key for 150 ms.
#
# Each call registers the D-pad again, which takes about a second: send a run of keys in one call
# when you know them, and look at the screen between calls when you do not.
set -euo pipefail

target="${1:?avd name or adb serial}"
shift
[ $# -gt 0 ] || { sed -n '2,6p' "$0"; exit 1; }
case "$target" in
  ce_tv_1080p) serial=emulator-5572 ;;
  ce_tv_4k) serial=emulator-5574 ;;
  *) serial="$target" ;;
esac
ADB="$(command -v adb || echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb")"
export MSYS_NO_PATHCONV=1

code() { # Linux input key codes
  case "$1" in
    up) echo 103 ;; down) echo 108 ;; left) echo 105 ;; right) echo 106 ;;
    ok) echo 353 ;; back) echo 158 ;; home) echo 172 ;;
    *) echo "Unknown key: $1 (up down left right ok back home wait)" >&2; exit 1 ;;
  esac
}

file="$(mktemp)"
trap 'rm -f "$file"' EXIT
{
  # type 100 is the event types (1 = keys), type 101 the key codes this device has. The vendor and
  # product ids are made up on purpose: ids of a real remote would pick that remote's key layout.
  echo '{"id":1,"command":"register","name":"Ephyra test D-pad","vid":4660,"pid":22136,"bus":"usb","configuration":[{"type":100,"data":[1]},{"type":101,"data":[103,108,105,106,353,158,172]}]}'
  echo '{"id":1,"command":"delay","duration":1000}'
  for key in "$@"; do
    if [ "$key" = wait ]; then echo '{"id":1,"command":"delay","duration":1000}'; continue; fi
    c="$(code "$key")"
    echo "{\"id\":1,\"command\":\"inject\",\"events\":[1,$c,1,0,0,0]}"
    echo '{"id":1,"command":"delay","duration":150}'
    echo "{\"id\":1,\"command\":\"inject\",\"events\":[1,$c,0,0,0,0]}"
    echo '{"id":1,"command":"delay","duration":350}'
  done
} > "$file"

# through stdin, not `adb push`: a Windows adb cannot read Git Bash's /tmp path
"$ADB" -s "$serial" shell 'cat > /data/local/tmp/ephyra-dpad.json' < "$file"
"$ADB" -s "$serial" shell uinput /data/local/tmp/ephyra-dpad.json
