#!/usr/bin/env bash
# Copy the desktop's window into the phone's assets.
#
# A copy rather than a second implementation, and a copy made by a script
# rather than by hand, because the alternative is two designs that agree on
# the day they are written and drift every day after. app/ui is the original;
# everything here is downstream of it.
#
# Run it after any change to app/ui, and before building the app.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$(cd "$HERE/../app/ui" && pwd)"
DEST="$HERE/app/assets/ui"

rm -rf "$DEST"
mkdir -p "$DEST"

# Exactly what index.html loads, read out of index.html rather than listed
# here by hand. Guessing that list is how the phone came to draw a different
# orb from the desktop: the page loads sphere.js, the guess said orb.js, and
# the result looked close enough to be believed for a whole build.
for f in $(grep -oE 'src="[^"]+\.js"|href="[^"]+\.css"' "$SRC/index.html" |
           sed 's/.*="//;s/"$//' | sort -u); do
    cp "$SRC/$f" "$DEST/"
done
cp "$SRC"/index.html "$DEST/"
cp -r "$SRC"/fonts "$SRC"/flags "$DEST/"
cp "$SRC"/*LICENSE*.txt "$DEST/" 2>/dev/null || true

# The phone's own two files, added to the copy rather than to the original.
# app/ui/index.html is never edited; the copy of it is, here, so the desktop
# cannot inherit a phone's opinion by accident.
#
# Both tags go on the line they replace rather than on a new one. A newline in
# a sed replacement is a portability argument this script does not need to
# have, and HTML does not care.
cp "$HERE/app/assets/phone.css" "$HERE/app/assets/phone.js" "$DEST/"
sed -i 's|</head>|<link rel="stylesheet" href="phone.css"></head>|' "$DEST/index.html"
sed -i 's|<script src="app.js"></script>|<script src="app.js"></script><script src="phone.js"></script>|' \
    "$DEST/index.html"

# Checked rather than assumed. Both edits match on markup that upstream is free
# to reword, and a phone silently running without its own stylesheet looks like
# a layout bug rather than like a missing file.
grep -q 'phone\.css' "$DEST/index.html" || {
    echo "phone.css was not linked - index.html no longer has a </head> to hook" >&2
    exit 1
}
grep -q 'phone\.js' "$DEST/index.html" || {
    echo "phone.js was not linked - index.html no longer loads app.js the same way" >&2
    exit 1
}

echo "  ui       $(du -sh "$DEST" | cut -f1) copied from app/ui"
echo "  scripts  $(ls "$DEST"/*.js "$DEST"/*.css 2>/dev/null | xargs -n1 basename | tr '\n' ' ')"
echo "  flags    $(ls "$DEST/flags" | wc -l)"
