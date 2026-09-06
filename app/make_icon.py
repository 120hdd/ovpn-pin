"""Draw the Relay application icon.

A multi-size .ico, not one image scaled down. Windows picks 16px for the
taskbar and Alt-Tab, 32 for the title bar, 256 for large views, and anything
drawn once at 256 and resampled turns to grey mush at 16 - which is exactly
the size the user sees most often.

The mark is a custom, forward-leaning R. Its broad strokes and hard cuts keep
the motorsport silhouette legible even in the 16px Windows title bar.
"""

import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'assets', 'app.ico')
UI_PNG = os.path.join(HERE, 'ui', 'app-logo.png')

BG = (11, 12, 24, 255)
INK = (248, 248, 253, 255)
VIOLET = (116, 99, 255, 255)
JADE = (74, 229, 195, 255)
FONT = os.path.join(os.environ.get('WINDIR', r'C:\Windows'),
                    'Fonts', 'ariblk.ttf')

SIZES = (16, 24, 32, 48, 64, 128, 256)


def mark(image, s, colour, offset=(0, 0), outline=None):
    """Draw a heavy, forward-leaning display R with an optional keyline."""
    ox, oy = offset
    mask = Image.new('L', (s, s), 0)
    font = ImageFont.truetype(FONT, int(s * 0.88))
    left, top, right, bottom = font.getbbox('R')
    x = (s - (right - left)) / 2 - left - s * 0.035 + ox
    y = s * 0.18 - top + oy
    ImageDraw.Draw(mask).text((x, y), 'R', font=font, fill=255)

    # Shear the top farther forward than the foot for the racing-wordmark
    # posture without making the small icon look off-centre.
    mask = mask.transform(
        (s, s), Image.AFFINE,
        (1, 0.12, -s * 0.12, 0, 1, 0),
        resample=Image.Resampling.BICUBIC,
    )

    if outline:
        keyline = max(3, int(s * 0.031) | 1)
        outer = mask.filter(ImageFilter.MaxFilter(keyline))
        image.paste(Image.new('RGBA', (s, s), outline), (0, 0), outer)

    ink = Image.new('RGBA', (s, s), colour)
    image.paste(ink, (0, 0), mask)


def draw(size):
    # 4x supersampling, then one clean resize. Each ICO size is still drawn
    # independently so the title-bar frame is not a scaled-down 256px blur.
    s = size * 4
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))

    inset = int(s * 0.035)
    bounds = [inset, inset, s - inset - 1, s - inset - 1]
    radius = int(s * 0.235)
    mask = Image.new('L', (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(bounds, radius=radius, fill=255)

    panel = Image.new('RGBA', (s, s), BG)
    glow = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse([-s * 0.28, -s * 0.32, s * 0.83, s * 0.79],
                      fill=VIOLET[:-1] + (220,))
    glow_draw.ellipse([s * 0.40, s * 0.38, s * 1.22, s * 1.18],
                      fill=JADE[:-1] + (145,))
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.17))
    panel = Image.alpha_composite(panel, glow)
    panel.putalpha(mask)
    img.alpha_composite(panel)

    d = ImageDraw.Draw(img)
    d.rounded_rectangle(bounds, radius=radius,
                        outline=(173, 163, 255, 105),
                        width=max(1, int(s * 0.012)))

    shadow = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    mark(shadow, s, (3, 5, 17, 165), offset=(0, s * 0.018))
    shadow = shadow.filter(ImageFilter.GaussianBlur(s * 0.018))
    img.alpha_composite(shadow)

    mark(img, s, INK, outline=(7, 8, 17, 235))

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
    preview = draw(256)
    preview.save(png)
    preview.save(UI_PNG)
    print(f'{OUT}  ({os.path.getsize(OUT)} bytes, sizes {SIZES})')
    print(f'{png}')
    print(f'{UI_PNG}')


if __name__ == '__main__':
    main()
