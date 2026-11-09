"""The whole machine through the connection, not just the programs that ask.

The proxy setting is a request. Browsers honour it, and so does most of what
Windows ships, but a game, a command-line tool or a messenger with network
code of its own goes straight past it. This is the other way to route
Windows: a virtual network adapter that every program's traffic falls into
whether it knows about proxies or not, handed to the same worker on the same
port. Everything from the worker on is unchanged - the exit, the meter, the
host list.

Relay does not drive an adapter itself. It runs sing-box, which creates one
with wintun (carried inside sing-box.exe, there is no driver to install),
points the routes at it, keeps Windows from asking the other adapters' DNS
servers, and turns each TCP connection it catches into a SOCKS5 request to
127.0.0.1:<port>.

Three things the worker cannot carry, and what happens to them instead:

  UDP   The provider's proxy is HTTP CONNECT, which is TCP and nothing else.
        UDP is refused on the spot rather than dropped, so a browser gives up
        on QUIC at once and falls back to TCP instead of waiting it out. Calls
        and games that need UDP do not work while this is on.
  DNS   Answered by sing-box, which asks 1.1.1.1 over HTTPS through the exit.
        The forged answers on this line never get a say.
  IPv6  Given no address, so it is unreachable rather than leaking around
        the adapter, and answers come back IPv4 only so nothing tries it.

What must not fall into the adapter is what carries it: the worker's own
connection to the exit, left out by address, and gost's to the server when
the way out is a tunnel of your own, left out by process. This app is left
out too, so a reachability test still measures this line and not the exit's.
Running from source that means every python.exe, which is the price of the
worker being an ordinary interpreter there.

Split tunnelling (split.py) is decided here too when the adapter is up:
sing-box sees each connection's program, address and - by sniffing its
first packet - its name, which is more than the worker is ever told. What
goes around the exit leaves by the direct outbound, UDP included, and a
listed name is looked up from this line's own resolver rather than through
the exit, so it is answered as seen from here.

Creating an adapter takes administrator rights, so sing-box is started by a
small elevated PowerShell script: one UAC prompt per connect, none if Relay
is already elevated. An unelevated window cannot stop an elevated process,
so it asks - the same way the sweep is asked. It drops a stop file, and the
script ends sing-box and says so in a done file. The script also watches the
worker, and if that goes it takes the adapter down with it, rather than
leave every program on the machine pointed at a port nobody answers.
"""

import ctypes
import ipaddress
import json
import os
import re
import subprocess
import time

import paths
from sweep import CREATE_NO_WINDOW, _ps_array, _ps_string

# The adapter's name in Network Connections, and what the script waits for.
ADAPTER = 'RelayTun'
# Not one of the private ranges below: those are routed around the adapter,
# and the adapter's own subnet must not be one of the things routed around.
# sing-box answers DNS on the next address, 198.18.0.2.
ADDRESS = '198.18.0.1/30'
DOH = '1.1.1.1'

# Never into the adapter. The LAN, link-local, multicast and broadcast stay
# where they are - a printer or a router's page reached through an exit in
# another country is not reached at all.
LOCAL_NETS = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16',
              '169.254.0.0/16', '224.0.0.0/4', '255.255.255.255/32')

# How long the adapter gets to appear once sing-box is running. The UAC
# prompt is not counted: that waits for a person, for as long as it takes.
STARTUP_SECONDS = 25
STOP_SECONDS = 8

KINDS = ('proxy', 'tun')


def available():
    return paths.singbox_exe() is not None


def is_admin():
    if os.name != 'nt':
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def _path_rx(path):
    """A process path as sing-box matches it, ignoring case.

    sing-box reports a Win32 path, and Windows does not care whether it is
    C:\\ or c:\\ - so neither may the rule. Go's regexp takes an escaped
    punctuation character as itself, which is all re.escape produces.
    """
    return '(?i)^' + re.escape(os.path.normpath(path)) + '$'


def line_dns():
    """This line's own resolver: the first IPv4 DNS server Windows has, or
    None. Asked before the adapter is up - once it is, the adapter's own
    address is among them, which is the one answer that is no use here.
    """
    if os.name != 'nt':
        return None
    try:
        from ctypes import wintypes

        class IP_ADDR_STRING(ctypes.Structure):
            pass
        IP_ADDR_STRING._fields_ = [
            ('Next', ctypes.POINTER(IP_ADDR_STRING)),
            ('IpAddress', ctypes.c_char * 16),
            ('IpMask', ctypes.c_char * 16),
            ('Context', wintypes.DWORD)]

        class FIXED_INFO(ctypes.Structure):
            _fields_ = [
                ('HostName', ctypes.c_char * 132),
                ('DomainName', ctypes.c_char * 132),
                ('CurrentDnsServer', ctypes.POINTER(IP_ADDR_STRING)),
                ('DnsServerList', IP_ADDR_STRING),
                ('NodeType', wintypes.UINT),
                ('ScopeId', ctypes.c_char * 260),
                ('EnableRouting', wintypes.UINT),
                ('EnableProxy', wintypes.UINT),
                ('EnableDns', wintypes.UINT)]

        size = wintypes.ULONG(0)
        get = ctypes.windll.iphlpapi.GetNetworkParams
        get(None, ctypes.byref(size))
        buf = ctypes.create_string_buffer(size.value or 1024)
        if get(buf, ctypes.byref(size)) != 0:
            return None
        info = ctypes.cast(buf, ctypes.POINTER(FIXED_INFO)).contents
        node = info.DnsServerList
        adapter_net = ipaddress.ip_network('198.18.0.0/15')
        while True:
            text = node.IpAddress.split(b'\0', 1)[0].decode('ascii', 'replace')
            try:
                addr = ipaddress.IPv4Address(text.strip())
            except ValueError:
                addr = None
            if (addr is not None and not addr.is_loopback
                    and not addr.is_unspecified and addr not in adapter_net):
                return str(addr)
            if not node.Next:
                return None
            node = node.Next.contents
    except Exception:
        return None


def _split_rules(r, line_resolver):
    """Route and DNS rules for split tunnelling, from split.rules().

    Programs and destinations are separate rules because sing-box ANDs the
    two kinds of condition within one rule, and these are meant as either.
    In "only", a listed UDP flow is refused rather than sent through: the
    exit is TCP and nothing else, and refusing at once is what makes QUIC
    fall back to TCP straight away.
    """
    route, dns = [], []
    apps = list(r.get('apps') or ())
    dest = {}
    if r.get('names'):
        dest['domain_suffix'] = list(r['names'])
    if r.get('nets'):
        dest['ip_cidr'] = list(r['nets'])

    if r['mode'] == 'bypass':
        if apps:
            route.append({'process_name': apps, 'action': 'route',
                          'outbound': 'direct'})
        if dest:
            route.append(dict(dest, action='route', outbound='direct'))
        if r.get('names') and line_resolver:
            dns.append({'domain_suffix': list(r['names']), 'action': 'route',
                        'server': 'dns-line'})
    else:
        conds = ([{'process_name': apps}] if apps else []) + ([dest] if dest else [])
        for cond in conds:
            route.append(dict(cond, network='udp', action='reject'))
            route.append(dict(cond, action='route', outbound='relay'))
    return route, dns


def build_config(port, keep=(), own=(), carriers=(), log_path=None,
                 split_rules=None, line_resolver=None):
    """The whole sing-box configuration, as a dict.

    A plain function of its arguments so the test can read what would be
    run without running it.

    keep      addresses that must reach the internet around the adapter -
              the exit the worker is connected to. Anything that is not a
              public IPv4 address is skipped: a tunnel's local end is
              127.0.0.1:9091 and has no business in a route table.
    own       programs whose connections go straight out: this app.
    carriers  programs that carry the tunnel itself: gost. Their DNS is
              asked directly too - asked through the exit, it would wait on
              the very connection it was trying to bring back.
    split_rules    split.rules(), or None for everything through the exit.
    line_resolver  this line's own DNS server, for names going around.
    """
    kept = []
    for value in keep or ():
        try:
            addr = ipaddress.ip_address(str(value).strip())
        except ValueError:
            continue
        if addr.version == 4 and addr.is_global:
            kept.append(f'{addr}/32')

    straight = [_path_rx(p) for p in list(own or ()) + list(carriers or ())
                if p]
    carried = [_path_rx(p) for p in carriers or () if p]

    split_route, split_dns = (_split_rules(split_rules, line_resolver)
                              if split_rules else ([], []))
    only = bool(split_rules) and split_rules.get('mode') == 'only'

    rules = []
    if split_rules and split_rules.get('names'):
        # A name is only known if it is read off the connection: the TLS
        # ClientHello, the HTTP Host, QUIC's Initial. Only when names are
        # listed, because sniffing holds every new connection for a moment.
        rules.append({'action': 'sniff'})
    rules.append({'port': 53, 'action': 'hijack-dns'})
    if straight:
        rules.append({'process_path_regex': straight,
                      'action': 'route', 'outbound': 'direct'})
    rules.extend(split_route)
    if not only:
        rules.append({'network': 'udp', 'action': 'reject'})

    dns_rules = []
    if carried:
        dns_rules.append({'process_path_regex': carried,
                          'action': 'route', 'server': 'dns-direct'})
    dns_rules.extend(split_dns)

    dns_servers = [
        {'type': 'https', 'tag': 'dns-remote', 'server': DOH,
         'detour': 'relay'},
        {'type': 'https', 'tag': 'dns-direct', 'server': DOH},
    ]
    if split_dns:
        dns_servers.append({'type': 'udp', 'tag': 'dns-line',
                            'server': line_resolver})

    log = {'level': 'warn', 'timestamp': True}
    if log_path:
        log['output'] = log_path

    return {
        'log': log,
        'dns': {
            'servers': dns_servers,
            'rules': dns_rules,
            'final': 'dns-remote',
            'strategy': 'ipv4_only',
        },
        'inbounds': [{
            'type': 'tun',
            'tag': 'tun-in',
            'interface_name': ADAPTER,
            'address': [ADDRESS],
            'mtu': 9000,
            'auto_route': True,
            # On Windows this is what stops DNS leaking out of the other
            # adapters, and what makes IPv6 unreachable instead of a way round.
            'strict_route': True,
            'route_exclude_address': list(LOCAL_NETS) + kept,
        }],
        'outbounds': [
            {'type': 'socks', 'tag': 'relay', 'server': '127.0.0.1',
             'server_port': int(port), 'version': '5', 'network': 'tcp'},
            {'type': 'direct', 'tag': 'direct'},
        ],
        'route': {
            'rules': rules,
            # In "only", what is not listed goes straight out - UDP and all.
            'final': 'direct' if only else 'relay',
            # Without it, sing-box's own connections - the direct ones, and
            # its DNS - would be routed into its own adapter.
            'auto_detect_interface': True,
            'default_domain_resolver': 'dns-direct',
        },
    }


# Run elevated. Doubled braces are literal; single ones are filled in by
# str.format. Everything it says, it says in a file, because elevation
# leaves no pipe to say it down.
WRAPPER = r'''
$ErrorActionPreference = 'Continue'
$exe    = {exe}
$out    = {out}
$err    = {err}
$pidf   = {pidf}
$ready  = {ready}
$failed = {failed}
$stop   = {stop}
$done   = {done}
$nic    = {nic}
$watch  = {watch}

Remove-Item -LiteralPath $done -Force -ErrorAction SilentlyContinue
try {{
    $box = Start-Process -FilePath $exe -ArgumentList {argv} -NoNewWindow `
               -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    # Without a handle taken now, ExitCode reads as nothing once it has exited.
    $null = $box.Handle
}} catch {{
    Set-Content -LiteralPath $failed -Value "sing-box would not start: $_" -Encoding utf8
    Set-Content -LiteralPath $done -Value 'failed' -Encoding ascii
    exit 1
}}
Set-Content -LiteralPath $pidf -Value $box.Id -Encoding ascii

$up = $false
$began = Get-Date
$deadline = $began.AddSeconds({startup})
# Where the NetAdapter module is missing, three seconds of sing-box not
# falling over is the nearest thing to an answer there is.
$canAsk = [bool](Get-Command Get-NetAdapter -ErrorAction SilentlyContinue)
while (-not $box.HasExited) {{
    if (Test-Path -LiteralPath $stop) {{ break }}
    if ($watch -and -not (Get-Process -Id $watch -ErrorAction SilentlyContinue)) {{
        Set-Content -LiteralPath $failed -Value 'the proxy it was carrying traffic to has stopped' -Encoding utf8
        break
    }}
    if (-not $up) {{
        if ($canAsk) {{
            $a = Get-NetAdapter -Name $nic -ErrorAction SilentlyContinue
            $seen = [bool]($a -and $a.Status -eq 'Up')
        }} else {{
            $seen = ((Get-Date) - $began).TotalSeconds -ge 3
        }}
        if ($seen) {{
            $up = $true
            Set-Content -LiteralPath $ready -Value 'up' -Encoding ascii
        }} elseif ((Get-Date) -gt $deadline) {{
            Set-Content -LiteralPath $failed -Value 'the network adapter did not come up' -Encoding utf8
            break
        }}
        Start-Sleep -Milliseconds 300
    }} else {{
        Start-Sleep -Milliseconds 1000
    }}
}}

if ($box.HasExited) {{
    if (-not (Test-Path -LiteralPath $failed) -and -not (Test-Path -LiteralPath $stop)) {{
        Set-Content -LiteralPath $failed -Value ("sing-box stopped by itself (exit " + $box.ExitCode + ")") -Encoding utf8
    }}
}} else {{
    # The adapter, its routes and its firewall filters all belong to the
    # process, and go when it does.
    Stop-Process -Id $box.Id -Force -ErrorAction SilentlyContinue
    $box.WaitForExit(5000) | Out-Null
}}
Remove-Item -LiteralPath $pidf -Force -ErrorAction SilentlyContinue
Set-Content -LiteralPath $done -Value 'done' -Encoding ascii
'''


def explain(error):
    """One sentence for a failure this module raised, or None."""
    text = str(error)
    kind, _, detail = text.partition(':')
    detail = detail.strip()
    if kind == 'tun-missing':
        return ('sing-box.exe is not beside Relay, so there is no adapter to '
                'bring up. Use the proxy setting instead.')
    if kind == 'tun-refused':
        return ('Windows was not given administrator approval, so the adapter '
                'was not brought up.')
    if kind == 'tun-failed':
        return ('The network adapter did not come up'
                + (f': {detail[:200]}' if detail else '.'))
    return None


class Tun:
    """One adapter at a time, and always able to say whether it is up."""

    def __init__(self, alive):
        # The worker's own liveness check, passed in rather than imported:
        # it is the one that knows a dead process from a held handle.
        self.alive = alive
        self.dir = os.path.join(paths.STATE_DIR, 'tun')

    def _path(self, name):
        return os.path.join(self.dir, name)

    def _remove(self, *names):
        for name in names:
            try:
                os.remove(self._path(name))
            except OSError:
                pass

    def _read(self, name):
        try:
            with open(self._path(name), encoding='utf-8-sig',
                      errors='replace') as f:
                return f.read().strip()
        except OSError:
            return ''

    def _pid(self):
        try:
            return int(self._read('sing-box.pid') or 0)
        except ValueError:
            return 0

    def record(self):
        """What was started, and for which port - or None."""
        try:
            with open(self._path('state.json'), encoding='utf-8') as f:
                rec = json.load(f)
            return rec if isinstance(rec, dict) else None
        except (OSError, ValueError):
            return None

    def running(self):
        rec = self.record()
        if not rec:
            return None
        if os.path.exists(self._path('done')):
            return None
        return rec if self.alive(self._pid()) else None

    def engaged_for(self, port):
        """Up, and carrying traffic to this port. Another copy's adapter, on
        another port, is somebody's live connection and not ours to touch."""
        rec = self.running()
        return bool(rec and rec.get('port') == port)

    # -- up ------------------------------------------------------------------

    def start(self, port, keep=(), watch=None, own=(), carriers=(),
              split_rules=None):
        exe = paths.singbox_exe()
        if not exe:
            raise RuntimeError('tun-missing: sing-box.exe was not found')
        self.stop()
        os.makedirs(self.dir, exist_ok=True)
        self._remove('ready', 'failed', 'stop', 'done', 'sing-box.pid',
                     'sing-box.out', 'sing-box.err', 'sing-box.log')

        config = self._path('config.json')
        with open(config, 'w', encoding='utf-8') as f:
            json.dump(build_config(port, keep, own, carriers,
                                   self._path('sing-box.log'),
                                   split_rules=split_rules,
                                   line_resolver=line_dns()
                                   if split_rules else None), f, indent=2)

        wrapper = self._path('run.ps1')
        with open(wrapper, 'w', encoding='utf-8') as f:
            f.write(WRAPPER.format(
                exe=_ps_string(exe),
                argv=_ps_array(['run', '-c', config, '--disable-color']),
                out=_ps_string(self._path('sing-box.out')),
                err=_ps_string(self._path('sing-box.err')),
                pidf=_ps_string(self._path('sing-box.pid')),
                ready=_ps_string(self._path('ready')),
                failed=_ps_string(self._path('failed')),
                stop=_ps_string(self._path('stop')),
                done=_ps_string(self._path('done')),
                nic=_ps_string(ADAPTER),
                watch=int(watch or 0),
                startup=STARTUP_SECONDS))

        with open(self._path('state.json'), 'w', encoding='utf-8') as f:
            json.dump({'port': port, 'watch': watch, 'since': time.time()}, f)

        script = ['-NoProfile', '-ExecutionPolicy', 'Bypass',
                  '-WindowStyle', 'Hidden', '-File', wrapper]
        if is_admin():
            # Already elevated: no prompt to wait for. Its own process group
            # and no console, so it outlives this window when the setting
            # says the connection should.
            subprocess.Popen(
                ['powershell'] + script, stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=CREATE_NO_WINDOW | 0x200)
        else:
            raise_it = ('Start-Process powershell -Verb RunAs '
                        '-WindowStyle Hidden -ArgumentList ' + _ps_array(script))
            try:
                done = subprocess.run(
                    ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                     '-Command', raise_it],
                    capture_output=True, text=True, timeout=300,
                    creationflags=CREATE_NO_WINDOW)
            except (OSError, subprocess.SubprocessError) as e:
                self._remove('state.json')
                raise RuntimeError(f'tun-failed: could not ask for elevation: {e}')
            if done.returncode != 0:
                # The one that actually happens: the UAC prompt was dismissed.
                self._remove('state.json')
                raise RuntimeError('tun-refused: administrator rights were refused')

        deadline = time.time() + STARTUP_SECONDS + 10
        while time.time() < deadline:
            if os.path.exists(self._path('ready')):
                return self.record()
            if os.path.exists(self._path('done')):
                break
            time.sleep(0.25)
        said = self._why()
        self.stop()
        raise RuntimeError(f'tun-failed: {said or "it did not come up in time"}')

    def _why(self):
        """What went wrong, from whichever of the three files has it."""
        said = self._read('failed')
        for name in ('sing-box.err', 'sing-box.log'):
            tail = self._read(name)
            if tail:
                last = [line for line in tail.splitlines() if line.strip()][-1:]
                if last:
                    said = f'{said} - {last[0]}' if said else last[0]
                    break
        return said[:300]

    # -- down ----------------------------------------------------------------

    def stop(self, wait=STOP_SECONDS):
        """Take the adapter down if it is up, and forget it either way.

        Says whether it was up, which is not the same as whether there were
        files to forget: a script that ended by itself leaves its record
        behind with nothing running."""
        pid = self._pid()
        was_up = bool(pid and self.alive(pid))
        if was_up and not os.path.exists(self._path('done')):
            try:
                with open(self._path('stop'), 'w', encoding='ascii') as f:
                    f.write('stop')
            except OSError:
                pass
            deadline = time.time() + wait
            while time.time() < deadline:
                if os.path.exists(self._path('done')) or not self.alive(pid):
                    break
                time.sleep(0.2)
            if self.alive(pid):
                # The script is gone and sing-box is not. Left like that, the
                # whole machine stays pointed at a worker that may be about to
                # stop - so it is ended directly, which needs the same rights
                # it was started with.
                self._kill(pid)
        self._remove('state.json', 'stop', 'ready', 'sing-box.pid')
        return was_up

    def _kill(self, pid):
        argv = ['/F', '/PID', str(int(pid))]
        try:
            if is_admin():
                subprocess.run(['taskkill'] + argv, capture_output=True,
                               timeout=15, creationflags=CREATE_NO_WINDOW)
            else:
                subprocess.run(
                    ['powershell', '-NoProfile', '-Command',
                     'Start-Process taskkill -Verb RunAs -WindowStyle Hidden '
                     '-ArgumentList ' + _ps_array(argv)],
                    capture_output=True, timeout=300,
                    creationflags=CREATE_NO_WINDOW)
        except (OSError, subprocess.SubprocessError):
            pass

    def tidy(self):
        """After a crash: an adapter whose worker is gone is wreckage.

        The script normally sees to this itself. This is for the case where
        the script was killed too, and its files are all that is left."""
        rec = self.record()
        if rec and not self.alive(rec.get('watch') or 0):
            return self.stop()
        return False
