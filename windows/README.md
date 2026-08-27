# ovpn-pin on Windows

The Windows half of [ovpn-pin](../README.md): PowerShell 5.1, which is already
on the machine, so there is nothing to install for the pinning.

Double-click `run.cmd` in this folder for the menu. What pinning is and why,
re-syncing when the addresses move, checking the exit is clean and the full
flag tables are in the [root README](../README.md) — they read the same
on both platforms, so they are kept in one place rather than written twice
and left to drift.

## Keeping those two files to yourself

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

## The proxy, called directly

`ovpn` is a bash script, so there is no `ovpn proxy` here. Call the script
directly — it takes the same verbs:

```
python core/ovpn-proxy.py connect uk-man --dir success --port 8899 --detach
python core/ovpn-proxy.py status
python core/ovpn-proxy.py stop
```

It prints its own name back the way you would type it, so the hints in its
output are runnable as they stand rather than naming a command this machine
does not have.

**Which folder it acts on** is the thing to know here. Standing in a folder
does nothing — that convenience lives in the `ovpn` wrapper, which does not
exist on Windows. Say it outright, either per command or once:

```
python core/ovpn-proxy.py connect uk-gla --dir sitetest\www-scamspotter-org

$env:OVPN_OUT_DIR = "C:\Users\you\ovpn-pin\sitetest\www-scamspotter-org"
python core/ovpn-proxy.py connect uk-gla
```

`OVPN_OUT_DIR` is the same variable `ovpn-connect.sh` and
`resolve-ovpn-remote.sh` read, so all three now agree on how they are told.
Note that `sitetest/` itself holds a folder per host rather than configs — it
is `sitetest/<host>/` you want, not `sitetest/`.

There is no `px` either — that is a bash function. The same job in PowerShell:

```
python core/ovpn-proxy.py env --port 8899 | Out-String | Invoke-Expression
python core/ovpn-proxy.py env --off       | Out-String | Invoke-Expression
```

`env` prints `$env:` lines here and `export` lines on Linux, guessed from the
platform. In Git Bash on Windows that guess is wrong, so pass `--sh`.

Measured end to end in PowerShell: `--detach` returns immediately, and `curl`
with no flags at all came out of Oslo. It is the one thing in this repo that needs Python 3 — everything
else runs without it — because it has to hold a socket open both ways for a
browser and check a certificate along the way, which neither `curl` nor
PowerShell's stack will do on their own terms.

## The same sweep, in PowerShell

```powershell
.\windows\Sweep-OvpnExits.ps1 -OnePerLandlord            # ~21 tests, start here
.\windows\Sweep-OvpnExits.ps1 -OnePer
.\windows\Sweep-OvpnExits.ps1 -Name de- -Site chatgpt.com,github.com
.\windows\Sweep-OvpnExits.ps1 -OnePerLandlord -PickLandlord
.\windows\Sweep-OvpnExits.ps1 -OnePer -Landlord M247,CDN77 -Pick
```

Or option 4 in `windows\run.cmd`. Same measurement, same three verdicts, and the
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
.\windows\Sweep-OvpnExits.ps1 -PinnedDir success
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
.\windows\Sweep-OvpnExits.ps1 -Wsl
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

