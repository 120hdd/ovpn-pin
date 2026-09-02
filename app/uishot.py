"""A picture of our own window, for the checks that want one.

Lifted out of ui_check so the layout audit can take the same photographs
rather than a second implementation of them that drifts.

PrintWindow rather than a screen grab, and the difference is not a detail. A
grab of the rectangle our window occupies captures whatever is actually on
those pixels - and this runs while the person's own windows still have focus,
so the first two versions of this wrote somebody's chat window to disk under
our name. Asking the window to render itself can only ever produce the
window. It also works while it is behind something, which a check running in
the background always is.

PW_RENDERFULLCONTENT, because WebView2 draws through DWM and the plain call
comes back with the page missing.

A caption is not an identity. FindWindowW hands back the first window with
that title, and a built Relay open on the same desktop answers to exactly the
same one - so a check run while somebody has Relay open photographed *their*
window and reported the size it meant to capture rather than the size it got.
Every picture in one such run came back 237x39 and said 600x1110.

Which is why `pid` exists. The app knows which process is meant to be in the
picture and says so; the layout audit drives a separate copy it has no pid
for, and falls back to matching the caption alone - the same behaviour it has
always had, now written down as a choice rather than as the only option.
"""

import ctypes
import ctypes.wintypes
import os

PW_RENDERFULLCONTENT = 2


def find_window(title, pid=None):
    """The top-level window with that caption, owned by `pid` if one is given.

    EnumWindows rather than FindWindowW because FindWindowW cannot be asked
    the second question, and the second question is the one that matters when
    two copies of the app are open.
    """
    mine, found = pid, []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.wintypes.HWND,
                        ctypes.wintypes.LPARAM)
    def each(handle, _):
        length = ctypes.windll.user32.GetWindowTextLengthW(handle)
        if not length:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        ctypes.windll.user32.GetWindowTextW(handle, buf, length + 1)
        if buf.value != title:
            return True
        if mine is not None:
            owner = ctypes.wintypes.DWORD()
            ctypes.windll.user32.GetWindowThreadProcessId(
                handle, ctypes.byref(owner))
            if owner.value != mine:
                return True
        found.append(handle)
        return False

    ctypes.windll.user32.EnumWindows(each, 0)
    return found[0] if found else 0


def shot(name, out, title='Relay', pid=None):
    """Write out/<name>.png. Returns what happened, as a line for the log."""
    try:
        from PIL import Image
        user32, gdi32 = ctypes.windll.user32, ctypes.windll.gdi32
        handle = find_window(title, pid)
        if not handle:
            return ('no picture: our window was not found'
                    if pid is None else
                    'no picture: this process has no window called ' + title)
        rect = ctypes.wintypes.RECT()
        user32.GetWindowRect(handle, ctypes.byref(rect))
        w, h = rect.right - rect.left, rect.bottom - rect.top

        screen = user32.GetDC(0)
        dc = gdi32.CreateCompatibleDC(screen)
        bitmap = gdi32.CreateCompatibleBitmap(screen, w, h)
        gdi32.SelectObject(dc, bitmap)
        drawn = user32.PrintWindow(handle, dc, PW_RENDERFULLCONTENT)

        buf = ctypes.create_string_buffer(w * h * 4)
        info = ctypes.create_string_buffer(40)
        ctypes.memmove(info, int(40).to_bytes(4, 'little')
                       + w.to_bytes(4, 'little')
                       + (-h & 0xFFFFFFFF).to_bytes(4, 'little')
                       + int(1).to_bytes(2, 'little')
                       + int(32).to_bytes(2, 'little')
                       + bytes(20), 40)
        gdi32.GetDIBits(dc, bitmap, 0, h, buf, info, 0)
        image = Image.frombuffer('RGBA', (w, h), buf, 'raw', 'BGRA', 0, 1)

        gdi32.DeleteObject(bitmap)
        gdi32.DeleteDC(dc)
        user32.ReleaseDC(0, screen)

        os.makedirs(out, exist_ok=True)
        path = os.path.join(out, f'{name}.png')
        image.convert('RGB').save(path)
        size = f'{w}x{h}'
        if h < 300:
            # Worth saying rather than leaving as a picture of a title bar: a
            # window this short is a window that was not ready, and the shot
            # is of nothing.
            return f'{path} ({size} - too short to be the window)'
        return f'{path} ({size})' if drawn \
            else f'{path} ({size}, PrintWindow said no)'
    except Exception as e:
        return f'no picture: {e!r}'
