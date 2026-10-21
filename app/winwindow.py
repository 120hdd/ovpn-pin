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

The size is kept as a share of the screen rather than as pixels. A window
the right size on a 1080p monitor is a postage stamp on a 4K one and taller
than the whole screen on a 768-line laptop, so the work area it was saved
against rides along with the rectangle, and the rectangle is scaled by how
much the screen has changed when it comes back.

The window is never maximised. It is a narrow dashboard, and filled out to a
whole monitor it is a small card in a sea of background. WS_MAXIMIZEBOX is
taken away for good - that takes the button, the double-click on the title
bar and the snap to the top of the screen - whether or not it is locked.

There is no Windows frame at all: no title bar, no edges. The page draws
its own header, with minimise and close in it, and moves the window when
that header is dragged. Locking the position is the page no longer treating
its header as somewhere to drag from.

Every function here takes an HWND and does nothing without one. Nothing here
is needed for the app to work; all of it is needed for it to be pleasant.
"""

import ctypes
import ctypes.wintypes as wintypes
import os

GWL_STYLE = -16
WS_CAPTION = 0x00C00000
WS_THICKFRAME = 0x00040000
WS_SYSMENU = 0x00080000
WS_MINIMIZEBOX = 0x00020000
WS_MAXIMIZEBOX = 0x00010000

SWP_NOSIZE = 0x0001
SWP_NOMOVE = 0x0002
SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SWP_FRAMECHANGED = 0x0020

SW_SHOWNORMAL = 1

MONITOR_DEFAULTTONULL = 0
MONITOR_DEFAULTTONEAREST = 2

SPI_GETWORKAREA = 0x0030

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
    _GetWindowPlacement = _proto(_user32, 'GetWindowPlacement',
                                 wintypes.BOOL, wintypes.HWND,
                                 ctypes.POINTER(WINDOWPLACEMENT))
    _SetWindowPlacement = _proto(_user32, 'SetWindowPlacement',
                                 wintypes.BOOL, wintypes.HWND,
                                 ctypes.POINTER(WINDOWPLACEMENT))
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
    _MonitorFromRect = _proto(_user32, 'MonitorFromRect', wintypes.HMONITOR,
                              ctypes.POINTER(wintypes.RECT), wintypes.DWORD)
    _GetMonitorInfoW = _proto(_user32, 'GetMonitorInfoW', wintypes.BOOL,
                              wintypes.HMONITOR, ctypes.POINTER(MONITORINFO))
    _DwmSetWindowAttribute = _proto(_dwm, 'DwmSetWindowAttribute',
                                    ctypes.c_long, wintypes.HWND,
                                    wintypes.DWORD, ctypes.c_void_p,
                                    wintypes.DWORD)
    _SystemParametersInfoW = _proto(_user32, 'SystemParametersInfoW',
                                    wintypes.BOOL, wintypes.UINT,
                                    wintypes.UINT, ctypes.c_void_p,
                                    wintypes.UINT)


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


def _work(rc):
    """The work area of the monitor nearest a rectangle - the screen less
    the taskbar - or None."""
    info = MONITORINFO()
    info.cbSize = ctypes.sizeof(MONITORINFO)
    mon = _MonitorFromRect(ctypes.byref(rc), MONITOR_DEFAULTTONEAREST)
    if not mon or not _GetMonitorInfoW(mon, ctypes.byref(info)):
        return None
    return info.rcWork


def _primary_work():
    rc = wintypes.RECT()
    if not _SystemParametersInfoW(SPI_GETWORKAREA, 0, ctypes.byref(rc), 0):
        return None
    return rc


def _units():
    """How many of the pixels the monitor calls report make one of the
    pixels pywebview is given a size in.

    pywebview multiplies the size it is handed by the display scale, so it
    wants pixels as they were at 100%. The monitor calls answer in real
    pixels to a process that is DPI-aware and in 100% pixels to one that is
    not, and which this process is depends on whether pywebview has got to
    declaring it yet - so it is asked rather than assumed.
    """
    try:
        ctx = _user32.GetThreadDpiAwarenessContext
        ctx.restype = ctypes.c_void_p
        of = _user32.GetAwarenessFromDpiAwarenessContext
        of.restype, of.argtypes = ctypes.c_int, [ctypes.c_void_p]
        aware = of(ctx()) != 0
    except (AttributeError, OSError):
        # Older than Windows 10 1607.
        aware = bool(_user32.IsProcessDPIAware())
    if not aware:
        return 1.0
    try:
        # The same number pywebview multiplies by.
        return ctypes.windll.shcore.GetScaleFactorForDevice(0) / 100 or 1.0
    except (AttributeError, OSError):
        return 1.0


def placement(hwnd):
    """Where the window goes when it is put back, as something JSON keeps.

    The rectangle is the restored one in every state, and 'screen' is the
    size of the work area it was on, so that it can be scaled to fit the
    screen that is there next time.
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
    work = _work(rc)
    if not work:
        return None
    return {'left': rc.left, 'top': rc.top, 'right': rc.right,
            'bottom': rc.bottom,
            'screen': [work.right - work.left, work.bottom - work.top]}


def _onscreen(rc):
    """The same rectangle, moved onto a monitor that exists if it is not on
    one - the nearest, and shrunk to its work area if it is bigger."""
    if _MonitorFromRect(ctypes.byref(rc), MONITOR_DEFAULTTONULL):
        return rc
    work = _work(rc)
    if not work:
        return rc
    w = min(rc.right - rc.left, work.right - work.left)
    h = min(rc.bottom - rc.top, work.bottom - work.top)
    left = min(max(rc.left, work.left), work.right - w)
    top = min(max(rc.top, work.top), work.bottom - h)
    return wintypes.RECT(left, top, left + w, top + h)


def _restored(saved):
    """The rectangle placement() saved, scaled to the screen as it is now
    and put on one that exists; or None if there is nothing usable.

    One factor for both sides, taken from the height: the window keeps its
    shape on a screen of a different shape rather than being stretched to
    match it. A placement saved before the screen was kept with it has
    nothing to scale by and is not used - its size is from when the window
    was a fixed number of pixels, which is the thing being got away from.
    """
    if not isinstance(saved, dict):
        return None
    try:
        rc = wintypes.RECT(*(int(saved[k]) for k in
                             ('left', 'top', 'right', 'bottom')))
        was_w, was_h = (int(v) for v in saved['screen'])
    except (KeyError, TypeError, ValueError):
        return None
    if rc.right - rc.left < MIN_W or rc.bottom - rc.top < MIN_H \
            or was_w <= 0 or was_h <= 0:
        return None
    work = _work(rc)
    if work:
        fx = (work.right - work.left) / was_w
        fy = (work.bottom - work.top) / was_h
        if fx != 1 or fy != 1:
            left = work.left + round((rc.left - work.left) * fx)
            top = work.top + round((rc.top - work.top) * fy)
            rc = wintypes.RECT(left, top,
                               left + round((rc.right - rc.left) * fy),
                               top + round((rc.bottom - rc.top) * fy))
    return _onscreen(rc)


def opening(saved, aspect, share, floor):
    """The size to open at and the smallest it may be made, both in
    pywebview's units: ((w, h), (min_w, min_h)), or None off Windows.

    The saved rectangle if there is one, scaled to the screen as it is now.
    Otherwise `share` of the primary screen's height, at `aspect` width to
    height. Never smaller than `floor`, unless the screen is, and never
    bigger than the screen.
    """
    if not _user32:
        return None
    try:
        units = _units()
        rc = _restored(saved)
        work = _work(rc) if rc else _primary_work()
        if not work:
            return None
        ww = (work.right - work.left) / units
        wh = (work.bottom - work.top) / units
        least = (min(floor[0], ww), min(floor[1], wh))
        if rc:
            w = (rc.right - rc.left) / units
            h = (rc.bottom - rc.top) / units
        else:
            h = wh * share
            w = h * aspect
        w = min(max(w, least[0]), ww)
        h = min(max(h, least[1]), wh)
        return (round(w), round(h)), (int(least[0]), int(least[1]))
    except Exception:
        return None


def place(hwnd, saved):
    """Put the window back where placement() said it was."""
    if not alive(hwnd):
        return False
    rc = _restored(saved)
    if not rc:
        return False
    wp = WINDOWPLACEMENT()
    wp.length = ctypes.sizeof(WINDOWPLACEMENT)
    wp.showCmd = SW_SHOWNORMAL
    wp.rcNormalPosition = rc
    return bool(_SetWindowPlacement(hwnd, ctypes.byref(wp)))


# Windows 11 rounds a window's corners only when it has a frame; without one
# it has to be asked. Windows 10 has no such attribute and the call fails
# quietly - square corners there, as every window has.
DWMWA_WINDOW_CORNER_PREFERENCE = 33
DWMWCP_ROUND = 2


def borderless(hwnd):
    """No Windows chrome: no caption, no resize edges, no maximise.

    Minimise and the system menu stay in the style even with no caption to
    show them, because they are what lets the taskbar button minimise the
    window, and Win+Down. Safe to repeat - it runs on every restore, since
    WinForms puts its own idea of the frame back now and then.
    """
    if not alive(hwnd):
        return False
    style = _GetStyle(hwnd, GWL_STYLE)
    want = (style & ~(WS_CAPTION | WS_THICKFRAME | WS_MAXIMIZEBOX))         | WS_SYSMENU | WS_MINIMIZEBOX
    if want != style:
        _SetStyle(hwnd, GWL_STYLE, want)
        _SetWindowPos(hwnd, None, 0, 0, 0, 0,
                      SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
                      | SWP_FRAMECHANGED)
    round_ = wintypes.DWORD(DWMWCP_ROUND)
    _DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
                           ctypes.byref(round_), ctypes.sizeof(round_))
    return True
