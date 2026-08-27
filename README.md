# ovpn-pin

Pins the `remote` line of an OpenVPN config to a real IP address, resolved over
DNS-over-HTTPS — and tells you whether the exit you land on is one Cloudflare
will actually serve.

A config you download from a VPN provider points at a name:

```
remote de-fra.prod.surfshark.com 1443 tcp
```

On a censored line that name is the weak link. The resolver answers with a
forged address — `10.10.34.35` and friends, a machine on your own LAN — and
OpenVPN dials it and gets nowhere. Nothing is wrong with the server or the
tunnel; the connection never left the building.

This script resolves each name over DoH instead, throws away anything that
isn't a public address, and writes the config back out with the address in it
literally. After that, nothing about connecting depends on your resolver.

```
remote 146.70.160.237 1443 tcp
```

Two scripts, the same tool: `Resolve-OvpnRemote.ps1` for Windows,
`resolve-ovpn-remote.sh` for Linux. The Linux one also carries your
credentials, so the client stops asking for them.

## Which half is yours

- **Windows** — double-click `windows\run.cmd`, and read
  [windows/README.md](windows/README.md).
- **Linux** — `./linux/ovpn install`, and read
  [linux/README.md](linux/README.md).

Everything below is shared. It is the same idea on both, and where the two
differ the commands are given side by side rather than in two documents that
would drift.

## Layout

```
ovpn-pin/
  README.md                <- this file: what pinning is, and every flag
  core/
    ovpn-proxy.py          <- the proxy, run by both halves and by the app
    ovpn-mobile.py         <- pinned configs, turned into ones a phone holds
  linux/                   <- ./linux/ovpn install, and the four scripts
    README.md
  windows/                 <- double-click windows\run.cmd
    README.md
  app/                     <- Relay, the desktop app
  .env.example             <- credentials, for the Linux scripts
  configs/                 <- put the .ovpn files you downloaded here
  pinned/                  <- the pinned copies come out here
  success/                 <- what connected, named by how long it took
    landlord/              <- the quickest per company, per country
      fastest/             <- the quickest per company, anywhere
  sitetest/                <- one folder per --site host, holding what served it
```

`configs/` arrives with a set of Surfshark's already in it, and everything
from there down is data rather than code. Both halves read and write the same
folders, so you can pin on Windows and connect on Linux without having to tell
either one where the other put things.

The `configs` folder is made on first run if it isn't there, so your own
provider's files go in beside them or instead of them.

## Use it

**Windows**

Double-click `windows\run.cmd`. It offers a short menu — pin, pin through
a proxy, check the exit you are on — and keeps the window open at the
end, so a run that fails is still readable.

```powershell
.\windows\Resolve-OvpnRemote.ps1
```

If PowerShell refuses to run it:

```powershell
powershell -ExecutionPolicy Bypass -File .\windows\Resolve-OvpnRemote.ps1
```

`windows\run.cmd` already starts PowerShell that way, for this one run
only — nothing on the machine is changed. It also passes arguments straight
through, so `windows\run.cmd -CheckCloudflare -Site chatgpt.com` works as a
desktop shortcut.

**Linux**

```bash
./linux/run.sh
```

If that says `Permission denied`, the executable bit did not survive however
the files reached you — a zip has no such bit, and neither does a checkout
made on Windows. `chmod +x *.sh` fixes it for good, and `bash run.sh` works in
the meantime. The menu runs the other scripts through `bash` for the same
reason, so only `run.sh` itself can be stopped by this.

The same menu `windows\run.cmd` gives on Windows, numbered the same way:
pin, pin through a proxy, judge the exit you are on, sweep every location,
who owns the addresses, connect, status. Each item asks the few questions
that item needs — which folder, which configs, which landlords — and then
prints the command your answers came to before running it:

```
  $ ./linux/ovpn-connect.sh --sweep --retest --pick-landlord
```

So it is a way of learning the flags rather than a substitute for them.
`./linux/run.sh --print-only` answers the questions and stops at that line,
and anything you pass straight through — `./linux/run.sh --sweep --one-per`
— skips the menu and goes to whichever of the two scripts owns that flag.

Or run the scripts directly, which is all the menu does:

```bash
./linux/resolve-ovpn-remote.sh
```

Either way:

```
  Configs (1)
  -----------
  [ ok ] de-fra_tcp_146.70.160.237.ovpn  146.70.160.237:1443  reachable
  [ ok ] de-fra_tcp_146.70.178.251.ovpn  146.70.178.251:1443  reachable
```

**Originals are never modified.** Import one of the pinned files into your
OpenVPN client and connect, or on Linux:

```bash
sudo openvpn --config pinned/de-fra_tcp_146.70.160.237.ovpn
```

## The rest of the Linux side

Installing `ovpn` so it works from any folder, keeping the client from asking
for a password, and connecting with the proxy out of the way are in
[linux/README.md](linux/README.md).

## When the tunnel connects but crawls

A tunnel that comes up, stays up, reports a clean exit — and then takes seven
seconds to start a page. Nothing in the diagnostics looks wrong, because
nothing is: pinging the exit over the physical link while the tunnel is
saturated comes back 0% loss at a steady 110 ms, and `openvpn` sits at 0.0%
CPU the whole time. There is no queue, no loss and no work being done. The
bytes are simply not arriving.

What that pattern means is that the line has recognised OpenVPN and is
suppressing it. Not the address — the protocol. The same exits also answer
HTTPS on 443, and there the same line has no opinion at all:

```
                                   through the tunnel      through 443
  40 MB                            never finished          2.4 s
  effective rate                   0.3 Mbit                130 Mbit
  time to first byte               1.4 - 7.1 s             0.6 s
```

Same server, same exit address, same credentials. Nothing about the exit is
faster; it is the same machine. Only one of the two is recognisable.

```bash
ovpn proxy uk-man            # or any part of a pinned config's name
ovpn proxy uk-man --port 8080
```

```
  config      uk-man.prod.surfshark.com_tcp_103.214.44.42.ovpn
  listening   http://127.0.0.1:8888
  exit        103.214.44.42:443
  certificate must serve uk-man.prod.surfshark.com, and the name is not sent

  In the browser, set BOTH the HTTP and the HTTPS proxy to
      127.0.0.1   port 8888
```

It carries HTTP and HTTPS, which is what a browser asks of a proxy. Nothing
else on the machine goes through it, and neither does anything that is not
HTTP — it is not a tunnel and does not pretend to be one. In exchange it needs
no root, changes no routes, no DNS and no system setting, and leaves nothing
to put back when you stop it.

### Not every exit runs one

Most of them do not, and there is no way to tell from the config which do —
so ask them all:

```bash
ovpn proxy-sweep --one-per --site www.scamspotter.org
```

```
  141 configs from /home/you/ovpn-pin/pinned
  also asking each exit for: www.scamspotter.org
  12 at a time - nothing is connected, so they do not queue behind each other

  [ ok ]    4/141   0.89s  be-bru.prod.surfshark.com_tcp_146.70.123.173  146.70.123.174 BE
  [part]    5/141   1.06s  bg-sof.prod.surfshark.com_tcp_37.19.203.78    37.19.203.79 BG  refused by www.scamspotter.org
  [fail]    6/141          us-sea.prod.surfshark.com_tcp_138.199.12.52   no proxy for this account
  ...

  13 of 141 served everything asked of them
```

That run took 25 seconds — the whole of `--one-per` in about the time one
tunnel takes to come up, because nothing is being connected and no route
moves. Of those 141 exits, 27 ran a proxy that took the credentials, 112
answered `407`, and 13 of the 27 also served the site.

`407` there is not a wrong password and not a rate limit. Two things decide
it, and the second one is easy to mistake for the first.

**How much you have been asking.** This is the big one, and it makes a large
sweep worse than useless — it produces a confident wrong answer. The same 17
configs, the same command, minutes apart:

```
  after a rest, --jobs 2     16 of 17
  after a rest, --jobs 8     16 of 17, three runs back to back
  after a 528-config sweep    3 of 17
  five minutes later          5 of 17, eight runs, identically
  later again                16 of 17
```

Nothing about those exits changed. Concurrency is not it — 2 and 8 behave the
same, and three consecutive 17-config runs at 8 never degrade. Volume is:
somewhere between fifty-odd requests and two hundred, the refusals stop being
about the exits. What exactly Surfshark limits is not something this repo has
established — the obvious models do not survive their own tests, and the
passes in a long sweep are spread evenly through it rather than stopping
after some count, which rules out the simplest reading.

What follows from it is simple enough anyway: **ask about forty at a time**,
which is what `proxy-sweep` now does unless told otherwise. Pointed at 528
configs it answered 27; pointed at the first 40 of the same folder, minutes
later, it answered 32.

Things ruled out along the way, each tested rather than assumed: credential
format (`Proxy-Authenticate: Basic` from both camps, and the same string that
fails one server succeeds on another in the same second), and any second door
— ports 1080, 3128, 8080, 8443 and 1443 do not speak proxy.

**Where you are asking from.** This one cost an evening. The same three
servers, in the same hour:

```
  from a machine leaving over the plain line          10/10
  from a machine already leaving via another VPN       0/20
```

`cz-prg`, `nl-ams` and `uk-man` all refused twenty times out of twenty from
the second machine and accepted ten out of ten from the first. If your own
traffic already leaves through some other tunnel or proxy, you are asking
from an address Surfshark's proxies mostly turn away, and the results tell
you about that address rather than about the exits. Check what you are
leaving from before believing a sweep:

```bash
curl -s https://www.cloudflare.com/cdn-cgi/trace | grep -E '^(ip|loc)='
```

### A third answer: filtered here

Some addresses complete the TCP handshake at the normal round trip and then
never answer the TLS one. That is neither of the two above, and it is worth
saying so in its own words:

```
  [fail]   39/40   01.0s-de-ber...152.89.163.229   filtered here - TCP answers, TLS gets nothing back
```

The server is not down. Reached *through* another exit's proxy, the same
address completes TLS immediately, three times out of three — so something
between this line and it takes the SYN, answers it, and then swallows the
payload.

It is not the DNS-correlation mechanism some Iranian ISPs use either, which
would be keyed on the name. `de-ber.prod.surfshark.com` is poisoned to
`10.10.34.35` by the ISP resolver, and two of its addresses behave
differently: `152.89.163.229` is swallowed, `86.38.98.71` works perfectly.
Same name, same poison, opposite outcomes — so the filter is on the address.

Which makes it the one failure here worth acting on rather than retrying:
re-pin that exit and take a different address for it. `ovpn sync` will find
one.

There is no other door either. Port 80 looks like a second proxy — it answers
`CONNECT` with `502` and serves absolute-URI `GET`s quite happily — but that
is this line, not Surfshark: the same request to `203.0.113.9`, a reserved
address that routes nowhere, returns the same `200` from the same Cloudflare
colo. Everything on port 80 is being intercepted before it leaves.

The practical consequence is that most exits will not have you, so let it
find one rather than naming one:

```bash
ovpn proxy            # no name - asks ~140 exits and takes the quickest
                      # one that will have it
```

```
  asking 140 exits which of them will take the credentials right now...
  de-fra.prod.surfshark.com_tcp_138.199.19.157.ovpn  answered in 0.45s
```

Naming one still works when you need a particular country, and it now asks
before it listens rather than coming up healthy and answering `502` to every
request — a fault that otherwise sends you hunting through browser settings
for something that is at the other end.

The set is stable enough to keep, though. `sitetest/www-scamspotter-org` — 17
exits that served that site through the tunnel — answered **17 of 17**, twice,
including once immediately after a 528-config sweep. Whatever makes a server
willing to proxy is the same thing that made it serve a picky site cleanly.

What served lands in `proxy-ok/`, named so that sorting the folder by name
sorts it by how quick the exit was, the same convention `success/` uses.
Every result including the failures goes to `.state/proxy-exits.tsv`:

```
config                                             verdict  ttfb   exit                www.scamspotter.org
be-bru.prod.surfshark.com_tcp_146.70.123.173.ovpn  ok       0.890  146.70.123.174 BE   ok
bg-sof.prod.surfshark.com_tcp_37.19.203.78.ovpn    ok       1.062  37.19.203.79 BG     challenged
us-sea.prod.surfshark.com_tcp_138.199.12.52.ovpn   no proxy for this account
```

It does not write to `success/` and does not read it. That folder means "the
tunnel came up here", which is a different question with a different answer —
on a throttled line, an exit the tunnel reached is not one you can use.

And the two answers overlap far less than you would guess — pointed at
`success/`, 533 configs every one of which came up as a tunnel, asked only
whether a proxy answers at all:

```bash
ovpn proxy-sweep --dir success --connect-only
```

```
    504   no proxy for this account
     26   ok
      3   TLS failed
```

Twenty-six of 528. Which is almost entirely an artefact of having asked 528
of them — see below. Asked forty at a time, the same folder returns **32 of
40**.

`--connect-only` stops at the proxy's `200` and fetches nothing, which makes
it fast — 533 exits in 63 seconds — and narrow: it says an exit will talk to
you, not what it will serve. So it copies nothing, and writes to
`.state/proxy-connect.tsv` rather than the file the full sweep writes. Use it
to find the handful worth asking properly.

### The name is proved, but never announced

Two things have to stay off the wire for this to survive the same line that
ate the tunnel.

The **address** is the one already pinned into the config. It is read back out
of the file rather than looked up again, so this inherits the property the
rest of the repo exists for: no resolver gets to answer for where the exit is.

The **name** is not sent as SNI. A TLS handshake that says
`*.prod.surfshark.com` in the clear is killed on its way out — which is worth
knowing on its own, because it is the same inspection that makes the tunnel
unusable, one layer up. Connecting by address with no SNI goes through
untouched.

Dropping SNI would normally cost you authentication, so the certificate is
checked by hand instead: the chain still has to verify against the system CAs,
and the presented certificate still has to say it serves the name the config
was pinned from. Fail either and the connection is dropped rather than
downgraded. What is given up is announcing the name, not proving it.

### Every request carries the credentials, not just the first

The exit authenticates each request separately, the way every real proxy does.
That is invisible to a browser and fatal to everything else, and it took a
while to see why.

A browser asking for HTTPS sends one `CONNECT`; past the `200` the connection
is an opaque tunnel and the question of credentials never comes up again. So a
proxy that puts the credentials on the first request of a connection and then
hands the rest over as an untouched byte pipe looks perfect in a browser, and
it is what this did.

Anything speaking plain HTTP down a kept-alive connection asks again and
again. The second request went out with no `Proxy-Authorization`, the exit
answered `407`, and the client's only move was to drop the connection and open
another — which bought it exactly one more request. Telegram, pointed at
`127.0.0.1:8899` as an HTTP proxy, connected and died about once a second for
as long as you watched it, while a browser on the same proxy was fine the
whole time. Which is what made it look like Telegram's problem.

So requests are now parsed one at a time on the way out and each is given the
credentials, with bodies followed by `Content-Length` so the next request head
is found where it really starts. `CONNECT` is unchanged in spirit and stricter
in one detail: the exit's answer is read before anything the client pipelined
behind the `CONNECT` is forwarded, since those bytes belong inside the tunnel
and, sent ahead of a refusal, would sit in front of the exit's parser to be
read as a second request.

```
python ovpn-proxy-test.py
```

Ten cases, no exit involved and nothing on the wire: a fake upstream that
demands `Proxy-Authorization` on every request stands in for the real one. It
is a test rather than a note because the fix lives in the middle of the
forwarding path and the failure is silent everywhere a browser can see.

### For Telegram, choose SOCKS5

That fix made Telegram work. It did not make it quick, and the reason is not
in this proxy at all.

Telegram Desktop, told its proxy is HTTP, **stops tunnelling entirely**:

```cpp
const auto useTcp = (proxyType != ProxyData::Type::Http);
```

With the TCP transport off, every MTProto packet becomes its own
`POST http://<dc-ip>:80/api` — one request per message, on port 80, in the
clear. No setting changes it; the branch has no exception. Choosing SOCKS5
instead leaves `useTcp` true and MTProto runs over one long-lived connection
inside a tunnel, which is what the Windows system proxy was quietly doing all
along and why it always felt better.

So the proxy answers **both protocols on the same port**, decided by the first
byte a client sends — `0x05` is SOCKS5, a letter is HTTP. Nothing to configure
and no second port:

```
In Telegram:  Connection type > Custom > SOCKS5
              127.0.0.1   port 8877   no username, no password
```

The exit never sees any of this. A SOCKS5 request becomes the same `CONNECT`
it has always been sent, so Surfshark's own SOCKS5 offering being discontinued
is beside the point. Names are forwarded rather than resolved here — the
`socks5h` behaviour — which is the only safe kind on a line whose resolver
lies.

### Connections opened before they are asked for

Every accepted connection paid TCP, TLS and `CONNECT` to another country
before a byte moved — three round trips, and on this line about 340 ms of
them. Two connections are now opened ahead of time and held, which removes
the first two. Same exit, same code, one connection at a time:

| | median | 
|---|---|
| `--warm 0` | 343 ms |
| `--warm 2` | **140 ms** |

Two rather than eight, deliberately, and the reason is visible in the numbers
above and absent from them. **The win is in serial short-lived connections** —
a chat client reconnecting, the first request of a page — where the refill
finishes long before the next connection arrives. Six tunnels opened *at once*
are barely helped at all: two held ready cover two of the six and the other
four dial cold, so the median moves within the noise. Measured repeatedly at
both settings, a six-way burst is a coin toss between them.

Sizing for the burst case would mean six or eight held open per exit, and that
is the connection rate `note.md` records an account being locked for. So the
burst stays uncovered on purpose.

A `CONNECT` tunnel consumes its connection — there is no framing to return to
once the exit has said `200` — so these are connections opened and not yet
spoken on, never connections handed back. One retry is allowed on a warm one
that turns out to have died while it waited, because nothing has reached the
client yet and a `CONNECT` that was never answered left no state behind.
Every sweep runs at `--warm 0`.

### More than one at a time

A proxy is a port and an exit, and nothing else. Two of them are two ports:

```
ovpn proxy connect                                # whatever is quickest, on 8888
ovpn proxy connect de-ber --port 8899 --detach    # and one that stays put
```

`--detach` is what makes the second one worth having. It comes back instead of
holding the terminal, and having no terminal of its own it outlives that one
closing — and logging out. Without it the proxy lives exactly as long as the
window you started it in.

```
$ ovpn proxy connect de-ber --port 8899 --detach
  Detached
  [ ok ] http://127.0.0.1:8899
  exit        152.89.163.229   de-ber.prod.surfshark.com
  pid         486367
  output      .state/proxy-8899.out
```

`nohup ovpn proxy connect … &` does the same job and was measured doing it —
it survives the terminal closing, and a full logout too where `logind` is left
at its default `KillUserProcesses=no`. `--detach` only saves you remembering
that, and puts the output somewhere predictable instead of wherever you were
standing.

What it does *not* do is restart itself. If the exit stops taking the
credentials, the proxy stays up and every request through it fails — `px
status` will not catch that, because the proxy is running exactly as it should
be. For that, a `systemd --user` unit with `Restart=always` is the honest
answer, and this repo does not ship one.

The exit is chosen, checked and proved *before* anything is detached, so a
failure is reported to you rather than disappearing into a log. What is left
can only fail at the bind, and that is waited for too — a detached proxy that
died quietly would leave `stop` finding nothing while `connect` found the port
taken.

Each also keeps count of what it carries. Beside the state file, every proxy
writes `.state/traffic-<port>.json` once a second — bytes out, bytes back, and
the rate over the last second — and deletes it when it stops. The counting
happens where the bytes are forwarded, so it is this proxy's traffic and not
the machine's, and the two directions are told apart rather than summed. It is
there for the Windows app's meter to read, and it is plain JSON, so `cat` or
`jq` will do as well:

```
$ jq . .state/traffic-8899.json
{ "pid": 486367, "port": 8899, "up": 1840244, "down": 58221097,
  "up_bps": 12480.0, "down_bps": 984320.0, "since": ..., "at": ... }
```

Beside it, `.state/hosts-<port>.json` every other second: the same bytes split
by where they went and — on Windows — which program asked, looked up from the
source port in the machine's own TCP table. Five hundred rows are kept and the
busiest hundred written, with `total` saying how many there were.

```
$ jq '.rows[0]' .state/hosts-8899.json
{ "host": "cdn.jsdelivr.net", "app": "msedge.exe", "pid": 21440,
  "up": 205312, "down": 24117248, "hits": 42, "live": 2,
  "first": ..., "last": ... }
```

A file left behind by a proxy that was killed outright is stale by definition,
so `at` is written with every reading and anything reading this should check
it. Nothing in the CLI does — the numbers are only ever asked for by something
that already knows the proxy is up.

They know nothing of each other. Starting, stopping or reconnecting one
leaves the other serving, because there is no shared state to disturb — no
routes, no DNS, no system proxy setting. Measured on one line, both up at
once:

| port | exit | what a request came out as |
|---|---|---|
| 8888 | `de-ber` | `152.89.163.230`, Frankfurt |
| 8899 | `se-sto` | `130.195.218.206`, Stockholm |

The reason to want a second one is that the two jobs pull in opposite
directions. A browser's exit is something you change on purpose and often. A
terminal or a chat client wants one that does not move underneath it — a
download that dies halfway because you switched countries is a download you
start again.

For a terminal there is `px`, which does that exporting for you:

```
px            send this shell through the proxy that is running
px 8899       through the one on that port
px off        stop
px status     what this shell is set to, and whether it still works
```

```
$ px
  Terminal
  [ ok ] http://127.0.0.1:8899
  exit        130.195.218.205   se-sto.prod.surfshark.com

$ curl -s https://ipinfo.io/country
SE
```

`px` is a shell function rather than a command, and has to be: it changes the
shell you typed it in, and nothing run as a child can do that to its parent.
`ovpn install` adds the line that defines it — or add it by hand:

```
. /path/to/ovpn-pin/ovpn-shell.sh
```

With more than one proxy up it asks which port. Name one once in the same rc
file and it stops asking:

```
export OVPN_PROXY_PORT=8899
```

`px status` is worth knowing about, because it names a failure that otherwise
says nothing useful. The variables outlive the proxy: stop the proxy without
running `px off` and every request from that shell fails at once, with nothing
to suggest why.

```
$ px status
  Terminal
  [warn] set to http://127.0.0.1:8899, but nothing of ours is listening there
         Every request from this shell will fail until that is one or
         the other. The proxy was probably stopped after it was set.
```

Underneath, `px` calls `ovpn proxy env`, which *prints* the exports rather
than applying them — same reason. Its stdout is shell and nothing else, and
every word meant for a person goes to stderr, because a sentence in among the
exports would be `eval`-ed as a command.

`curl`, `git`, `npm`, `pip` and `wget` all read what it sets. Anything speaking TCP on
443 travels the same way, because HTTPS goes through as `CONNECT` and
`CONNECT` carries whatever the two ends put inside it — a chat client pointed
at the same address and port works without knowing it is a proxy at all. What
does *not* go through: UDP, ICMP, and anything that ignores the proxy setting
it was handed.

`curl` fetching 25 MB through it finished at **11.4 MB/s** — about 91 Mbit,
against the 0.3 Mbit the same line gives the tunnel.

DNS needs no thought here. A request goes upstream as `CONNECT host:443`, so
the *exit* resolves the name; the only address this machine ever looks up is
the exit's own, and that one came out of the pinned config. There is no
setting to get wrong.

Because two are ordinary rather than exceptional, `stop` stops guessing once
there is more than one:

```
ovpn proxy stop              # names them and ends nothing
ovpn proxy stop --port 8899  # ends that one
ovpn proxy stop --all        # ends all of them
```

Refusing is the point. The browser's proxy and the one something has been
sitting on for a week are one keystroke apart, and only one of the two is easy
to notice the loss of.

### Not the other proxy in this README

`ovpn proxy` **serves** one, for your browser to reach the web through. The
proxy discussed under
[Connect, and get the proxy out of the way](linux/README.md#connect-and-get-the-proxy-out-of-the-way-linux)
is the opposite direction: a local proxy you already run, which `ovpn connect`
may have to dial *through* to reach a blocked OpenVPN server, and switches off
afterwards.
One is a way out; the other is a way in. They do not interact — `ovpn proxy`
brings up no tunnel and touches no system proxy setting.

### On Windows

There is no `ovpn proxy` here — `ovpn` is a bash script. The same verbs,
called directly, are in
[windows/README.md](windows/README.md#the-proxy-called-directly).

## Re-syncing when the addresses move

Providers rotate addresses, and a pinned file cannot follow them. The pinner
remembers every hostname it ever pinned from — in `.state/pins.tsv`, and in the
header of each pinned file — so getting fresh ones is:

```bash
./linux/resolve-ovpn-remote.sh --sync
```

```
  Sync
  ----
         cf-dns: hostnames recovered from cf-dns_1.0.0.1.ovpn

  Configs (1)
  -----------
  [ ok ] one.one.one.one: 1 new, 1 gone, 2 in total
         new:   1.0.0.1
         gone:  9.9.9.9  - anything pinned to those is stale
         removed 1 stale file(s) for cf-dns
```

It does not need the original `.ovpn` files. A pinned config is the original
with its hostnames swapped for addresses and a header saying which was which,
so the hostnames can be put back and re-resolved years after the download
folder was tidied away. If the originals *are* still in `configs/`, those are
used instead. Files for addresses that are gone are removed — except one that
happens to be connected right now, and `--keep-stale` turns that off entirely.

### What the warnings tell you apart

An empty answer has three different causes, and only one of them is yours to
fix:

| | |
|---|---|
| `does not exist any more (NXDOMAIN)` | the provider retired the hostname. No re-run brings it back — download a fresh config. |
| `exists but has no A record right now` | mid-change at the provider, or IPv6-only. Try later; what is pinned still works. |
| `the lookup itself did not get through` | the DoH path is broken, not the name. Nothing is known about the hostname either way. |

And an address that does not answer is asked about twice — once directly, once
through the proxy — because those answers mean opposite things:

| | |
|---|---|
| `reachable` | nothing to think about. |
| `only through the proxy` | the server is alive; your line blocks the **address**, not just its DNS. Connect it with `--via-proxy`. |
| `NOT reachable - and not through the proxy either` | the server has stopped answering. `--sync` for fresh addresses. |
| `NOT reachable` | nothing was running to ask. Start the proxy and re-run to find out which of the two it is. |

The proxy is asked with a `CONNECT`, and the reply is not taken at face value:
v2ray, Xray and that whole family answer `200` the moment they have parsed the
request and dial the far end afterwards, so a dead address gets the same
cheerful `200` as a live one — and then the tunnel is closed a moment later.
That close is the real answer, so the probe waits for it.

## Who the address is actually rented from

```powershell
.\windows\Resolve-OvpnRemote.ps1 -WhoIs
```

Or option 5 in `windows\run.cmd`. It connects to nothing and takes seconds.

```
  [ ok ] de-fra..._146.70.160.213.ovpn   146.70.160.213   M247 AS9009       Frankfurt, Germany
  [ ok ] ad-leu..._62.197.152.115.ovpn   62.197.152.115   Cyberzonehub ...  Andorra La Vella, Andorra

  By landlord
  -----------
         Cyberzonehub AS209854          303 addresses   62 countries
         CDNEXT AS212238                172 addresses   33 countries
         M247 AS9009                     96 addresses   23 countries
         Clouvider AS62240               21 addresses   Germany, United Kingdom, United States
```

A VPN provider owns almost none of its racks — it rents them from M247,
Datacamp, Clouvider, Cyberzone — and it is the landlord's name, not your
provider's, that a site sees. So when something blocks "a VPN" it is usually
blocking an ASN, and the four addresses you were about to try one after
another turn out to be one company in one building. That is worth knowing
*before* spending an hour sweeping them.

The lookup is [ip-api.com](http://ip-api.com), a hundred addresses per
request. Its free tier is HTTP only, so the query — a list of VPN addresses,
to a third party — crosses the line in clear. Nothing is in it that your DNS
and SNI did not already announce, but it should be your call: `-NoOwner` turns
it off inside the sweep, and nothing else does it unasked. If the plain
request does not come back at all, `ipwho.is` is asked over HTTPS instead.

Answers are kept in `.state/owners.tsv`, so it is free after the first run and
works with the line down.

## Then check the exit is clean

```powershell
.\windows\Resolve-OvpnRemote.ps1 -CheckCloudflare
```

```bash
./linux/resolve-ovpn-remote.sh --check-cloudflare
```

```
  [ ok ] traffic leaves over tun0
         Cloudflare sees you as 193.19.204.85 in CY, via LCA

  [ ok ] www.cloudflare.com       served
  [ ok ] speed.cloudflare.com     served
  [fail] chatgpt.com              challenge page - this exit is flagged

  [warn] partly served: some Cloudflare sites answer and others refuse.
```

A VPN exit shared by enough people picks up a bad reputation, and Cloudflare
then answers everything behind it with 403s and endless "checking your
browser". The tunnel is perfectly healthy — half the web just stops working.
This tells the two apart, so you stop debugging a connection that is fine.

Add the sites you actually care about, because Cloudflare's rules are
per-customer and a strict site refuses exits that Cloudflare's own pages serve:

```bash
./linux/resolve-ovpn-remote.sh --check-cloudflare --site chatgpt.com,github.com
```

**Run it while connected.** This is a measurement, not a prediction: nothing
observable from your own line says how Cloudflare will treat an exit you are
not using yet.

### Or judge them all at once

Which is what `--sweep` does: connect each pinned config in turn, ask
Cloudflare what it makes of that exit, drop it, move on.

```bash
./linux/ovpn-connect.sh --sweep --site chatgpt.com,github.com
./linux/ovpn-connect.sh --sweep de-fra          # only the files matching de-fra
./linux/ovpn-connect.sh --sweep --pick          # connect the best one at the end
```

```
  -> de-fra_146.70.160.237.ovpn  146.70.160.237:1443
  [ ok ] clean     exit 193.19.204.85 CY  (3 served)

  -> de-fra_146.70.178.251.ovpn  146.70.178.251:1443
  [warn] partly    exit 45.87.213.11 DE  (2 served, 1 refused: chatgpt.com challenged)

  Sweep results
  -------------
  [ ok ] de-fra_146.70.160.237.ovpn      193.19.204.85 CY      clean
  [warn] de-fra_146.70.178.251.ovpn      45.87.213.11 DE       partly - chatgpt.com challenged
  [fail] nl-ams_1.2.3.4.ovpn             -                     unreachable - address does not answer
```

Each verdict is remembered in `.state/exits.tsv`, so the menu shows it next
time without measuring anything again:

```
    1  de-fra_146.70.160.237.ovpn             146.70.160.237:1443 tcp  clean
    2  de-fra_146.70.178.251.ovpn             146.70.178.251:1443 tcp  partly
```

A sweep takes about half a minute per config — it is connecting to each one
for real, which is the only way this can be known. It leaves nothing connected
unless you asked for `--pick`.

Everything below is on both sides of the repo now, in the same words and
writing to the same tables:

```bash
./linux/ovpn-connect.sh --sweep --one-per-landlord   # one per company: ~21 tests
./linux/ovpn-connect.sh --sweep --one-per-landlord-location  # one per company per place
./linux/ovpn-connect.sh --sweep --one-per            # one address per location
./linux/ovpn-connect.sh --sweep --first 5            # stop after five
./linux/ovpn-connect.sh --sweep --landlord M247,CDN77
./linux/ovpn-connect.sh --sweep --pick-landlord      # list them, pick numbers
./linux/ovpn-connect.sh --sweep --retest             # re-test what worked
./linux/resolve-ovpn-remote.sh --who                 # who owns every pinned address
```

Configs that come up are copied into `success/` with the handshake time in
front of the name, and into `success/landlord/` with the hosting company and
the country as well — the same folders, the same naming, and the same
`.state/owners.tsv` the Windows half fills in. Sweep on one, read the results
on the other.

`--retest` sweeps `success/` instead of `pinned/`, and drops anything from it
that no longer connects.

### The same sweep on Windows

`Sweep-OvpnExits.ps1`, the folders it writes, the narrowing flags and running
it through WSL are in
[windows/README.md](windows/README.md#the-same-sweep-in-powershell).

## Options

| Windows | Linux | |
|---|---|---|
| `-Path` | `-p, --path` | A `.ovpn` file or a folder of them. Defaults to `configs`. |
| `-OutDir` | `-o, --out-dir` | Where copies go. Defaults to `pinned`. |
| — | `--sync` | Re-resolve the hostnames already pinned from, report what changed, remove what went stale. |
| `-WhoIs` | `--who` | Name the company each pinned address is rented from. Pins nothing. |
| — | `--keep-stale` | With `--sync`, keep files for addresses that are gone. |
| `-Proxy` | `--proxy` | HTTP proxy for the DoH lookups, e.g. `http://127.0.0.1:10808`. |
| `-NoProxy` | `--doh-via direct` | Resolve directly and never reach for a proxy, not even one that is running. Gives up in one sentence rather than in twenty seconds per hostname. |
| — | `--doh-via` | `auto` (default), `direct`, `proxy`. |
| `-Resolver` | `--resolver` | `cloudflare` (default) or `google`. |
| `-MaxIps` | `-n, --max-ips` | Cap on files written per input. Default 4. |
| `-InPlace` | `--in-place` | Overwrite the input instead of writing copies. |
| `-NoTest` | `--no-test` | Skip the reachability check. |
| `-CheckCloudflare` | `--check-cloudflare` | Judge the exit you are connected to now. Pins nothing. |
| `-Site` | `--site` | Extra hosts for the Cloudflare check. |
| — | `--env-file` | Where the credentials live. Defaults to `.env`. |
| — | `--auth-file` | Where to write them for OpenVPN. Defaults to `.ovpn-auth`. |
| — | `--add-auth` | Add `auth-user-pass` to configs that have none. |
| — | `--no-auth` | Ignore credentials; leave the client to ask. |

And for `ovpn-connect.sh`:

| | |
|---|---|
| `[config]` | a number, a filename, or part of one. Nothing: a menu. |
| `--switch NAME` | stop whatever is up, then connect `NAME`. |
| `--status` | what is up, and where traffic is leaving from. |
| `--stop` | tunnel down, system proxy back, kill switch removed. |
| `--sweep [NAME]` | connect each pinned config in turn and judge its exit. |
| `--site HOST[,HOST]` | the sites to test on each exit. |
| `--pick` | connect the best exit when the sweep is done. |
| `--one-per` | one address per location rather than all of them. |
| `--one-per-landlord` | one address per hosting company. ~21 tests, not ~141. Run this first. |
| `--one-per-landlord-location` | one address per company per location. Nine locations of HostRoyale, nine tests. |
| `--first N` | stop after N configs. |
| `--landlord A,B` | only the configs rented from these hosting companies. |
| `--pick-landlord` | list the companies behind them and pick by number. |
| `--retest` | sweep `success/` instead of `pinned/`, dropping what no longer connects. |
| `--success-dir DIR` | where the ones that connect are kept. Default `success/`. |
| `--sitetest-dir DIR` | where the per-`--site` folders go. Default `sitetest/`. |
| `--no-owner` | do not look up who owns each exit. |
| `--dns-check` | is DNS going through the tunnel, or still being forged? |
| `--via MODE` | `auto` (default), `direct`, `proxy`. |
| `--via-proxy` | `= --via proxy`. |
| `--fallback MODE` | when `auto` finds it unreachable: `ask`, `proxy`, `next`, `stop`. |
| `--next` | `= --fallback next`. |
| `--no-proxy-off` | leave the system proxy alone after connecting. |
| `--set-dns` | point the tunnel interface at the pushed DNS. |
| `--kill-switch` | drop everything that is not the tunnel while it is up. |
| `--kill-switch-off` | remove one left behind, and exit. |
| `--install-service` | write a systemd unit that connects at boot. |
| `--supervise` | connect and stay in the foreground. What the unit runs. |
| `--timeout N` | seconds to wait for the handshake. Default 45. |
| `--dry-run` | print the `openvpn` command that would run, change nothing. |

Every Linux flag has a `.env` equivalent (`OVPN_PROXY`, `OVPN_RESOLVER`,
`OVPN_MAX_IPS`, `OVPN_OUT_DIR`, `OVPN_FALLBACK`, `OVPN_SET_DNS`, …) if you
would rather not type them. The flag wins over an exported variable, which wins
over `.env`.

## What it actually does

- **Resolves over DoH, not your resolver.** Plain DNS is the poisoned layer, so
  a tool that used it would faithfully pin the forged address.
- **Rejects private and reserved addresses.** Not a blocklist of known-forged
  IPs — the whole of RFC1918, loopback, link-local and multicast. That keeps
  working when the censor picks a different address tomorrow.
- **Finds a proxy only if it needs one.** It tries DoH directly first; if that
  is blocked too, it looks for a local HTTP proxy (v2rayN, Clash, Nekoray,
  sing-box, Hiddify) on the usual ports. Tell it with `--proxy` if yours is
  somewhere unusual.
- **One file per address.** Where a name resolves to several servers you get a
  file for each. They are alternatives, not a ranking: if one stops answering,
  try the next.
- **Touches one line.** Port, protocol, certificates, `tls-auth` and everything
  else are copied through byte for byte, and the file keeps its original line
  endings, so a diff shows you the single change. (Two, with credentials.)
- **Checks TCP configs are reachable** before you find out the hard way. UDP
  ones are reported as untestable rather than guessed at — OpenVPN drops any
  datagram without a valid `tls-auth` HMAC, so silence from a UDP port means
  "blocked" and "working" equally.

## Does pinning an IP weaken anything?

No. OpenVPN validates the server against the CA in the config and whatever
`verify-x509-name` asks for. None of that involves the address you dialled — an
imposter at a pinned IP fails the certificate check exactly as it would at a
resolved one. What you give up is the provider's ability to move you to a new
address by changing DNS, which is the point.

## When to re-run it

Whenever the pinned files stop working. Providers rotate addresses — two runs
minutes apart here returned entirely different sets for the same hostname — and
a pinned file cannot follow them. On Linux that is `--sync` (above); on Windows,
run the script again. Either takes a couple of seconds.

## What it remembers

Two tab-separated tables under `.state/`, both gitignored, both readable and
editable by hand:

| | |
|---|---|
| `pins.tsv` | `host  source  port  proto  ips  first_seen  last_seen  status` — what each hostname resolved to last time, which is what `--sync` diffs against and what `--dns-check` compares your resolver to. |
| `exits.tsv` | `file  ip  verdict  checked  detail` — what Cloudflare made of each exit, which is what the menu shows. |
| `owners.tsv` | `ip  asn  owner  country  city  checked` — who each address is rented from, so `-WhoIs` and the sweep only ask once. |

Deleting either loses only history: the next run rebuilds what it can from the
pinned files themselves.

## A note on detecting the tunnel

The Cloudflare check asks the system which interface it would use to reach a
public address (`Find-NetRoute` on Windows, `ip route get` on Linux) rather
than reading the `0.0.0.0/0` route. OpenVPN's `redirect-gateway def1` does not
replace the default route; it lays `0.0.0.0/1` and `128.0.0.0/1` over the top,
and those win on longest prefix. An earlier version read `0.0.0.0/0`, reported
"not a tunnel", and was flatly wrong while Cloudflare was plainly reporting the
VPN exit.

The second trap on Windows is `Get-NetAdapter`. An IKEv2 or L2TP client —
Windscribe's among them — is a RAS interface, not a network adapter, so it
does not appear there at all, `-IncludeHidden` or not. A check written that way
finds nothing and reports no tunnel while every packet on the machine is going
through one. The route object knows the interface's alias without being asked
twice, so that is what both scripts read.

This matters beyond cosmetics: the sweep refuses to run while another VPN holds
the default route, and a detection that quietly fails would have let it sweep
forty configs through somebody else's tunnel and file the results as though
they were about Surfshark's exits.

## Requirements

- **Windows, pinning:** PowerShell 5.1 (built in) — nothing to install.
- **Windows, sweeping:** openvpn.exe, the community client
  (`winget install --id OpenVPNTechnologies.OpenVPN`), and administrator
  rights. Or WSL with `openvpn` in it, via `-Wsl`.
- **Linux, pinning:** bash 4+, `curl`, coreutils. All of which you have.
  `netcat` is used for the reachability check only if bash was built without
  `/dev/tcp`, which is rare.
- **Linux, connecting:** `openvpn`, `iproute2`, `sudo`. `ss` (iproute2) only
  matters for the proxied fallback, `resolvectl` for `--set-dns`, `nftables`
  for `--kill-switch`, and `systemd` for `--install-service`. Everything else
  works without them.
- Working DoH, either directly or through a local proxy.

## A warning about publishing these

`.ovpn` files often carry private keys, and some providers embed credentials
in them. The included `.gitignore` keeps `pinned/`, `success/`, `sitetest/`,
`.env` and `.ovpn-auth` out of git for that reason.

`configs/` is committed here on purpose, and it is worth saying why rather
than leaving it to look like an oversight. What a Surfshark config holds is a
`<ca>` block and a `<tls-auth>` key that every subscriber is handed the same
copy of, and an `auth-user-pass` line with nothing after it — the credentials
live in `.env`, which is ignored. There is nothing of yours in them, so the
repo arrives ready to pin rather than sending you back to the provider's
download page first.

Yours may not be the same. Open one and look before you push it.
