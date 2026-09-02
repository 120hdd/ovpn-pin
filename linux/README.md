# ovpn-pin on Linux

The Linux half. What pinning is and why it exists is in the
[root README](../README.md); this is how you actually live with it — one
command from anywhere, credentials that stop the client asking, connecting
without leaving a proxy in the way, and the safety nets around all of that.

---

## Install the command

```bash
./linux/ovpn install
```

That drops a symlink at `~/.local/bin/ovpn` and tells you whether that folder
is on your `PATH`. Nothing is copied and nothing outside that one file is
touched; `./linux/ovpn uninstall` removes it. Pass a folder to put it
elsewhere — `./linux/ovpn install /usr/local/bin`, with `sudo`.

Then, from any directory:

| | |
|---|---|
| `ovpn` | the menu, if you would rather be asked |
| `ovpn connect uk-lon` | a number, a filename, or part of one |
| `ovpn uk-lon` | the same thing — an unrecognised word is a config name |
| `ovpn switch uk-lon` | stop what is up, then connect that |
| `ovpn stop` · `ovpn status` | |
| `ovpn proxy` | serve a browser proxy through a live exit |
| `ovpn proxy tunnel connect` | the same, but through a server of your own |
| `ovpn proxy tunnel scan` | find another CDN address, when the one in use is filtered |
| `ovpn pin` · `ovpn sync` · `ovpn who` | the pinner |
| `ovpn sweep` | time the exits, and keep what worked |
| `ovpn check` | judge the exit you are on right now |
| `ovpn where` | which repo this name points at, and which folder it would use |

Flags pass straight through to whichever script owns them, so
`ovpn sweep --one-per-landlord` works exactly as if you had run the script.

### It uses the folder you are standing in

This is the part worth having the command for.

```bash
cd ~/ovpn-pin/success
ovpn connect uk
```

connects to something in `success/`, not in `pinned/`. The same holds in
`sitetest/www-scamspotter-org/`, or any other folder with `.ovpn` files in it.
It says which folder it chose, on one line, before anything else happens:

```
  folder: /home/you/ovpn-pin/success  (533 configs)
```

It declines more often than it accepts, and that is deliberate. If you have
already said which folder — `OVPN_OUT_DIR`, or `--retest`, or `--success-dir` —
it stays out of it. The repo root holds scripts rather than configs, so that
is refused. A folder with no `.ovpn` in it is ignored, so `ovpn stop` in your
home directory is not an error about missing configs. And `configs/` it will
use, but warns first, because those are the downloaded originals that still
name a hostname — the very thing this repo exists to work around.

The scripts underneath do not behave this way and are not meant to.
`ovpn-connect.sh` reads one folder, named by `OVPN_OUT_DIR`, defaulting to
`pinned/`. A script that quietly acts on wherever you happen to be standing is
a script you cannot put in a cron job. So the convenience lives in the wrapper
you type by hand, and the scripts stay literal.

`ovpn help folders` prints all of this, along with which folders get written to.

### What a sweep writes, and what it removes

A sweep only ever *reads* the folder you point it at. What it writes goes to
`success/`, `success/landlord/`, `success/landlord/fastest/` and
`sitetest/<host>/`. So sweeping `configs/` cannot damage `configs/`; it is
only pointless, because those files still name a hostname.

Standing in `success/` and running `ovpn sweep` is the same as `--retest`:
anything that has stopped connecting is **dropped from that folder**, because
a folder that claims these all work should not be quietly wrong. It says so
before it starts, and every deletion is keyed on a config's own name, so
nothing else is touched.

The landlord folders are the exception, and worth understanding before you
sweep one. They keep one entry per *company*, so their deletions are keyed on
a tag that several configs share: the first M247 exit to come up deletes the
other M247 files, including any still queued in the same sweep. Those are
skipped and named plainly —

```
  [warn] gone from the folder since this sweep started - skipped
         A quicker entry for the same company replaced it. Nothing is
         wrong with the config; this folder only keeps one of them.
```

— rather than reported as `openvpn would not start`, which is a lie about a
file that was working ten minutes ago. If the question you actually have is
*which company is quickest*, ask it without the pruning:

```bash
ovpn sweep --retest --one-per-landlord
```

---

## Stop the client asking for a password

Copy the example file, fill it in, and lock it down:

```bash
cp .env.example .env && chmod 600 .env
```

```ini
OVPN_USER=your-provider-username
OVPN_PASS=your-provider-password
```

From then on, every pinned config comes out pointing at an auth file the
script writes, mode 600:

```
auth-user-pass /home/you/ovpn-pin/.ovpn-auth
```

That is OpenVPN's own mechanism rather than a wrapper around it. The client
reads the file instead of prompting — on the first connect and on every
reconnect after a dropped tunnel, which is the case that actually costs you.
A re-prompt at three in the morning just means the tunnel stays down until
morning.

The path written in is absolute, because OpenVPN resolves a relative one
against whatever directory it was started in, and under `systemd` that is not
this one.

A few things it deliberately does not do. A config **without** an
`auth-user-pass` line is left alone, and the run says so: those authenticate
by certificate, and handing a username to a server that never asked can get
the connection refused outright — a failure that looks nothing like its cause.
If your provider wants one anyway, `--add-auth` adds the line. `--no-auth`
ignores the credentials for one run. And with no `.env` at all, nothing about
auth is touched, so the Linux script behaves exactly like the Windows one.

### Changing your password

Edit `.env` and connect as usual. `.ovpn-auth` is only a cache of those two
lines, and every script that reads it checks it against `.env` first and
rewrites it if they disagree. There is nothing to re-pin and nothing to delete
by hand.

It compares contents rather than timestamps, because an auth file touched
after the edit — a `chmod`, a restored backup, a folder sync — would still be
stale while looking newer.

Both `.env` and `.ovpn-auth` are gitignored.

---

## Connecting

```bash
ovpn connect                # a menu of pinned/
ovpn connect de-fra         # or a number, a filename, or part of one
ovpn status
ovpn switch nl-ams
ovpn stop
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

### Why the proxy gets switched off

Here is the thing that trips everyone up. A tunnel dialled *through* a proxy
rides on that proxy's TCP connection for its whole life. The proxy is not "the
initial connect" — it is the transport. Close it once you are connected and
the tunnel goes with it. There is no arrangement where OpenVPN uses a proxy to
get started and then lets go.

So the way to end up with the proxy switched off is to never need it for the
tunnel in the first place. Where the block is on DNS — which is what this repo
is about — the proxy is only needed for the lookup at pinning time. Once the
address is written into the config, OpenVPN dials it directly and the proxy has
no part in the connection at all.

`ovpn-connect.sh` measures that rather than assuming it. Before connecting it
probes the pinned address directly, with the proxy bypassed, and then either:

**The address answers.** Connect with no proxy, and switch the system proxy
off once the tunnel is up. This is the normal case and the one you want.

**It does not answer.** Then the address itself is blocked, not just its DNS,
and a tunnel to it would have to ride the proxy for its whole life. Rather
than doing that quietly, you get the choice:

| | |
|---|---|
| `--fallback proxy` | dial through the proxy; it stays up, and traffic is encrypted twice |
| `--fallback next` | try the other pinned files, take the first that answers directly |
| `--fallback stop` | connect nothing, and list which pinned files do answer |

On a terminal it asks. In a script it stops.

### Switching

`--switch` is a stop and a start, and the order around it is deliberate:

```
system proxy back on  →  SIGTERM to openvpn  →  wait for it to actually exit
→  check the tunnel device is gone  →  connect the next  →  proxy off again
```

The proxy comes back *before* the tunnel goes down, because the next connect
may need it. OpenVPN is never `SIGKILL`ed: a killed one leaves its routes and
DNS behind, and the next connect then fails for reasons that look like
anything but that. One tunnel at a time is enforced with a pid file.

### Does a local proxy fight the tunnel?

With v2rayN, sing-box or anything else used as a **local proxy** — a port on
`127.0.0.1` that you point things at — no. That is layer 7 and only carries
what you hand it; OpenVPN's routes are layer 3 and hand it nothing. The only
overlap is the system proxy setting, which is what gets switched off above,
and any browser you pointed at the port by hand.

There are two cases where they really do fight.

**The proxy client in TUN or transparent mode.** Then it is layer 3 as well,
and both want the default route. Use it as a plain local proxy for this, or
expect to debug routing loops.

**A tunnel dialled through the proxy while `redirect-gateway` is in the
config.** The proxy's own connection to its server gets routed into the tunnel
that connection is carrying, and the tunnel strangles itself. OpenVPN cannot
see this coming — as far as it knows, its server is `127.0.0.1`. So in that
mode, and only that mode, the script finds the address your proxy client is
talking to (from `ss`, or `OVPN_PROXY_UPSTREAM` in `.env`) and pins a direct
route for it before connecting, then removes it on the way down.

---

## The safety nets

### DNS

A tunnel does not fix your resolver. Without an `up` script, OpenVPN on Linux
leaves `/etc/resolv.conf` alone, so a poisoned answer still reaches you
through a perfectly good tunnel.

```bash
ovpn connect de-fra --set-dns       # or OVPN_SET_DNS=1 in .env
```

points the tunnel interface at the DNS the server pushed, through
`systemd-resolved`. It is off by default because that is the only resolver it
knows how to talk to, and because plenty of configs carry their own
`up`/`down` scripts that do the same job.

To answer the question directly, at any time:

```bash
ovpn connect --dns-check
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

It uses what the pinner already knows: ask the system resolver the same
question the pinner asked over DoH, and compare. A forged answer gives itself
away, because no public hostname resolves to a machine on your LAN.

### Kill switch

```bash
ovpn connect de-fra --kill-switch   # or OVPN_KILL_SWITCH=1 in .env
```

While the tunnel is up, everything that is not the tunnel is dropped — only
loopback, the tunnel device, the VPN server itself and your own LAN get out.
It lives in one `nftables` table of its own, `inet ovpn_pin`, so removing it
cannot take anyone else's firewall rules with it. IPv6 is covered by the same
drop policy, which is where a leak around a v4 tunnel usually goes.

`--stop` removes it. If something goes wrong and it outlives the tunnel your
network will look broken, so the way out is printed every time it is installed:

```bash
sudo nft delete table inet ovpn_pin       # or: ovpn connect --kill-switch-off
```

### At boot

```bash
ovpn connect --install-service de-fra
sudo systemctl enable --now ovpn-pin
```

That writes a unit which runs `--supervise`: connect, then hold the foreground
until the tunnel dies, so `Restart=always` brings it back.

One thing to know about it. The unit runs as root, and root has no desktop
session, so it cannot switch your GNOME proxy setting off. Set
`OVPN_PROXY_OFF_CMD` in `.env` if that matters on an unattended machine.

---

## The proxy, and your own tunnel

When the line throttles OpenVPN — see the [root README](../README.md) for the
measurements — you want the proxy rather than a tunnel:

```bash
ovpn proxy connect          # through a provider exit
eval "$(ovpn proxy env)"    # this shell now goes through it
```

And when the provider's proxy stops carrying uploads, you want one of your own:

```bash
ovpn proxy tunnel connect       # your server, then out at a provider exit
ovpn proxy tunnel cdn           # out at your server itself
ovpn proxy tunnel country de    # change where connect leaves by, live
ovpn proxy tunnel status
```

Setting that up takes three things on this machine: the repo, the `gost`
binary in `tunnel/`, and `.state/tunnel.json` holding what the server's
installer printed.

```bash
curl -fsSL -o /tmp/g.tgz \
  https://github.com/go-gost/gost/releases/download/v3.3.0/gost_3.3.0_linux_amd64.tar.gz
tar xzf /tmp/g.tgz -C /tmp gost && mkdir -p tunnel && mv /tmp/gost tunnel/gost
```

```json
{
  "domain": "yourdomain.com",
  "password": "the tunnel password",
  "apiPassword": "the api password"
}
```

If either is missing, the command says which and what to do about it rather
than failing at you. The server side — one script, run once — is in the
[root README](../README.md#set-up-a-tunnel-of-your-own).
