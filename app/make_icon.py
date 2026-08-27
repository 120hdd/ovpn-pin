"""Draw the Relay application icon.

A multi-size .ico, not one image scaled down. Windows picks 16px for the
taskbar and Alt-Tab, 32 for the title bar, 256 for large views, and anything
drawn once at 256 and resampled turns to grey mush at 16 - which is exactly
the size the user sees most often.

The mark is a custom R with a jade exit node. It reads as Relay at desktop
sizes, but its stem, bowl and forward leg also remain distinct at 16px.
"""

import os

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'assets', 'app.ico')

BG = (11, 12, 24, 255)
INK = (248, 248, 253, 255)
VIOLET = (116, 99, 255, 255)
JADE = (74, 229, 195, 255)

SIZES = (16, 24, 32, 48, 64, 128, 256)


def cubic(a, b, c, d, steps=24):
    """Sample a cubic curve for Pillow's antialiased line renderer."""
    points = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        points.append((
            u ** 3 * a[0] + 3 * u ** 2 * t * b[0] +
            3 * u * t ** 2 * c[0] + t ** 3 * d[0],
            u ** 3 * a[1] + 3 * u ** 2 * t * b[1] +
            3 * u * t ** 2 * c[1] + t ** 3 * d[1],
        ))
    return points


def mark(drawable, s, colour, offset=(0, 0)):
    """Draw the geometric R; explicit round caps keep the 16px frame crisp."""
    ox, oy = offset
    point = lambda x, y: (s * x + ox, s * y + oy)
    width = max(2, int(s * 0.082))
    radius = width / 2

    stem_a = point(0.315, 0.255)
    stem_b = point(0.315, 0.755)
    bowl = cubic(
        point(0.315, 0.275), point(0.57, 0.215),
        point(0.705, 0.285), point(0.69, 0.39),
    )
    bowl += cubic(
        point(0.69, 0.39), point(0.69, 0.49),
        point(0.55, 0.53), point(0.315, 0.50),
    )[1:]
    leg_a = point(0.49, 0.50)
    leg_b = point(0.705, 0.755)

    drawable.line([stem_a, stem_b], fill=colour, width=width)
    drawable.line(bowl, fill=colour, width=width, joint='curve')
    drawable.line([leg_a, leg_b], fill=colour, width=width)

    for x, y in (stem_a, stem_b, bowl[0], bowl[-1], leg_b):
        drawable.ellipse([x - radius, y - radius, x + radius, y + radius],
                         fill=colour)


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
    mark(ImageDraw.Draw(shadow), s, (3, 5, 17, 165),
         offset=(0, s * 0.018))
    shadow = shadow.filter(ImageFilter.GaussianBlur(s * 0.018))
    img.alpha_composite(shadow)

    mark(d, s, INK)

    # The coloured terminal makes the forward leg feel like a relayed exit,
    # and remains a useful recognition cue in the 16px Windows title bar.
    node_x, node_y = s * 0.705, s * 0.755
    node_r = s * 0.062
    d.ellipse([node_x - node_r, node_y - node_r,
               node_x + node_r, node_y + node_r], fill=JADE)
    shine_r = node_r * 0.24
    d.ellipse([node_x - node_r * 0.35 - shine_r,
               node_y - node_r * 0.35 - shine_r,
               node_x - node_r * 0.35 + shine_r,
               node_y - node_r * 0.35 + shine_r],
              fill=(220, 255, 247, 210))

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
