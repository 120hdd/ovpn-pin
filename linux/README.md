# ovpn-pin on Linux

The Linux half of [ovpn-pin](../README.md): one command for all of it, the
credentials, and connecting with the proxy out of the way.

What pinning is and why, re-syncing when the addresses move, checking the exit
is clean and the full flag tables are in the [root README](../README.md).
They read the same on both platforms, so they are kept in one place rather
than written twice and left to drift.

## One command, from anywhere (Linux)

```bash
./linux/ovpn install
```

That drops a symlink at `~/.local/bin/ovpn` and tells you whether that folder
is on your `PATH`. Nothing is copied and nothing outside that one file is
touched; `./linux/ovpn uninstall` removes it again. Pass a folder to install
somewhere else — `./linux/ovpn install /usr/local/bin`, with `sudo`.

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
./linux/ovpn-connect.sh              # a menu of pinned/
./linux/ovpn-connect.sh de-fra       # or a number, a filename, or part of one
./linux/ovpn-connect.sh --status
./linux/ovpn-connect.sh --switch nl-ams
./linux/ovpn-connect.sh --stop
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
./linux/ovpn-connect.sh de-fra --set-dns      # or OVPN_SET_DNS=1 in .env
```

It is off by default because it only knows how to talk to `systemd-resolved`,
and because plenty of configs already carry their own `up`/`down` scripts that
do the same job.

`--dns-check` answers the question directly, at any time:

```bash
./linux/ovpn-connect.sh --dns-check
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
./linux/ovpn-connect.sh de-fra --kill-switch    # or OVPN_KILL_SWITCH=1 in .env
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
sudo nft delete table inet ovpn_pin       # or: ./linux/ovpn-connect.sh --kill-switch-off
```

### At boot

```bash
./linux/ovpn-connect.sh --install-service de-fra
sudo systemctl enable --now ovpn-pin
```

That writes a unit which runs `--supervise`: connect, then hold the foreground
until the tunnel dies, so `Restart=always` brings it back. The unit runs as
root, which has no desktop session, so it cannot switch your GNOME proxy
setting off — set `OVPN_PROXY_OFF_CMD` in `.env` if that matters on an
unattended machine.

