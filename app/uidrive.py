"""Drive the two new sheets through what a person actually does with them.

uilayout answers "is anything in the wrong place" and ui-check answers "does
everything still do what it did". Neither answers the question these two
sheets raise, which is whether the buttons move the right files: a set-aside
that took the wrong folder would photograph perfectly.

So this one presses them for real, against a throwaway copy of the tree, and
checks the folders afterwards. Run with:

    python app/main.py --ui-drive

Nothing here connects and nothing here touches the real folders - RELAY_DATA
points the whole app at a temporary tree built by _fixture(), which is thrown
away at the end.
"""

import os
import shutil
import sys
import time

import paths
import uilayout

SETTLE = 0.45


def _out(line=''):
    sys.stdout.write(line.encode('ascii', 'replace').decode('ascii') + '\n')
    sys.stdout.flush()


class Checks:
    def __init__(self):
        self.rows = []

    def is_(self, label, got, want):
        ok = got == want
        self.rows.append((ok, label, got, want))
        _out(f"  {'ok  ' if ok else 'FAIL'}  {label:<44} "
             + (f'{got}' if ok else f'{got!r}, wanted {want!r}'))
        return ok

    def true(self, label, got):
        return self.is_(label, bool(got), True)

    def done(self):
        bad = [r for r in self.rows if not r[0]]
        _out(f"\nUI-DRIVE: {len(self.rows) - len(bad)} of {len(self.rows)} "
             f"checks passed")
        return not bad


def _files(*rel):
    p = os.path.join(paths.DATA_DIR, *rel)
    try:
        return sorted(f for f in os.listdir(p) if f.endswith('.ovpn'))
    except OSError:
        return []


def run(window):
    p = uilayout.Page(window)
    c = Checks()
    time.sleep(2.0)
    _out(f"\nUI-DRIVE - {time.strftime('%H:%M:%S')}")
    _out(f"  tree: {paths.DATA_DIR}\n")

    try:
        _drive(p, c)
    except Exception as e:
        import traceback
        _out(f"  DRIVE FAILED: {e!r}")
        traceback.print_exc()
        c.rows.append((False, 'run completed', 'threw', 'finished'))
    ok = c.done()
    window.destroy()
    sys.exit(0 if ok else 1)


def _drive(p, c):
    before_success = _files('success')
    before_pinned = _files('pinned')
    _out(f"  success/ {len(before_success)}   pinned/ {len(before_pinned)}\n")

    # -- the header mark ---------------------------------------------------
    p.do('refreshDead()', 1.2)
    c.true('header button is showing',
           p.ask("!document.getElementById('dropOpen').hidden"))
    c.is_('and says how many stopped answering',
          p.ask("document.getElementById('dropOpen').dataset.n"), '3')

    # -- the sheet, opened ------------------------------------------------
    p.click('#dropOpen', 1.2)
    c.true('the sheet is open',
           p.ask("document.getElementById('dropDlg').open"))
    c.true('nothing is ticked to begin with',
           p.ask("document.querySelectorAll("
                 "'#dropFolders .chip[aria-pressed=\"true\"]').length") == 0)
    c.true('and the action is refused until something is',
           p.ask("document.getElementById('dropGo').disabled"))
    c.is_('both folders holding a copy are offered',
          p.ask("document.querySelectorAll('#dropFolders .chip').length"), 2)
    # Opening it must not have moved anything. This is the whole promise.
    c.is_('opening it removed nothing', _files('success'), before_success)

    # -- tick success/, leave pinned/ alone -------------------------------
    p.do("(() => { for (const b of document.querySelectorAll("
         "'#dropFolders .chip')) {"
         " if (b.textContent.startsWith('success')) b.click(); } })()", 0.6)
    c.is_('ticking success shows its three rows',
          p.ask("document.querySelectorAll('#dropList .drop__row').length"), 3)
    c.true('and the action is allowed now',
           not p.ask("document.getElementById('dropGo').disabled"))

    # -- set aside --------------------------------------------------------
    p.click('#dropGo', 2.0)
    after_success = _files('success')
    c.is_('three left success/', len(after_success), len(before_success) - 3)
    c.is_('pinned/ was not ticked and is untouched',
          _files('pinned'), before_pinned)
    c.is_('they are on the shelf', len(_files('dropped', 'success')), 3)

    # -- put back ---------------------------------------------------------
    c.true('the put-back pane appeared',
           not p.ask("document.getElementById('dropBack').hidden"))
    p.click('#dropRestore', 2.0)
    c.is_('everything came back', _files('success'), before_success)
    c.is_('and the shelf is empty', _files('dropped', 'success'), [])
    p.click('#dropClose', 0.6)

    # -- the source picker ------------------------------------------------
    p.click('#pick', 1.0)
    everything = p.ask("document.getElementById('listCount').textContent")
    c.true('the list head names the source',
           p.ask("document.getElementById('sourceName').textContent")
           == 'Everything')
    c.is_('and is not marked as filtered',
          p.ask("document.getElementById('sourcePick').dataset.narrow"), '0')

    p.click('#sourcePick', 1.0)
    c.true('the source sheet is open',
           p.ask("document.getElementById('srcDlg').open"))
    rows = p.ask("Array.from(document.querySelectorAll("
                 "'#srcRows .menu__row')).map(r => r.dataset.source)")
    c.true('Everything and Pinned are both offered',
           'all' in (rows or []) and 'pinned' in (rows or []))

    p.do("document.querySelector("
         "'#srcRows .menu__row[data-source=\"pinned\"]').click()", 2.2)
    c.true('choosing one closes the sheet',
           not p.ask("document.getElementById('srcDlg').open"))
    c.is_('the head says which pool is on',
          p.ask("document.getElementById('sourceName').textContent"), 'Pinned')
    c.is_('and is lit, because a filter is on',
          p.ask("document.getElementById('sourcePick').dataset.narrow"), '1')
    # listCount is places, not exits - the line has always read "All
    # locations, 99". The pool really did change; what proves it is the
    # source sheet's own numbers, which the head is now drawn from.
    c.is_('the source sheet counts places the way the head does',
          p.ask("(() => { const s = state.sources;"
                " const r = s.rows.find(x => x.key === s.source);"
                " return String(r.count); })()"),
          p.ask("document.getElementById('listCount').textContent"))
    c.true('and says how many exits are behind them',
           p.ask("(() => { const s = state.sources;"
                 " return s.rows.find(x => x.key === 'pinned').exits; })()")
           == 8)

    # Back where we found it, or every run after this one starts somewhere
    # else - and the settings file would keep it.
    p.click('#sourcePick', 1.0)
    p.do("document.querySelector("
         "'#srcRows .menu__row[data-source=\"all\"]').click()", 2.2)
    c.is_('and switching back restores the whole list',
          p.ask("document.getElementById('listCount').textContent"), everything)


def fixture():
    """A throwaway tree with three dead exits in it.

    Built before the app boots, because paths.DATA_DIR is read at import time
    by half the modules. main.py calls this from the --ui-drive branch.
    """
    import json
    import re
    import tempfile

    real = paths.DATA_DIR
    root = tempfile.mkdtemp(prefix='relay-drive-')
    for rel in ('pinned', 'success', '.state'):
        os.makedirs(os.path.join(root, rel), exist_ok=True)

    src = os.path.join(real, 'success')
    names = sorted(f for f in os.listdir(src) if f.endswith('.ovpn'))[:8]
    for n in names:
        shutil.copy(os.path.join(src, n), os.path.join(root, 'success', n))
        base = re.sub(r'^\d{1,3}(?:\.\d+)?s-', '', n)
        shutil.copy(os.path.join(src, n), os.path.join(root, 'pinned', base))

    # Three refused, the rest fine. `ok: null` on one of them on purpose:
    # that is what a second look leaves when it could not judge, and it must
    # not be counted as dead.
    reach = {}
    for i, n in enumerate(names):
        if i < 3:
            reach[n] = {'ok': False, 'ip': f'10.0.0.{i}', 'at': int(time.time()),
                        'why': 'timed out'}
        elif i == 3:
            reach[n] = {'ok': None, 'ip': f'10.0.0.{i}', 'at': int(time.time())}
        else:
            reach[n] = {'ok': True, 'ms': 90, 'ip': f'10.0.0.{i}'}
    with open(os.path.join(root, '.state', 'reach.json'), 'w',
              encoding='utf-8') as f:
        json.dump(reach, f)

    # The credentials, or the window opens on the sign-in sheet instead of
    # the app. Copied rather than invented so the roster reads as set up.
    for name in ('.ovpn-auth', '.windscribe-auth'):
        got = os.path.join(real, name)
        if os.path.isfile(got):
            shutil.copy(got, os.path.join(root, name))
    for name in ('accounts.json',):
        got = os.path.join(real, '.state', name)
        if os.path.isfile(got):
            shutil.copy(got, os.path.join(root, '.state', name))
    return root
