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

## Layout

```
ovpn-pin/
  Resolve-OvpnRemote.ps1   <- Windows: pin the configs
  Sweep-OvpnExits.ps1      <- Windows: judge every exit in turn
  run.cmd                  <- Windows: double-click this
  run.sh                   <- Linux: the same menu
  ovpn                     <- Linux: one command for all of it. ./ovpn install
  resolve-ovpn-remote.sh   <- Linux: pin the configs
  ovpn-connect.sh          <- Linux: connect, and drop the proxy
  ovpn-lib.sh              <- what the two Linux scripts share
  .env.example             <- credentials, for the Linux scripts
  configs/                 <- put the .ovpn files you downloaded here
  pinned/                  <- the pinned copies come out here
  success/                 <- what connected, named by how long it took
    landlord/              <- the quickest per company, per country
      fastest/             <- the quickest per company, anywhere
  sitetest/                <- one folder per --site host, holding what served it
```

The `configs` folder is created on first run if it isn't there.

## Use it

**Windows**

Double-click `run.cmd`. It offers a short menu — pin, pin through a proxy,
check the exit you are on — and keeps the window open at the end, so a run
that fails is still readable.

```powershell
.\Resolve-OvpnRemote.ps1
```

If PowerShell refuses to run it:

```powershell
powershell -ExecutionPolicy Bypass -File .\Resolve-OvpnRemote.ps1
```

`run.cmd` already starts PowerShell that way, for this one run only — nothing
on the machine is changed. It also passes arguments straight through, so
`run.cmd -CheckCloudflare -Site chatgpt.com` works as a desktop shortcut.

**Linux**

```bash
./run.sh
```

If that says `Permission denied`, the executable bit did not survive however
the files reached you — a zip has no such bit, and neither does a checkout
made on Windows. `chmod +x *.sh` fixes it for good, and `bash run.sh` works in
the meantime. The menu runs the other scripts through `bash` for the same
reason, so only `run.sh` itself can be stopped by this.

The same menu `run.cmd` gives on Windows, numbered the same way: pin, pin
through a proxy, judge the exit you are on, sweep every location, who owns
the addresses, connect, status. Each item asks the few questions that item
needs — which folder, which configs, which landlords — and then prints the
command your answers came to before running it:

```
  $ ./ovpn-connect.sh --sweep --retest --pick-landlord
```

So it is a way of learning the flags rather than a substitute for them.
`./run.sh --print-only` answers the questions and stops at that line, and
anything you pass straight through — `./run.sh --sweep --one-per` — skips the
menu and goes to whichever of the two scripts owns that flag.

Or run the scripts directly, which is all the menu does:

```bash
./resolve-ovpn-remote.sh
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

## One command, from anywhere (Linux)

```bash
./ovpn install
```

That drops a symlink at `~/.local/bin/ovpn` and tells you whether that folder
is on your `PATH`. Nothing is copied and nothing outside that one file is
touched; `./ovpn uninstall` removes it again. Pass a folder to install
somewhere else — `./ovpn install /usr/local/bin`, with `sudo`.

Then, from any directory:

| | |
|---|---|
| `ovpn` | the menu |
| `ovpn connect uk-lon` | a number, a filename, or part of one |
| `ovpn uk-lon` | the same thing — an unrecognised word is a config name |
| `ovpn switch uk-lon` | stop what is up, then connect that |
| `ovpn stop` / `ovpn status` | |
| `ovpn sweep --one-per-landlord` | flags pass straight through |
| `ovpn proxy` | serve a browser proxy through a live exit, when the tunnel is throttled |
| `ovpn proxy-sweep --connect-only` | which exits will take the proxy right now |
| `ovpn pin` / `ovpn sync` / `ovpn who` | the pinner |
| `ovpn check` | judge the exit you are on right now |
| `ovpn where` | which repo this name points at, and which folder it would use |

### The folder you are standing in

This is the part worth having it for:

```bash
cd ~/ovpn-pin/success
ovpn connect uk
```

connects to something in `success/`, not in `pinned/`. Same in
`sitetest/www-scamspotter-org/`, or any other folder with `.ovpn` files in it.
It says which folder it picked, on one line, before anything else happens:

```
  folder: /home/you/ovpn-pin/success  (533 configs)
```

`ovpn-connect.sh` itself does not work this way and is not meant to: it reads
one folder, named by `OVPN_OUT_DIR` and defaulting to `pinned/`. A script that
quietly acts on wherever you happen to be is a script you cannot put in a cron
job. So the convenience lives in the `ovpn` wrapper, which is the thing you
type by hand, and the scripts underneath stay literal.

It declines more often than it accepts, which is the point:

- `OVPN_OUT_DIR` already set, or `--retest` / `--success-dir` / `--sitetest-dir`
  on the command line — you already said which folder, so it stays out of it
- the repo root — that holds scripts, not configs
- any folder with no `.ovpn` in it, so `ovpn stop` in your home directory is
  not an error about there being no configs there
- `configs/` it will use, but warns first: those are the downloaded originals
  that still name a hostname, which is the thing this repo exists to work around

`ovpn help folders` prints all of this, plus which folders get written to.

### Which folders a sweep writes to

A sweep only ever *reads* the folder you point it at. What it writes goes to
`success/`, `success/landlord/`, `success/landlord/fastest/` and
`sitetest/<host>/`, wherever it was pointed from. So sweeping `configs/` cannot
damage `configs/` — it is only pointless, because those files still name a
hostname.

Standing in `success/` and running `ovpn sweep` is the same as `--retest`:
anything that has stopped connecting is **dropped from that folder**, because a
folder that says these all work should not be quietly wrong. It says so before
it starts, and every deletion there is keyed on a config's own name, so nothing
else is touched.

The landlord folders are the exception, and worth understanding before you
sweep one. They keep one entry per **company**, so their deletions are keyed on
a tag that several different configs share: the first M247 to come up deletes
the other M247 files, including any still queued in that same sweep. Those are
skipped and named —

```
  [warn] gone from the folder since this sweep started - skipped
         A quicker entry for the same company replaced it. Nothing is
         wrong with the config; this folder only keeps one of them.
```

— rather than reported as `openvpn would not start`, which is what used to
happen and is a lie about a file that was working ten minutes ago. The sweep
also warns at the start. But the same question is better asked as

```bash
ovpn sweep --retest --one-per-landlord
```

which tests one address per company out of `success/` and prunes nothing.

## Stop the client asking for a password (Linux)

Copy the example file, fill it in, and lock it down:

```bash
cp .env.example .env && chmod 600 .env
```

```ini
OVPN_USER=your-provider-username
OVPN_PASS=your-provider-password
```

Now every pinned config comes out pointing at an auth file the script writes
from that, mode 600:

```
  Credentials
  -----------
  [ ok ] written to /home/you/ovpn-pin/.ovpn-auth (mode 600)
         user: your-provider-username
         every pinned config will point at it, so the client stops asking.
```

```
auth-user-pass /home/you/ovpn-pin/.ovpn-auth
```

That is OpenVPN's own mechanism, not a wrapper: the client reads the file
instead of prompting, on the first connect and on every reconnect after a
dropped tunnel — which is the case that actually costs you, since a
re-prompt at 3am just means the tunnel stays down.

The path written in is absolute, because OpenVPN resolves a relative one
against whatever directory it happens to be started in, and under `systemd`
that is not this one.

Some notes on what it does and doesn't touch:

- A config with an `auth-user-pass` line gets it redirected at the auth file.
- A config **without** one is left alone, and the run says so. Those
  authenticate by certificate and have no use for a password; handing a
  username to a server that never asked can get the connection refused
  outright, and that failure looks nothing like its cause. If your provider
  does want one anyway, pass `--add-auth` and the line is added after the last
  `remote`.
- `--no-auth` ignores the credentials entirely for one run.
- Without a `.env`, nothing about auth is touched at all — the Linux script
  then behaves exactly like the Windows one.

### Keeping those two files to yourself (Windows)

`.env` and `.ovpn-auth` hold your password in clear. On Linux the scripts warn
if either is readable by anyone else, and `chmod 600` fixes it.

**On Windows `chmod` does nothing.** NTFS has no mode bits, and git-bash
returns success without changing anything:

```
$ ls -l .env
644  .env
$ chmod 600 .env      # exits 0
$ ls -l .env
644  .env             # unchanged
```

What decides it there is the ACL, and a folder that grants read to a group
passes it down to every file inside — so a repo you cloned into a shared or
sandboxed folder hands your password to whoever that group is, silently. The
sweeper now checks and prints the exact fix:

```
  [warn] C:\...\ovpn-pin\.env can be read by SOMEGROUP
         That file holds your VPN password. chmod does nothing on NTFS -
         it is an ACL, so it takes icacls:
              icacls "C:\...\ovpn-pin\.env" /inheritance:d
              icacls "C:\...\ovpn-pin\.env" /remove:g "SOMEGROUP"
```

The first line stops the file inheriting from the folder; the second takes away
what it already inherited. Nothing else in the folder is affected, so a group
that needs to read the repo still can — it just cannot read your password.
`icacls <file> /inheritance:e` puts it back.

SYSTEM, the local Administrators group and you are not reported. An
administrator can take ownership of any file and rewrite its ACL, so listing
them would be a warning you learn to scroll past — and a warning nobody reads
protects nothing.

### Changing your password

Edit `OVPN_USER` / `OVPN_PASS` in `.env` and connect as usual. `.ovpn-auth` is
only a cache of those two lines, and every script that reads it now checks it
against `.env` first and rewrites it if the two disagree — so there is nothing
to re-pin and nothing to delete by hand.

It compares the contents, not the timestamps: an auth file touched after the
`.env` edit — a `chmod`, a restored backup, a folder sync — would still be
stale while looking newer.

If `.env` has no credentials at all, an existing `.ovpn-auth` is left exactly
as it is and used as-is; a hand-written one is not clobbered.

`.env` and `.ovpn-auth` are both gitignored.

## Connect, and get the proxy out of the way (Linux)

```bash
./ovpn-connect.sh              # a menu of pinned/
./ovpn-connect.sh de-fra       # or a number, a filename, or part of one
./ovpn-connect.sh --status
./ovpn-connect.sh --switch nl-ams
./ovpn-connect.sh --stop
```

```
  Preflight
  ---------
         de-fra_tcp_146.70.160.237.ovpn  ->  146.70.160.237:1443 tcp
  [ ok ] the address answers directly - the proxy is not needed for this

  Up
  --
  [ ok ] de-fra_tcp_146.70.160.237.ovpn
         server:  146.70.160.237:1443 (tcp, direct - no proxy)
         device:  tun0

  [ ok ] system proxy off (was 'manual') - traffic goes through the tunnel only

         Cloudflare sees you as 193.19.204.85 in CY, via LCA
```

### The thing to understand about the proxy

A tunnel dialled through a proxy **rides on that proxy's TCP connection for
its whole life**. The proxy is not "the initial connect" — it is the
transport. Close it after connecting and the tunnel goes with it. There is no
arrangement in which OpenVPN uses a proxy to get started and then lets go of
it.

The way to end up with the proxy switched off is therefore to never need it
for the tunnel at all. Where the block is on DNS — which is what this repo is
about — the proxy is only needed for the DoH lookup at pinning time. Once the
address is written into the config, OpenVPN dials it directly and the proxy has
no part in the connection, so it can be shut off and nothing is tunnelled
twice.

`ovpn-connect.sh` measures that rather than assuming it. Before connecting it
probes the pinned address directly, with the proxy bypassed:

- **It answers** — connect with no proxy at all, and switch the system proxy
  off once the tunnel is up. This is the normal case, and the one you want.
- **It does not answer** — then the address itself is blocked, not just its
  DNS, and a tunnel to it has to ride the proxy for its whole life. Rather
  than doing that quietly, you get the choice:

  | | |
  |---|---|
  | `--fallback proxy` / `--via-proxy` | dial through the proxy; it stays up, traffic is encrypted twice |
  | `--fallback next` / `--next` | try the other pinned files, take the first that answers directly |
  | `--fallback stop` | connect nothing, and list which pinned files do answer |

  On a terminal it asks. In a script it stops.

### Switching

`--switch` is a stop and a start, in that order, and the order of everything
around it is deliberate:

```
system proxy back on  →  SIGTERM to openvpn  →  wait for it to actually exit
→  check the tunnel device is gone  →  connect the next one  →  proxy off again
```

The proxy comes back *before* the tunnel goes down, because the next connect
may need it. OpenVPN is never `SIGKILL`ed: a killed one leaves its routes and
DNS behind, and the next connect then fails for reasons that look like
anything but that. One tunnel at a time is enforced with a pid file.

### Does the proxy fight the tunnel?

With v2rayN (or anything else) used as a **local proxy** — a port on
`127.0.0.1` that you point things at — no. It is layer 7 and only carries what
you hand it; OpenVPN's routes are layer 3 and it hands it nothing. The only
overlap is the system proxy setting, which is exactly what gets switched off
above, and any browser you pointed at the port by hand.

Two cases where they do fight, and what happens:

- **The proxy client in TUN / transparent mode.** Then it is layer 3 too, and
  both want the default route. Use it as a plain local proxy for this, or
  expect to debug routing loops.
- **A tunnel dialled through the proxy** (the fallback above) while
  `redirect-gateway` is in the config. The proxy's own connection to its
  server would be routed into the tunnel that connection carries, and the
  tunnel strangles itself. OpenVPN cannot see this coming — as far as it
  knows, its server is `127.0.0.1`. So in that mode, and only that mode,
  `ovpn-connect.sh` finds the address your proxy client is talking to (from
  `ss`, or `OVPN_PROXY_UPSTREAM` in `.env`) and pins a direct route for it
  before connecting, then removes it on the way down.

### DNS

A tunnel does not fix your resolver. Without an `up` script, OpenVPN on Linux
leaves `/etc/resolv.conf` alone, so a poisoned answer still reaches you through
a perfectly good tunnel. `--set-dns` points the tunnel interface at the DNS the
server pushed, through `systemd-resolved`:

```bash
./ovpn-connect.sh de-fra --set-dns      # or OVPN_SET_DNS=1 in .env
```

It is off by default because it only knows how to talk to `systemd-resolved`,
and because plenty of configs already carry their own `up`/`down` scripts that
do the same job.

`--dns-check` answers the question directly, at any time:

```bash
./ovpn-connect.sh --dns-check
```

```
  [ ok ] 10.8.0.1  goes through the tunnel
  [warn] 192.168.1.1  leaves over wlan0 - outside the tunnel

         asking about de-fra.prod.surfshark.com
           your resolver: 10.10.34.35
           over DoH:      146.70.160.237 146.70.178.251
  [fail] your resolver is still handing back a private address - it is lying,
         and those answers are not coming through the tunnel.
```

It uses what the pinner already knows: for a hostname it has resolved over
DoH, ask the system resolver the same question and compare. A forged answer
gives itself away, because no public hostname resolves to a machine on your
LAN.

### Kill switch

```bash
./ovpn-connect.sh de-fra --kill-switch    # or OVPN_KILL_SWITCH=1 in .env
```

While the tunnel is up, everything that is not the tunnel is dropped: only
loopback, the tunnel device, the VPN server itself, and your own LAN get out.
It is one `nftables` table of its own — `inet ovpn_pin` — so removing it cannot
take anyone else's firewall rules with it, and IPv6 is covered by the same drop
policy, which is where a leak around a v4 tunnel usually goes.

`--stop` removes it. If something goes wrong and it outlives the tunnel, the
network will look broken, and the way out is printed every time it is
installed:

```bash
sudo nft delete table inet ovpn_pin       # or: ./ovpn-connect.sh --kill-switch-off
```

### At boot

```bash
./ovpn-connect.sh --install-service de-fra
sudo systemctl enable --now ovpn-pin
```

That writes a unit which runs `--supervise`: connect, then hold the foreground
until the tunnel dies, so `Restart=always` brings it back. The unit runs as
root, which has no desktop session, so it cannot switch your GNOME proxy
setting off — set `OVPN_PROXY_OFF_CMD` in `.env` if that matters on an
unattended machine.

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

### More than one at a time

A proxy is a port and an exit, and nothing else. Two of them are two ports:

```
ovpn proxy connect                      # whatever is quickest, on 8888
ovpn proxy connect de-ber --port 8899   # and one that stays put
```

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
proxy discussed under [Connect, and get the proxy out of the
way](#connect-and-get-the-proxy-out-of-the-way-linux) is the opposite
direction: a local proxy you already run, which `ovpn connect` may have to
dial *through* to reach a blocked OpenVPN server, and switches off afterwards.
One is a way out; the other is a way in. They do not interact — `ovpn proxy`
brings up no tunnel and touches no system proxy setting.

### On Windows

Same file, same command:

```
python ovpn-proxy.py uk-man
```

`ovpn proxy` is the Linux dispatcher's name for it; on Windows call the script
directly. It is the one thing in this repo that needs Python 3 — everything
else runs without it — because it has to hold a socket open both ways for a
browser and check a certificate along the way, which neither `curl` nor
PowerShell's stack will do on their own terms.

## Re-syncing when the addresses move

Providers rotate addresses, and a pinned file cannot follow them. The pinner
remembers every hostname it ever pinned from — in `.state/pins.tsv`, and in the
header of each pinned file — so getting fresh ones is:

```bash
./resolve-ovpn-remote.sh --sync
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
.\Resolve-OvpnRemote.ps1 -WhoIs
```

Or option 5 in `run.cmd`. It connects to nothing and takes seconds.

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
.\Resolve-OvpnRemote.ps1 -CheckCloudflare
```

```bash
./resolve-ovpn-remote.sh --check-cloudflare
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
./resolve-ovpn-remote.sh --check-cloudflare --site chatgpt.com,github.com
```

**Run it while connected.** This is a measurement, not a prediction: nothing
observable from your own line says how Cloudflare will treat an exit you are
not using yet.

### Or judge them all at once

Which is what `--sweep` does: connect each pinned config in turn, ask
Cloudflare what it makes of that exit, drop it, move on.

```bash
./ovpn-connect.sh --sweep --site chatgpt.com,github.com
./ovpn-connect.sh --sweep de-fra          # only the files matching de-fra
./ovpn-connect.sh --sweep --pick          # connect the best one at the end
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
./ovpn-connect.sh --sweep --one-per-landlord   # one per company: ~21 tests
./ovpn-connect.sh --sweep --one-per-landlord-location  # one per company per place
./ovpn-connect.sh --sweep --one-per            # one address per location
./ovpn-connect.sh --sweep --first 5            # stop after five
./ovpn-connect.sh --sweep --landlord M247,CDN77
./ovpn-connect.sh --sweep --pick-landlord      # list them, pick numbers
./ovpn-connect.sh --sweep --retest             # re-test what worked
./resolve-ovpn-remote.sh --who                 # who owns every pinned address
```

Configs that come up are copied into `success/` with the handshake time in
front of the name, and into `success/landlord/` with the hosting company and
the country as well — the same folders, the same naming, and the same
`.state/owners.tsv` the Windows half fills in. Sweep on one, read the results
on the other.

`--retest` sweeps `success/` instead of `pinned/`, and drops anything from it
that no longer connects.

### The same sweep on Windows

```powershell
.\Sweep-OvpnExits.ps1 -OnePerLandlord            # ~21 tests, start here
.\Sweep-OvpnExits.ps1 -OnePer
.\Sweep-OvpnExits.ps1 -Name de- -Site chatgpt.com,github.com
.\Sweep-OvpnExits.ps1 -OnePerLandlord -PickLandlord
.\Sweep-OvpnExits.ps1 -OnePer -Landlord M247,CDN77 -Pick
```

Or option 4 in `run.cmd`. Same measurement, same three verdicts, and the
results go into the same `.state/exits.tsv`, so the Linux menu will show what
a Windows sweep found and the other way round. Each exit is also named — the
hosting company it belongs to, as above — unless you pass `-NoOwner`.

Every config that comes up is copied into `success/` with the handshake time
written on the front of its name:

```
success/
  03.9s-nl-ams.prod.surfshark.com_tcp_146.70.161.237.ovpn
  04.2s-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn
  10.0s-us-nyc.prod.surfshark.com_tcp_146.70.240.219.ovpn
  11.8s-jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn
```

Sorted by name, that is a list of what actually works with the quickest at the
top — which is the question you have at the moment a tunnel drops and you want
another one running, rather than "which exit is cleanest". The seconds are
zero-padded so the sort does not put `10s` above `4s`. Originals are copied,
never moved, and a config swept again replaces its own entry instead of
leaving one per run. `-SuccessDir` puts them somewhere else.

### The same list, by landlord

Inside it, `success/landlord/` keeps the **quickest** exit from each hosting
company in each country — labelled with the company and the country the exit
actually came out in:

```
success/landlord/
  02.7s-Cyberzonehub-AS209854-CY-ad-leu.prod...._62.197.152.115.ovpn
  03.1s-Datacamp-AS60068-DE-de-fra.prod...._138.199.19.157.ovpn
  04.2s-M247-AS9009-DE-de-fra.prod...._146.70.160.213.ovpn
  05.5s-M247-AS9009-NL-nl-ams.prod...._146.70.161.237.ovpn
```

One file per company-and-country, not one per config: of six M247 exits in
Germany you only ever dial the fastest, and the other five are noise in a
folder you go to in a hurry.

And `success/landlord/fastest/` goes one step coarser — the quickest from each
company, wherever it lands:

```
success/landlord/fastest/
  02.7s-Cyberzonehub-AS209854-ad-leu.prod...._62.197.152.115.ovpn
  04.2s-M247-AS9009-de-fra.prod...._146.70.160.213.ovpn
```

About twenty files, one per company. When something blocks "a VPN" it is
almost always blocking a hosting company rather than your provider, so the
useful question at that point is *whose racks still work* — and this answers
it at a glance, quickest first.

The country is Cloudflare's reading of the exit, not the provider's label on
the file: `de-fra` is where they say it is, `DE` is where it answered from,
and the two do not always agree — which is worth knowing before you pick a
config for a country-specific reason.

A config that turns up under a different landlord next time replaces its own
entry rather than appearing twice, because providers do move a location onto
someone else's racks. One that stops connecting on a re-test leaves *every*
folder that claimed it works — `success/`, both landlord folders, and each
`sitetest/` folder — in the same breath. A config that cannot connect is not
serving anybody's site either, and folders disagreeing about the same config
are worse than any one of them being out of date.

### A folder per site

Pass `--site chatgpt.com,github.com` and each host gets a folder of its own
under `sitetest/`, holding the configs whose exit actually served **that**
site:

```
sitetest/
  chatgpt-com/
    02.0s-de-fra.prod...._146.70.160.213.ovpn
    03.0s-nl-ams.prod...._146.70.161.237.ovpn
  github-com/
    02.0s-de-fra.prod...._146.70.160.213.ovpn
    05.0s-de-ber.prod...._152.89.163.229.ovpn
```

This is a different question from the verdict. A `partly` exit is one that
served some things and was challenged on others, and which of the two your
site fell into is exactly what you wanted to know — reading it back out of a
detail string afterwards is not an answer. Every config that served the site
goes in, not just the quickest: this is a list of what works, and one entry
would be a single point of failure dressed up as a survey.

An exit that stops serving a site is taken back out on the next sweep, so the
folder keeps meaning what its name says. `--sitetest-dir` / `-SiteTestDir`
puts them somewhere else. It is gitignored, like `success/`.

### Re-testing what worked

That folder can then be swept in its own right, which is what option 4 asks
before anything else — `pinned\` or `success\`:

```powershell
.\Sweep-OvpnExits.ps1 -PinnedDir success
```

A survey of everything is a once-in-a-while job; re-testing the few hundred
that came up is a short one, and it is the honest way to find out whether
yesterday's good list is still good. Providers rotate addresses and exits get
flagged, so a list of what works is only ever true about the day it was made.

Re-testing behaves in one way the full sweep does not: **a config that no
longer connects is removed from `success\`**. A folder that says these all
work should not be quietly wrong. Only the copy goes — the original in
`pinned\` is left alone, and a later sweep can put it back.

The time on the front is stripped before anything is keyed on the name, so a
config gets one row in `exits.tsv` whether it was swept from `pinned\` or from
`success\`, the prefixes never stack up, and `-OnePer` still groups the four
addresses of a location together.

It waits 15 seconds for a handshake, not the 45 the Linux script allows. The
address was probed a moment earlier and answered, so a config that has not
finished the handshake by then is not being slow; raise it with `-Timeout` on
a line where that turns out to be wrong.

That 15 is a ceiling and nothing else. Every wait in the sweep is watched for
rather than slept through — the handshake appearing in the log, the default
route moving onto the tunnel, the route coming back off it when the tunnel
goes down — so a server that answers in three seconds costs three seconds, and
only the ones that never answer cost the full timeout. Measured end to end, a
config that connects takes about ten seconds including its Cloudflare verdict,
and each result line prints what it actually took:

```
  [ ok ] clean     exit 193.19.204.85 CY  (3 served) [8.2s]
  [fail] did not come up - no handshake in 15s [15.1s]
```

**Disconnect any other VPN first.** Two tunnels do not stack, they fight, and
all the ways it goes wrong look identical to "every server is dead": a kill
switch stops openvpn reaching the address, the routes contradict each other, or
the tunnel comes up and traffic still leaves the old way — measuring the other
VPN's exit. The sweep checks for this and stops rather than filing forty
results that are about somebody else's tunnel.

It drives **openvpn.exe — the community command line client**, which is a
separate thing from the OpenVPN Connect app and the GUI. Neither of those can
be driven from a script; that is the whole reason this file exists.

```powershell
winget install --id OpenVPNTechnologies.OpenVPN
```

It installs alongside whatever client you already have and does not disturb
it. The sweep needs administrator rights — opening a tunnel adapter and adding
routes both do — so it asks for them once and reruns itself elevated.

### Choosing what not to sweep

The flags worth knowing, because a full sweep of a real download folder —
791 files here — is six hours nobody has.

`-OnePer` takes one file per location rather than all of the four-odd
addresses each hostname resolved to. 791 files becomes 141 locations.

`-OnePerLandlordLocation` sits between the two: one address per company
*per location*. A location is only ever rented from one company, but a company
is spread over dozens of locations and they do not share a fate — HostRoyale
being fine in Paris says nothing about HostRoyale in Lisbon. So it asks about
each separately: nine locations of HostRoyale, nine tests, whatever the
forty-nine files underneath them say. This is the one to run *after* a
`-OnePerLandlord` sweep has told you which companies are worth having.

`-OnePerLandlord` is the coarsest: one address per hosting company, so
1561 files becomes **21 tests**. Being blocked is mostly a property of the
company rather than of the individual address, so this answers *whose
addresses still work* in ten minutes. Run it first, then narrow with
`-Landlord` and sweep the survivors properly. Given together with `-OnePer`
it wins, and says so.

These are not the same question, and it is worth being clear which one you
asked:

| | groups by | tests |
|---|---|---|
| `-OnePerLandlord` | company | ~21 |
| `-OnePer` | location | ~141 |
| `-OnePerLandlordLocation` | company × location | ~150 |
| — | nothing | ~1561 |

Choosing sixteen companies and asking for one per *location* still leaves you
most of the 141, because those companies cover dozens of locations between
them. If you wanted sixteen tests, that is `-OnePerLandlord`.

Given more than one of the three, the coarsest wins and the sweep says which
it used.

**Out of each group it takes the address that connected quickest the last time
it was swept**, read back off the names in `success/` — those have been
carrying the handshake time all along. With nothing on record yet it takes the
first, as it always did, and only claims otherwise for the ones it really had
a number for:

```
         51 company-and-location pairs, one address each
         51 of them chosen as the quickest a previous sweep recorded
```

`-PickLandlord` is the bigger cut. It shows you who the addresses are rented
from and sweeps only the companies you choose:

```
     1  Cyberzonehub AS209854           303 configs   62 countries
     2  CDNEXT AS212238                 172 configs   33 countries
     3  M247 AS9009                      96 configs   23 countries
     4  HostRoyale AS203020              49 configs   10 countries
     5  CDN77 AS60068                    47 configs   15 countries
     ...
         which ones? numbers, e.g. 1,3 or 1-4 (enter for all): 3,5
         chosen: M247 AS9009, CDN77 AS60068
         143 config(s) are rented from those
```

With `-OnePer` that is 38 configs, about twenty minutes, instead of six
hours. `-Landlord M247,CDN77` does the same without the list, for when you
already know.

Both of these ask before the elevation prompt, deliberately: everything you
have to decide is decided in the window you are already looking at, and the
choice is carried into the elevated one rather than asked again.

The sweep then reports by company as well as by config, which is the part
that tells you something about the addresses you did *not* sweep:

```
  By landlord
  -----------
  [ ok ] M247 AS9009                    6 clean, 2 partly
  [fail] CDN77 AS60068                  4 flagged
```

Two things it does that the Linux one does not have to:

- **Credentials up front.** Most pinned configs carry a bare `auth-user-pass`,
  and openvpn.exe stops and asks for it. A sweep that stops and asks 40 times
  is not a sweep, so the credentials are settled once before anything
  connects — from `.env`, or from `.ovpn-auth` if there is no `.env`, or by
  asking you once and writing a temp file that is deleted at the end. `.env`
  wins over the cached `.ovpn-auth`, and refreshes it when the two disagree.
- **A baseline.** It records the address Cloudflare sees *before* connecting
  anything. If an exit reports that same address, the tunnel came up but the
  traffic never went into it, and the run says `noroute` rather than filing a
  verdict about your own line as though it were the server's.

Stopping is done by sending `SIGTERM` over OpenVPN's management socket rather
than killing the process, for the reason the Linux script gives: a killed
openvpn leaves its routes behind, and in a sweep that would poison every
result after the first.

### Or from WSL

```powershell
.\Sweep-OvpnExits.ps1 -Wsl
```

Runs `ovpn-connect.sh --sweep` inside your WSL distribution, if you would
rather not install anything on Windows. It needs `openvpn` in there
(`sudo apt install openvpn`), it will ask for your sudo password, and the
tunnel exists inside WSL only — Windows itself is not on the VPN while it
runs. For a measurement that is fine, since the probes are in there too, but
do not expect your browser to be affected.

The narrowing flags cross the border with it — `-OnePer`, `-OnePerLandlord`,
`-Landlord`, `-PickLandlord`, `-First`, `-Pick`, `-NoOwner`, `-Timeout`, the
`success\` folder — and the translated command line is printed before it
runs, so you can see what actually went in. `-Force` has no counterpart on
the Linux side and is not passed on; it says so rather than pretending.

## Options

| Windows | Linux | |
|---|---|---|
| `-Path` | `-p, --path` | A `.ovpn` file or a folder of them. Defaults to `configs`. |
| `-OutDir` | `-o, --out-dir` | Where copies go. Defaults to `pinned`. |
| — | `--sync` | Re-resolve the hostnames already pinned from, report what changed, remove what went stale. |
| `-WhoIs` | `--who` | Name the company each pinned address is rented from. Pins nothing. |
| — | `--keep-stale` | With `--sync`, keep files for addresses that are gone. |
| `-Proxy` | `--proxy` | HTTP proxy for the DoH lookups, e.g. `http://127.0.0.1:10808`. |
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

`.ovpn` files often carry private keys, and some providers embed credentials in
them. The included `.gitignore` keeps `*.ovpn`, the `pinned` folder, `.env` and
`.ovpn-auth` out of git for that reason. Check before you push.
