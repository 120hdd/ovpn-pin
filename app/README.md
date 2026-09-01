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

### Sign-in

The provider's **service** username and password, not the login email. An
email is refused by every exit with exactly the same silence as a wrong
password, so it reads as *no server accepted just now* and sends people
looking at their servers folder. An address typed into the username field is
refused here, out loud, for that reason.

It writes `.ovpn-auth` beside the app and takes the inherited permissions off
it with `icacls` — `chmod` on NTFS returns success and changes nothing, which
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
