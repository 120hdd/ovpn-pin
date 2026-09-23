"""Taking an exit out of the list, and putting it back.

The sweep used to do the first half on its own. A config that failed to answer
during a re-test was deleted from every folder that claimed it worked - and
because the window hands the same folder in as both the source and the
destination, that meant every measuring run quietly pruned the pool it was
measuring. Nothing said so and nothing could undo it.

Two things are wrong with that, and they are separable. Deleting without being
asked is one; deleting at all is the other. The sweep no longer does either
(see -Prune), and what is here is the deliberate version: the person is shown
what would go, ticks the folders it should go from, and the files are moved to
dropped/ rather than removed. An exit can fail one thirty-second window and
answer perfectly a minute later - Engine._second_look is a whole function about
that - so a verdict is never quite final enough to destroy anything on.

The layout is a folder per source folder, named after it, plus an index in
.state/dropped.json saying where each file actually came from. The folder is
what holds the data and would be enough on its own to put things back by hand;
the index is what makes it exact when two source folders share a basename, or
when one of them has since been renamed.
"""

import json
import os
import shutil
import time

import paths
import sweep


def _index():
    try:
        with open(paths.DROPPED_INDEX, encoding='utf-8') as f:
            got = json.load(f)
        return got if isinstance(got, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_index(rows):
    os.makedirs(paths.STATE_DIR, exist_ok=True)
    tmp = f'{paths.DROPPED_INDEX}.{os.getpid()}'
    try:
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(rows, f, indent=1)
        os.replace(tmp, paths.DROPPED_INDEX)
    except OSError:
        pass


def tag_for(folder):
    """The name of a source folder's shelf inside dropped/.

    Its own basename where that is free, and the parent's name in front of it
    where it is not - sitetest/www-reddit-com and a top-level www-reddit-com
    would otherwise share a shelf, and putting one back would put the other
    back with it. The index carries the real path either way; this only has to
    be stable and readable.
    """
    folder = os.path.normpath(folder)
    base = os.path.basename(folder) or 'folder'
    parent = os.path.basename(os.path.dirname(folder))
    if parent and parent not in ('', os.path.basename(paths.DATA_DIR)):
        return f'{parent}-{base}'
    return base


def label_for(folder):
    """What to call a folder on a chip. The path relative to the data folder
    where it is inside it, and the whole path where it is not - somebody who
    pointed the app at a folder of their own should see which one."""
    folder = os.path.normpath(folder)
    try:
        rel = os.path.relpath(folder, paths.DATA_DIR)
    except ValueError:
        return folder
    return folder if rel.startswith('..') else rel.replace(os.sep, '/')


def copies_of(folder, bases):
    """The files in one folder whose base name is in `bases`.

    Base names, not filenames: the same config is `03.2s-de-fra…` in success\\
    and `de-fra…` in pinned\\, and the whole point of asking about a folder is
    to find the copy it holds rather than the one that was tested.
    """
    try:
        names = os.listdir(folder)
    except OSError:
        return []
    return sorted(n for n in names
                  if n.endswith('.ovpn') and sweep.base_name(n) in bases)


def folders_holding(bases, roots):
    """Every folder worth offering, given a set of dead configs.

    The folders the exits were read from, the landlord shelves inside them,
    and each per-site folder - which is the same list Remove-Successful went
    through, except that here it is a set of choices rather than something
    that happens to you.
    """
    seen, out = set(), []
    candidates = []
    for root in roots:
        candidates.append(root)
        candidates.append(os.path.join(root, 'landlord'))
        candidates.append(os.path.join(root, 'landlord', 'fastest'))
    site = sweep.sitetest_dir()
    try:
        for name in sorted(os.listdir(site)):
            path = os.path.join(site, name)
            if os.path.isdir(path):
                candidates.append(path)
    except OSError:
        pass

    for folder in candidates:
        key = os.path.normcase(os.path.abspath(folder))
        if key in seen or not os.path.isdir(folder):
            continue
        seen.add(key)
        files = copies_of(folder, bases)
        if files:
            out.append({'path': os.path.abspath(folder),
                        'label': label_for(folder),
                        'tag': tag_for(folder),
                        'count': len(files),
                        'files': files})
    return out


def put_aside(folder, files, why=None):
    """Move these files out of that folder and onto its shelf.

    Overwriting is refused rather than resolved: a file already on the shelf
    under the same name is the same config set aside earlier, and quietly
    replacing it would lose whichever copy the person meant to keep. It counts
    as already put aside, which is what it is.
    """
    tag = tag_for(folder)
    shelf = os.path.join(paths.dropped_dir(), tag)
    os.makedirs(shelf, exist_ok=True)
    rows = _index()
    moved, failed = [], []
    for name in files:
        src = os.path.join(folder, name)
        dest = os.path.join(shelf, name)
        if os.path.exists(dest):
            failed.append(name)
            continue
        try:
            shutil.move(src, dest)
        except OSError:
            failed.append(name)
            continue
        rows[f'{tag}/{name}'] = {'from': os.path.abspath(folder),
                                 'file': name,
                                 'at': int(time.time()),
                                 # Keyed by base name: the same config is
                                 # "03.2s-de-fra..." in one folder and
                                 # "de-fra..." in the next.
                                 'why': (why or {}).get(sweep.base_name(name),
                                                        '')}
        moved.append(name)
    _save_index(rows)
    return moved, failed


def shelved():
    """What is on the shelves now, by tag, with where each row came from.

    Read off the folder rather than out of the index, so a file put there by
    hand is still offered and an index that has lost a row does not strand
    one. The index only supplies the origin; without it the row still shows,
    it just cannot say where to put it back.
    """
    rows = _index()
    out = {}
    root = paths.dropped_dir()
    try:
        tags = sorted(os.listdir(root))
    except OSError:
        return out
    for tag in tags:
        shelf = os.path.join(root, tag)
        if not os.path.isdir(shelf):
            continue
        try:
            names = sorted(n for n in os.listdir(shelf) if n.endswith('.ovpn'))
        except OSError:
            continue
        if not names:
            continue
        known = rows.get(f'{tag}/{names[0]}') or {}
        out[tag] = {'tag': tag,
                    'count': len(names),
                    'files': names,
                    'from': known.get('from', ''),
                    'label': label_for(known['from']) if known.get('from')
                    else tag}
    return out


def put_back(tags=None):
    """Move them home. Everything, or only the shelves named.

    A file whose origin the index has forgotten goes back to the folder its
    shelf is named after if that folder exists, and stays put if it does not -
    guessing a path and moving somebody's config to it is worse than leaving
    it somewhere they can see.
    """
    rows = _index()
    back, stuck = [], []
    root = paths.dropped_dir()
    for tag, shelf in sorted(shelved().items()):
        if tags and tag not in tags:
            continue
        home = shelf['from'] or os.path.join(paths.DATA_DIR, tag)
        if not os.path.isdir(home):
            try:
                os.makedirs(home, exist_ok=True)
            except OSError:
                stuck.extend(shelf['files'])
                continue
        for name in shelf['files']:
            src = os.path.join(root, tag, name)
            dest = os.path.join(home, name)
            if os.path.exists(dest):
                # Already back - a sweep re-filed it, or it was copied in by
                # hand. Take it off the shelf rather than leaving a duplicate
                # that would be offered for restoring forever.
                try:
                    os.remove(src)
                    rows.pop(f'{tag}/{name}', None)
                    back.append(name)
                except OSError:
                    stuck.append(name)
                continue
            try:
                shutil.move(src, dest)
            except OSError:
                stuck.append(name)
                continue
            rows.pop(f'{tag}/{name}', None)
            back.append(name)
        try:
            os.rmdir(os.path.join(root, tag))
        except OSError:
            pass
    _save_index(rows)
    try:
        os.rmdir(root)
    except OSError:
        pass
    return back, stuck
