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
#
# Images are in the list for the same reason, and were missing from it for a
# while: <img src="app-logo.png"> is neither src="*.js" nor href="*.css", so
# the phone shipped without the mark and drew a broken-image box where the
# desktop draws a logo. Same failure as orb.js, one attribute over.
#
# .svg is deliberately not in the list. The icons are one inline sprite, so
# nothing here loads an svg file - and the only .svg in index.html is
# `other.svg#id`, an example inside the comment explaining why the sprite is
# inline. A pattern that matched it would send this script after a file that
# was never meant to exist. The `#` exclusion keeps fragments out generally,
# which is what makes the completeness check below safe to fail on.
#
# The copy keeps each reference's own directory, so a src="flags/x.png" lands
# under flags/ rather than flattened into the top of the bundle, where the
# page would ask for a path that is not there.
ASSETS='(src|href)="[^"#]+\.(js|css|png|jpe?g|webp|gif|ico|avif)"'
for f in $(grep -oE "$ASSETS" "$SRC/index.html" |
           sed 's/.*="//;s/"$//' | sort -u); do
    mkdir -p "$DEST/$(dirname "$f")"
    cp "$SRC/$f" "$DEST/$f"
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

# The server installer, which the tunnel pane hands over as one pasteable
# line. The desktop reads it off disk next to the app; a phone has no disk
# next to the app, so it travels inside the APK and Kotlin base64s it from
# there. Copied here rather than listed in pubspec by hand for the same
# reason everything else here is: one original, and a script that carries it.
INSTALLER="$HERE/../tunnel/install-server.sh"
if [ -s "$INSTALLER" ]; then
    cp "$INSTALLER" "$HERE/app/assets/install-server.sh"
else
    echo "tunnel/install-server.sh is missing - the tunnel pane will have no command to copy" >&2
    exit 1
fi
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

# And the check that the extension list has not fallen behind the page. Every
# src= and href= naming a file rather than an in-document fragment has to have
# arrived; anything the list does not know about is reported here rather than
# discovered on a phone, which is how app-logo.png went missing for a build.
for f in $(grep -oE '(src|href)="[^"#]+\.[A-Za-z0-9]+"' "$SRC/index.html" |
           sed 's/.*="//;s/"$//' | sort -u); do
    [ -e "$DEST/$f" ] || {
        echo "$f is loaded by index.html and was not copied - add its extension to ASSETS" >&2
        exit 1
    }
done

echo "  ui       $(du -sh "$DEST" | cut -f1) copied from app/ui"
echo "  scripts  $(ls "$DEST"/*.js "$DEST"/*.css 2>/dev/null | xargs -n1 basename | tr '\n' ' ')"
echo "  flags    $(ls "$DEST/flags" | wc -l)"
echo "  server   install-server.sh $(wc -c < "$HERE/app/assets/install-server.sh") bytes"
