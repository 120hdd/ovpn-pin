# اتصال — the Windows app

A window with one control, for someone who should never have to hear the word
proxy. It drives `ovpn-proxy.py`, sets the system proxy so every program on
the machine goes through it, and puts the machine back when it stops.

```
python app/build.py       build it, then run the Desktop shortcut it makes
python app/main.py        or run it straight from the repo, unbuilt
```

The built app is **`dist/Ettesal/Ettesal.exe`**, and the build puts a shortcut
to it on the Desktop. Nothing is left in `build/` — PyInstaller writes an
intermediate `Ettesal.exe` there which looks identical, has no `_internal`
beside it and fails the moment it is double-clicked, so the build deletes the
whole folder rather than leaving two exes where one is broken.

The build also copies the servers and credentials in, so what comes out is
ready to run rather than ready to assemble.

```
dist/Ettesal/Ettesal.exe --selftest    what it can see, and where it looked
```

## What it does when you press the button

1. Asks up to 140 exits at once whether they will take the credentials, and
   takes the quickest that says yes. Roughly one in twenty is willing at any
   moment, so this is a race, not a lookup — that is why the progress readout
   counts servers asked rather than showing a percentage of nothing.
2. Starts `ovpn-proxy.py` as its own process on `127.0.0.1:8877` — or on
   whatever port Settings was told to use instead.
3. Points Windows at it.
4. Asks Cloudflare, *through the proxy*, what IP it now sees, and shows that.

Measured on the machine this was written on: about three seconds from press to
connected.

## Settings

### Sign-in

The Surfshark **service** username and password — not the login email, which
is refused by every exit with the same silence as a wrong password, and so
reads as *no server accepted just now* and sends people looking at their
servers folder. An address typed into the username field is refused here with
that said out loud.

It writes `.ovpn-auth` beside the app, two lines, and takes the inherited
permissions off it with `icacls` — `chmod` on NTFS returns success and changes
nothing, which is worse than failing. If `.env` already carries an `OVPN_USER`
and `OVPN_PASS`, those are updated too and the window says so: `.env` is the
side the shell and PowerShell halves edit, and `Sync-CachedAuthFile` rewrites
`.ovpn-auth` from it on the next sweep. A password set in one place and not
the other would come back changed, hours later, blaming the server.

The password is never read back into the page. The field's placeholder says
whether one is on file, an empty password field means *keep the one I have*
rather than *wipe it*, and what is typed is cleared out of the DOM the moment
it has been written.

### The port it listens on

`8877` unless you say otherwise, kept in `.state/settings.json`, and the same
number whether Windows is routed or not — the switch above it decides who uses
the port, not where it is.

It exists because `8877` is only free until it is not. A second copy of this,
a proxy somebody already runs, a corporate agent: any of them takes the port,
and a fixed one turns that into an app that cannot connect and cannot say why.
The failure it replaces is real — the machine this was written on already had
something on `8899`, which is what the default used to be, and the only sign
was `[WinError 10048]` in a worker log nobody thinks to open. The default
moved to `8877` for that reason: `8899` is what the command-line half of the
repo uses, and the two are meant to be able to run side by side.

**One port, both protocols.** It answers HTTP and SOCKS5 on the same number,
decided by the first byte a client sends. Browsers can use either.

**Telegram must be set to SOCKS5, not HTTP.** Told its proxy is HTTP,
Telegram Desktop turns its TCP transport off altogether and sends a separate
request per message, on port 80, in the clear — `useTcp = (proxyType !=
Type::Http)` in its source, with no exception. On SOCKS5 it keeps one
connection and is measurably quicker. No username or password: the proxy
listens on the loopback only, and a password between two programs on one
machine protects nothing the machine does not already.

Three decisions in it are worth naming:

**It moves a live connection rather than waiting for the next connect.** The
field would otherwise read `9050` while Windows was still pointed at `8877`,
and a number on screen that has stopped being true is the one bug this window
cannot have. So while something is up, changing the port restores the machine,
stops the worker, dials **the same exit** again on the new port and re-points
Windows at it. The exit is not re-chosen: it was raced for and it accepted the
credentials, and throwing that away to change a port number would cost the
several seconds that finding it took. Everything already known about it — the
country, how fast it answered, the address Cloudflare confirmed — survives the
move. Only the pid is new.

**A port somebody else holds is refused while connected and merely reported
while not.** Disconnected, whatever has it may well be gone by the time anyone
presses Connect, so the number is saved and the sheet says what it found.
Connected, the same tolerance would trade a working connection for one that
cannot come up, so the move is refused before anything is torn down and the
connection stays where it is. Who holds it is answered by a **bind**, not a
connect: something that has the port but is not accepting refuses a connect
and would read as free.

**It is committed on leaving the field, not on every keystroke.** The other
port box in this sheet — the resolver's — commits as you type, because the
worst that costs is a socket test. This one restarts a running proxy and
re-points the machine, so typing `9050` would otherwise be four of those.

Out of range is refused out loud rather than snapped quietly back to `8877`: a
person typing a port has a reason for it, and a field that accepts `88999` and
silently keeps the old number is a field that lies about where the app is
listening. Below `1024` is refused too — not forbidden by Windows, but every
one of them belongs to something that expects to be found there. An empty
field means `8877`, which is the way back.

### Pin them to real addresses

The step before everything else, and the one the whole repo is named after. A
config you download points at a hostname:

```
remote de-fra.prod.surfshark.com 1443 tcp
```

On a censored line that hostname is the weak link. The resolver answers with a
forged address — 10.10.34.35 and friends, a machine on your own LAN — and
OpenVPN dials it and gets nowhere. It is not the tunnel failing; it never left
the building. This resolves each remote name over DoH, throws away anything
that is obviously forged, and writes the address into the file literally, so
nothing depends on your resolver at connect time. A hostname with four
addresses gives you four files, which are alternatives rather than a ranking.

The pane is shaped as the route it describes rather than as a form: three
nodes on a hairline — **from** a folder, **over** something, **into** a
folder — and the node in the middle is the only one of the three that can be
wrong, which is why it is the only one that carries a colour. It goes jade
once something really answers on that port and red once nothing does.

**With a proxy, or without.** The resolver's own default is neither: left
alone it tries direct, and the moment that fails it goes hunting through
twelve well-known ports for a local proxy. That is the right reflex at a
terminal and a third answer nobody chose in a window with two buttons on it,
so the window always says which — `-Proxy http://127.0.0.1:PORT` for one,
`-NoProxy` for the other. `-NoProxy` is new, and it exists because *direct*
had no way to be said: without it, asking for direct on a line where DoH is
blocked would quietly become a proxy hunt, and asking for it on a line with no
proxy at all would spend twenty seconds per hostname discovering that. It
probes once and gives up in a sentence.

Through a proxy on `127.0.0.1:10808` is the default, because it is the answer
that works on the line this app exists for — direct DoH is the first thing a
censored resolver takes away, and anyone who could resolve directly would not
be pinning anything. v2rayN, Nekoray, Clash and sing-box all listen there
unless they have been moved. Only the lookups go that way; nothing else does.

**Addresses each** caps how many files one config turns into — four by
default, which is the resolver's own. **Knock on each address** is the TCP
probe on every file written, and it is on: a dead address is worth finding
here rather than at the moment you try to connect through it. UDP configs
cannot be knocked on — OpenVPN drops any datagram without a valid `tls-auth`
HMAC, so silence and success look identical — and they say so rather than
guess.

Nothing about the resolving is reimplemented. `pin.py` starts
`Resolve-OvpnRemote.ps1`, reads its output, and turns that into events the
window can draw; `app/pin-test.py` holds the script's own lines, captured from
a real run, and fails loudly if somebody rewrites one of them. Unlike the
sweep it needs no administrator rights and no `openvpn.exe`, so there is a
pipe to read rather than a log file to poll across an elevation boundary, and
stopping it is killing it. Files already written stay written.

The folder it writes into is one of the three the app looks for servers in, so
the run ends with a chip that points the app at it — download, pin, time, and
connect, without leaving the sheet.

### Time the servers properly

There are two ways to ask an exit how quick it is, and they measure different
things.

The connect button uses the fast one: a TLS session to the exit's HTTPS proxy,
credentials offered, answer timed, eight at once. That is the right question
when you are choosing which exit to open in the next second — but what it
times is the proxy's front door, and a server whose proxy answers in 300 ms can
still take fourteen seconds to raise a tunnel, or fail to raise one at all.

This is the slow one, and it is `Sweep-OvpnExits.ps1` — the same sweep the
command line runs, driven from the window rather than reimplemented in it.
`openvpn.exe` connects to each config for real, the handshake is timed to the
moment traffic can actually leave, Cloudflare is asked what it makes of the
exit, and it is dropped again. That number goes on the front of the filename,
which is what the country list is ordered by.

The cost is stated before anything starts, using the script's own arithmetic —
a quarter of a minute per server, so 533 of them is about two and a quarter
hours — and your connection goes down and up once per server for the whole of
it. Everything that would make it fail or lie is checked before the UAC prompt
rather than after it: the scripts being present, `openvpn.exe` being
installed, this app not holding a connection of its own, and no other VPN
already holding the default route. A person who has just approved
administrator rights for something that then says *openvpn is not installed*
has been made to pay for nothing.

Three things about driving it are not obvious:

- **Elevation cannot be redirected.** Opening the tunnel adapter and writing
  routes need administrator, and `-Verb RunAs` means ShellExecute, which has
  nowhere to put a pipe. So a small wrapper is elevated instead and starts the
  sweep as an ordinary child of itself, with its output redirected to a file
  the window reads along.
- **An unelevated process cannot kill an elevated one.** Stop writes a file;
  the wrapper is watching for it and does the killing.
- **Killed halfway leaves a tunnel up.** openvpn is mid-connection when the
  sweep dies, so the wrapper takes down the openvpn processes the sweep
  started — identified by the sweep's own log folder on their command line, so
  nothing else anyone has running is touched.

It needs the OpenVPN *community* client. OpenVPN Connect and the GUI cannot be
driven from a script:

```
winget install --id OpenVPNTechnologies.OpenVPN
```

### The sheet itself

Five decisions, five shapes — not five of the same card. The version before
this gave every one of them the same box, the same padding and the same
rhythm, which is what makes a settings panel read as a form to fill in rather
than as somewhere to decide four unrelated things.

So: the gate is a card, the switch is a single line with no card at all, the
folder is a strip, measuring carries the weight of the other four put
together, and the paths at the bottom are a footnote and are shaped like one.
Each pane arrives sixty milliseconds after the one above it — under the
threshold where that reads as a queue, over the one where it reads as nothing
— and the only thing that moves on hover is the section's own glyph.

### Narrowing it: how many, and whose

Five hundred and thirty-three addresses is two and a quarter hours. It is also
mostly the same measurement over and over, because a hostname resolves to four
addresses and you got a file for each, and because being blocked is largely a
property of the hosting company rather than of the address — a site that
refuses the first M247 address usually refuses the rest.

So the sheet offers the sweep's own four groupings, each showing what it comes
to, because the count is the whole decision:

| | flag | here |
|---|---|---|
| Every address | — | 533 · about 134 min |
| One per location | `-OnePer` | 91 · about 23 min |
| One per company, per location | `-OnePerLandlordLocation` | 115 · about 29 min |
| One per company | `-OnePerLandlord` | 16 · about 4 min |

The middle one is the one to reach for once a sweep has told you which
companies are worth having: a location is only ever rented from one company,
but a company is spread over dozens of locations and they do not share a fate.

Underneath, every company behind the folder as a chip with its ASN and its
address count, from `.state/owners.tsv` — the table the pinner already keeps,
read here rather than the service asked again. Picking some passes `-Landlord`;
picking none sweeps them all. Addresses that have never been looked up are
counted and named, because choosing any company at all leaves them out, and a
count that quietly shrinks would be the app hiding its own arithmetic. **Look
the rest up** fills them in through the pinner's own batched lookup.

`-PickLandlord` is deliberately not used. It is a numbered menu and a
`Read-Host`, which is right at a terminal and a hang behind a window — so the
choosing happens in the sheet and only the answer is handed over.

Three things this cost, all found by driving the controls rather than looking
at them:

- **Every choice took a second and a half to show its new number**, because
  recomputing the plan re-ran the check for another VPN holding the default
  route, and that is a PowerShell process. It is worth a second when the sheet
  opens and when **Start** is pressed, and it is not worth it on a click of a
  chip. `Start` still runs the full check, so nothing is skipped where it
  matters.
- **Clicking a company put the scope back.** The page resent the scope
  alongside the company, reading it out of the last answer it had been handed
  — which is stale precisely when somebody clicks a second thing. Each control
  now changes only its own setting, and the chips read their state off the DOM
  rather than off that answer.
- **Arguments have to carry their own quotes.** `Start-Process -ArgumentList`
  joins an array with spaces and quotes nothing, so `M247 AS9009` arrived as
  two parameters. A servers folder under `Program Files` would have been the
  same bug, sitting there since the first version.

### Sites to check from each exit

*Clean* is a statement about Cloudflare, not about the web. Cloudflare's rules
are set per customer, so an exit that serves `cloudflare.com` happily can still
hand you a challenge page on the one site you opened this app for — and the
sweep would have called it clean.

Name those sites and each exit is asked for them too, while it is connected.
Every name gets a folder in `sitetest/` holding the configs that **served that
one site** — not "was clean overall", which is a different and weaker claim. A
config that stops serving it is taken back out on the next sweep, so the folder
keeps meaning what its name says.

Those folders appear under the field as chips with a count. Clicking one points
the app's server folder at it, which is the whole point of the exercise:
connecting only through servers that were measured getting into the site you
named, rather than through whatever is quickest and hoping.

Two details in the parsing, both there because getting them wrong is silent:

- **The names are checked more strictly here than in the script.** The sweep
  accepts `^[A-Za-z0-9._-]+$`, which lets a bare word through — and a bare word
  is probed as `https://word/`, fails, and is counted against the exit. One
  typo in this field would quietly mark every server dirty. A name has to have
  a dot and end in letters, and anything thrown out is named on screen.
- **The folder name has to match `Get-NameTag` exactly.** `chatgpt.com` becomes
  `chatgpt-com`, capped at 28 characters. If that drifts, the window lists
  folders that were never written and misses the ones that were.

```
python app/sweep-test.py
```

The window follows a sweep by reading its log, so the two are joined by nothing
but the exact wording of a handful of lines. That test feeds the reader the
script's own messages, filled in, and fails by name if one of them is reworded
— rather than a two-hour sweep running behind a window that shows nothing and
cannot say why.

## The parts worth knowing about

### The system proxy is the whole product, and it is the dangerous part

Setting it is what makes this useful to someone non-technical: no browser
settings, no per-app configuration, everything just goes. It is also the one
thing here that can leave a machine broken — a proxy setting pointing at a
program that is no longer running means nothing loads, with no clue why.

So `winproxy.py` is arranged around putting it back:

- The previous settings are written to `.state/system-proxy-before.json`
  **before** anything is changed. That file existing means "this machine is
  altered and something owes it a restore", and the next launch acts on it.
- Restoring happens on window close, on `atexit`, on SIGINT/SIGTERM, and after
  any failed connect.
- Disconnect restores the machine *first*, then stops the proxy. The other
  order leaves a window where everything fails.

Two things in there were found by measuring, not by reading:

**Writing the registry does not work.** The standard recipe — set
`ProxyEnable`/`ProxyServer`, then call `InternetSetOption` with
`INTERNET_OPTION_SETTINGS_CHANGED` — makes WinINET flush its own cached copy
back over what you just wrote. The value reads back correctly right up until
the refresh call and is the old one immediately after. Settings go in through
`INTERNET_OPTION_PER_CONNECTION_OPTION` instead.

**And the two halves of Windows read different places.** WinINET holds the
truth; the `ProxyEnable`/`ProxyServer` registry values are a mirror it does not
keep in step. Edge and Chrome follow the first, while Python, many CLI tools
and various installers read the second. Setting only one produces the worst
outcome for someone who cannot diagnose it — the browser works and something
else does not — so both are written, the mirror always second.

It also restores **everything it read**, not just the three obvious values. The
machine this was built on had a PAC file configured by another tool while its
proxy was off; a narrower restore would have silently deleted it.

### Nothing about the protocol is reimplemented

`engine.py` loads `ovpn-proxy.py` and uses its `Exit`, `can_connect`,
`read_config` and `read_auth` directly — the certificate check, the withheld
SNI, the CONNECT probe are all that file's. The one thing not reused is
`pick_live()`, which prints to stdout and calls `die()`; correct for a command
line, useless behind a window that has to stay up and explain itself.

### It shows the country it measured, not the one on the label

A server named `mk-skp` came out in Croatia. One named `eg-cai` came out in
France. Providers sell locations they do not physically have, so the large
word is always what Cloudflare reported, and a line underneath says what the
server was labelled when the two disagree.

### Frozen, it re-launches itself as its own proxy

`sys.executable` is the app once bundled, so starting a proxy by running a
script would start a second window. `Ettesal.exe --proxy-worker …` turns that
run into the proxy instead. `paths.py` holds the resource/data split that
makes this work, and everything it gets wrong shows up in `--selftest`.

## The title bar is the real one, painted

Still Windows' own caption, and still for the reasons at the top of `main.py`:
frameless on pywebview costs the resize handles, Aero Snap, snap layouts,
double-click-to-maximise, minimise from the taskbar, the open and close
animations and correct behaviour under display scaling, and pywebview restores
none of it.

But Windows 11 will recolour the caption it is already drawing.
`DWMWA_CAPTION_COLOR` takes the bar, `DWMWA_TEXT_COLOR` the title,
`DWMWA_BORDER_COLOR` the hairline — so the bar is `#111113`, the same as the
window under it, and stops reading as a lid on the app. The minimise, maximise
and close glyphs follow the caption and come out white; they stay the system's
own, which means they keep their hover colours, their tooltips, their snap
layouts flyout and their hit targets at the very edge of the screen.

Their *shape* is Segoe Fluent Icons and is not ours to change. That is the
whole trade: the glyphs are Windows', and so is everything they do.

Before Windows 11 22000 the attributes are simply not there and the call does
nothing. The app looks like it did.

One thing this cost, and it is worth writing down: `import ctypes.wintypes`
was first put inside the function that needed it, which makes `ctypes` a local
name for that entire body — so the line above it saying `ctypes.windll` raised
`UnboundLocalError`. On pywebview's own thread, where the traceback is printed
and swallowed. What you saw was a window that came up with no tray icon, no
address watcher and no diagnostic, and no clue why. `on_start` now runs each
of its steps in its own try, so one of them failing can no longer take the
other three with it.

## The first open of the exit sheet

It stuttered, once, on the first open after launch and never again. Everything
below was measured rather than reasoned about, and the first two instruments
were both wrong in the same way — they stopped the clock before the part that
was slow.

Timing `openPicker()` measured the DOM work, which was never the problem.
Timing to the second frame afterwards still stopped before the compositor had
finished. What a stutter *is* is one frame far longer than the rest, so the
frames themselves are sampled for a second after the click and the worst one
is the number. `--ui-check` reports it, so nobody has to decide from a
screenshot whether it feels better.

| | worst frame, first open |
|---|---|
| as found | 375 ms |
| sheet with no list and no search field | 7 ms |
| after the changes below | 195 ms, and the first frame is 1 ms |

The 7 ms line is the useful one: the dialog, the backdrop, the top layer and
the sheet's own gradients cost nothing. All of it is the seventy-five rows and
the four blurred conic gradients behind the search field.

What helped:

- **`content-visibility: auto` on the rows.** Sixty-seven of the seventy-five
  are below the fold. 375 → 215 ms.
- **A screenful built at once, the rest on idle callbacks in batches.** The
  first frame went from 43 ms to 1 ms, which is the part that reads as the
  sheet taking a moment to open.
- **The search field's layers cut from 640 px to 400.** They are clipped to a
  field 340 px wide; the gradient only ever has to cover that box's diagonal
  as it turns. Worth about 60 ms.
- **The near-nothing filter off each row.** `brightness(1)`, `contrast(1.04)`,
  `blur(0.2px)` — invisible, and between them a composited layer per row.

What did not, and is written down so nobody tries them again:

- **Warming the flags into the image cache.** The cost is rasterising
  seventy-five masked layers, not fetching the files. No measurable change.
- **Opening the sheet invisibly at boot to pay the cost early.** With
  `opacity: 0` the compositor skips painting a transparent subtree, so it
  warms nothing and costs a frame saying so — it measured *worse*. Moved
  off-screen with a transform instead, `content-visibility` then skips every
  row and the compositor culls the rest, so it warms nothing again.

**What is left.** The sheet now appears in a frame. About 195 ms of settling
still happens roughly 200 ms after it is up, and it is in the rows —
`display: none` on the list removes it and nothing else tried does. The honest
next step is not to build seventy-five of them at all, which is a windowed
list and a bigger change than this was.

## The three pieces with a life of their own

Everything else in this app is Fluent: flat, quick, out of the way. Three
things are not, and they are the three the app is actually about — the control
that changes your address, the field you find a country in, and the button that
spends an afternoon measuring servers.

The references those came from arrive in violet, magenta and blue. They are
here in this app's own jade, because three borrowed palettes in one 400px
window is three products in a trenchcoat. What was borrowed is the mechanism,
not the colour.

**Connect** is the mint pill it always was until it is pressed: the word in the
middle, nothing to hover for, nothing to discover. The one control on the page
reads as the one control on the page.

Pressing it is what changes the shape. The face goes dark, the word slides
across to the left, and two lights come up on the right where the word used to
be. That is the borrowed mechanism, spent on the move into being connected
rather than on a hover state nobody asked for. It slides rather than jumps
because the word is placed at the middle and pulled back by half itself —
`text-align` and `justify-content` both change in a single frame, and a word
that teleports is not a word that moved.

Working gets the same two lights in amber, and they breathe rather than sit
still, so "Cancel" never wears the look that means you have arrived.

The sizes are not the reference's, and getting that wrong twice is what taught
it. That button is 64px tall and 256px wide with 48 and 80px circles; this one
is 40px tall. At the reference's sizes the two lights merged into one green
slab across the right-hand third; shrunk but still taller than the button, the
overflow that keeps them inside clipped the big one top and bottom and it read
as a green rectangle at the edge. Both now fit inside the forty pixels with
room for their own blur.

**Search** is four blurred conic gradients stacked behind one dark field, each
a different radius, blur and starting angle. Hovering turns them; focusing
turns them further and over four seconds rather than two — hovering is a
glance, typing is a stay, and a sweep that finishes before you have looked is
a sweep nobody saw. The `/` hint sits in a frame that rakes on its own: the one
moving thing on the page that is not a reaction to something, and it is there
to say the field is live before anybody touches it.

**Start** keeps the reference's three states in the reference's own order. At
rest it is quiet — a dark bevelled pill whose letters take it in turns to
brighten, which is the only thing on it that says it is a button rather than a
caption. Pressed, and for as long as the sweep runs, the underlight comes up
and the letters go faster.

That light is the reference's blue rather than this app's jade, on purpose:
everything jade in this window means connected, and a two-hour measurement of
servers is not that. Different job, different colour.

Reached for while it runs it says **Stop**, because a button that only ever
says what it is doing never says what pressing it would do.

Beside it, the cap on how many servers to test. The browser's own number
spinner is two grey nubs drawn to its own taste, and at this size they were
half the field — turned off, and two chevrons put in their place. Stepping up
from an empty field starts at the whole selection rather than at one: nudging
a cap you have not set should offer the number you already have, not restart
the count.

Icons are Lucide, ISC, vendored as one inline sprite. Over `file://` an
external `<use href="other.svg#id">` is blocked outright, so the symbols are in
the page. Stroke width, colour and size are all CSS's.

## Design

Type does the work. **Estedad** (OFL-1.1, variable 100–900, one 125 KB file)
was chosen for the Persian because its own brief is low contrast, small
optical size, screen — which is what survives at 12px in a small window.
**IBM Plex Mono** carries addresses and ports, and the change of voice is the
point: it separates the app talking from the network reporting. Nothing here
is Vazir, Vazirmatn, IRANSans or Sahel, including the fallback.

Two things this forces in code rather than in a document. Estedad ships a
Latin→Farsi digits feature that is switched **off** globally — if it fired on
an address, `192.168.1.1` would render as `۱۹۲.۱۶۸.۱.۱`, unverifiable and
unpasteable. Persian numerals are produced in JavaScript where they belong, on
counts. And every machine string is `unicode-bidi: isolate`, because an IP at
the end of a Persian sentence gets reordered otherwise — worst exactly when
someone is checking whether to trust it.

One accent, appearing once, only when the line is live. Three movements, each
because a value really changed: the place name stretches and settles, the wire
draws itself, and the action control traces its own perimeter while trying.
All of it is switched off under `prefers-reduced-motion`, and every state is
readable without any of it.

Fonts are bundled under the OFL, licences beside them in `ui/fonts/`.

## What it needs beside the exe

```
Ettesal.exe
_internal/            the app
servers/              pinned .ovpn files
configs/              somewhere to drop the ones you downloaded, to be pinned
.ovpn-auth            service username and password, one per line
Sweep-OvpnExits.ps1   only for "Time the servers properly"
Resolve-OvpnRemote.ps1   that, and all of "Pin them to real addresses"
```

`servers/`, `success/` and `pinned/` are all accepted, in that order, so it
runs from the repo unchanged.

The two `.ps1` files are copied in beside the exe rather than bundled:
PyInstaller would put them inside `_internal/`, and `Sweep-OvpnExits.ps1`
dot-sources its neighbour by looking beside itself and writes its results into
folders beside itself too. Without them the app connects exactly as before and
only the timing and pinning sections say they have nothing to run.

`configs/` is made empty by the build rather than explained in a sentence:
pinning reads it, and a first run whose answer is the name of a folder that
does not exist is a step nobody can follow.

## Checking a build

```
Ettesal.exe --selftest      what it can see, and where it looked
Ettesal.exe --probe-test    why each of eight exits refused, one line each
python app/main.py --ui-check
python app/sweep-test.py    the sweep's wording, against the reader of it
python app/pin-test.py      the resolver's wording, against the reader of it
python ovpn-proxy-test.py   the four shapes of client that reach the proxy
```

The last one is for the failure this app has actually had twice: a window that
loads, looks finished, and answers nothing, because a script error early on
took the page down before any of it was wired up. It runs the real window,
reads back the errors the page collected before anything else could throw,
opens each sheet, and leaves a picture of each in `.state/ui-check/`.

The pictures are taken with `PrintWindow`, which asks the window to draw
itself, rather than by grabbing the screen rectangle it occupies. A screen grab
captures whatever is on those pixels, and this runs in the background while
the person's own windows still have focus — the first version of it wrote
somebody's chat window to disk under our name.

## Known limits

- **Unsigned.** SmartScreen will warn on a downloaded copy until it earns
  reputation; the user has to click *More info → Run anyway* once.
- **It does not restart itself.** If the exit stops accepting the credentials
  the proxy stays up and requests fail. Press disconnect and connect again.
- **Killed outright**, it cannot restore the machine in the moment — the saved
  file is what fixes that on next launch.
- Programs that ignore the system proxy (some game clients, anything using its
  own DNS or raw sockets) are not covered. UDP and ICMP never are.
