"""Measuring an exit the only way that is true: by connecting to it.

There are two ways to ask an exit how quick it is, and they do not agree.

The fast one is what the connect button already does - open a TLS session to
the exit's HTTPS proxy, offer the credentials, and time the answer. Eight at
once, a whole folder in under a minute. What it measures is the proxy's front
door: whether that address is answering right now, and how far away it is.
That is the right question to ask when you are choosing which exit to open in
the next second, and it is the wrong one to write on a file, because a server
whose proxy answers in 300ms can still take fourteen seconds to raise a
tunnel, or fail to raise one at all.

The slow one is this. openvpn.exe connects to the config for real, the
handshake is timed to the moment traffic can actually leave, Cloudflare is
asked what it makes of the exit, and then it is dropped and the next one
starts. One at a time, because two tunnels do not stack - they fight - so the
whole thing costs about fifteen seconds per server and takes your connection
down and up again for each one. That number is the one worth keeping, and it
is the number already written on the front of every filename in the servers
folder.

Nothing here reimplements any of that. Sweep-OvpnExits.ps1 is the thing that
was measured and argued over, and a second copy of it in Python would drift
from it within a week. This starts it, reads what it says, and turns that
into something a window can show.

Three things are not obvious and are the reason this file is longer than a
subprocess call:

  It has to run elevated, and elevation cannot be redirected. Opening the
  tunnel adapter and writing routes both need administrator, and Windows will
  not let you both raise a process and capture its output - `-Verb RunAs`
  means ShellExecute, and ShellExecute has nowhere to put a pipe. So a small
  wrapper is raised instead, and it starts the sweep as an ordinary child of
  itself with its output redirected to a file this side can read.

  It has to be stoppable, and an unelevated process cannot kill an elevated
  one. The wrapper watches for a file appearing and does the killing itself.

  Being killed halfway leaves a tunnel up. openvpn is mid-connection when the
  sweep dies, so the wrapper takes down the openvpn processes the sweep
  started - identified by the log folder on their command line, so nothing
  else anyone has running is touched.
"""

import json
import math
import os
import re
import subprocess
import threading
import time

import paths

SCRIPT = 'Sweep-OvpnExits.ps1'
LIBRARY = 'Resolve-OvpnRemote.ps1'

# What one server costs, taken from the sweep's own reckoning rather than
# guessed at here, so the estimate this shows and the estimate the script
# prints cannot drift apart:
#
#     $mins = [math]::Ceiling($configs.Count * 0.25)
MINUTES_EACH = 0.25

# And what each named site adds to that. The sweep's own probe budget for one
# site is 15 seconds, but that is the ceiling a site pays only when it never
# answers; a served page is a second or two. Five is between the two and errs
# towards the estimate running long rather than short.
SECONDS_PER_SITE = 5

# The folder openvpn's own logs go in, which is also how the wrapper tells the
# sweep's openvpn processes from anybody else's. Set by the script; repeated
# here because the wrapper has to match it exactly.
OPENVPN_LOG_MARK = 'ovpn-sweep'

CREATE_NO_WINDOW = 0x08000000

# The sweep's own lines, as they appear once PowerShell has written them to a
# file. The [ ok ] / [fail] / [warn] prefixes are the repo's, and the colour
# they are printed in does not survive redirection, which is convenient.
LINE_CONFIG = re.compile(
    r'^\s*\[(\d+)/(\d+)\]\s+(\S+)\s+(\d{1,3}(?:\.\d{1,3}){3}):(\d+)')
LINE_UP = re.compile(r'^\s*\[ ok \]\s+up in ([\d.]+)s\s+-\s+kept as (.+?)\s*$')
LINE_NOCONNECT = re.compile(
    r'^\s*\[fail\]\s+did not come up - (.+?)(?:\s*\[[\d.]+s\])?\s*$')
LINE_DEAD = re.compile(r'^\s*\[fail\]\s+the address does not answer')
LINE_VERDICT = re.compile(
    r'^\s*\[(?: ok |warn|fail)\]\s+(clean|partly|flagged)\s+exit\s+(\S+)')
LINE_NOROUTE = re.compile(r'^\s*\[warn\].*not judged\s*$')
LINE_SERVES = re.compile(r'^\s*serves\s+(\S+)\s+-\s+kept in\s')
LINE_STOPPED = re.compile(r'^\s*no longer serves\s+(\S+)\s+-')


#------------------------------------------------------------- what is around

def scripts_dir():
    """Where the PowerShell half of the repo lives.

    Beside the exe once frozen, and windows/ when run from source - build.py
    copies both scripts out of there and in, because a sweep is the one thing
    here that cannot be done without them. paths.py knows the difference.
    """
    return paths.SCRIPTS_DIR


def script_path():
    return os.path.join(scripts_dir(), SCRIPT)


def library_path():
    return os.path.join(scripts_dir(), LIBRARY)


def find_openvpn():
    """The community client - openvpn.exe, the command line one. Looked for
    where the sweep script looks, in the same order, so the app cannot say it
    is missing while the script finds it or the other way round."""
    candidates = [
        os.path.join(os.environ.get('ProgramFiles', r'C:\Program Files'),
                     'OpenVPN', 'bin', 'openvpn.exe'),
        os.path.join(os.environ.get('ProgramFiles(x86)', ''),
                     'OpenVPN', 'bin', 'openvpn.exe'),
        os.path.join(os.environ.get('ProgramW6432', ''),
                     'OpenVPN', 'bin', 'openvpn.exe'),
    ]
    for path in candidates:
        if path and os.path.isfile(path):
            return path
    for folder in os.environ.get('PATH', '').split(os.pathsep):
        path = os.path.join(folder.strip('"'), 'openvpn.exe')
        if os.path.isfile(path):
            return path
    return None


def configs_in(folder):
    try:
        return sorted(f for f in os.listdir(folder) if f.endswith('.ovpn'))
    except OSError:
        return []


#------------------------------------------------------------- named sites

# Cloudflare's rules are per customer, so "clean" is a statement about
# Cloudflare and not about the web. An exit that serves cloudflare.com happily
# can still hand you a challenge page on the one site you opened the app for.
# Naming those sites is how the sweep is asked the question you actually have.

SITE_SPLIT = re.compile(r'[,\s]+')

# Stricter than the sweep's own check, which is only `^[A-Za-z0-9._-]+$`.
# That accepts a bare word, and a bare word is probed as https://word/, fails,
# and is counted against the exit - so a typo in this field would quietly mark
# every server dirty. A name has to have a dot and end in letters to be a name.
SITE_OK = re.compile(r'^(?=.{1,253}$)[a-z0-9]([a-z0-9-]*[a-z0-9])?'
                     r'(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\.[a-z]{2,}$')


def split_sites(text):
    """A typed-in list turned into hostnames, and whatever would not become
    one.

    Whatever people type - a full URL, a trailing slash, spaces instead of
    commas - becomes a bare host, because that is what the sweep probes.
    Anything left over comes back separately so it can be said out loud: a
    site silently never tested is worse than one refused.
    """
    if isinstance(text, (list, tuple)):
        text = ','.join(str(t) for t in text)
    kept, dropped = [], []
    for piece in SITE_SPLIT.split((text or '').strip()):
        if not piece:
            continue
        host = re.sub(r'^[a-z]+://', '', piece, flags=re.I)
        host = host.split('/')[0].split('?')[0].split('#')[0]
        host = host.split('@')[-1].split(':')[0].strip().rstrip('.').lower()
        if not host:
            continue
        if not SITE_OK.match(host):
            if piece not in dropped:
                dropped.append(piece)
        elif host not in kept:
            kept.append(host)
    return kept, dropped


def clean_sites(text):
    return split_sites(text)[0]


def name_tag(text, cap=28):
    """The folder name the sweep gives a site. Get-NameTag, in Python: it has
    to match exactly, or the app would list folders the sweep never wrote."""
    tag = re.sub(r'[^A-Za-z0-9]+', '-', text or '').strip('-')
    if len(tag) > cap:
        tag = tag[:cap].rstrip('-')
    return tag or 'unknown'


def sitetest_dir():
    return os.path.join(paths.DATA_DIR, 'sitetest')


#--------------------------------------------------------------- who owns what

# Being blocked is mostly a property of the hosting company rather than of the
# address. Twenty-one companies stand behind five hundred addresses here, and
# a site that refuses the first M247 address usually refuses the rest - so
# "whose addresses still work" is answerable in twenty-one tests where "which
# addresses work" needs five hundred and thirty-three.
#
# The lookup itself belongs to the PowerShell half, which caches every answer
# in .state\owners.tsv: ip, asn, owner, country, city, when it was checked.
# That table is read here rather than the service asked again - it is a plain
# file, it is already paid for, and a settings sheet that opened a network
# request per address would be unusable.

PINNED_IN_NAME = re.compile(r'_(\d{1,3}(?:\.\d{1,3}){3})\.ovpn$')
REMOTE_IN_FILE = re.compile(r'^\s*remote\s+(\d{1,3}(?:\.\d{1,3}){3})\b',
                            re.MULTILINE)
TIME_PREFIX = re.compile(r'^\d{1,3}(?:\.\d+)?s-')

SCOPES = ('all', 'location', 'company-location', 'company')


def owners_path():
    return os.path.join(paths.STATE_DIR, 'owners.tsv')


def owner_cache():
    """ip -> what is known about it. Missing file means nothing is known,
    which is a fine answer and not an error."""
    out = {}
    try:
        with open(owners_path(), encoding='utf-8', errors='replace') as f:
            rows = f.read().splitlines()
    except OSError:
        return out
    for row in rows:
        if not row.strip():
            continue
        cell = row.split('\t')
        if len(cell) < 3 or not cell[0]:
            continue
        out[cell[0]] = {'asn': cell[1], 'owner': cell[2],
                        'country': cell[3] if len(cell) > 3 else '',
                        'city': cell[4] if len(cell) > 4 else ''}
    return out


def format_owner(o):
    """Format-Owner, in Python. It has to match: this string is what -Landlord
    is matched against, and what the chips are labelled with."""
    if not o or not o.get('owner') or o['owner'] == '-':
        return '?'
    return f"{o['owner']} {o['asn']}" if o.get('asn') else o['owner']


def base_name(name):
    """Get-BaseConfigName - the name without the handshake time on the front,
    so a config swept from success\\ and one swept from pinned\\ are the same
    config."""
    return TIME_PREFIX.sub('', name)


def location_key(name):
    """Get-LocationKey - de-fra.prod.surfshark.com_tcp, with the time and the
    address taken off. Several files share it, which is what makes it worth
    grouping by."""
    return re.sub(r'_[0-9.]+\.ovpn$', '', base_name(name))


def config_address(folder, name):
    """The address a config is pinned to.

    Off the filename first, because a pinned name carries it and reading five
    hundred files to learn what is already written on them is a settings sheet
    that takes a second to open. The file is only opened when the name does
    not say.
    """
    m = PINNED_IN_NAME.search(name)
    if m:
        return m.group(1)
    try:
        with open(os.path.join(folder, name), encoding='utf-8',
                  errors='replace') as f:
            m = REMOTE_IN_FILE.search(f.read(4096))
    except OSError:
        return None
    return m.group(1) if m else None


def landlords(folder):
    """Who the configs in a folder are rented from, biggest first.

    Returns the groups and how many could not be traced. The untraced number
    matters and is shown: choosing any company at all leaves those configs
    out, and a count that quietly shrinks is worse than one that explains
    itself.
    """
    cache = owner_cache()
    groups, unknown = {}, 0
    for name in configs_in(folder):
        found = cache.get(config_address(folder, name) or '')
        if not found:
            unknown += 1
            continue
        key = format_owner(found)
        if key == '?':
            unknown += 1
            continue
        group = groups.setdefault(key, {
            'name': key, 'owner': found['owner'], 'asn': found['asn'],
            'count': 0, 'countries': []})
        group['count'] += 1
        if found['country'] and found['country'] not in group['countries']:
            group['countries'].append(found['country'])
    ordered = sorted(groups.values(), key=lambda g: (-g['count'], g['name']))
    return ordered, unknown


def selection(folder, scope='all', chosen=None, first=0):
    """How many servers a given set of choices actually comes to.

    The same grouping the sweep does, done here for the count alone - which
    is the whole reason the choices are worth making. "Two hundred and
    twenty-three minutes" and "eleven minutes" are different decisions, and
    the difference has to be visible before the UAC prompt rather than after
    it.
    """
    if scope not in SCOPES:
        scope = 'all'
    cache = owner_cache()
    wanted = set(chosen or [])
    keys = set()
    for name in configs_in(folder):
        owner = format_owner(cache.get(config_address(folder, name) or ''))
        if wanted and owner not in wanted:
            continue
        if scope in ('company', 'company-location') and owner == '?':
            # The sweep drops these outright in the company modes: there is no
            # company to be one-per.
            continue
        if scope == 'location':
            keys.add(location_key(name))
        elif scope == 'company':
            keys.add(owner)
        elif scope == 'company-location':
            keys.add(f'{owner}|{location_key(name)}')
        else:
            keys.add(name)
    count = len(keys)
    return min(count, first) if first else count


def site_folders():
    """What the sweep has found so far, per site.

    A folder here is a list of the servers that really served that one site -
    not "was clean overall", which is a different and weaker claim. Reading it
    back is the whole payoff, so it is shown whether or not a sweep is running.
    """
    root = sitetest_dir()
    out = []
    try:
        names = sorted(os.listdir(root))
    except OSError:
        return out
    for name in names:
        path = os.path.join(root, name)
        if not os.path.isdir(path):
            continue
        count = len(configs_in(path))
        if count:
            out.append({'tag': name, 'folder': path, 'count': count})
    return out


def estimate_minutes(count, sites=0):
    """The script's own arithmetic, plus what naming sites adds to it.

    Rounded up, and never zero - a sweep of one server is not an instant
    thing. Each named site is one more HTTPS request on an exit that has just
    come up, which is seconds rather than the tens of seconds a handshake
    costs; SECONDS_PER_SITE is deliberately generous, because an estimate that
    runs over is worse than one that comes in early.
    """
    each = MINUTES_EACH + sites * (SECONDS_PER_SITE / 60)
    return max(1, math.ceil(count * each))


def other_vpn():
    """Whether traffic already leaves over somebody else's tunnel.

    Asked before starting because a VPN that is already up breaks a sweep in
    three separate ways and all three look like every server being dead. The
    judgement is the repo's own - Get-DefaultHop, dot-sourced rather than
    guessed at again here - so the app and the script cannot disagree about
    what counts as a tunnel.
    """
    lib = library_path()
    if not os.path.isfile(lib):
        return None
    command = (f". '{lib}' -AsLibrary; "
               "$h = Get-DefaultHop; "
               "if ($h -and $h.IsTunnel) { "
               "  @{alias=$h.Alias; description=$h.Description} "
               "  | ConvertTo-Json -Compress }")
    try:
        done = subprocess.run(
            ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
             '-Command', command],
            capture_output=True, text=True, timeout=30,
            creationflags=CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except (OSError, subprocess.SubprocessError):
        return None
    out = (done.stdout or '').strip()
    if not out.startswith('{'):
        return None
    try:
        return json.loads(out)
    except ValueError:
        return None


def look_up_owners(folder, timeout=600):
    """Fill the cache for the addresses in a folder that are not in it yet.

    Needs the network and no administrator rights, so it is a plain call
    rather than anything elevated. The lookup, the batching and the five
    second wait between requests are the pinner's - dot-sourced, not written
    again here, so the app and the scripts cannot end up with two ideas of
    who owns an address.

    Returns how many addresses are still unknown afterwards. Some never
    resolve; the cache records that too, so they are not asked about again
    for a week.
    """
    lib = library_path()
    if not os.path.isfile(lib):
        return {'ok': False, 'error': f'{LIBRARY} is not beside the app.'}

    cache = owner_cache()
    missing = sorted({ip for ip in
                      (config_address(folder, n) for n in configs_in(folder))
                      if ip and ip not in cache})
    if not missing:
        return {'ok': True, 'asked': 0, 'unknown': landlords(folder)[1]}

    command = (f". '{lib}' -AsLibrary; "
               "$ips = @(" + ','.join(_ps_string(ip) for ip in missing) + "); "
               "Get-IpOwner $ips $null | Out-Null; "
               "Save-OwnerCache")
    try:
        done = subprocess.run(
            ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
             '-Command', command],
            capture_output=True, text=True, timeout=timeout,
            creationflags=CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except subprocess.TimeoutExpired:
        return {'ok': False, 'error': 'The lookup took too long and was '
                                      'stopped. What it did learn was kept.'}
    except (OSError, subprocess.SubprocessError) as e:
        return {'ok': False, 'error': f'Could not run the lookup: {e}'}
    if done.returncode != 0:
        said = (done.stderr or done.stdout or '').strip().splitlines()
        return {'ok': False,
                'error': said[-1][:200] if said else 'The lookup failed.'}
    return {'ok': True, 'asked': len(missing), 'unknown': landlords(folder)[1]}


#--------------------------------------------------------------- the wrapper

# Raised with administrator rights, and the only thing in here that has them.
# It starts the sweep as an ordinary child so its output can be redirected,
# watches for the stop file, and leaves a done file behind saying how it went
# - which is how this side tells "finished" from "the window closed".
WRAPPER = r'''
$ErrorActionPreference = 'Continue'
$log     = {log}
$errlog  = {errlog}
$done    = {done}
$stop    = {stop}
$argv    = {argv}

Remove-Item -LiteralPath $done -Force -ErrorAction SilentlyContinue

$child = Start-Process powershell -ArgumentList $argv -NoNewWindow -PassThru `
             -RedirectStandardOutput $log -RedirectStandardError $errlog

$cancelled = $false
while (-not $child.HasExited) {{
    if (Test-Path -LiteralPath $stop) {{
        $cancelled = $true
        # The sweep first, so it stops starting new ones, and then whatever it
        # had open. Only openvpn processes it started: they carry the sweep's
        # own log folder on their command line, and anything else running on
        # this machine does not.
        Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue
        Get-CimInstance Win32_Process -Filter "Name='openvpn.exe'" -ErrorAction SilentlyContinue |
            Where-Object {{ $_.CommandLine -like '*{mark}*' }} |
            ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }}
        break
    }}
    Start-Sleep -Milliseconds 400
}}
$child.WaitForExit(8000) | Out-Null

$code = 0
try {{ $code = $child.ExitCode }} catch {{ $code = -1 }}
if ($cancelled) {{ $text = 'cancelled' }} else {{ $text = "$code" }}
Set-Content -LiteralPath $done -Value $text -Encoding ascii
'''


def _ps_string(value):
    """A PowerShell single-quoted literal, which escapes one character."""
    return "'" + str(value).replace("'", "''") + "'"


def _win_arg(value):
    """One argument, carrying its own quotes.

    Start-Process -ArgumentList joins an array with spaces and quotes nothing
    itself, so anything with a space in it arrives at the script as two
    arguments unless the quotes are put in here. A servers folder under
    "Program Files" would have been one; a company called "M247 AS9009"
    certainly is.
    """
    text = str(value)
    if not text:
        return '""'
    if not re.search(r'[\s"]', text):
        return text
    # The command line's own rule: backslashes are literal except immediately
    # before a quote, where they have to be doubled first.
    escaped = re.sub(r'(\\*)"', lambda m: m.group(1) * 2 + '\\"', text)
    escaped = re.sub(r'(\\+)$', lambda m: m.group(1) * 2, escaped)
    return f'"{escaped}"'


def _ps_array(values):
    return '@(' + ','.join(_ps_string(_win_arg(v)) for v in values) + ')'


#----------------------------------------------------------------- the sweep

class Sweep:
    """One sweep at a time, and always able to say what it is doing."""

    def __init__(self):
        self.lock = threading.Lock()
        self.thread = None
        self.state = 'idle'          # idle | running | done
        self.progress = {}
        self.results = []
        self.error = None
        self.into = None
        self.sites = []

    #-- before it starts --------------------------------------------------

    def blockers(self, engine, folder=None, ask_windows=True):
        """Everything that would make a sweep fail or lie, asked before the
        UAC prompt rather than after it. A person who has just approved
        administrator rights for something that then says "openvpn is not
        installed" has been made to pay for nothing.

        ask_windows covers the one check that is not a file test: whether
        another VPN already holds the default route, which costs a PowerShell
        process. Worth a second when the sheet opens or when Start is pressed;
        not worth it on every click of a chip, which is what it was doing -
        every choice took a second and a half to show its new number.
        """
        folder = folder or engine.folder
        out = []
        if os.name != 'nt':
            out.append({'kind': 'windows',
                        'say': 'This test only runs on Windows.'})
            return out
        if not os.path.isfile(script_path()):
            out.append({'kind': 'no-script',
                        'say': f'{SCRIPT} is not beside the app, so there is '
                               f'nothing to run the test with.'})
        elif not os.path.isfile(library_path()):
            out.append({'kind': 'no-library',
                        'say': f'{LIBRARY} is missing. {SCRIPT} reads it and '
                               f'cannot run without it.'})
        if not find_openvpn():
            out.append({'kind': 'no-openvpn',
                        'say': 'The OpenVPN community client is not installed. '
                               'Each server has to be connected for real, and '
                               'that is what does it:  '
                               'winget install --id OpenVPNTechnologies.OpenVPN'})
        if not configs_in(folder):
            out.append({'kind': 'no-configs',
                        'say': 'No .ovpn files in that folder, so there is '
                               'nothing to test.'})
        if engine.running():
            out.append({'kind': 'connected',
                        'say': 'Disconnect first. The test connects each server '
                               'in turn, and it cannot do that around a '
                               'connection this app is already holding.'})
        elif ask_windows:
            hop = other_vpn()
            if hop:
                out.append({'kind': 'other-vpn',
                            'say': f'Traffic already leaves over '
                                   f'{hop.get("alias") or "another VPN"}. Turn it '
                                   f'off first - two tunnels do not stack, they '
                                   f'fight, and every server would be written '
                                   f'off as dead.'})
        return out

    def plan(self, engine, folder=None, sites=None, scope='all', chosen=None,
             first=0, ask_windows=True):
        folder = folder or engine.folder
        named = clean_sites(sites)
        companies, untraced = landlords(folder)
        # Only the ones still in this folder: a company chosen last week whose
        # addresses are all gone would otherwise sit selected and silently
        # narrow the sweep to nothing.
        known = {c['name'] for c in companies}
        chosen = [c for c in (chosen or []) if c in known]
        count = selection(folder, scope, chosen, first)
        return {'folder': folder,
                'into': engine.folder,
                'total': len(configs_in(folder)),
                'count': count,
                # Each named site is another request per exit, on a connection
                # that has just come up. Measured against the sweep's own
                # quarter-minute, that is roughly a fifth of one each.
                'minutes': estimate_minutes(count, len(named)),
                'sites': named,
                'siteTestDir': sitetest_dir(),
                'siteFolders': site_folders(),
                'scope': scope if scope in SCOPES else 'all',
                # What each of the other choices would come to, so the cost of
                # changing your mind is on screen next to the choice rather
                # than one click away behind it.
                'scopeCounts': {s: selection(folder, s, chosen, first)
                                for s in SCOPES},
                'companies': companies,
                'chosen': chosen,
                'untraced': untraced,
                'first': first,
                'openvpn': find_openvpn(),
                'blockers': self.blockers(engine, folder, ask_windows),
                'state': self.state}

    #-- running it --------------------------------------------------------

    def start(self, engine, folder, emit, sites=None, scope='all',
              chosen=None, first=0):
        with self.lock:
            if self.state == 'running':
                return {'ok': False, 'error': 'A test is already running.'}
            blockers = self.blockers(engine, folder)
            if blockers:
                return {'ok': False, 'error': blockers[0]['say'],
                        'blockers': blockers}

            named = clean_sites(sites)
            chosen = list(chosen or [])
            total = selection(folder, scope, chosen, first)
            if not total:
                return {'ok': False,
                        'error': 'Those choices leave no servers to test.'}

            self.state = 'running'
            self.progress = {'done': 0, 'total': total, 'name': ''}
            self.results = []
            self.error = None
            self.into = engine.folder
            self.sites = named
            self.thread = threading.Thread(
                target=self._run,
                args=(folder, engine.folder, total, named, scope, chosen,
                      first, emit),
                daemon=True)
            self.thread.start()
        return {'ok': True, 'count': total, 'sites': named,
                'minutes': estimate_minutes(total, len(named))}

    def cancel(self):
        if self.state != 'running':
            return {'ok': False}
        try:
            with open(self._stop_path(), 'w', encoding='ascii') as f:
                f.write('stop')
        except OSError as e:
            return {'ok': False, 'error': str(e)}
        return {'ok': True}

    #-- the paths it works through ---------------------------------------

    def _log_path(self):
        return os.path.join(paths.STATE_DIR, 'sweep.log')

    def _err_path(self):
        return os.path.join(paths.STATE_DIR, 'sweep.err')

    def _done_path(self):
        return os.path.join(paths.STATE_DIR, 'sweep.done')

    def _stop_path(self):
        return os.path.join(paths.STATE_DIR, 'sweep.stop')

    def _wrapper_path(self):
        return os.path.join(paths.STATE_DIR, 'sweep-run.ps1')

    #-- the work ----------------------------------------------------------

    def _run(self, folder, into, total, sites, scope, chosen, first, emit):
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        for path in (self._log_path(), self._err_path(), self._done_path(),
                     self._stop_path()):
            try:
                os.remove(path)
            except OSError:
                pass

        argv = ['-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', script_path(),
                '-PinnedDir', folder,
                '-SuccessDir', into,
                # Said rather than left to the script, which guesses .state is
                # two folders up from itself. That is right in the repo and
                # wrong in a bundle, where both scripts sit beside the exe -
                # and the way it goes wrong is silent. The owners cache lives
                # in here, the landlord chips are read out of it, and
                # -Landlord is matched against what the sweep reads out of
                # its own copy: two caches, filled by whichever lookup
                # service answered, spelling the same company two ways, and
                # every name the window offered matches nothing.
                '-StateDir', paths.STATE_DIR,
                # The sweep asks two questions at a terminal that a window has
                # to answer for it: whether an afternoon is an acceptable price
                # and whether the VPN it can see is really one. Both were put
                # to the person in the sheet before this ran, so -Force is the
                # answer to a question already asked rather than one skipped.
                '-Force']

        # Which addresses, out of everything in that folder. -PickLandlord is
        # deliberately not used: it is a numbered menu and a Read-Host, which
        # is right at a terminal and a hang behind a window. The choosing
        # happens in the sheet, and only the answer is handed over.
        if chosen:
            argv += ['-Landlord', ','.join(chosen)]
        scope_flag = {'location': '-OnePer',
                      'company': '-OnePerLandlord',
                      'company-location': '-OnePerLandlordLocation'}.get(scope)
        if scope_flag:
            argv += [scope_flag]
        if first:
            argv += ['-First', str(int(first))]

        if sites:
            # One string, comma separated: PowerShell would take a second
            # bare element as the next positional parameter, and the script
            # splits this itself either way.
            argv += ['-Site', ','.join(sites),
                     '-SiteTestDir', sitetest_dir()]
        openvpn = find_openvpn()
        if openvpn:
            argv += ['-OpenVpn', openvpn]
        auth = paths.AUTH_FILE
        if os.path.isfile(auth):
            argv += ['-AuthFile', auth]

        wrapper = WRAPPER.format(
            log=_ps_string(self._log_path()),
            errlog=_ps_string(self._err_path()),
            done=_ps_string(self._done_path()),
            stop=_ps_string(self._stop_path()),
            argv=_ps_array(argv),
            mark=OPENVPN_LOG_MARK)
        with open(self._wrapper_path(), 'w', encoding='utf-8') as f:
            f.write(wrapper)

        emit({'phase': 'elevating', 'total': total})

        raise_it = ("Start-Process powershell -Verb RunAs -WindowStyle Hidden "
                    "-ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass',"
                    "'-File'," + _ps_string(self._wrapper_path()) + ")")
        try:
            done = subprocess.run(
                ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                 '-Command', raise_it],
                capture_output=True, text=True, timeout=180,
                creationflags=CREATE_NO_WINDOW if os.name == 'nt' else 0)
        except (OSError, subprocess.SubprocessError) as e:
            self._finish(emit, error=f'could not start the test: {e}')
            return
        if done.returncode != 0:
            # The one that actually happens: the UAC prompt was dismissed.
            self._finish(emit, error='Administrator rights were refused, so '
                                     'nothing was tested.')
            return

        self._read_along(total, emit)

    def _read_along(self, total, emit):
        """Follow the log while it is written, and say what it says.

        Polled rather than piped, because the thing writing it is on the other
        side of an elevation boundary and there is no pipe to be had.
        """
        offset, tail = 0, ''
        started = time.monotonic()
        while True:
            try:
                with open(self._log_path(), 'rb') as f:
                    f.seek(offset)
                    chunk = f.read()
                    offset = f.tell()
            except OSError:
                chunk = b''
            if chunk:
                tail += chunk.decode('utf-8', 'replace')
                lines = tail.split('\n')
                tail = lines.pop()
                for line in lines:
                    self._saw(line.rstrip('\r'), emit)

            if os.path.isfile(self._done_path()):
                break
            # Nothing has been written and nothing has finished. The sweep can
            # be quiet for a whole timeout at a time, so this is only a
            # giving-up point if it never said anything at all.
            if offset == 0 and time.monotonic() - started > 240:
                self._finish(emit, error='The test did not start. '
                                         f'Its output would be in '
                                         f'{self._log_path()}')
                return
            time.sleep(0.4)

        try:
            with open(self._done_path(), encoding='ascii') as f:
                said = f.read().strip()
        except OSError:
            said = ''
        if said == 'cancelled':
            self._finish(emit, cancelled=True)
        elif said not in ('0', ''):
            self._finish(emit, error=self._why_it_failed(said))
        else:
            self._finish(emit)

    def _why_it_failed(self, code):
        """The sweep's own last words, which are more use than its exit code."""
        for path in (self._err_path(), self._log_path()):
            try:
                with open(path, encoding='utf-8', errors='replace') as f:
                    said = [l.strip() for l in f.read().splitlines() if l.strip()]
            except OSError:
                continue
            for line in reversed(said):
                if '[fail]' in line or '[warn]' in line:
                    return line.split(']', 1)[-1].strip()
            if said:
                return said[-1]
        return f'The test stopped with code {code}.'

    def _saw(self, line, emit):
        m = LINE_CONFIG.search(line)
        if m:
            self.progress = {'done': int(m.group(1)), 'total': int(m.group(2)),
                             'name': m.group(3), 'ip': m.group(4)}
            emit({'phase': 'testing', **self.progress})
            return

        name = self.progress.get('name', '')
        m = LINE_UP.search(line)
        if m:
            self._result(emit, name, 'up', seconds=float(m.group(1)),
                         kept=m.group(2))
            return
        m = LINE_VERDICT.search(line)
        if m:
            # A verdict lands after the handshake time for the same server,
            # so it sharpens the row rather than adding one.
            if self.results and self.results[-1]['name'] == name:
                self.results[-1]['verdict'] = m.group(1)
                self.results[-1]['exit'] = m.group(2)
                emit({'phase': 'result', **self.results[-1]})
            return
        # Per site, and attached to the server it was measured on. The verdict
        # line above says how many were served and which were not; only these
        # can answer "did this one server get me into that one site", which is
        # the question a folder named after a site has to answer.
        m = LINE_SERVES.search(line)
        if m:
            self._site(emit, name, m.group(1), True)
            return
        m = LINE_STOPPED.search(line)
        if m:
            self._site(emit, name, m.group(1), False)
            return

        m = LINE_NOCONNECT.search(line)
        if m:
            self._result(emit, name, 'noconnect', detail=m.group(1))
            return
        if LINE_DEAD.search(line):
            self._result(emit, name, 'unreachable',
                         detail='the address does not answer')
            return
        if LINE_NOROUTE.search(line):
            self._result(emit, name, 'noroute',
                         detail='the tunnel came up but traffic did not go in')

    def _result(self, emit, name, outcome, seconds=None, kept=None,
                detail=None):
        row = {'name': name, 'outcome': outcome, 'seconds': seconds,
               'kept': kept, 'detail': detail, 'verdict': None, 'exit': None,
               'sites': []}
        self.results.append(row)
        emit({'phase': 'result', **row})

    def _site(self, emit, name, host, served):
        if not (self.results and self.results[-1]['name'] == name):
            return
        row = self.results[-1]
        row['sites'] = [s for s in row['sites'] if s['host'] != host]
        row['sites'].append({'host': host, 'served': served})
        emit({'phase': 'result', **row})

    def _finish(self, emit, error=None, cancelled=False):
        self.state = 'done'
        self.error = error
        worked = [r for r in self.results if r['outcome'] == 'up']
        emit({'phase': 'finished',
              'tested': len(self.results),
              'worked': len(worked),
              'quickest': min((r['seconds'] for r in worked
                               if r['seconds'] is not None), default=None),
              # Re-read from disk rather than counted from what went past:
              # these folders carry what earlier sweeps found too, and a
              # cancelled run leaves the older entries standing.
              'siteFolders': site_folders(),
              # Where the ones that worked ended up. Obvious from inside this
              # file and not from the window, which until now said seven came
              # up and left you to guess where seven files had gone.
              'into': self.into,
              'siteTestDir': sitetest_dir() if self.sites else None,
              'cancelled': cancelled,
              'error': error,
              'log': self._log_path()})
