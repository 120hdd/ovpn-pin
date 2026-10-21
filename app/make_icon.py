"""Draw the Relay application icon.

A multi-size .ico, not one image scaled down. Windows picks 16px for the
taskbar and Alt-Tab, 32 for small views, 256 for large ones, and anything
drawn once at 256 and resampled turns to grey mush at 16 - which is exactly
the size the user sees most often.

The mark is the one the Android app has: a disc, the ring around it open
across the upper right, and the bead that has left through the opening.
Drawn here on the same 256 grid as android/.../res/drawable/ic_launcher_fg.xml
and ui/Logo.kt, so the two apps' icons are one drawing, not two that drift.
"""

import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'assets', 'app.ico')
PNG = os.path.join(HERE, 'assets', 'app.png')

PANEL = (11, 13, 20, 255)
DISC = (244, 246, 250, 255)
RING = (97, 239, 191, 255)

# The grid. The ring runs clockwise from 10 degrees above three o'clock to
# 10 degrees right of twelve; the bead sits on the opening's bisector, a
# little outside the ring.
GRID = 256
DISC_R = 56
RING_R, RING_W = 100, 20
RING_FROM, RING_TO = -10, 280
BEAD_R, BEAD_ANGLE, BEAD_OUT = 16, -45, 132

SIZES = (16, 24, 32, 48, 64, 128, 256)


def mark(d, s, inset):
    """The mark, in a square of side s, `inset` in from each edge."""
    k = (s - 2 * inset) / GRID
    cx = cy = s / 2

    def at(angle, radius):
        a = math.radians(angle)
        return cx + radius * k * math.cos(a), cy + radius * k * math.sin(a)

    def dot(x, y, r, fill):
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)

    dot(cx, cy, DISC_R * k, DISC)
    r, w = RING_R * k, RING_W * k
    d.arc([cx - r - w / 2, cy - r - w / 2, cx + r + w / 2, cy + r + w / 2],
          RING_FROM, RING_TO, fill=RING, width=max(1, round(w)))
    # PIL's arc has square ends; the mark's are round.
    for end in (RING_FROM, RING_TO):
        dot(*at(end, RING_R), w / 2, RING)
    dot(*at(BEAD_ANGLE, BEAD_OUT), BEAD_R * k, RING)


def draw(size):
    # 4x supersampling, then one clean resize, each size on its own.
    s = size * 4
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=PANEL)
    # Below 32px the panel's margin is pixels the mark cannot spare.
    mark(d, s, inset=s * (0.10 if size <= 24 else 0.15))
    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    frames = [draw(n) for n in SIZES]
    # Saved from the LARGEST frame. Pillow writes the image it is called on
    # plus whatever is appended, so calling save() on the 16px one produces a
    # 16px-only icon - which looks fine in the taskbar and like a smudge
    # everywhere else.
    frames[-1].save(OUT, format='ICO',
                    sizes=[(n, n) for n in SIZES],
                    append_images=frames[:-1])
    draw(256).save(PNG)
    print(f'{OUT}  ({os.path.getsize(OUT)} bytes, sizes {SIZES})')
    print(PNG)


if __name__ == '__main__':
    main()
