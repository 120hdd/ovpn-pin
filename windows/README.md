# ovpn-pin on Windows

The Windows half. Pinning needs nothing installed — PowerShell 5.1 is already
on the machine. What pinning *is* and why is in the
[root README](../README.md); this is how to drive it here.

Double-click `run.cmd` in this folder for a menu, or call the scripts
directly. The menu asks the few questions each job needs, prints the command
your answers came to, and then runs it — so it teaches the flags rather than
hiding them.

---

## Protecting the two files that hold your password

`.env` and `.ovpn-auth` hold your provider password in clear text. On Linux
the scripts warn if either is readable by anyone else and `chmod 600` fixes
it. **On Windows `chmod` does nothing.** NTFS has no mode bits, and git-bash
returns success without changing a thing:

```
$ ls -l .env
644  .env
$ chmod 600 .env      # exits 0
$ ls -l .env
644  .env             # unchanged
```

What decides it here is the ACL — and a folder that grants read to a group
passes that down to every file inside it. So a repo you cloned into a shared
or sandboxed folder hands your password to whoever that group is, silently and
with no warning from any of the usual tools.

The sweeper checks, and prints the exact fix rather than the general advice:

```
  [warn] C:\...\ovpn-pin\.env can be read by SOMEGROUP
         That file holds your VPN password. chmod does nothing on NTFS -
         it is an ACL, so it takes icacls:
              icacls "C:\...\ovpn-pin\.env" /inheritance:d
              icacls "C:\...\ovpn-pin\.env" /remove:g "SOMEGROUP"
```

---

## Pinning

```powershell
.\windows\Resolve-OvpnRemote.ps1
```

If PowerShell refuses to run it, `run.cmd` already starts it with the policy
bypassed **for that one run only** — nothing on the machine is changed:

```powershell
powershell -ExecutionPolicy Bypass -File .\windows\Resolve-OvpnRemote.ps1
```

`run.cmd` also passes arguments straight through, so
`windows\run.cmd -CheckCloudflare -Site chatgpt.com` works as a desktop
shortcut.

Your originals are never modified. The pinned copies land in `pinned/`, and
you import one of those into your OpenVPN client.

---

## The proxy

`ovpn` is a bash script, so there is no `ovpn proxy` here. Call the Python
directly — it takes the same verbs, and prints its own name back the way you
typed it, so every hint in its output is runnable as it stands:

```powershell
python core/ovpn-proxy.py connect uk-man --dir success --port 8899 --detach
python core/ovpn-proxy.py status
python core/ovpn-proxy.py stop
```

**Which folder it acts on** is the thing to know here. Standing in a folder
does nothing; that convenience lives in the `ovpn` wrapper, which does not
exist on Windows. Say it outright, either per command or once for the session:

```powershell
python core/ovpn-proxy.py connect uk-gla --dir sitetest\www-scamspotter-org

$env:OVPN_OUT_DIR = "C:\Users\you\ovpn-pin\sitetest\www-scamspotter-org"
python core/ovpn-proxy.py connect uk-gla
```

`OVPN_OUT_DIR` is the same variable the Linux scripts read, so all of them
agree on how they are told. Note that `sitetest/` holds a folder per host
rather than configs — it is `sitetest/<host>/` you want.

There is no `px` shortcut either; that is a bash function. The same job here:

```powershell
python core/ovpn-proxy.py env --port 8899 | Out-String | Invoke-Expression
python core/ovpn-proxy.py env --off       | Out-String | Invoke-Expression
```

`env` prints `$env:` lines here and `export` lines on Linux, guessed from the
platform. In Git Bash on Windows that guess is wrong, so pass `--sh`.

This is the one part of the repo that needs Python 3 — everything else runs
without it — because it has to hold a socket open both ways for a browser and
check a certificate by hand along the way, which neither `curl` nor
PowerShell's stack will do on their own terms.

### A tunnel of your own

The same script carries it, and the setup is the same three values as
everywhere else:

```powershell
python core/ovpn-proxy.py tunnel connect     # your server, then a provider exit
python core/ovpn-proxy.py tunnel cdn         # out at your server itself
python core/ovpn-proxy.py tunnel country de
python core/ovpn-proxy.py tunnel status
```

It needs `gost.exe` in `tunnel\` or on your `PATH`, and `.state\tunnel.json`
holding what the server's installer printed. If either is missing it tells you
which and what to do about it. The server side, and why any of this exists, is
in the [root README](../README.md#set-up-a-tunnel-of-your-own).

If you would rather click than type, the desktop app has all of it —
see [app/README.md](../app/README.md).

---

## Sweeping

```powershell
.\windows\Sweep-OvpnExits.ps1 -OnePerLandlord            # ~21 tests, start here
.\windows\Sweep-OvpnExits.ps1 -OnePer
.\windows\Sweep-OvpnExits.ps1 -Name de- -Site chatgpt.com,github.com
.\windows\Sweep-OvpnExits.ps1 -OnePerLandlord -PickLandlord
```

Or option 4 in `run.cmd`. Same measurement and same verdicts as the Linux
side, and the results go into the same `.state/exits.tsv` — so a Linux menu
will show what a Windows sweep found, and the other way round.

Every config that comes up is copied into `success/` with its handshake time
written on the front of the name:

```
success/
  03.9s-nl-ams.prod.surfshark.com_tcp_146.70.161.237.ovpn
  04.2s-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn
  10.0s-us-nyc.prod.surfshark.com_tcp_146.70.240.219.ovpn
  11.8s-jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn
```

Sorted by name, that is a list of what actually works with the quickest at the
top — which is the question you have the moment a tunnel drops and you want
another one running. It is a different question from *which exit is cleanest*,
and worth keeping separate.

The seconds are zero-padded so the sort does not put `10s` above `4s`.
Originals are copied and never moved, and a config swept again replaces its
own entry rather than leaving one per run. `-SuccessDir` puts them elsewhere.

### By landlord

Inside that, `success/landlord/` keeps the quickest exit from each hosting
company in each country, labelled with the company and the country the exit
actually came out in. A provider's hundred addresses in one country are often
a handful of companies wearing different numbers, so this is where real
diversity comes from — ten names for one machine is not a choice.

### A folder per site

`-Site` files each exit under every site it really served, in
`sitetest/<host>/`. Cloudflare's rules are set per customer, so *clean* is a
statement about Cloudflare rather than about the web: an exit that serves one
site happily can hand you a challenge page on the next. Asking about the sites
you actually use is the only version of the question worth answering.

### Re-testing what worked

Pointing a sweep at `success/` re-tests it and drops whatever has stopped
connecting, because a folder claiming these all work should not be quietly
wrong. It says so before it starts.

The landlord folders keep one entry per company, so their deletions are keyed
on a tag several configs share — the first M247 exit to come up replaces the
others. Those are named as replaced rather than reported as failures, which is
what used to happen and was a lie about a file that had been working minutes
earlier.

---

## From WSL

The Linux half runs there unchanged, and both halves read the same folders, so
pinning in PowerShell and connecting in WSL is a normal way to work. The one
thing to watch is that a Windows-side clone has no executable bit, so
`chmod +x linux/*.sh` once inside WSL saves repeating `bash` in front of
everything.
