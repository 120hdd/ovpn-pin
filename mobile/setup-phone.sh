#!/usr/bin/env bash
# Put the app on the phone with everything it needs to connect, both ways.
#
# The provider half wants pinned configs and a service credential, pushed from
# here. The server half wants a domain and two passwords, which live in the
# app's own preferences because the service reads them and the service has no
# page - so they go in through the same settings the window writes.
#
#     ./setup-phone.sh                       # app + configs + credentials
#     ./setup-phone.sh --tunnel DOMAIN PW    # and the server, saved on device
#
# Nothing here is secret to the phone that was not already secret to this
# machine: the same two files are on both. What it does avoid is typing a
# twenty-character password into a phone keyboard once per reinstall.
set -euo pipefail

# Git Bash rewrites anything that looks like a Unix path into a Windows one
# before a native program sees it, which is right for the APK and catastrophic
# for the phone's own paths: /sdcard/Android/... becomes
# C:/Program Files/Git/sdcard/Android/... and adb is asked about a directory
# that has never existed. Turning the whole thing off breaks the other half
# instead, so only the device's prefixes are excluded.
export MSYS2_ARG_CONV_EXCL='/sdcard;/data;/system'
unset MSYS_NO_PATHCONV

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

APK="$HERE/app/build/app/outputs/flutter-apk/app-debug.apk"
APP_ID="com.erelay.relay.debug"
FILES="/sdcard/Android/data/$APP_ID/files"

domain=""; password=""; api_password=""
if [ "${1:-}" = "--tunnel" ]; then
    domain="${2:-}"; password="${3:-}"; api_password="${4:-}"
    [ -n "$domain" ] && [ -n "$password" ] || {
        echo "--tunnel wants a domain and a password" >&2; exit 2; }
fi

[ -f "$APK" ] || {
    echo "build it first:" >&2
    echo "    ./build-android.sh && ./sync-ui.sh" >&2
    echo "    cd app && flutter build apk --debug --target-platform android-arm64" >&2
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

# -- the provider half ------------------------------------------------------

adb shell mkdir -p "$FILES/pinned" >/dev/null 2>&1 || true

count=$(ls -1 "$ROOT/pinned"/*.ovpn 2>/dev/null | wc -l)
if [ "$count" -gt 0 ]; then
    adb push "$ROOT/pinned/." "$FILES/pinned/" >/dev/null
    echo "  configs  $count pushed"
else
    echo "  configs  none in $ROOT/pinned - the provider half will have nothing to race"
fi

if [ -f "$ROOT/.ovpn-auth" ]; then
    adb push "$ROOT/.ovpn-auth" "$FILES/auth" >/dev/null
    echo "  auth     pushed"
else
    echo "  auth     no $ROOT/.ovpn-auth"
fi

# -- the server half --------------------------------------------------------
#
# Written straight into the preferences file the app reads. run-as is what
# makes that possible without root, and it works only on a debuggable build -
# which this is, and a release build would not be. So this is a development
# convenience and says so rather than pretending to be a feature.

if [ -n "$domain" ]; then
    xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name=\"tunnelDomain\">$domain</string>
    <string name=\"tunnelPassword\">$password</string>
    <string name=\"tunnelApiPassword\">$api_password</string>
    <string name=\"mode\">single</string>
</map>"
    if adb shell "run-as $APP_ID sh -c 'mkdir -p shared_prefs && cat > shared_prefs/relay.xml'" \
        <<<"$xml" 2>/dev/null; then
        echo "  tunnel   $domain saved on the phone"
        # The app has to be restarted to read a preferences file changed
        # underneath it.
        adb shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    else
        echo "  tunnel   could not write preferences (run-as refused)" >&2
        echo "           put the domain and password in Settings on the phone" >&2
    fi
fi

echo
echo "  open Relay. The strip at the top chooses the way out:"
echo "    Provider       races the pinned exits"
echo "    Your server    /gw  - exits at your own server"
echo "    Server + exit  /ex  - exits at a provider node it picks"
