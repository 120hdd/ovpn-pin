# mobile

The half of Relay that has to exist on a phone.

Nothing here replaces anything in `app/` or `core/`. The Python is still the
thing that was measured and argued over; this is a port of the part a phone
needs, in the one language that can be compiled for both phones at once.

## Why Go

Not preference. One function.

`Exit.Dial` in [core/exit.go](core/exit.go) has to hold three settings at the
same time, and no two of them are usually allowed together:

| | |
|---|---|
| `ServerName: ""` | nothing is announced. A handshake that names `*.prod.surfshark.com` in the clear is killed on the way out. |
| `InsecureSkipVerify: true` | not what it sounds like. With an empty `ServerName`, `crypto/tls` refuses to run at all unless this is set — so it is set, and everything it turns off is done by hand underneath. |
| `VerifyPeerCertificate` | the chain still has to reach a system root, and the leaf still has to serve the real name. The name is proved, it is just never sent. |

Kotlin can be argued into this with `SSLParameters` and a hand-rolled
verifier. Dart cannot: `SecureSocket` sends the host you give it, and there is
no way to keep the check without the announcement. Go says it in a struct
literal — and `gomobile` compiles that same file for Android and iOS both, so
it is written once rather than twice.

## What is here

```
core/
  config.go   read a pinned .ovpn: the address, and the name it was pinned from
  exit.go     the TLS above, then CONNECT with Basic credentials
  doh.go      resolve over DoH, and throw away anything reserved
  probe.go    Ask: does this exit take these credentials, and how fast
  race.go     Race: ask eight at once, take the first that says yes
  bind/       the flat, dull face gomobile can carry across
  cmd/probe/  a command line for arguing with all of the above
```

## Running it

```sh
go run ./cmd/probe -auth ../../.ovpn-auth       ../../pinned/*.ovpn   # ask them all
go run ./cmd/probe -auth ../../.ovpn-auth -race ../../pinned/*.ovpn   # stop at the first yes
go test ./...
```

The tests reach real exits on the real line on purpose. A stubbed certificate
would prove the harness works and nothing about the exits, so
`TestWrongNameIsRefused` points a live connection at a live certificate under
a name it does not serve — because a `verify` that quietly returned `nil`
would pass every test that only asks whether connecting works, and would have
thrown the certificate check away on every exit. They skip rather than fail
when nothing is reachable.

## Building for Android

```sh
./build-android.sh
```

Out comes `relay.aar`: the Go engine compiled for `arm64-v8a`, `armeabi-v7a`,
`x86` and `x86_64`, and four Java classes — `Client`, `Progress`, `Result`,
`Relay`. It is 22 MB and gitignored; any checkout rebuilds it in a minute.

The script pins its toolchain rather than trusting `PATH`, because each of
these fails in a way that reads like a code error:

- **Go 1.26+** — `golang.org/x/mobile` requires it. A 1.25 that tries to fetch
  the newer toolchain over a censored line dies with `unexpected EOF` halfway
  through 70 MB.
- **javac** — gomobile compiles the generated Java itself and looks for
  `javac` on `PATH`, not in `JAVA_HOME`. Without it the bind runs, all four
  architectures compile, and it fails on the very last step leaving an empty
  `.aar` behind.
- **NDK** — the C toolchain the Go compiler shells out to per architecture.

## Two things found by measuring rather than reading

**`http.ReadResponse` cannot read a CONNECT.** A `200` to CONNECT carries no
`Content-Length` and no chunked encoding, so `net/http` types the body as
"read until the connection closes" — and `Body.Close()` then drains it.
Against a live exit that meant waiting out the whole timeout: 8.34s, where the
Python asking the same question answered in 0.53s. The slowness was the
visible half. The other half is that the bytes it drained belong to the
tunnel. [core/exit.go](core/exit.go) reads the head by hand instead.

**The resolver is forged too.** Asked for `cloudflare-dns.com`, this line
answers `10.10.34.35` — the same address the top-level README opens with,
aimed at the resolver that exists to escape it. The lookup that is supposed to
be trustworthy cannot itself begin with an untrustworthy lookup, so
[core/doh.go](core/doh.go) pins the resolvers exactly as the exits are pinned.
Measured from here, only `https://1.1.1.1/dns-query` answered; `1.0.0.1`,
`8.8.8.8` and `dns.google` all timed out. It costs nothing to verify: for an
address literal Go sends no SNI at all, and Cloudflare's certificate carries
`1.1.1.1` as an IP SAN.

## The tunnel

```
VpnService opens the tun and hands over a file descriptor
  → sing-tun reassembles packets into connections
    → TCP        one CONNECT through the exit, then bytes both ways
    → UDP :53    the query, re-asked as DoH through the same exit
    → UDP other  dropped
```

**sing-box is not here, and that is a decision.** Its `libbox` wants a
twenty-eight method platform interface implemented in Kotlin and again in
Swift, a JSON config, a DNS engine and a rule engine — to arrive where
[core/tunnel.go](core/tunnel.go) arrives in three hundred lines, because the
exit is an HTTPS proxy and a proxy has exactly one verb. What is borrowed is
`sing-tun`, which is the genuinely hard part: a TCP/IP stack that turns
packets back into connections.

**No UDP, said plainly.** CONNECT carries a stream, so there is no UDP through
these exits and there never will be. DNS is the exception because it can be
re-asked over HTTPS, which is a stream. Everything else UDP is dropped — QUIC
falls back to TCP when it gets nothing, and that fallback is what makes the
web work here at all. The desktop has always been the same shape; it is only
visible now because a tun sees every packet rather than only what an
application chose to send through a proxy.

**The one call without which nothing works.** Once `addRoute("0.0.0.0", 0)`
sends everything into the tun, the socket the core opens *to the exit* is
routed there too: the exit's address enters the tunnel, comes back out of the
stack, is dialled again, and the phone spends a core talking to itself.
Nothing throws. `VpnService.protect(fd)` breaks the loop, and it reaches Go
through `Relay.setProtector` before anything dials — `net.Dialer.Control` is
where it lands, which is after the socket exists and before it connects.

## The app

```
app/                        Flutter, one screen
  lib/
    main.dart               the screen
    orb.dart                the status, drawn rather than written
    relay.dart              the Dart end of the bridge
    theme.dart              app/ui/app.css, moved across
  android/app/src/main/kotlin/com/erelay/relay/
    RelayVpnService.kt      the tunnel's whole lifetime
    MainActivity.kt         the Kotlin end of the bridge
```

**Two channels, not one.** `relay/control` is asked and answered — connect,
disconnect, where are the files. `relay/status` is pushed — the race counting
up, the exit that won, the failure. Collapsing them would mean Dart polling
for progress, and progress that arrives eight times a second is the one thing
polling is worst at.

**The orb is the status display.** A person glancing at a VPN client wants one
answer, and a colour crossing a room reads faster than a word. Still and dim
is idle, turning and amber is racing, still and mint with a glow is connected.
It animates only while something is happening — a phone holding a tunnel open
all evening should not also be repainting sixty times a second to say so.

**The palette is the desktop's**, name for name, from `app/ui/app.css`. A
colour changed there should be findable here by searching for the same word.

```sh
./build-android.sh                        # relay.aar, the Go core
cd app && flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

Configs and credentials are read from the app's own external files directory,
because this build is meant to be pushed at:

```sh
D=/sdcard/Android/data/com.erelay.relay.debug/files
adb push pinned/. $D/pinned/
adb push .ovpn-auth $D/auth
```

When they are missing the window prints those two paths rather than "no
configs", because the path is the only part a person can act on.

## What it did on a phone

Poco F3, Android 13, on the line this repo was written for.

```
racing 147 exits
connected via ad-leu.prod.surfshark.com (62.197.152.149), 636 ms
```

```
ip=62.197.152.150       ← Cloudflare sees the exit, not the phone
loc=FR  colo=MRS
```

Names resolve through the DoH hijack rather than the phone's resolver, and a
site that is refused without a tunnel answers `204` in 1.9s.

**No IPv6 leak, and not by our doing.** The phone has a global IPv6 address on
wlan0; forced onto IPv6 while the tunnel is up, a request fails in 0.23s and
applications fall back to IPv4 through the tunnel. That is Android refusing
traffic for an address family a non-bypassable VPN does not route — worth
knowing it is the platform holding that line and not this code, because it is
the platform that could stop.

**Throughput.** The same 25 MB, on the same line, within minutes of each
other:

| | |
|---|---|
| desktop, the Python proxy, at-vie | 509 KB/s |
| phone, this tunnel, ad-leu | 434 KB/s |

85% of the desktop, through a userspace TCP/IP stack and over wifi. The line
is the limit here, not the tunnel — the 99 Mbit in the top-level README was
measured on a different connection.

## Three bugs that only a phone could find

None of these appeared on the desktop, and none of them appeared in the tests.

| | how it showed itself |
|---|---|
| `with_gvisor` tag missing | a clear error, and the easy one |
| the tun descriptor owned twice | `fdsan` killed the process — a check only Android has |
| `UDPTimeout` left at zero | the app closed itself, in silence |

The third is the one worth keeping in mind. `sing-tun` validates that option
by panicking rather than by returning an error, and a Go panic inside a JNI
library takes the app down with no Java stack and nothing in the window. So
`StartTunnel` now recovers construction-time panics into errors: the next
thing to be wrong will be readable.

## Two ways out, and the stack cannot tell them apart

The window's strip chooses between three, and they are two different things
underneath:

| | | |
|---|---|---|
| **Provider** | `surfshark` | races the pinned exits — [exit.go](core/exit.go) |
| **Your server** | `single` → `/gw` | exits at the user's own machine |
| **Server + exit** | `multi` → `/ex` | exits at a provider node the server picks |

The second and third go through the user's own server behind Cloudflare, and
[tunnelclient.go](core/tunnelclient.go) speaks that itself rather than running
`gost` as a child process the way the desktop does. Not because spawning is
impossible on Android, but because gost is Go, this core is Go, and the `mwss`
path turned out to be four things:

```
TCP     to a Cloudflare edge address on 443
TLS     ServerName = the domain, and here the name IS announced
WS      an ordinary upgrade to wss://<domain><path>, binary messages
smux    version 1, keepalive 10s, otherwise smux's own defaults
CONNECT one per stream, Basic relay:<password>
```

Those numbers were read out of gost's own source, not guessed. The desktop and
the server were installed against a particular version and the framing has to
match it exactly — `smux.Version = 1` and the keepalive the desktop writes as
`mux.keepaliveInterval` are the two that would fail silently if they drifted.

**The name is announced here, and that is the opposite of everything else in
this package.** [exit.go](core/exit.go) goes to great trouble to prove a name
without ever sending it; this sends it, because Cloudflare routes on SNI and
will not know which site the connection is for otherwise. Two opposite
decisions, one directory, each right for its own reason.

`Way` in [core/way.go](core/way.go) is what lets them share everything above:
the tun stack, the DNS-over-HTTPS, the "seen as" check and the status line all
take a `Way` and none of them has an opinion about which one they got.

Measured on a Poco F3, on the line this repo is for:

| | dialled | came out at | |
|---|---|---|---|
| Your server | `/gw` | `164.92.225.16` | DE |
| Server + exit | `/ex` | `188.95.54.56` | BE |

The same two addresses the desktop gets, which is the point of the exercise:
one protocol, two clients, no drift. And they differ from each other, which is
the other point — `/ex` really does leave through a provider node rather than
through the server itself.

## Finding the way in

Most of Cloudflare's address space is filtered on the lines this is for, and
the few addresses that are not are not the ones DNS hands out. So they are
measured, by [edges.go](core/edges.go), the same way the desktop measures
them.

The probe is the neat part and it is the desktop's: ask `/api/config` with no
credentials. A `401` proves four things at once — the address is reachable,
the certificate matches, Cloudflare recognises the zone, and what stands
behind it is our nginx rather than somebody else's site. Nothing secret is
sent to learn that, which is what makes it safe to fire at fifty-six
strangers' addresses at once.

Measured on the line this was written on: **26 live, 28 filtered, 2 foreign.**
And it has to be measured *on the phone* rather than copied from the desktop —
which addresses are filtered is a fact about the line, and the phone is not
always on the line the desktop was.

## Not here yet

The Flutter window, and iOS. The core is already built for both — `gomobile
bind -target=ios` produces the same API as an `.xcframework`, and
[core/tunnel.go](core/tunnel.go) carries a `darwin` build tag for the same
reason. What iOS still needs is a `PacketTunnelProvider` to hand over the
descriptor, and an Apple developer account that can carry a VPN entitlement.
