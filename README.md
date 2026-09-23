# ovpn-pin

**Get a VPN working on a line that is trying to stop you — and keep it fast.**

This started as one small script and grew, because each fix uncovered the next
problem. That history is worth telling, because it is also the explanation for
why the tool has the shape it has.

---

## Three problems, in the order we met them

### The name never resolves

A config you download from a provider points at a name:

```
remote de-fra.prod.surfshark.com 1443 tcp
```

On a censored line, that name is the weak link. Your resolver answers with a
forged address — `10.10.34.35` and friends, a machine on your own network —
and OpenVPN dials it and gets nowhere. Nothing is wrong with the server, and
nothing is wrong with the tunnel. The connection never left the building.

So the first thing this does is look the name up over DNS-over-HTTPS, throw
away anything that is not a real public address, and write the config back
out with the address in it literally:

```
remote 146.70.160.237 1443 tcp
```

After that, connecting does not depend on your resolver at all. That is
**pinning**, and it is where the name comes from.

### The tunnel connects, and then crawls

Pinning gets you connected. Then you notice the connection is unusable.

We measured it: the same server, the same exit address, the same account,
minutes apart.

|  | OpenVPN tunnel, port 1443 | HTTPS proxy, port 443 |
|---|---|---|
| 40 MB | never finished | 2.3 s |
| effective | **0.3 Mbit** | **99–137 Mbit** |
| first byte | 1.4 – 7.1 s | 0.6 s |

CPU, congestion, cipher, buffers and DNS were each ruled out. The line
throttles OpenVPN and leaves ordinary TLS alone.

But every provider that sells you an OpenVPN config is also running an HTTPS
proxy on 443 for its own browser extension — same servers, same account, same
exit addresses. Speaking to that instead of dialling a tunnel gets you the
second column. So the repo grew a **proxy**: it holds one connection open to
an exit and serves HTTP and SOCKS5 on your own machine.

### The proxy stops carrying uploads

Later — 2026 — that path broke too, in one direction only.

```
upload    8 MB  →  stalls dead at about 5 MB. Half the attempts never finish.
download 25 MB  →  80 Mbit on the same socket, seconds later.
```

Six exits across five countries failed identically. Pointing `curl` straight
at them, with none of this code in the way, failed the same way. It was not
the exits and it was not the code: something on the path shapes the upload
direction and leaves the download alone.

Nothing you can configure fixes a path that will not carry the bytes. So the
last piece is a **tunnel of your own** — a small server you rent, reached
through a CDN, standing where the provider's proxy used to.

```
upload    8 MB  →  40–80 Mbit, none of them failing
download 25 MB  →  131–136 Mbit, better than the provider managed
browsing        →  level with a direct connection
```

---

## What is in the box

Three ways out, and you can switch between them. They are not three flavours
of one thing — they are three different machines carrying your traffic.

**Through the provider.** Nothing to set up. Every country the provider sells,
and you pick one from a list. Its upload is dead, which may or may not matter
to you.

**Through your own server.** One steady address that belongs to you and is not
on anybody's list of known VPN exits. The fastest and the most consistent of
the three. There is no country to choose, because the server is where it is.

**Through your own server, then out at a provider exit.** Your traffic reaches
the provider's exit from your server rather than from your line, so sites see
that country's address and the upload keeps your server's speed. This is the
one that gets you both.

Around those sit the pieces that make them usable: a resolver that pins
addresses, a sweep that measures exits and tells you which are actually clean,
and a generator that turns any of it into a config your phone can hold.

---

## Which half is yours

**Windows.** Double-click `windows\run.cmd` for the scripts, or build the
desktop app and use that. → [windows/README.md](windows/README.md) ·
[app/README.md](app/README.md)

**Linux.** `./linux/ovpn install`, then `ovpn` from anywhere. →
[linux/README.md](linux/README.md)

**A phone.** Generate a config and import it. → [The phone](#the-phone) below.

The two halves read and write the same folders, so you can pin on Windows and
connect on Linux without telling either one where the other put things.

```
ovpn-pin/
  core/
    ovpn-proxy.py       the proxy, and the tunnel. Both halves run this one.
    ovpn-mobile.py      pinned configs, turned into ones a phone holds
  linux/                ./linux/ovpn install, and the shell half
  windows/              double-click windows\run.cmd
  app/                  Relay, the desktop app
  tunnel/               install-server.sh, and the tunnel client binary
  configs/              the .ovpn files you downloaded
  pinned/               the pinned copies come out here
  success/              what connected, named by how long it took
  sitetest/             one folder per site, holding the exits that served it
```

`configs/` arrives with a set of Surfshark's already in it. Everything from
there down is data rather than code, so your own provider's files go in beside
them or instead of them.

---

## Getting started

### Pin your configs

This is the step everything else assumes.

```bash
./linux/resolve-ovpn-remote.sh          # linux
```
```powershell
.\windows\Resolve-OvpnRemote.ps1        # windows
```

Or use the menu — `./linux/run.sh`, or double-click `windows\run.cmd` — which
asks the few questions each job needs, prints the command your answers came to,
and then runs it. It is a way of learning the flags rather than a substitute
for them.

```
  Configs (2)
  -----------
  [ ok ] de-fra_tcp_146.70.160.237.ovpn  146.70.160.237:1443  reachable
  [ ok ] de-fra_tcp_146.70.178.251.ovpn  146.70.178.251:1443  reachable
```

**Your originals are never modified.** The pinned copies land in `pinned/`,
and you import one of those.

If PowerShell refuses to run the script, `windows\run.cmd` already starts it
with the policy bypassed for that one run — nothing on the machine is changed.
On Linux, `Permission denied` means the executable bit did not survive however
the files reached you; `chmod +x *.sh` fixes it for good.

### Serve a proxy through an exit

```bash
ovpn proxy connect
```

With no name it asks around 140 exits which will take your credentials and
serves the quickest that will. That is the way to use it — a good many refuse
at any one time, and nothing in the config file says which.

It comes up on `127.0.0.1:8888` and answers both HTTP and SOCKS5 on that one
port; the first byte a client sends decides which. Point your browser at it,
or:

```bash
eval "$(ovpn proxy env)"     # this shell now goes through it
```

Nothing on the machine is changed while it runs. No routes, no DNS, no system
proxy setting — nothing to put back.

### Set up a tunnel of your own

You need a small server and a domain. The server can be the cheapest thing
your host sells; this is not heavy work. Point the domain at it in Cloudflare
with the proxy **on** — the orange cloud — which is what makes it reachable
from a line that blocks the server's own address.

Then, once, on the server:

```bash
./install-server.sh yourdomain.com <provider-user> <provider-pass>
```

It is safe to run twice, it leaves other sites on the box alone, and it prints
two passwords when it finishes. Keep them.

Back on your own machine, put those in `.state/tunnel.json`:

```json
{
  "domain": "yourdomain.com",
  "password": "the tunnel password",
  "apiPassword": "the api password",
  "clients": ["C:/Users/you/gost"]
}
```

`clients` is optional and is there for a machine that has more than one copy of
the client — the one this repo starts, and the one somebody put in Startup
months ago. Each reads its own file, and nothing tells the forgotten one that
the addresses moved. Listed here, it gets written too, and what was there is
kept beside it as `config.yaml.before-pinning` the first time. A running client
is not restarted, because that is a decision with a dropped connection in it —
it reads the new file the next time it starts.

And then:

```bash
ovpn proxy tunnel connect        # your server, then out at a provider exit
ovpn proxy tunnel cdn            # out at your server itself
ovpn proxy tunnel country de     # change where connect leaves by, live
ovpn proxy tunnel status
ovpn proxy tunnel scan           # find another way in, if the address is filtered
```

The desktop app has the same three choices as a strip on its front, and a
settings pane that will hand you the install command with the whole installer
in it — one paste, nothing to upload first.

---

## How it works

### The exit is reached, and never named

The provider's proxy is HTTPS on 443. Speaking to it means a TLS handshake,
and a handshake normally announces the name it is asking for in the clear.
On a line that is watching, `*.prod.surfshark.com` in a ClientHello is the
whole game.

So the name is never sent. The address is dialled directly — it is pinned, so
there is no lookup either — and the certificate that comes back is verified
against the real name by hand, afterwards. The proof survives; the
announcement does not.

The phone configs do the same where the client allows it. sing-box has
`disable_sni` beside `server_name`, which is the rare pair that keeps the
check while dropping the announcement. Clash cannot: its `sni` sets what is
sent as well as what is checked, so there the certificate check is given up
and the address stays pinned. Both files say so in their own comments.

### Every request carries the credentials

Not just the first. An HTTP proxy is allowed to remember an authenticated
connection, and most do; these do not, reliably, and a request without the
header comes back 407 in the middle of a working session. So every request
gets them.

### For Telegram, choose SOCKS5

Same port, same proxy. Told its proxy is HTTP, Telegram Desktop sends a
request per message; on SOCKS5 it keeps one connection open and is markedly
quicker.

### Connections opened before they are asked for

Every accepted connection used to pay TCP, TLS and CONNECT to another country
before a byte moved — 516 ms measured here, and 1024 ms when six started
together. That second number mattered: Telegram rebuilds any connection that
takes longer than a second, so a burst put the proxy the wrong side of its
own client's patience.

Two connections are now held ready. The same burst measures 797–851 ms, none
of them over the line. Two rather than eight, deliberately: the win is in
serial short-lived connections, and chasing a parallel burst would mean six or
eight dials per exit, which is the connection rate that gets an account locked.

### Not every exit runs a proxy, and a filtered one looks fine

Two failures matter and they look nothing alike.

An exit can simply refuse the account — a 407, immediately. Nothing about the
config says which will; a good many refuse at any one time and the same one
takes you an hour later.

An address can be **filtered on your line**: the TCP handshake completes at
the normal round trip and then the TLS handshake is answered by nobody. The
server is not down — reached through another exit, the same address completes
TLS immediately. Something between you and it takes the SYN and swallows the
payload. Retrying will never help; a different address for the same exit
usually will.

The sweep tells these apart and says which it found, because the fix is
different for each.

### The tunnel, and why it is shaped this way

Your server runs `gost`, which holds a multiplexed WebSocket through the CDN.
Your machine's proxy connects to the local end of that instead of dialling an
exit abroad. Everything above that line is unchanged — the same CONNECT, the
same relay, the same byte meter, the same list of which program asked for what.

The exit hop, when you want a country, runs **on the server** rather than on
your machine. Chaining it locally works, but costs about 1.75 seconds per new
connection, because every handshake round trip to the exit crosses the whole
path. From the server that same handshake is 45 ms. Moved there, it is 0.62
seconds — level with going out at the server directly.

Changing country is one request to the server's own API. Nothing restarts,
and connections already open keep the exit they were made through.

### Which leg a test measures

A sweep asks each exit how quick it is, and the answer depends on who is
asking. Dialled from this line, it says whether that address answers *here* —
which was the only question worth asking until the traffic started leaving
through the server, and is the wrong one now. The two disagree, and not
subtly: the same exit refused six uploads straight from this line in the same
minute it carried them at 1.2–1.8 MB/s through the server.

So the sweep takes a route:

```bash
ovpn proxy sweep --through provider      # dialled from here, as it always was
ovpn proxy sweep --through server+exit   # through your server, out at each exit
ovpn proxy sweep --through server        # your server itself, one row
```

`server+exit` sets each exit on the server through its API and measures from
the far side of the tunnel, one at a time — the server holds a single exit
chain, so two at once would each be reading the exit the other had just set.
It needs no administrator and takes nothing down, which is the other half of
why it is worth having: the provider route drops your connection once per
server and the tunnel routes do not touch it.

The window has the same three as a choice at the top of the test pane, with
what each would cost in minutes on it.

### When the way in gets filtered

The domain is reached through Cloudflare, and Cloudflare hands out two
addresses. On the first of September both of them stopped answering on 443
from this line — the SYN unanswered, while port 80 to the same addresses
still connected, which rules out the server, the certificate and SNI
filtering in one measurement. What was blocked was the address.

That is repairable from here without touching the server, because Cloudflare
is anycast: any edge address that carries the domain answers for it, given
the name in the SNI and in the Host header. On the day it broke, 22 of 40
addresses spread across their ranges answered while the two DNS was handing
out did not. So the client stops letting DNS choose. It keeps a handful of
addresses that were measured answering, writes them into `gost` as several
nodes under one selector, and dials those. The name is not dropped — only
the dialling. It stays in the SNI, where the certificate is checked against
it, and in the Host header, without which Cloudflare answers 1034.

None of that costs anything on a normal day. The list is kept on disk, so
starting up writes it out and dials; there is no scan. An address that dies
mid-session costs one retry — measured at 1.8 seconds for a request that had
to step over two blocked addresses, against 0.9 for the ones after it. Only
when every kept address is gone does anything scan, and it takes a few
seconds.

The part worth knowing is what it does *before* it scans. From inside the
tunnel, a filtered address and a server that has fallen over are the same
event: `gost` answers 503. They want opposite things done about them. So the
first question is asked of the CDN rather than of the tunnel — an
unauthenticated request to `/api/config`, which answers 401 when the address
is good, times out when it is filtered, and comes back as a Cloudflare 521
when the edge is fine and your server is not. Only the filtered answer leads
to a scan. The others are reported, because a scan cannot fix them and
running one anyway would spend the time and then blame the wrong thing.

### What skips the tunnel

Traffic to Iranian destinations goes out on your ordinary line. It arrives
faster that way, and sending it abroad and back would spend your server's
transfer allowance to make it slower. The list covers `.ir` and the larger
Iranian services that are not on `.ir`, and lives in one place that the app,
the command line and the phone generator all read.

---

## The phone

```bash
core/ovpn-mobile.py pinned --count 20
```

writes two files — Clash YAML and sing-box JSON — because phone clients
disagree about which they read. Import the YAML first; it is the one every
client takes.

Add your tunnel and every exit is dialled through your server instead of from
the phone:

```bash
core/ovpn-mobile.py pinned --count 20 \
    --tunnel yourdomain.com --uuid <the uuid the installer printed>
```

`--uuid` is needed the once. The installer generates it on the server and
never again, so it is a constant: given here, it is kept in
`.state/tunnel/vless-uuid` and every later `--tunnel` run picks it up on
its own. Lose it and it is `cat /etc/gost/vless.uuid` on the server, or
another run of the installer, which prints it again.

The file keeps its shape. The way in goes in front, each exit gains a
`detour` — `dialer-proxy`, in Clash — and a selector goes on the end, so the
phone has both ways out and switches between them in its own proxy picker.

The way in is three addresses rather than the domain, measured before the file
is written, under a group that tests them. Same reasoning as the desktop: the
addresses the domain resolves to are the ones that get filtered. Three and not
one because the phone is on a different network from the machine that measured
them, and the address that answers here does not always answer there. `--edge
IP` names them yourself; `--no-edge` writes the domain and lets the phone
resolve it, the way this worked before any of it was filtered.

Two habits are worth keeping. Generate **two** versions, one with the tunnel
and one without, so a phone still works on a day the server does not. And pick
the exits deliberately: with no arguments the generator takes them in
alphabetical order from `pinned/`, which hands you Andorra, Dubai, Albania and
Armenia. Point it at `success/`, which is named by measured speed, or name the
files you want.

**These files hold your provider password in clear text.** Nowhere a URL alone
would reach them — no gists, no pastebins.

---

## Keeping it working

### When the addresses move

Providers rotate addresses. A pinned config that used to work and now does not
is usually that, and re-running the resolver against the same folder fixes it.
`success/` is a good folder to re-pin from: it holds what actually connected,
named by how long it took.

### Which exits are clean

An exit that connects is not the same as an exit that is served. Cloudflare's
rules are set per customer, so *clean* is a statement about Cloudflare and not
about the web — an address that serves one site happily can hand you a
challenge page on the next.

The sweep asks each exit for the sites you name and files it under each one it
really served, so the question you get answered is the one you actually have.

### Who the addresses are rented from

A provider's hundred addresses in a country are often a handful of companies
wearing different numbers. Grouping by the company that owns the range and
taking the quickest from each gets you real diversity instead of ten names for
one machine.

### A warning about publishing

Anything under `pinned/`, `success/` or `.state/` can carry your credentials —
the pinned configs do if you asked for them inline, and every phone config
does. They are all kept out of git for that reason. Check before you push.

---

## Requirements

Pinning needs PowerShell 5.1 on Windows, or `bash` with `curl` on Linux.
Nothing else, and no administrator rights.

The proxy and the phone generator need Python 3. The desktop app needs
Python 3 with `pywebview` and `pystray` to run from source; a built copy needs
nothing. The tunnel needs the `gost` binary — one file — beside the app or on
your `PATH`, and a server you can run one script on.

`openvpn` itself is only needed for the sweep that times how long each exit
takes to connect. Everything else works without it.

---

## Where to read next

| | |
|---|---|
| [linux/README.md](linux/README.md) | installing `ovpn`, connecting, the kill switch, DNS |
| [windows/README.md](windows/README.md) | the PowerShell half and its flags |
| [app/README.md](app/README.md) | Relay, the desktop app, and what it does to your machine |
| `ovpn proxy help` | every command, written out |
| `note.md` | the measurements, and the things that turned out to be wrong |
