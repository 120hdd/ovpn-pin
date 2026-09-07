#!/usr/bin/env bash
# Build the Android half of the core: one .aar carrying the Go engine
# compiled for every phone CPU, plus the Java classes that call into it.
#
# Run it from anywhere. It pins the toolchain explicitly rather than trusting
# whatever PATH happens to hold, because getting this wrong fails in a way
# that reads like a code error:
#
#   Go 1.26+  golang.org/x/mobile requires it, and a 1.25 that tries to fetch
#             the newer toolchain over a censored line dies with
#             "unexpected EOF" halfway through 70 MB.
#   javac     gomobile compiles the generated Java itself and looks for javac
#             on PATH, not in JAVA_HOME. Without it the whole bind runs, all
#             four architectures compile, and it fails on the last step with
#             an empty .aar left behind.
#   NDK       the C toolchain the Go compiler shells out to per architecture.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

NDK_VERSION="${NDK_VERSION:-27.3.13750724}"

# Named, then found, then left to PATH - in that order.
#
# What was here before pinned one person's home directory as the *default*,
# so every other machine got "missing: go" for a go that was installed and on
# PATH. An explicit variable still wins; after that, a directory is used only
# if it is actually there.
prefer() {                      # prefer VAR /a/path /another/path ...
    local var="$1"; shift
    [ -n "${!var-}" ] && return 0
    local dir
    for dir in "$@"; do
        [ -n "$dir" ] && [ -d "$dir" ] || continue
        printf -v "$var" '%s' "$dir"
        return 0
    done
    return 0
}

prefer GO_BIN "/c/Users/bashi/sdk/go1.27.0/go/bin"
prefer JAVA_HOME "/c/Program Files/Android/Android Studio/jbr"
prefer ANDROID_HOME "${ANDROID_SDK_ROOT-}" "$HOME/Android/Sdk" "${LOCALAPPDATA-}/Android/Sdk" "C:/Users/bashi/AppData/Local/Android/Sdk"

if [ -n "${ANDROID_HOME-}" ]; then
    export ANDROID_HOME
    export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
fi

# Only what exists goes on PATH. An empty element means the current
# directory, and a PATH beginning with one runs whatever happens to be in the
# folder the script was called from.
for dir in "${GO_BIN-}" "${GOPATH_BIN-}" "${JAVA_HOME:+$JAVA_HOME/bin}"; do
    if [ -n "$dir" ] && [ -d "$dir" ]; then PATH="$dir:$PATH"; fi
done
export PATH
hash -r

# Asked of go rather than guessed at, and only once go is reachable. gomobile
# installs itself here, and this is the one path that cannot be written down
# in advance because it is whatever GOPATH says today.
if [ -z "${GOPATH_BIN-}" ] && command -v go >/dev/null; then
    GOPATH_BIN="$(go env GOPATH)/bin"
    if [ -d "$GOPATH_BIN" ]; then PATH="$GOPATH_BIN:$PATH"; export PATH; hash -r; fi
fi

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need go
need gomobile
need javac

echo "  go       $(go version | cut -d' ' -f3)"
echo "  javac    $(javac -version 2>&1 | cut -d' ' -f2)"
echo "  ndk      $(basename "$ANDROID_NDK_HOME")"
echo

cd "$HERE/core"
go vet ./...

# with_gvisor is not optional, and leaving it off fails late rather than
# early: everything compiles, the app installs, the race finds an exit in a
# second and a half, and then NewStack("gvisor") says the stack was not
# included. sing-tun keeps gVisor behind a tag because it is a megabyte or
# two of userspace TCP/IP - which is exactly what an unprivileged Android app
# needs, because the system stack wants a raw socket per connection and an
# app does not get those.
gomobile bind -tags with_gvisor -target=android -androidapi 21 \
    -o "$HERE/relay.aar" ./bind

# Where Gradle actually reads it. build.gradle.kts asks for
# `files("libs/relay.aar")` - app/android/app/libs - and nothing ever put it
# there, so a clean checkout could build the core, build the app, and ship an
# APK with no core inside it. Both paths are gitignored; this is the copy
# step that was only ever done by hand.
LIBS="$HERE/app/android/app/libs"
mkdir -p "$LIBS"
cp "$HERE/relay.aar" "$LIBS/relay.aar"

echo
ls -la "$HERE/relay.aar" | awk '{printf "  relay.aar  %.1f MB\n", $5/1048576}'
echo "  libs       app/android/app/libs/relay.aar"
