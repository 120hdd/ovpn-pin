"""Draw the application icon.

A multi-size .ico, not one image scaled down. Windows picks 16px for the
taskbar and Alt-Tab, 32 for the title bar, 256 for large views, and anything
drawn once at 256 and resampled turns to grey mush at 16 - which is exactly
the size the user sees most often.

The mark is two points and the line between them: where you are, where you
come out. It survives 16px because at that size it is three shapes.
"""

import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'assets', 'app.ico')

BG = (17, 17, 19, 255)        # slate-1, the app's own background
JADE = (31, 216, 164, 255)    # jade-11, the connected colour
DIM = (119, 123, 132, 255)    # slate-10

SIZES = (16, 24, 32, 48, 64, 128, 256)


def draw(size):
    # 4x supersampling, then one clean resize. Pillow has no antialiased
    # primitives, so this is how the diagonal stops looking like a staircase.
    s = size * 4
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    radius = int(s * 0.22)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=BG)

    # The two points sit on a diagonal, inset far enough that the rounded
    # corners never crowd them.
    pad = s * 0.30
    a = (pad, s - pad)              # here, lower left
    b = (s - pad, pad)              # there, upper right

    line_w = max(1, int(s * 0.055))
    d.line([a, b], fill=DIM, width=line_w)

    r_small = s * 0.075
    r_big = s * 0.115
    d.ellipse([a[0] - r_small, a[1] - r_small, a[0] + r_small, a[1] + r_small],
              fill=DIM)
    d.ellipse([b[0] - r_big, b[1] - r_big, b[0] + r_big, b[1] + r_big],
              fill=JADE)

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
    png = os.path.join(HERE, 'assets', 'app.png')
    draw(256).save(png)
    print(f'{OUT}  ({os.path.getsize(OUT)} bytes, sizes {SIZES})')
    print(f'{png}')


if __name__ == '__main__':
    main()
