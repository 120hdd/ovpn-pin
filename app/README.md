# Relay — the desktop app

A window with one control, for someone who should never have to hear the word
*proxy*. It picks an exit, starts the proxy, points Windows at it, and puts
the machine back when it stops.

```
python app/build.py       build it, and use the Desktop shortcut it makes
python app/main.py        or run it straight from the repo, unbuilt
```

The built app is `dist/Relay/Relay.exe`, and the build leaves a shortcut on
the Desktop. It also copies in the servers, the credentials, the tunnel client
and the sweep scripts, so what comes out is ready to run rather than ready to
assemble.

In the Windows build, `Relay.exe` and its scripts may be installed under
`Program Files`. Relay keeps accounts, credentials, settings, server configs
and logs in `%LOCALAPPDATA%\Relay`, so it runs as a normal user. On first use
it copies any existing data beside the exe into that folder without replacing
files already there. The bundled starter configs are copied there too.

For a downloadable build, use `python app/build.py --public-release`. It writes
to `release-build/dist/Relay`, includes only configs tracked by git, and refuses
to finish if credentials, account data, sessions, or other runtime state enter
the output. It also renders five reviewed, credential-free pinned starters for
each supported provider, so adding a provider account is enough for a normal
first connection. It does not disturb the personal build under `dist/`.

```
dist/Relay/Relay.exe --selftest     what this copy can see, and where it looked
```

---

## What happens when you press the button

Four things, in about three seconds on the machine this was written on.

It asks up to 140 exits at once whether they will take your credentials, and
takes the quickest that says yes. Roughly one in twenty is willing at any
moment, so this is a race rather than a lookup — which is why the progress
readout counts servers asked instead of showing a percentage of nothing.

It starts the proxy as its own process on `127.0.0.1:8877`, or whichever port
Settings was given. Then it points Windows at that. Then it asks Cloudflare,
*through the proxy*, what address it now sees, and shows you that — because
that is the only evidence you have that any of it worked.

### The three ways out

A strip across the front of the card chooses where your traffic actually
leaves from. It sits above the country picker because it decides whether that
picker means anything at all.

**Provider.** Nothing to set up, every country the provider sells. Its upload
is dead on a shaped line, which may or may not matter to you.

**Your server.** Out at a machine you rent, reached through a CDN. The
quickest and steadiest of the three, and one address that is yours. There is
no country to choose, so the picker steps back — dimmed rather than hidden,
because a control that vanishes takes the layout with it and leaves you
wondering what you lost.

**Server + exit.** Through your server, then out at a provider exit, so sites
see that country's address while the upload keeps your server's speed.

Under the strip, one sentence says which of the three you are looking at and
what it costs as well as what it gives. Three words with no explanation would
make the second and third read as settings, when they are three different
machines carrying your traffic.

Why any of this exists — and the measurements behind it — is in the
[root README](../README.md).

---

## Settings

### Accounts

The provider's **service** username and password, not the login email. An
email is refused by every exit with exactly the same silence as a wrong
password, so it reads as *no server accepted just now* and sends people
looking at their servers folder. An address typed into the username field is
refused here, out loud, for that reason.

It writes `.ovpn-auth` under `%LOCALAPPDATA%\Relay` in a Windows build and
takes the inherited permissions off it with `icacls` — `chmod` on NTFS returns success and changes nothing, which
is worse than failing. If `.env` already carries credentials those are updated
too, and the window says so: `.env` is the side the shell and PowerShell
halves edit, and a password set in one place and not the other comes back
changed hours later, blaming the server.

The password is never read back into the page. The placeholder says whether
one is on file, an empty field means *keep the one I have* rather than *wipe
it*, and what you type is cleared out of the page the moment it is written.

### Route all of Windows

On, every program on the machine goes through the connection. Off, the proxy
still serves on its port and the machine is left alone — for someone who would
rather point one browser at it by hand.

### How Windows is routed: proxy setting or tunnel

Two ways to do the routing above, chosen under it.

**Proxy setting** (the default) sets the Windows proxy. Browsers and most of
what Windows ships follow it; a game, a command-line tool or a messenger with
network code of its own goes straight past it.

**Tunnel (TUN)** adds a network adapter that every program's traffic falls
into, whether it knows about proxies or not. `sing-box.exe` brings it up
(wintun is inside it; there is no driver to install) and hands each TCP
connection to the same worker on the same port, so the exit, the meter and the
host list are unchanged — except that the host list names sing-box rather
than the program behind it. What it does not carry:

- **UDP.** The provider's proxy is HTTP CONNECT, which is TCP only. UDP is
  refused at once so browsers fall back from QUIC straight away; voice calls
  and games that need UDP do not work while it is on.
- **IPv6.** Unreachable rather than leaking past the adapter; DNS answers are
  IPv4 only, so nothing tries it.

DNS is answered by sing-box, which asks `1.1.1.1` over HTTPS through the exit,
and `strict_route` stops Windows asking the other adapters' DNS servers. The
LAN, link-local and multicast ranges stay off the adapter. The worker's own
connection to the exit is routed around it by address, gost's to your server
by process, and this app's own traffic by process too, so a reachability test
still measures this line. From source that last rule covers every
`python.exe`.

The adapter needs administrator rights: Windows shows a UAC prompt each time it
connects this way, unless Relay is already elevated. An elevated script
(`.state/tun/run.ps1`) runs sing-box and is asked to stop through a file, the
same way the sweep is; it also watches the worker and takes the adapter down if
the worker goes, so the machine is never left pointed at nothing. Its files and
`sing-box.log` are in `.state/tun/`. Builds put `sing-box.exe` beside
`Relay.exe` (from `tunnel/` in the repo); without it the option is greyed out.

### Split tunnel

Under the routing choice: which connections go through the exit and which go
straight out on this machine's own line.

- **Off** — everything through the exit (the default).
- **Except these** — everything through the exit except the list.
- **Only these** — only the list goes through the exit.

The list is programs (by exe name — `telegram.exe`), sites (`digikala.com`
means it and everything under it), addresses and ranges (`5.6.0.0/16`), and the
**Iranian sites** preset: Iran's registered address ranges and the domains of
Iranian services, from `lists/` at the top of the repo — the same files the
phone uses. The ranges are RIPE NCC's record of what was handed to Iran,
regenerated with `python lists/update.py`; the domains are kept by hand.

Who applies it depends on how Windows is routed:

- **Tunnel (TUN)** — sing-box, which sees each connection's program, address
  and, by sniffing its first packet, its name. What goes around the exit
  leaves by its direct outbound, UDP included, and a listed name is looked up
  from this line's own DNS server so it is answered as seen from here. A
  change while connected restarts the adapter (one more UAC prompt).
- **Proxy setting** — the worker, which reads `.state/split.json` again
  whenever it changes, so a change applies to the next connection. It sees
  only programs that use the proxy, and matches a name as a name: looking up
  every name to compare its address would hand everything you visit to this
  line's resolver.

Never both: with the adapter up, the worker is told "off".

### The port it listens on

Editable in place rather than buried, because on a machine that already runs
a proxy 8877 is taken, and until this moves the app cannot connect and cannot
say why.

The one port answers both HTTP and SOCKS5; the first byte a client sends
decides which. That matters for Telegram in particular: told its proxy is
HTTP it sends a request per message, while on SOCKS5 it keeps one connection
and is markedly quicker. Same address, same port, better choice.

### Your own tunnel

The pane that turns the second and third ways out on. It wants three things —
a domain and two passwords — and all three are printed by the server's
installer when it finishes.

**The key.** The installer's last line is `relay://…`: base64url of a JSON
object with the server, its certificate's SHA-256 when it is reached by
address, both passwords and its Shadowsocks settings. Pasted into *Have a
key?* it sets the whole pane up at once — on the phone too, where the same
field is at the top of Settings → Connection. The key's pin is checked even
on the first contact, so a key also protects the first connection, which the
"keep whatever answers first" rule cannot. `cat /etc/relay/key` on the
server shows it again. It holds passwords: treat it like one.

**Another port.** On a server where something else already answers on 443 —
an Xray, another site — run the installer with `--port 8443` (or any free
port; through Cloudflare it has to be one Cloudflare proxies). The key then
carries the port, and both apps dial it for the WebSocket, the API and the
certificate; the exported links for other devices carry it too.

**Shadowsocks** is the second way in, beside the WebSocket through Cloudflare.
The server offers it on two ports (8388 and 8389 unless `--ss-port` moved
them): the first exits at the server, the second goes on through the same
exit-chain as `/ex`, so *Server + exit* reaches every location either way. It
goes straight to the server's address with no CDN in between — the quicker way
in whenever that address is reachable, and the one whose traffic counts as
domestic when the server is inside Iran. It does not survive a filtered
address the way the WebSocket does, so both stay available and the pane
switches between them. On this machine gost carries it (`connector: ss`); on
the phone the Go core does (`mobile/core/shadowsocks.go`). TCP only.

**Share with other devices** makes configs for anything with a V2Ray client
and no Relay — an iPhone in Shadowrocket, Streisand, V2Box, Hiddify or
sing-box. The server keeps numbered locations for them (`--slots`, 8 unless
told): slot 0 exits at the server, and each other slot is a user on its
sing-box routed to its own gost chain, `loc-<k>`. Pick locations, and for each
the app finds the exit that carries an upload best from the server — the same
search *Server + exit* makes — points a slot at it through the API, and asks
gost to write its config to its file so the slots survive a reboot. Then it
shows each slot's VLESS (`/vl`) and VMess (`/vm`) links over WebSocket and
TLS, with a QR code, through the same nginx and Cloudflare the apps use; for a
server reached by address the links accept its own certificate, since those
clients cannot pin one. The UUIDs are uuid5s of an id the key carries
(`x.id`), derived the same way by the installer, this app and the phone
(`app/export.py`, `mobile/core/export.go`). A slot's provider account lives
on the server for as long as the slot points at it, and every link carries
access to the server: share them like a password. Running the installer
again resets the slots to its placeholder, so share again afterwards.

There is a machine to set up first, and the pane says so rather than
pretending otherwise. **Copy** hands you the whole installer as a single
line: the script travels inside the command, base64, so there is one paste
into an SSH session and nothing to upload first. What is shown on screen is
the readable version, and a line underneath says so — a button that quietly
copies something different is a small lie.

**Test** does not check that a port is open. It carries something through the
tunnel and reports the address that comes back, because a listening port is
not a working tunnel: restart the server under a multiplexed session and what
is left answers the connection and then refuses everything, which from here
looks exactly like health. A stale session is restarted once and the window
says it did that, rather than simply taking five seconds longer for no stated
reason.

The two passwords do different jobs, and the pane explains which. The tunnel
one stops a stranger who finds your domain using it as an open proxy. The API
one is what lets this window change the exit country. If you lose them they
are on the server in `/etc/gost/tunnel.pw` and `/etc/gost/api.pw`.

### Pin them to real addresses, and time the servers properly

The two heavy jobs, both of which are the same work the scripts do, driven
from here instead. Pinning resolves each config's hostname over DoH and writes
the address in literally; the sweep connects to each exit for real and files
what worked by how long it took.

---

## The parts worth knowing about

### The system proxy is the whole product, and the dangerous part

Setting it is what makes this useful to someone non-technical — no browser
settings, no per-app configuration, everything just goes. It is also the one
thing here that can leave a machine broken: a proxy setting pointing at a
program that is no longer running means nothing loads, with no clue why.

So `winproxy.py` is arranged entirely around putting it back. The previous
settings are written to `.state/system-proxy-before.json` *before* anything
changes — that file existing means "this machine is altered and something owes
it a restore", and the next launch acts on it. Restoring happens on window
close, on `atexit`, on SIGINT and SIGTERM, and after any failed connect.
Disconnect restores the machine first and stops the proxy second, because the
other order leaves a window where everything fails.

Two things in there were found by measuring rather than by reading.

**Writing the registry does not work.** The standard recipe — set
`ProxyEnable` and `ProxyServer`, then call `InternetSetOption` with
`INTERNET_OPTION_SETTINGS_CHANGED` — makes WinINET flush its own cached copy
back over what you just wrote. The value reads back correctly right up until
the refresh call, and is the old one immediately after. Settings go in through
`INTERNET_OPTION_PER_CONNECTION_OPTION` instead.

**The two halves of Windows read different places.** WinINET holds the truth;
the registry values are a mirror it does not keep in step. Edge and Chrome
follow the first, while Python, many command line tools and various installers
read the second. Setting only one produces the worst outcome for someone who
cannot diagnose it — the browser works and something else does not — so both
are written, the mirror always second.

It restores everything it read, not just the three obvious values. The machine
this was built on had a PAC file configured by another tool while its proxy
was off, and a narrower restore would have silently deleted it.

### Nothing about the protocol is reimplemented

`engine.py` loads `ovpn-proxy.py` and uses its `Exit`, `can_connect`,
`read_config` and `read_auth` directly. The certificate check, the withheld
SNI and the CONNECT probe are all that file's. The same goes for the tunnel:
the ports, the paths and the client configuration written from them live in
`ovpn-proxy.py` and `app/tunnel.py` borrows them, because three copies of an
agreement the window, the command line and the server's installer all share is
three chances for one of them to learn something the others do not.

The one thing not reused is `pick_live()`, which prints to stdout and calls
`die()` — correct for a command line, useless behind a window that has to stay
up and explain itself.

### The tunnel is a child process, and it outlives the window

Relay does not speak WebSocket or multiplexing itself. It runs `gost`, which
does and which has been measured doing it, and points its own proxy at the
local port that comes up. Reimplementing that in Python would mean re-earning
numbers that already exist.

That process is left running when the window closes, on purpose: a connection
should not drop because somebody closed a settings sheet. Which also means it
outlives the copy of the app that started it — and since its log lives inside
`dist/`, the build has to end it before PyInstaller can clean that folder. It
does, finding it by which process holds the port rather than by name, because
someone else's `gost` is not ours to kill.

### The meter counts bytes, it does not estimate them

Every figure under the orb was counted by the worker process at the point where
it forwards the bytes, because that process is the only thing on the path that
sees both directions and can tell them apart. A per-adapter number would fold
in every other program on the machine, and the exit's own figures, if it
published any, would be everybody's at once.

The worker writes its totals once a second and deletes them on the way out.
The window checks the pid against the state file and the timestamp against the
clock, and shows nothing at all when either says the reading is not this
proxy's — a killed worker leaves its last file behind, and a new proxy on the
same port would otherwise inherit the old one's totals for a second and a half.

This survives the tunnel unchanged, which was the point of building it the way
it is built: the same meter, the same host list, the same *which program asked
for this*, whichever of the three ways out you chose.

### The log says which program, not just which host

Every row is attributed to the process that opened the connection, looked up
from the TCP table. Knowing that something on your machine talks to a host you
do not recognise is only half an answer; knowing which program does is the
half you can act on.

### It shows the country it measured, not the one on the label

A config named `us-nyc` can come out somewhere else entirely — providers move
capacity and the filename does not follow. The window shows where the exit
actually was, asked at connect time.

### Frozen, it re-launches itself as its own proxy

Once built, `sys.executable` is the app, so it cannot start a proxy by running
a script — that would open a second window. It runs its own exe again with a
flag, and that branch becomes the proxy for that run.

---

## The window itself

**The title bar is the real one.** Going frameless on pywebview costs resize
handles, Aero Snap, double-click-to-maximise, taskbar minimise, the open and
close animation and correct behaviour under display scaling — each with an
open unresolved issue, and pywebview restores none of them. Its drag is a
round trip to Python per mouse move, which is why frameless pywebview windows
are reported as choppy. Windows gives all of that away free with a title bar,
rounded corners included.

**The identity is set before any window exists.** Without an explicit
AppUserModelID the taskbar button belongs to the Python launcher rather than
to this app, and no icon fixes that afterwards.

**The tray icon owns the connection's lifetime.** Closing the window leaves
the proxy up, because that is what *connected* should mean. Quitting from the
tray takes it down and restores the machine.

**Minimising minimises.** To the taskbar, where the button says what it
does. Putting Relay away in the tray is what the close button asks about the
first time it is pressed, and is remembered from the answer; minimising as
well left no way to get the window off the screen and keep its taskbar
button.

**The tray menu says whether you are connected and as what**, connects or
disconnects, connects straight to a favourite, and holds the window's
switches: *Lock position* and *Always on top*. Its lines are cut to 40
characters (`short()`) - Windows sizes a menu to its longest item, and a
country with a long name through a hostname made it half the screen wide.
Windows draws that menu itself, so it is told dark or light to match the
theme (`winwindow.menus`), which is as near as a Win32 menu gets to nine
themes.

**It comes back where it was.** Size, position and maximised state are read
with `GetWindowPlacement`, which is right even while the window is minimised
or hidden in the tray, and put back with `SetWindowPlacement` — onto the
nearest monitor if the one it was on is gone. See `winwindow.py`.

---

## Checking a build

```
dist/Relay/Relay.exe --selftest
```

says what that copy can see and where it looked — the page, the proxy script,
the sweep scripts, the servers folder, the credentials, the tunnel client and
its installer. Bundled apps fail by not finding things, silently, behind a
window that only says something went wrong. This answers what support would
otherwise have to ask over the phone.

```
python app/main.py --ui-check
```

goes further: it runs the real page, reads back any script errors it collected
before anything else could throw, measures whether the front of the app fits
the window it was given, and leaves a picture of each sheet in
`.state/ui-check/`. A window that loads and then does nothing is this app's
characteristic failure — a script error early on leaves a page that looks
finished and answers nothing — and neither `--selftest` nor the sweep tests
can see it, because both stop before there is a window.

It has already earned its place twice: once catching a stylesheet that split
the install command down the middle of an argument, and once catching the orb
squeezed into an oval by a control that had grown the card underneath it.
