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
2. Starts `ovpn-proxy.py` as its own process on `127.0.0.1:8899`.
3. Points Windows at it.
4. Asks Cloudflare, *through the proxy*, what IP it now sees, and shows that.

Measured on the machine this was written on: about three seconds from press to
connected.

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
_internal/         the app
servers/           pinned .ovpn files
.ovpn-auth         service username and password, one per line
```

`servers/`, `success/` and `pinned/` are all accepted, in that order, so it
runs from the repo unchanged.

## Known limits

- **Unsigned.** SmartScreen will warn on a downloaded copy until it earns
  reputation; the user has to click *More info → Run anyway* once.
- **It does not restart itself.** If the exit stops accepting the credentials
  the proxy stays up and requests fail. Press disconnect and connect again.
- **Killed outright**, it cannot restore the machine in the moment — the saved
  file is what fixes that on next launch.
- Programs that ignore the system proxy (some game clients, anything using its
  own DNS or raw sockets) are not covered. UDP and ICMP never are.
