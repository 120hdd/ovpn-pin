"""Turn the Windows system proxy on and off, and always be able to put it back.

This is the part of the app that touches something the user did not create
and would not know how to repair, so all of it is arranged around one rule:
whatever the machine said before us goes back exactly, including when we are
killed without getting to run any cleanup.

That last case is what the saved-settings file is for. It is written before
anything is changed and deleted after it is put back, so its existence means
one thing only - this machine is currently altered and something still owes
it a restore. The next launch finds it, sees no proxy of ours listening, and
puts it back before the user has noticed more than one failed page.


Two things here were found the hard way, by measuring
----------------------------------------------------

**Writing the registry does not work.** Every "set the proxy from code"
recipe says to set ProxyEnable and ProxyServer under Internet Settings and
then call InternetSetOption with INTERNET_OPTION_SETTINGS_CHANGED. WinINET
keeps its own copy of these settings in memory, and that call makes it flush
*its* copy back out over the top of what was just written. Measured here: the
value reads back correctly right up until the refresh call and is the old one
immediately afterwards. Silently, and only sometimes - the worst way for a
thing like this to fail.

**And those registry values are not where the answer lives anyway.** On
Windows 11 the state that counts is a binary blob at
Connections\\DefaultConnectionSettings; ProxyEnable and ProxyServer are a
mirror of it that is not always up to date. Setting the proxy through
WinINET, then reading it back out of the registry, showed WinINET holding the
new proxy and the registry still showing a stale one from a tool uninstalled
long ago.

So both directions go through INTERNET_OPTION_PER_CONNECTION_OPTION, which is
the documented interface, and the registry is never touched at all.


Why all five options and not the three that matter
--------------------------------------------------

Because the machine this was written on had a PAC file configured -
`http://127.0.0.1:10811/pac` from a proxy client - while the proxy itself was
switched off. Restoring only the flags, the server and the bypass list would
have quietly deleted that, and the owner would have found their other tool
broken with nothing to connect it to what we did. Anything queried is
restored, whether or not this app understands what it is for.
"""

import ctypes
import ctypes.wintypes as wintypes
import json
import os
import winreg

# The legacy mirror. WinINET does not keep these in step with what it is
# actually using - measured: after setting the proxy through the documented
# interface, WinINET reported 127.0.0.1:8899 while these still read
# ProxyEnable=0 and a proxy address belonging to a tool uninstalled long ago,
# and urllib.getproxies() consequently saw nothing at all.
#
# Which matters because the two halves of the machine read different places.
# Edge, Chrome and anything else on WinINET follow the settings above; Python,
# a good many command-line tools and various installers read these values
# straight out of the registry. Writing only one of them produces the worst
# possible outcome for someone who cannot diagnose it: the browser works, and
# something else does not, with nothing to connect the two.
#
# So these are written as a mirror, always after WinINET has been told - by
# which point its cache already agrees, so a later flush writes out the same
# thing rather than reverting us.
REG_KEY = r'Software\Microsoft\Windows\CurrentVersion\Internet Settings'
REG_VALUES = ('ProxyEnable', 'ProxyServer', 'ProxyOverride')

INTERNET_OPTION_REFRESH = 37
INTERNET_OPTION_SETTINGS_CHANGED = 39
INTERNET_OPTION_PER_CONNECTION_OPTION = 75

# The per-connection options this reads and writes. The names are ours; the
# numbers are Microsoft's. Restored as a set, so nothing is left half-changed.
FLAGS = 1
PROXY_SERVER = 2
PROXY_BYPASS = 3
AUTOCONFIG_URL = 4
AUTODISCOVERY_FLAGS = 5

OPTIONS = (FLAGS, PROXY_SERVER, PROXY_BYPASS, AUTOCONFIG_URL,
           AUTODISCOVERY_FLAGS)
NAMES = {FLAGS: 'flags', PROXY_SERVER: 'server', PROXY_BYPASS: 'bypass',
         AUTOCONFIG_URL: 'pac', AUTODISCOVERY_FLAGS: 'autodiscovery'}
STRINGS = {PROXY_SERVER, PROXY_BYPASS, AUTOCONFIG_URL}

PROXY_TYPE_DIRECT = 0x01
PROXY_TYPE_PROXY = 0x02
PROXY_TYPE_AUTO_PROXY_URL = 0x04

# Used only when the machine had no list of its own. Anything that is this
# machine, or the network it sits on, has no business being sent abroad and
# back; <local> covers the bare hostnames with no dot in them.
DEFAULT_BYPASS = ('<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;'
                  '172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;'
                  '172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;'
                  '172.31.*;192.168.*')


class _Value(ctypes.Union):
    _fields_ = [('dwValue', wintypes.DWORD),
                ('pszValue', ctypes.c_wchar_p),
                ('ftValue', wintypes.FILETIME)]


class _Option(ctypes.Structure):
    _fields_ = [('dwOption', wintypes.DWORD), ('Value', _Value)]


class _OptionList(ctypes.Structure):
    _fields_ = [('dwSize', wintypes.DWORD),
                ('pszConnection', ctypes.c_wchar_p),
                ('dwOptionCount', wintypes.DWORD),
                ('dwOptionError', wintypes.DWORD),
                ('pOptions', ctypes.POINTER(_Option))]


def _dll():
    d = ctypes.WinDLL('wininet.dll')
    d.InternetSetOptionW.argtypes = [wintypes.LPVOID, wintypes.DWORD,
                                     wintypes.LPVOID, wintypes.DWORD]
    d.InternetSetOptionW.restype = wintypes.BOOL
    d.InternetQueryOptionW.argtypes = [wintypes.LPVOID, wintypes.DWORD,
                                       wintypes.LPVOID,
                                       ctypes.POINTER(wintypes.DWORD)]
    d.InternetQueryOptionW.restype = wintypes.BOOL
    return d


def _list_for(opts, count):
    """pszConnection is left null, which means the LAN connection rather than
    a named dial-up entry - what an ordinary machine uses, and what the
    Settings app shows you."""
    lst = _OptionList()
    lst.dwSize = ctypes.sizeof(_OptionList)
    lst.pszConnection = None
    lst.dwOptionCount = count
    lst.dwOptionError = 0
    lst.pOptions = opts
    return lst


def read_current():
    """What WinINET is actually going by, as a plain dict."""
    opts = (_Option * len(OPTIONS))()
    for i, o in enumerate(OPTIONS):
        opts[i].dwOption = o
    lst = _list_for(opts, len(OPTIONS))
    size = wintypes.DWORD(ctypes.sizeof(lst))
    if not _dll().InternetQueryOptionW(
            None, INTERNET_OPTION_PER_CONNECTION_OPTION,
            ctypes.byref(lst), ctypes.byref(size)):
        raise OSError(f'could not read the proxy settings: '
                      f'{ctypes.GetLastError()}')
    out = {}
    for i, o in enumerate(OPTIONS):
        out[NAMES[o]] = (opts[i].Value.pszValue if o in STRINGS
                         else int(opts[i].Value.dwValue))
    return out


def read_legacy():
    """The registry mirror, with None for anything absent - absent and empty
    are different things to put back."""
    out = {}
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_KEY, 0,
                        winreg.KEY_READ) as k:
        for name in REG_VALUES:
            try:
                out[name] = winreg.QueryValueEx(k, name)[0]
            except FileNotFoundError:
                out[name] = None
    return out


def write_legacy(values):
    """Best effort. A machine where this fails still has a working proxy
    through WinINET, so it is not worth failing a connection over."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_KEY, 0,
                            winreg.KEY_READ | winreg.KEY_WRITE) as k:
            for name in REG_VALUES:
                value = values.get(name)
                if value is None:
                    try:
                        winreg.DeleteValue(k, name)
                    except FileNotFoundError:
                        pass
                elif name == 'ProxyEnable':
                    winreg.SetValueEx(k, name, 0, winreg.REG_DWORD, int(value))
                else:
                    winreg.SetValueEx(k, name, 0, winreg.REG_SZ, str(value))
    except OSError:
        pass


def apply(settings):
    """Put a whole set of settings in force. The dict is the shape
    read_current returns, so what comes out can go straight back in."""
    opts = (_Option * len(OPTIONS))()
    for i, o in enumerate(OPTIONS):
        opts[i].dwOption = o
        value = settings.get(NAMES[o])
        if o in STRINGS:
            opts[i].Value.pszValue = value or ''
        else:
            opts[i].Value.dwValue = int(value or 0)

    lst = _list_for(opts, len(OPTIONS))
    d = _dll()
    if not d.InternetSetOptionW(None, INTERNET_OPTION_PER_CONNECTION_OPTION,
                                ctypes.byref(lst), ctypes.sizeof(lst)):
        raise OSError(f'could not change the proxy settings: '
                      f'{ctypes.GetLastError()}')

    # These two only tell everything already running to look again. Safe now,
    # unlike in the registry version: what WinINET holds is already what we
    # want, so a flush writes out the right thing rather than the old thing.
    d.InternetSetOptionW(None, INTERNET_OPTION_SETTINGS_CHANGED, None, 0)
    d.InternetSetOptionW(None, INTERNET_OPTION_REFRESH, None, 0)


class SystemProxy:
    """Sets the machine's proxy, and holds on to what was there before."""

    def __init__(self, save_path):
        self.save_path = save_path

    # -- the saved-settings file ------------------------------------------

    def _stash(self, settings):
        os.makedirs(os.path.dirname(self.save_path), exist_ok=True)
        tmp = f'{self.save_path}.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(settings, f, indent=2)
        os.replace(tmp, self.save_path)

    def stashed(self):
        try:
            with open(self.save_path, encoding='utf-8') as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def _drop_stash(self):
        try:
            os.remove(self.save_path)
        except OSError:
            pass

    # -- the two things it does -------------------------------------------

    def engage(self, host, port):
        """Point the machine at our proxy, remembering what it said first.

        An existing stash is never overwritten. Doing so would record our own
        settings as the ones to go back to and the user's real ones would be
        gone for good, so the first save is the true one and every later
        engage leaves it alone.

        Everything not about the manual proxy is carried across untouched -
        a PAC URL in particular, which is left exactly where it was and
        merely not consulted while our flag is the one in force.
        """
        before, before_legacy = read_current(), read_legacy()
        if not self.stashed():
            self._stash({'wininet': before, 'legacy': before_legacy})

        bypass = before.get('bypass') or DEFAULT_BYPASS
        keep = dict(before)
        keep['flags'] = PROXY_TYPE_PROXY | PROXY_TYPE_DIRECT
        keep['server'] = f'{host}:{port}'
        keep['bypass'] = bypass
        apply(keep)
        write_legacy({'ProxyEnable': 1, 'ProxyServer': f'{host}:{port}',
                      'ProxyOverride': bypass})

    def restore(self):
        """Put back exactly what was there. Safe when nothing was changed,
        and safe to call twice."""
        saved = self.stashed()
        if saved is None:
            return False
        # Older stashes held only the WinINET half; treat one as that shape
        # rather than throwing, so an app updated mid-connection can still
        # put the machine back.
        if 'wininet' in saved:
            apply(saved['wininet'])
            write_legacy(saved.get('legacy') or {})
        else:
            apply(saved)
        self._drop_stash()
        return True

    def engaged_for(self, port):
        """Whether the machine is pointed at our own port right now - which
        is how "we are connected" is told from "the user has a proxy of their
        own switched on"."""
        now = read_current()
        return (bool(now.get('flags', 0) & PROXY_TYPE_PROXY)
                and str(now.get('server') or '').endswith(f':{port}'))
