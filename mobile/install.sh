#!/usr/bin/env bash
# Put the app on the phone, with the configs and credentials it needs, and
# then show what it says.
#
# The debug build carries a .debug suffix on its application id, so its files
# directory is not the one a release build would use. Getting that wrong
# leaves an app that installs, runs, and says "nothing pinned yet" - which is
# why the id is computed here rather than typed.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

APK="$HERE/app/build/app/outputs/flutter-apk/app-debug.apk"
APP_ID="com.erelay.relay.debug"
FILES="/sdcard/Android/data/$APP_ID/files"

[ -f "$APK" ] || {
    echo "build it first:" >&2
    echo "    ./build-android.sh          # the Go core" >&2
    echo "    cd app && flutter build apk --debug" >&2
    exit 1
}

device=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
[ -n "$device" ] || {
    echo "no phone. Plug it in, allow USB debugging, and check:  adb devices" >&2
    exit 1
}
echo "  phone    $device"

adb install -r "$APK" >/dev/null
echo "  app      installed"

# The directory exists only once the app has run, so make it rather than
# assume it. mkdir -p on a path Android already made is not an error.
adb shell mkdir -p "$FILES/pinned" >/dev/null 2>&1 || true

count=$(ls -1 "$ROOT/pinned"/*.ovpn 2>/dev/null | wc -l)
[ "$count" -gt 0 ] || { echo "nothing pinned in $ROOT/pinned" >&2; exit 1; }
adb push "$ROOT/pinned/." "$FILES/pinned/" >/dev/null
echo "  configs  $count pushed"

[ -f "$ROOT/.ovpn-auth" ] || { echo "no $ROOT/.ovpn-auth" >&2; exit 1; }
adb push "$ROOT/.ovpn-auth" "$FILES/auth" >/dev/null
echo "  auth     pushed"

echo
echo "  open Relay and press connect. Watching the log - ctrl-c to stop:"
echo
adb logcat -c
# GoLog carries anything the core panics with, which is the half that would
# otherwise close the app in silence.
adb logcat -v time Relay:V GoLog:V '*:S'
