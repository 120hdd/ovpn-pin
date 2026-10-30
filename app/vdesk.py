"""Put this process's window on another of Windows' virtual desktops.

For the UI checks. They open a real window and photograph it, and on the
desktop someone is working at, that window keeps landing on top of what
they are doing. Set RELAY_TEST_DESKTOP to a desktop's number, counting
from 1 as Task View does, and a check runs over there instead:

    set RELAY_TEST_DESKTOP=2
    python app/main.py --ui-check

Windows only lets a process move its own windows between desktops - asked
from outside, MoveWindowToDesktop answers access denied - so the app does
it to itself, before the first picture. There is no comtypes here, so the
one COM call is made by hand through its vtable.
"""

import ctypes
import ctypes.wintypes
import os
import uuid
import winreg

ENV = 'RELAY_TEST_DESKTOP'

_CLSID_MANAGER = uuid.UUID('aa509086-5ca9-4c25-8f95-589d3c07b48a')
_IID_MANAGER = uuid.UUID('a5cd92ff-29be-454c-8d04-d82879fb3f1b')
_CLSCTX_ALL = 0x17
# IVirtualDesktopManager's methods, after IUnknown's three.
_RELEASE = 2
_MOVE_WINDOW_TO_DESKTOP = 5


class _GUID(ctypes.Structure):
    _fields_ = [('Data1', ctypes.c_ulong), ('Data2', ctypes.c_ushort),
                ('Data3', ctypes.c_ushort), ('Data4', ctypes.c_ubyte * 8)]

    @classmethod
    def of(cls, u):
        g = cls()
        ctypes.memmove(ctypes.byref(g), u.bytes_le, 16)
        return g


def wanted():
    """The desktop number the environment asks for, or None."""
    try:
        n = int(os.environ.get(ENV, ''))
    except ValueError:
        return None
    return n if n >= 1 else None


def desktops():
    """The virtual desktops' ids, in the order Task View shows them."""
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                        r'Software\Microsoft\Windows\CurrentVersion'
                        r'\Explorer\VirtualDesktops') as key:
        raw, _ = winreg.QueryValueEx(key, 'VirtualDesktopIDs')
    return [uuid.UUID(bytes_le=bytes(raw[i:i + 16]))
            for i in range(0, len(raw) - 15, 16)]


def move(handle, number):
    """Move window `handle` to virtual desktop `number`. Returns a line for
    the log: what happened, or why not."""
    try:
        ids = desktops()
    except OSError:
        return 'no virtual desktops to move to'
    if not 1 <= number <= len(ids):
        return f'no desktop {number}: there are {len(ids)}'
    ole32 = ctypes.windll.ole32
    ole32.CoInitialize(None)
    manager = ctypes.c_void_p()
    hr = ole32.CoCreateInstance(ctypes.byref(_GUID.of(_CLSID_MANAGER)), None,
                                _CLSCTX_ALL, ctypes.byref(_GUID.of(_IID_MANAGER)),
                                ctypes.byref(manager))
    if hr != 0 or not manager:
        return f'no desktop manager: 0x{hr & 0xFFFFFFFF:08x}'
    vtable = ctypes.cast(manager, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    try:
        call = ctypes.WINFUNCTYPE(ctypes.HRESULT, ctypes.c_void_p,
                                  ctypes.wintypes.HWND, ctypes.POINTER(_GUID))(
            vtable[_MOVE_WINDOW_TO_DESKTOP])
        target = _GUID.of(ids[number - 1])
        try:
            call(manager, handle, ctypes.byref(target))
        except OSError as e:
            return f'not moved to desktop {number}: {e}'
        return f'on desktop {number}'
    finally:
        ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtable[_RELEASE])(manager)
