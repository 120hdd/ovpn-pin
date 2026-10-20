"""Where the window is, how big it is, and whether it is allowed to change.

pywebview reports a size and a position, and for a window that is on screen
and neither minimised nor maximised those are the right numbers. For every
other window they are not. Minimised, Windows parks it at -32000; maximised,
it is the size of the monitor at -8,-8; hidden in the tray it is wherever it
was parked when it went. Remembering any of those faithfully is how a window
comes back the size of a monitor without being maximised, or off every
screen there is.

GetWindowPlacement is the call that answers the question actually being
asked: where does this window go when it is put back. Its rectangle is the
restored one whatever state the window is in, and SetWindowPlacement is its
other half. The rectangle is checked against the monitors that exist now
before it is used, because the one it was saved on may have been unplugged.

Locking is done with the frame rather than by catching messages. Taking away
WS_THICKFRAME takes the resize edges, taking away WS_MAXIMIZEBOX takes the
maximise button and the double-click on the title bar, and removing Move and
Size from the system menu takes the drag - DefWindowProc will not move a
window whose menu has no Move in it. A window procedure written in Python
would do the same, at the price of the interpreter running for every mouse
move the window ever gets.

Every function here takes an HWND and does nothing without one. Nothing here
is needed for the app to work; all of it is needed for it to be pleasant.
"""

import ctypes
import ctypes.wintypes as wintypes
import os

GWL_STYLE = -16
WS_THICKFRAME = 0x00040000
WS_MAXIMIZEBOX = 0x00010000

SC_SIZE = 0xF000
SC_MOVE = 0xF010
SC_MAXIMIZE = 0xF030
MF_BYCOMMAND = 0x0

SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SWP_FRAMECHANGED = 0x0020

SW_SHOWNORMAL = 1
SW_SHOWMINIMIZED = 2
SW_SHOWMAXIMIZED = 3
WPF_RESTORETOMAXIMIZED = 0x0002

MONITOR_DEFAULTTONULL = 0
MONITOR_DEFAULTTONEAREST = 2

DWMWA_EXTENDED_FRAME_BOUNDS = 9

# Smaller than this is not a window anybody meant to leave behind; it is
# the remains of one being torn down.
MIN_W, MIN_H = 300, 400


class WINDOWPLACEMENT(ctypes.Structure):
    _fields_ = [('length', wintypes.UINT),
                ('flags', wintypes.UINT),
                ('showCmd', wintypes.UINT),
                ('ptMinPosition', wintypes.POINT),
                ('ptMaxPosition', wintypes.POINT),
                ('rcNormalPosition', wintypes.RECT)]


class MONITORINFO(ctypes.Structure):
    _fields_ = [('cbSize', wintypes.DWORD),
                ('rcMonitor', wintypes.RECT),
                ('rcWork', wintypes.RECT),
                ('dwFlags', wintypes.DWORD)]


_user32 = _dwm = None
if os.name == 'nt':
    # Prototypes spelled out, because the defaults are int, and an int is
    # half of a 64-bit handle - the call works on the first machine and
    # fails on the one where the menu happens to live above 4 GB.
    _user32 = ctypes.WinDLL('user32', use_last_error=True)
    _dwm = ctypes.WinDLL('dwmapi')
    _ENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND,
                                   wintypes.LPARAM)

    def _proto(lib, name, res, *args):
        fn = getattr(lib, name)
        fn.restype, fn.argtypes = res, list(args)
        return fn

    _LONG_PTR = ctypes.c_ssize_t
    _EnumWindows = _proto(_user32, 'EnumWindows', wintypes.BOOL,
                          _ENUMPROC, wintypes.LPARAM)
    _GetWindowThreadProcessId = _proto(
        _user32, 'GetWindowThreadProcessId', wintypes.DWORD,
        wintypes.HWND, ctypes.POINTER(wintypes.DWORD))
    _GetWindowTextW = _proto(_user32, 'GetWindowTextW', ctypes.c_int,
                             wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
    _GetClassNameW = _proto(_user32, 'GetClassNameW', ctypes.c_int,
                            wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
    _IsWindow = _proto(_user32, 'IsWindow', wintypes.BOOL, wintypes.HWND)
    _IsZoomed = _proto(_user32, 'IsZoomed', wintypes.BOOL, wintypes.HWND)
    _IsIconic = _proto(_user32, 'IsIconic', wintypes.BOOL, wintypes.HWND)
    _GetWindowPlacement = _proto(_user32, 'GetWindowPlacement',
                                 wintypes.BOOL, wintypes.HWND,
                                 ctypes.POINTER(WINDOWPLACEMENT))
    _SetWindowPlacement = _proto(_user32, 'SetWindowPlacement',
                                 wintypes.BOOL, wintypes.HWND,
                                 ctypes.POINTER(WINDOWPLACEMENT))
    _GetWindowRect = _proto(_user32, 'GetWindowRect', wintypes.BOOL,
                            wintypes.HWND, ctypes.POINTER(wintypes.RECT))
    _SetWindowPos = _proto(_user32, 'SetWindowPos', wintypes.BOOL,
                           wintypes.HWND, wintypes.HWND, ctypes.c_int,
                           ctypes.c_int, ctypes.c_int, ctypes.c_int,
                           wintypes.UINT)
    # GetWindowLongPtrW only exists in 64-bit user32; a 32-bit Python has the
    # plain one, which is the same call at that width.
    _GetStyle = _proto(_user32, 'GetWindowLongPtrW'
                       if hasattr(_user32, 'GetWindowLongPtrW')
                       else 'GetWindowLongW',
                       _LONG_PTR, wintypes.HWND, ctypes.c_int)
    _SetStyle = _proto(_user32, 'SetWindowLongPtrW'
                       if hasattr(_user32, 'SetWindowLongPtrW')
                       else 'SetWindowLongW',
                       _LONG_PTR, wintypes.HWND, ctypes.c_int, _LONG_PTR)
    _GetSystemMenu = _proto(_user32, 'GetSystemMenu', wintypes.HMENU,
                            wintypes.HWND, wintypes.BOOL)
    _RemoveMenu = _proto(_user32, 'RemoveMenu', wintypes.BOOL,
                         wintypes.HMENU, wintypes.UINT, wintypes.UINT)
    _DrawMenuBar = _proto(_user32, 'DrawMenuBar', wintypes.BOOL,
                          wintypes.HWND)
    _MonitorFromRect = _proto(_user32, 'MonitorFromRect', wintypes.HMONITOR,
                              ctypes.POINTER(wintypes.RECT), wintypes.DWORD)
    _GetMonitorInfoW = _proto(_user32, 'GetMonitorInfoW', wintypes.BOOL,
                              wintypes.HMONITOR, ctypes.POINTER(MONITORINFO))
    _DwmGetWindowAttribute = _proto(_dwm, 'DwmGetWindowAttribute',
                                    ctypes.c_long, wintypes.HWND,
                                    wintypes.DWORD, ctypes.c_void_p,
                                    wintypes.DWORD)


def find(title):
    """This process's own top-level window with that title, or None.

    By process and by class as well as by title, because FindWindow by title
    alone answers with whichever window has it first - a second copy of the
    app, or the tray's hidden message window, are both candidates.
    """
    if not _user32:
        return None
    me = os.getpid()
    found = []

    def each(hwnd, _):
        pid = wintypes.DWORD()
        _GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value != me:
            return True
        text = ctypes.create_unicode_buffer(256)
        _GetWindowTextW(hwnd, text, 256)
        cls = ctypes.create_unicode_buffer(256)
        _GetClassNameW(hwnd, cls, 256)
        if text.value == title and cls.value.startswith('WindowsForms'):
            found.append(hwnd)
            return False
        return True

    try:
        _EnumWindows(_ENUMPROC(each), 0)
    except Exception:
        return None
    return found[0] if found else None


def alive(hwnd):
    return bool(_user32 and hwnd and _IsWindow(hwnd))


def placement(hwnd):
    """Where the window goes when it is put back, as something JSON keeps.

    The rectangle is the restored one in every state. Minimised from a
    maximised window still counts as maximised, which is what the flag that
    rides along with a minimised placement is there to say.
    """
    if not alive(hwnd):
        return None
    wp = WINDOWPLACEMENT()
    wp.length = ctypes.sizeof(WINDOWPLACEMENT)
    if not _GetWindowPlacement(hwnd, ctypes.byref(wp)):
        return None
    rc = wp.rcNormalPosition
    if rc.right - rc.left < MIN_W or rc.bottom - rc.top < MIN_H:
        return None
    maximised = wp.showCmd == SW_SHOWMAXIMIZED or (
        wp.showCmd == SW_SHOWMINIMIZED
        and bool(wp.flags & WPF_RESTORETOMAXIMIZED))
    return {'left': rc.left, 'top': rc.top, 'right': rc.right,
            'bottom': rc.bottom, 'max': maximised}


def _onscreen(rc):
    """The same rectangle, moved onto a monitor that exists if it is not on
    one - the nearest, and shrunk to its work area if it is bigger."""
    if _MonitorFromRect(ctypes.byref(rc), MONITOR_DEFAULTTONULL):
        return rc
    info = MONITORINFO()
    info.cbSize = ctypes.sizeof(MONITORINFO)
    mon = _MonitorFromRect(ctypes.byref(rc), MONITOR_DEFAULTTONEAREST)
    if not mon or not _GetMonitorInfoW(mon, ctypes.byref(info)):
        return rc
    work = info.rcWork
    w = min(rc.right - rc.left, work.right - work.left)
    h = min(rc.bottom - rc.top, work.bottom - work.top)
    left = min(max(rc.left, work.left), work.right - w)
    top = min(max(rc.top, work.top), work.bottom - h)
    return wintypes.RECT(left, top, left + w, top + h)


def place(hwnd, saved):
    """Put the window back where placement() said it was."""
    if not alive(hwnd) or not isinstance(saved, dict):
        return False
    try:
        rc = wintypes.RECT(*(int(saved[k]) for k in
                             ('left', 'top', 'right', 'bottom')))
    except (KeyError, TypeError, ValueError):
        return False
    if rc.right - rc.left < MIN_W or rc.bottom - rc.top < MIN_H:
        return False
    wp = WINDOWPLACEMENT()
    wp.length = ctypes.sizeof(WINDOWPLACEMENT)
    wp.showCmd = SW_SHOWMAXIMIZED if saved.get('max') else SW_SHOWNORMAL
    wp.rcNormalPosition = _onscreen(rc)
    return bool(_SetWindowPlacement(hwnd, ctypes.byref(wp)))


def _frame(hwnd):
    """The edges you can see. GetWindowRect includes the invisible resize
    border, which is the part that goes away with the thick frame."""
    rc = wintypes.RECT()
    if _DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS,
                              ctypes.byref(rc), ctypes.sizeof(rc)) != 0:
        return None
    return rc


def lock(hwnd, on):
    """Hold the window where it is and at the size it is, or let it go.

    Safe to repeat. Changing the frame changes how much of the window rect
    is invisible border, so the window is nudged afterwards to keep the
    edges you can see exactly where they were - otherwise locking would grow
    the window by the width of a border it no longer has.
    """
    if not alive(hwnd):
        return False
    style = _GetStyle(hwnd, GWL_STYLE)
    if on:
        want = style & ~(WS_THICKFRAME | WS_MAXIMIZEBOX)
        menu = _GetSystemMenu(hwnd, False)
        if menu:
            for command in (SC_MOVE, SC_SIZE, SC_MAXIMIZE):
                _RemoveMenu(menu, command, MF_BYCOMMAND)
    else:
        want = style | WS_THICKFRAME | WS_MAXIMIZEBOX
        if want == style:
            # Never locked, so nothing to undo - and this runs on every
            # restore, so it should not be touching the menu each time.
            return True
        # True puts the default system menu back, Move and Size with it.
        _GetSystemMenu(hwnd, True)
    _DrawMenuBar(hwnd)
    if want == style:
        return True

    steady = not _IsZoomed(hwnd) and not _IsIconic(hwnd)
    before = _frame(hwnd) if steady else None
    _SetStyle(hwnd, GWL_STYLE, want)
    _SetWindowPos(hwnd, None, 0, 0, 0, 0,
                  SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
                  | SWP_FRAMECHANGED)
    after = _frame(hwnd) if before else None
    outer = wintypes.RECT()
    if after and _GetWindowRect(hwnd, ctypes.byref(outer)):
        left = outer.left + before.left - after.left
        top = outer.top + before.top - after.top
        right = outer.right + before.right - after.right
        bottom = outer.bottom + before.bottom - after.bottom
        _SetWindowPos(hwnd, None, left, top, right - left, bottom - top,
                      SWP_NOZORDER | SWP_NOACTIVATE)
    return True
