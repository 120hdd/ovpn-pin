"""Pinning a folder of configs to real addresses, from the window.

A downloaded .ovpn points at a hostname, and on a censored line that hostname
is the weak link: the resolver answers with an address on your own LAN,
OpenVPN dials it, and nothing ever leaves the building. Resolving that name
over DoH and writing the answer into the config is what fixes it, and
Resolve-OvpnRemote.ps1 is the thing that does it.

That script is not reimplemented here, for the same reason the sweep is not.
It is the file that was measured and argued over - which answers count as
forged, how a config's line endings survive being rewritten, how the
PowerShell that ships with Windows reads the JSON - and a second copy of that
in Python would drift from it within a week. This starts it, reads what it
says, and turns that into something a window can show.

Why this is a quarter the length of sweep.py, which does the same job for the
other script: pinning needs no administrator rights and no openvpn.exe.
Nothing is elevated, so there is a pipe to read rather than a log file to
poll from the other side of a boundary, and stopping it is killing it rather
than asking it to notice a file appearing.

The one thing this adds to the script is the choice the window has to offer.
The script's own default is neither of the two answers - it tries direct and
goes hunting for a local proxy the moment that fails, which is the right
reflex at a terminal and is a third answer nobody chose in a window with two
buttons on it. So the window always says which, every time: -Proxy for one,
-NoProxy for the other.
"""

import math
import os
import re
import socket
import subprocess
import threading

import paths
import sweep

# The two answers, and the port the one that needs a number defaults to -
# v2rayN, Nekoray and sing-box all listen there, which is why it is first in
# the script's own list of ports to try.
ROUTES = ('proxy', 'direct')
DEFAULT_PORT = 10808

# The script's own default, and its ceiling.
DEFAULT_MAX_IPS = 4
MAX_IPS_CEILING = 32

# One DoH request per distinct hostname, and one TCP probe per file written.
# Both are a second or two on a working line; this is only ever used to say
# "about a minute", so it is deliberately coarse.
SECONDS_EACH = 1.6

# The script's own lines, as they arrive down the pipe. The [ ok ] / [warn] /
# [fail] prefixes are the repo's, and the colour they are printed in does not
# survive redirection, which is convenient.
LINE_TOTAL = re.compile(r'^\s*Configs \((\d+)\)\s*$')
LINE_NOTHING = re.compile(r'^\s*Nothing to do yet\s*$')
LINE_WROTE = re.compile(r'^\s*\[(?: ok |warn)\]\s+(\S+\.ovpn)\s{2,}'
                        r'(\d{1,3}(?:\.\d{1,3}){3}):(\d+)\s*(.*?)\s*$')
LINE_SKIP = re.compile(r'^\s*\[(?:warn|fail)\]\s+(\S+\.ovpn):\s+(.+?)\s*$')
LINE_FORGED = re.compile(r'^\s*\[warn\]\s+(\S+):\s+threw away (.+?)'
                         r' - not a public address\s*$')
LINE_NAMED = re.compile(r'using the proxy you named:\s+(\S+)')
LINE_FOUND = re.compile(r'DoH will go through\s+(\S+)')
LINE_DIRECT = re.compile(r'DoH works directly')
LINE_FAIL = re.compile(r'^\s*\[fail\]\s+(.+?)\s*$')

# de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn -> the file it was made from.
# Several addresses for one hostname means several files, and they are one
# config's worth of progress between them, not four.
FROM_NAME = re.compile(r'_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$')


def source_dir():
    """The inbox: where the files you downloaded from the provider go.

    The same folder the script uses when nobody tells it otherwise, so a run
    from the window and a run from a terminal read the same pile.
    """
    return os.path.join(paths.DATA_DIR, 'configs')


def out_dir():
    """And where the pinned copies land - also the script's own default, and
    one of the three folders the app looks in for servers to connect to."""
    return os.path.join(paths.DATA_DIR, 'pinned')


def listening(port, host='127.0.0.1', timeout=1.0):
    """Whether anything is on that port. A local listener accepts at once or
    it is not there, so this is a fast no and never a long wait."""
    try:
        with socket.create_connection((host, int(port)), timeout):
            return True
    except (OSError, ValueError, OverflowError):
        return False


def clean_port(value, fallback=DEFAULT_PORT):
    try:
        port = int(str(value).strip())
    except (TypeError, ValueError):
        return fallback
    return port if 1 <= port <= 65535 else fallback


def clean_max_ips(value, fallback=DEFAULT_MAX_IPS):
    try:
        n = int(value)
    except (TypeError, ValueError):
        return fallback
    return max(1, min(MAX_IPS_CEILING, n))


def estimate_minutes(count, max_ips):
    """Long enough to be worth saying, short enough that nobody leaves the
    room. One hostname is one lookup however many files come out of it, so
    this leans on the file count rather than multiplying by max_ips."""
    if not count:
        return 0
    return max(1, math.ceil(count * SECONDS_EACH * (1 + max_ips / 8) / 60))


def outcome_of(note):
    """What the note on the end of a written line means.

    'reachable', 'NOT reachable', '(udp - not testable)', or nothing at all
    when the reachability check was turned off.
    """
    said = (note or '').strip().lower()
    if said.startswith('not reachable'):
        return 'unreachable'
    if said.startswith('reachable'):
        return 'reachable'
    if said.startswith('(udp'):
        return 'udp'
    return 'written'


class Pin:
    """One pinning run at a time, and always able to say what it is doing."""

    def __init__(self):
        self.lock = threading.Lock()
        self.thread = None
        self.proc = None
        self.state = 'idle'          # idle | running | done
        self.results = []
        self.error = None
        self.route = None            # what the script said it was using
        self.out = None
        self.total = 0
        self.seen = set()
        self.stopping = False

    # -- before it starts --------------------------------------------------

    def blockers(self, folder, out, route, port, quick=False):
        """Everything that would make a run fail or lie, asked before the
        person presses anything.

        quick leaves out the one check that touches a socket - whether the
        proxy they named is actually there. Right when the sheet opens and
        right before Start; wrong on every keystroke in the port field.
        """
        said = []
        if os.name != 'nt':
            said.append({'kind': 'windows',
                         'say': 'Pinning runs the PowerShell resolver, so it '
                                'only works on Windows.'})
            return said
        if not os.path.isfile(sweep.library_path()):
            said.append({'kind': 'no-script',
                         'say': f'{sweep.LIBRARY} is not beside the app, so '
                                f'there is nothing to resolve with.'})
        if not sweep.configs_in(folder):
            said.append({'kind': 'no-configs',
                         'say': 'No .ovpn files in that folder. Put the ones '
                                'you downloaded from your provider in it '
                                'first.'})
        # Writing the copies back into the folder they came from means the
        # next run reads its own output and pins an address that is already
        # an address. The script has -InPlace for people who really want
        # that; this is not it.
        if folder and out and os.path.normcase(os.path.abspath(folder)) \
                == os.path.normcase(os.path.abspath(out)):
            said.append({'kind': 'same-folder',
                         'say': 'The pinned copies would land on top of the '
                                'originals, and the next run would pin its '
                                'own output. Choose a different folder for '
                                'them.'})
        if route == 'proxy' and not quick and not listening(port):
            said.append({'kind': 'no-proxy',
                         'say': f'Nothing is listening on 127.0.0.1:{port}. '
                                f'Start your proxy - v2rayN, Clash, Nekoray, '
                                f'sing-box, Hiddify - or resolve directly.'})
        return said

    def plan(self, folder=None, out=None, route='proxy', port=DEFAULT_PORT,
             max_ips=DEFAULT_MAX_IPS, quick=False):
        folder = folder or source_dir()
        out = out or out_dir()
        route = route if route in ROUTES else 'proxy'
        port = clean_port(port)
        max_ips = clean_max_ips(max_ips)
        total = len(sweep.configs_in(folder))
        return {'folder': folder,
                'out': out,
                'total': total,
                # What is already there. A folder with four hundred files in
                # it from a run last month is the difference between "this
                # wrote nothing" and "this had nothing to do".
                'existing': len(sweep.configs_in(out)),
                'route': route,
                'port': port,
                'maxIps': max_ips,
                # The ceiling, not a promise: most hostnames answer with
                # fewer addresses than the cap allows.
                'most': total * max_ips,
                'minutes': estimate_minutes(total, max_ips),
                # Whether the proxy was really asked. A quick plan skips the
                # socket, so the page must not paint a stale answer as a
                # fresh one - a port that was answering before somebody
                # changed the number is not an answer about the new number.
                'checked': not quick,
                'blockers': self.blockers(folder, out, route, port, quick),
                'state': self.state}

    # -- running it --------------------------------------------------------

    def start(self, folder, out, emit, route='proxy', port=DEFAULT_PORT,
              max_ips=DEFAULT_MAX_IPS, test=True):
        with self.lock:
            if self.state == 'running':
                return {'ok': False, 'error': 'A run is already going.'}
            folder = folder or source_dir()
            out = out or out_dir()
            route = route if route in ROUTES else 'proxy'
            port = clean_port(port)
            max_ips = clean_max_ips(max_ips)

            blockers = self.blockers(folder, out, route, port)
            if blockers:
                return {'ok': False, 'error': blockers[0]['say'],
                        'blockers': blockers}

            total = len(sweep.configs_in(folder))
            self.state = 'running'
            self.stopping = False
            self.results = []
            self.error = None
            self.route = None
            self.out = out
            self.total = total
            self.seen = set()
            self.thread = threading.Thread(
                target=self._run,
                args=(folder, out, route, port, max_ips, test, emit),
                daemon=True)
            self.thread.start()
        return {'ok': True, 'total': total, 'out': out,
                'minutes': estimate_minutes(total, max_ips)}

    def cancel(self):
        """Kill it. Nothing is elevated and nothing else was started, so this
        is the whole of stopping - the files already written stay written,
        which is the point of writing one per config rather than a batch at
        the end."""
        if self.state != 'running':
            return {'ok': False}
        self.stopping = True
        proc = self.proc
        if proc:
            try:
                proc.kill()
            except OSError:
                pass
        return {'ok': True}

    # -- the work ----------------------------------------------------------

    def _argv(self, folder, out, route, port, max_ips, test):
        argv = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', sweep.library_path(),
                '-Path', folder,
                '-OutDir', out,
                '-StateDir', paths.STATE_DIR,
                '-MaxIps', str(max_ips)]
        # Always one or the other, never the script's own default. Left to
        # itself it would try direct and then go looking for a proxy, which
        # is a third answer to a question the window asked as two.
        if route == 'proxy':
            argv += ['-Proxy', f'http://127.0.0.1:{port}']
        else:
            argv += ['-NoProxy']
        if not test:
            argv += ['-NoTest']
        return argv

    def _run(self, folder, out, route, port, max_ips, test, emit):
        try:
            os.makedirs(out, exist_ok=True)
        except OSError as e:
            self._finish(emit, error=f'Could not make {out}: {e}')
            return

        emit({'phase': 'starting', 'total': self.total, 'out': out,
              'route': route, 'port': port})

        try:
            self.proc = subprocess.Popen(
                self._argv(folder, out, route, port, max_ips, test),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                creationflags=sweep.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        except (OSError, subprocess.SubprocessError) as e:
            self._finish(emit, error=f'Could not start the resolver: {e}')
            return

        # Bytes rather than text=True on purpose: PowerShell writes whatever
        # the console encoding is once redirected, and a config named in a
        # language this machine is not set to would otherwise raise a decode
        # error in the middle of a run that was going fine.
        last_fail = None
        for raw in self.proc.stdout:
            line = raw.decode('utf-8', 'replace').rstrip('\r\n')
            said = self._saw(line, emit)
            if said:
                last_fail = said
        code = self.proc.wait()
        self.proc = None

        if self.stopping:
            self._finish(emit, cancelled=True)
        elif code != 0:
            # The script's own last words, which are more use than its exit
            # code - it prints the reason and then exits 1.
            self._finish(emit, error=last_fail
                         or f'The resolver stopped with code {code}.')
        else:
            self._finish(emit)

    def _saw(self, line, emit):
        """One line of the script's output. Returns the text of a [fail] line
        so the caller can keep the last one as the reason."""
        m = LINE_TOTAL.search(line)
        if m:
            # What the script found in the folder, which beats what this side
            # counted: it is the one doing the reading.
            self.total = int(m.group(1))
            emit({'phase': 'resolving', 'done': 0, 'total': self.total,
                  'route': self.route})
            return None

        if LINE_NOTHING.search(line):
            self.total = 0
            return None

        m = LINE_NAMED.search(line) or LINE_FOUND.search(line)
        if m:
            self.route = m.group(1)
            emit({'phase': 'route', 'via': self.route})
            return None
        if LINE_DIRECT.search(line):
            self.route = 'direct'
            emit({'phase': 'route', 'via': 'direct'})
            return None

        m = LINE_WROTE.search(line)
        if m:
            name, ip, port, note = m.groups()
            self._result(emit, FROM_NAME.sub('.ovpn', name), name,
                         outcome_of(note), ip=ip, port=int(port))
            return None

        m = LINE_FORGED.search(line)
        if m:
            # Not a result of its own - it is the censor being caught, and it
            # belongs to whichever config is being read right now.
            emit({'phase': 'forged', 'host': m.group(1),
                  'addresses': m.group(2)})
            return None

        m = LINE_SKIP.search(line)
        if m:
            self._result(emit, m.group(1), None, 'skipped', detail=m.group(2))
            return None

        m = LINE_FAIL.search(line)
        if m:
            return m.group(1)
        return None

    def _result(self, emit, source, written, outcome, ip=None, port=None,
                detail=None):
        self.seen.add(source)
        row = {'source': source, 'name': written or source, 'outcome': outcome,
               'ip': ip, 'port': port, 'detail': detail}
        self.results.append(row)
        emit({'phase': 'result', 'done': len(self.seen), 'total': self.total,
              **row})

    def _finish(self, emit, error=None, cancelled=False):
        self.state = 'done'
        self.error = error
        written = [r for r in self.results if r['outcome'] != 'skipped']
        emit({'phase': 'finished',
              'read': len(self.seen),
              'total': self.total,
              'written': len(written),
              'reachable': len([r for r in written
                                if r['outcome'] == 'reachable']),
              'unreachable': len([r for r in written
                                  if r['outcome'] == 'unreachable']),
              'skipped': len([r for r in self.results
                              if r['outcome'] == 'skipped']),
              'out': self.out,
              # Re-read from disk rather than counted from what went past: a
              # cancelled run leaves what earlier runs wrote standing, and
              # that is what the folder is worth now.
              'inFolder': len(sweep.configs_in(self.out or '')),
              'route': self.route,
              'cancelled': cancelled,
              'error': error})
