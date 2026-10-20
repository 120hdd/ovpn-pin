"""One copy of the app at a time.

Two copies is two hands on the same Windows proxy setting, two tray icons
and two engines each sure the tunnel is theirs - one disconnecting puts the
proxy back underneath the other. So the first copy holds a named mutex for
as long as it runs, and a second one that finds it taken does not start: it
sets a named event the first is waiting on, which brings the first one's
window forward - out of the tray if that is where it was - and then leaves.

Local\\ names, so the claim is per signed-in user: another account on the
same machine has its own proxy setting and its own copy to run.

Nothing here is needed off Windows, and everything here does nothing there.
"""

import ctypes
import ctypes.wintypes as wintypes
import os
import threading

MUTEX = 'Local\\Relay.SingleInstance'
EVENT = 'Local\\Relay.ShowWindow'

ERROR_ALREADY_EXISTS = 183
EVENT_MODIFY_STATE = 0x0002
WAIT_OBJECT_0 = 0
ASFW_ANY = 0xFFFFFFFF

_kernel32 = _user32 = None
if os.name == 'nt':
    _kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    _user32 = ctypes.WinDLL('user32', use_last_error=True)

    def _proto(lib, name, res, *args):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = res, list(args)
        return fn

    _CreateMutexW = _proto(_kernel32, 'CreateMutexW', wintypes.HANDLE,
                           ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR)
    _CreateEventW = _proto(_kernel32, 'CreateEventW', wintypes.HANDLE,
                           ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL,
                           wintypes.LPCWSTR)
    _OpenEventW = _proto(_kernel32, 'OpenEventW', wintypes.HANDLE,
                         wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR)
    _SetEvent = _proto(_kernel32, 'SetEvent', wintypes.BOOL, wintypes.HANDLE)
    _WaitForSingleObject = _proto(_kernel32, 'WaitForSingleObject',
                                  wintypes.DWORD, wintypes.HANDLE,
                                  wintypes.DWORD)
    _CloseHandle = _proto(_kernel32, 'CloseHandle', wintypes.BOOL,
                          wintypes.HANDLE)
    _AllowSetForegroundWindow = _proto(_user32, 'AllowSetForegroundWindow',
                                       wintypes.BOOL, wintypes.DWORD)

# Held, never closed: the claim is the handle, and it lasts exactly as long
# as this process does - Windows lets go of it however the process ends.
_held = []


def claim():
    """True if this is the only copy, and it now holds the claim.

    False if another copy already does, which has by then been asked to come
    forward. Anything going wrong in the asking answers True: a second
    window is a nuisance, an app that will not open at all is worse.
    """
    if not _kernel32:
        return True
    # The event first, so that it exists by the time anyone can see the
    # mutex and go looking for it.
    show = _CreateEventW(None, False, False, EVENT)
    mutex = _CreateMutexW(None, False, MUTEX)
    taken = ctypes.get_last_error() == ERROR_ALREADY_EXISTS
    if not mutex:
        return True
    if not taken:
        _held.extend(h for h in (mutex, show) if h)
        return True

    _CloseHandle(mutex)
    if show:
        _CloseHandle(show)
    other = _OpenEventW(EVENT_MODIFY_STATE, False, EVENT)
    if not other:
        return True
    try:
        # Windows only lets a process take the foreground if the one that
        # has it says so. This copy has it - it was just launched - and is
        # about to go, so it hands it on.
        _AllowSetForegroundWindow(ASFW_ANY)
        _SetEvent(other)
    finally:
        _CloseHandle(other)
    return False


def listen(on_show, stop):
    """Call on_show whenever another copy asks this one to come forward,
    until stop (a threading.Event) is set. Returns at once if this copy does
    not hold the claim."""
    if len(_held) < 2:
        return
    show = _held[1]

    def wait():
        while not stop.is_set():
            # A timeout rather than forever, so that stop is seen.
            if _WaitForSingleObject(show, 500) == WAIT_OBJECT_0:
                try:
                    on_show()
                except Exception:
                    pass

    threading.Thread(target=wait, daemon=True).start()
