"""Pack app/ui/world-data.js into android/app/src/main/assets/world.bin.

The desktop reads the world as a JavaScript object (window.WORLD); the phone
reads this instead, because parsing 840 KB of JSON-in-JS at start-up is real
time and memory on a phone and the same data fits in a third of that as
binary. Run it again whenever app/make_world.py regenerates the data.

Layout, all little-endian (the loader is orb-free: world/WorldData.kt):

  "RLW1"                      magic + version
  u32 rings, u32 points, u32 countries
  rings x u32                 points in each ring
  points x (i16 lon, i16 lat) hundredths of a degree, rings back to back
  countries x {
      2 bytes  iso2, lower case
      u8 n, n bytes   name, UTF-8
      4 x f32  box: lon0, lat0, lon1, lat1
      2 x f32  mid: lon, lat
      u16 k, k x u16  indexes of the country's rings
  }

The source has two decimals everywhere, so hundredths lose nothing; the
script refuses to write the file if that stops being true.
"""
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "app", "ui", "world-data.js")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "world.bin")


def main():
    text = open(SRC, encoding="utf-8").read()
    world = json.loads(text[text.index("{"):text.rindex("}") + 1])
    rings, by, names, box, mid = (world[k] for k in ("rings", "by", "name", "box", "mid"))

    out = bytearray(b"RLW1")
    out += struct.pack("<III", len(rings), sum(len(r) // 2 for r in rings), len(by))
    for r in rings:
        out += struct.pack("<I", len(r) // 2)
    for r in rings:
        q = [round(v * 100) for v in r]
        if any(abs(v * 100 - c) > 1e-6 or not -32768 <= c <= 32767 for v, c in zip(r, q)):
            sys.exit("a ring has more than two decimals; widen the format")
        out += struct.pack("<%dh" % len(q), *q)
    for code in sorted(by):
        name = names[code].encode("utf-8")
        out += code.encode("ascii") + struct.pack("<B", len(name)) + name
        out += struct.pack("<4f", *box[code]) + struct.pack("<2f", *mid[code])
        out += struct.pack("<H", len(by[code])) + struct.pack("<%dH" % len(by[code]), *by[code])

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(out)
    print("%s: %d rings, %d countries, %d bytes" % (os.path.relpath(OUT), len(rings), len(by), len(out)))


main()
