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

GO_BIN="${GO_BIN:-/c/Users/bashi/sdk/go1.27.0/go/bin}"
GOPATH_BIN="${GOPATH_BIN:-/c/Users/bashi/go/bin}"
JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bashi/AppData/Local/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.3.13750724}"

export PATH="$GO_BIN:$GOPATH_BIN:$JAVA_HOME/bin:$PATH"
hash -r

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

echo
ls -la "$HERE/relay.aar" | awk '{printf "  relay.aar  %.1f MB\n", $5/1048576}'
