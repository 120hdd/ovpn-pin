"""Relay — every state the window has, measured rather than looked at.

    python app/main.py --ui-layout            all of it
    python app/main.py --ui-layout picker     one scene, by name

ui_check drives the app and writes down what it saw, leaving a person to
decide whether 141 recorded values are right. This asks a narrower question
that has an answer: is anything laid out wrong. It opens each sheet in turn,
runs uiaudit.js over whatever is on screen, and prints the faults - so a
clean run is four lines rather than ten kilobytes of JSON to read.

What it can find is in uiaudit.js. What it cannot is whether the design is
any good, so the pictures go to .state\\ui-layout\\ beside the report and are
worth opening when something reads oddly.

Adding a scene is the point: a new sheet nobody audits is a new sheet that
drifts. One entry in SCENES, `enter` to get there and `leave` to put it back,
and the settings screens do not even need that - they are read off the menu
at runtime, so a screen added there is audited the day it appears.
"""

import json
import os
import sys
import time

import paths
import uishot

OUT = os.path.join(paths.STATE_DIR, 'ui-layout')

# Long enough for a sheet's opening animation (167ms) and the paint after it.
# The list is drawn from data already in hand, so this is about the frame
# rather than the network - except where a scene says otherwise.
SETTLE = 0.45


class Page:
    """The window, with the two rules that drive it written into the API.

    `do` is for anything that opens a dialog and `ask` is for reading a
    value back, and they are never the same call: showModal() inside a
    synchronous evaluate_js whose result is waited on deadlocks WebView2's
    message loop, and the window simply never comes back. The `;null` is
    what makes that explicit at every call site rather than a thing you have
    to remember.
    """

    def __init__(self, window):
        self.w = window

    def do(self, code, wait=SETTLE):
        self.w.evaluate_js(code + ';null')
        time.sleep(wait)

    def ask(self, code):
        return self.w.evaluate_js(code)

    def click(self, sel, wait=SETTLE):
        self.do(f"(document.querySelector({sel!r})||{{click(){{}}}}).click()",
                wait)


# ---------------------------------------------------------------------------
# The states. Each is (name, enter, leave); either may be None.
#
# Nothing here connects. The look of a connected window is set on the page -
# opening a real tunnel to take a photograph would move the person's system
# proxy, and a check has no business doing that.
# ---------------------------------------------------------------------------

def _idle(p):
    p.do("state.mode = 'off'; render();"
         "setStatus('DISCONNECTED', 'off', '', '')")


def _busy(p):
    p.do("document.getElementById('act').dataset.mode = 'busy';"
         "setStatus('CONNECTING', 'busy', 'Frankfurt, Germany', '')")


def _connected(p):
    p.do("document.getElementById('act').dataset.mode = 'on';"
         "document.getElementById('hero').dataset.state = 'on';"
         "document.getElementById('swap').dataset.state = 'on';"
         "document.getElementById('card').dataset.state = 'on';"
         "setStatus('CONNECTED', 'on', 'Frankfurt, Germany',"
         "          '146.70.160.237:1443');"
         "document.getElementById('exitIp').textContent = '146.70.160.237';"
         "document.getElementById('exitPlace').textContent = 'Frankfurt, DE'")


def _crowded(p):
    """The same window with the longest text it will ever hold.

    Layout that is fine on 'Germany' and broken on the real name of a place
    is the common case, and the only way to see it is to put the long name
    in. These are real: the countries list has every one of them.
    """
    p.do("document.getElementById('act').dataset.mode = 'on';"
         "setStatus('CONNECTED', 'on',"
         "          'South Georgia and the South Sandwich Islands',"
         "          'de-fra-v046.prod.surfshark.com:1443');"
         "document.getElementById('pickLabel').textContent ="
         "  'United Kingdom of Great Britain and Northern Ireland';"
         "document.getElementById('realPlace').textContent ="
         "  'Saint Helena, Ascension and Tristan da Cunha';"
         "document.getElementById('exitPlace').textContent ="
         "  'Bandar Seri Begawan, Brunei Darussalam';"
         "document.getElementById('realIp').textContent = '203.0.113.199';"
         "document.getElementById('exitIp').textContent = '198.51.100.242'")


def _failed(p):
    """The window after a connection that could not be made.

    The failure hint is the longest run of text the main window ever shows -
    longer than any status line, and it arrives under a card that is already
    full - and until this scene existed nothing had ever measured it. The
    port-taken one is the longest of them, so it is the one asked.
    """
    p.do("window.onFailed({kind: 'port-taken', port: 8877,"
         " detail: 'cannot listen on 127.0.0.1:8877'})")


def _details(p):
    p.click('#more')


def _picker(p):
    p.click('#pick', 0.8)


def _shut_picker(p):
    p.do("document.getElementById('picker').close()")


def _search(p):
    p.do("document.getElementById('search').value = 'net'; drawList('net')")


def _unsearch(p):
    p.do("document.getElementById('search').value = ''; drawList('')")


def _scrolled(p):
    """The list, part-way down.

    Worth its own scene because the audit only measures what is on screen,
    and `content-visibility: auto` means the rows below the fold are not
    laid out at all until they are scrolled to. Auditing only the top of the
    list is auditing the six rows that were never in doubt - and the rows a
    person actually complains about are the ones they had to scroll to.
    """
    # The list is built a screenful at a time, so scrolling the instant the
    # sheet opens scrolls an empty box and the scene measures nothing while
    # reporting itself clean. Wait for rows, then move.
    for _ in range(20):
        if (p.ask("document.querySelectorAll('#list .row').length") or 0) > 20:
            break
        time.sleep(0.15)
    p.do("(() => { const l = document.getElementById('list');"
         " l.scrollTop = Math.round(l.scrollHeight / 3); })()", 0.9)


def _unscroll(p):
    p.do("document.getElementById('list').scrollTop = 0")


def _exits(p):
    p.do("(() => { const m = document.querySelector('[data-exits]');"
         " if (m) m.click(); })()", 1.2)


def _sorted(p):
    """The sort strip on a choice that has no data behind it.

    Two of the three sorts read a figure the list may not have - nothing has
    a time until a test is run, and only some providers say how loaded a
    server is - so pressing them puts the note underneath into a sentence
    the other states never show. It is also the only state where the lit
    backing under the icons is anywhere but its resting place, which is the
    part that was broken: it was measured at zero width and stayed there.
    """
    for _ in range(20):
        if (p.ask("document.querySelectorAll('#list .row').length") or 0) > 20:
            break
        time.sleep(0.15)
    p.do("document.querySelector('#sortBy [data-sort=\"load\"]').click()", 0.7)


def _unsorted(p):
    p.do("document.querySelector('#sortBy [data-sort=\"ping\"]').click()", 0.5)


def _prefs(p):
    p.click('#settings', 1.0)


def _shut_prefs(p):
    p.do("document.getElementById('prefs').close()")


def _prefs_working(p):
    """The settings sheet with something in flight.

    Its own state because it is one: a button that is turning, a note with
    the light passing along it, a pill saying it does not know yet, and the
    floor running amber behind all of it. Nothing about that layout is
    implied by the resting screen, and a busy state nobody photographs is a
    busy state that drifts.

    Driven through the page's own busy() and said() rather than by setting
    the attributes by hand, so that a scene which stops matching the app
    fails here instead of quietly photographing something the app can no
    longer produce.
    """
    p.click('#settings', 1.0)
    p.click('.menu__row[data-goto="connection"]', 0.5)
    p.do("document.getElementById('prefsBody').scrollTop = 720", 0.2)
    p.do("busy(document.getElementById('tunnelTest'), true);"
         "said(document.getElementById('tunnelSaid'),"
         "     'Starting the client and asking the server…', 'work');"
         "var pill = document.getElementById('tunnelPill');"
         "pill.dataset.state = 'work'; pill.textContent = 'asking'", 0.5)


def _shut_prefs_working(p):
    p.do("busy(document.getElementById('tunnelTest'), false);"
         "said(document.getElementById('tunnelSaid'), '');"
         # Put the pill back from where it came, rather than from a guess:
         # paintTunnel on an empty object would leave it reading "no client"
         # for every state after this one.
         "if (state.tunnel) paintTunnel(state.tunnel);"
         "showScreen(null);"
         "document.getElementById('prefs').close()")


def _log(p):
    p.click('#logOpen', 0.6)


def _shut_log(p):
    p.do("document.getElementById('log').close()")


def _source(p):
    """The pools to connect out of, opened off the head of the list.

    Reached through the picker, because that is where it lives - the line it
    hangs off is the one counting the list, and there is no list without the
    picker open.
    """
    _picker(p)
    p.click('#sourcePick', 0.8)


def _shut_source(p):
    p.do("document.getElementById('srcDlg').close()")
    _shut_picker(p)


def _dropped(p):
    """The set-aside sheet, with a folder ticked and rows under it.

    Stubbed rather than reached, for the reason the skill gives: the
    assertion is about the page. It is also the only way to have this state
    at all on a copy where everything answers - the sheet is empty then, and
    the part that can be laid out wrong is the list of rows, which only
    exists when something has failed. So a plausible answer is handed to it:
    two folders, a long place name, a long reason, and a count wide enough to
    make a column out of.

    Ticked on purpose. Unticked, the list is one sentence where the rows go.
    """
    p.do("""(() => {
      window.pywebview.api.deadExits = async () => ({
        ok: true,
        folders: [
          { path: 'C:/x/pinned', label: 'pinned', tag: 'pinned', count: 131 },
          { path: 'C:/x/success', label: 'success', tag: 'success', count: 20 },
        ],
        exits: [
          { file: 'a.ovpn', folder: 'C:/x/pinned', tag: 'pinned',
            country: 'ru', city: 'Saint Petersburg', provider: 'windscribe',
            ip: '146.70.253.162', at: Math.floor(Date.now() / 1000) - 5400,
            why: 'filtered here - TCP answers, TLS gets nothing back' },
          { file: 'b.ovpn', folder: 'C:/x/pinned', tag: 'pinned',
            country: 'kr', city: 'Seoul', provider: 'surfshark',
            ip: '61.97.243.105', at: Math.floor(Date.now() / 1000) - 5400,
            why: 'timed out' },
          { file: 'c.ovpn', folder: 'C:/x/pinned', tag: 'pinned',
            country: 'br', city: 'Sao Paulo', provider: 'windscribe',
            ip: '188.95.54.55', at: Math.floor(Date.now() / 1000) - 5400,
            why: 'no proxy for this account' },
        ],
        dead: 151, shelved: [], aside: 0,
      });
    })()""")
    p.do("(() => { const b = document.getElementById('dropOpen');"
         " b.hidden = false; b.dataset.n = '151'; })()")
    p.click('#dropOpen', 1.2)
    p.do("(() => { const c = document.querySelector('#dropFolders .chip');"
         " if (c) c.click(); })()", 0.5)


def _shut_dropped(p):
    p.do("document.getElementById('dropDlg').close()")


SCENES = [
    ('main', None, None),
    ('main-busy', _busy, _idle),
    ('main-on', _connected, _idle),
    ('main-on-details', lambda p: (_connected(p), _details(p)),
     lambda p: (_details(p), _idle(p))),
    # Left dirty on purpose: the next scene reloads the page, and the long
    # names are worth having in the picture this one leaves behind.
    ('main-failed', _failed, _idle),
    ('main-crowded', _crowded, None),
    ('picker', _picker, _shut_picker),
    ('picker-search', lambda p: (_picker(p), _search(p)),
     lambda p: (_unsearch(p), _shut_picker(p))),
    ('picker-scrolled', lambda p: (_picker(p), _scrolled(p)),
     lambda p: (_unscroll(p), _shut_picker(p))),
    ('picker-exits', lambda p: (_picker(p), _exits(p)), _shut_picker),
    ('picker-sorted', lambda p: (_picker(p), _sorted(p)),
     lambda p: (_unsorted(p), _shut_picker(p))),
    ('prefs', _prefs, _shut_prefs),
    ('prefs-working', _prefs_working, _shut_prefs_working),
    ('log', _log, _shut_log),
    ('source', _source, _shut_source),
    ('dropped', _dropped, _shut_dropped),
]


# ---------------------------------------------------------------------------
# Proof that the audit can fail.
#
# A checker that reports "clean" is either good news or broken, and from the
# outside those look identical - which is how a suite ends up green for a
# year while measuring nothing. So before it is allowed to say the app is
# clean, it is handed a page that is deliberately wrong in one of each way
# and has to name every one of them.
#
# Built rather than borrowed from the app, so it does not quietly start
# passing because a button moved.
# ---------------------------------------------------------------------------

PROOF = """
(function () {
  const old = document.getElementById('__proof');
  if (old) old.remove();
  const d = document.createElement('div');
  d.id = '__proof';
  d.style.cssText =
    'position:fixed;left:0;top:0;width:200px;background:#000;z-index:9999';
  d.innerHTML =
    // a 1px indent nobody chose: edge-off
    '<div style="height:20px">aaa</div>' +
    '<div style="height:20px;margin-left:1px">bbb</div>' +
    // pulled up into the one above it: overlap, and gaps that differ
    '<div style="height:20px;margin-top:-10px">ccc</div>' +
    // words in a box too small for them, with nothing to say so: clipped-x
    '<div style="width:30px;overflow:hidden;white-space:nowrap">' +
    'a label far too long for this</div>' +
    // out of the window entirely: offscreen-x
    '<div style="margin-left:300px;width:200px;height:20px">ddd</div>' +
    // text in a box with no size at all: collapsed
    '<div style="width:0;height:0;overflow:hidden">unseeable</div>' +
    // a row that says centre and then does not: mid-off
    '<div style="display:flex;align-items:center;height:40px">' +
    '<span style="height:20px">L</span>' +
    '<span style="height:20px;margin-top:2px">R</span></div>' +
    // three rows ending in the same control, one of which sits elsewhere:
    // column-off. Each row is right on its own, which is the point.
    '<div id="__proofcol">' +
    '<div style="display:flex"><span style="flex:1;height:12px">x</span>' +
    '<i class="pcol" style="display:block;width:20px;height:12px"></i></div>' +
    '<div style="display:flex"><span style="flex:1;height:12px">x</span>' +
    '<i class="pcol" style="display:block;width:20px;height:12px"></i></div>' +
    '<div style="display:flex"><span style="flex:1;height:12px">x</span>' +
    '<i class="pcol" style="display:block;width:20px;height:12px;' +
    'margin-right:6px"></i></div></div>';
  document.body.appendChild(d);
})()
"""

# What the fixture above is wrong in. Every one of these has to come back.
WANTED = ['edge-off', 'overlap', 'gap-uneven', 'clipped-x',
          'offscreen-x', 'collapsed', 'mid-off', 'column-off']


def _prove(p, auditor):
    """Returns (ok, said). Run before anything else is believed."""
    p.w.evaluate_js(auditor + ';null')
    p.do(PROOF, 0.3)
    got = p.w.evaluate_js("window.__audit({scope: '#__proof'})") or {}
    p.do("document.getElementById('__proof').remove()", 0.1)
    found = {f['kind'] for f in got.get('faults', [])}
    missed = [k for k in WANTED if k not in found]
    return not missed, missed


def _settings_screens(p):
    """The settings screens, read off the menu rather than listed here.

    A screen added to the menu is audited from the moment it exists, which
    is the difference between a check that keeps up and one that is a year
    out of date and believed anyway.
    """
    return p.ask("Array.from(document.querySelectorAll('.menu__row[data-goto]'))"
                 ".map(r => r.dataset.goto)") or []


def _out(line):
    """Say it, whatever the console can hold.

    The window is full of characters a Windows console in cp1252 cannot
    encode - the app's own status glyphs among them - and a report that dies
    with UnicodeEncodeError halfway through is worse than one that prints a
    question mark, because it takes the states after it down with it.
    """
    try:
        print(line, flush=True)
    except UnicodeEncodeError:
        enc = getattr(sys.stdout, 'encoding', None) or 'ascii'
        print(line.encode(enc, 'replace').decode(enc, 'replace'), flush=True)


def _line(f):
    at = f['at']
    text = f.get('text')
    return (f"      {f['kind']:<12} {at}\n"
            f"                   {f['note']}"
            + (f"\n                   says: {text}" if text else ''))


def run(window, only=None, verbose=False):
    """Walk the states, audit each, print what is wrong."""
    os.makedirs(OUT, exist_ok=True)
    p = Page(window)
    report = {}

    with open(os.path.join(paths.APP_DIR, 'uiaudit.js'), encoding='utf-8') as f:
        auditor = f.read()

    # The page has to have finished booting, or the first scene measures a
    # window that is still empty and calls it clean.
    time.sleep(2.5)
    try:
        import ctypes
        handle = ctypes.windll.user32.FindWindowW(None, 'Relay')
        ctypes.windll.user32.SetForegroundWindow(handle)
        time.sleep(0.4)
    except Exception:
        pass

    def look(name):
        """Inject, measure, photograph. Injected every time rather than once:
        it costs a millisecond and it survives the page being reloaded."""
        p.w.evaluate_js(auditor + ';null')
        got = p.w.evaluate_js('window.__audit({})') or {}
        got['shot'] = uishot.shot(name, OUT)
        report[name] = got
        return got

    def visit(name, enter, leave):
        if only and only not in name:
            return
        try:
            if enter:
                enter(p)
            got = look(name)
            kind = ('clean' if not got.get('fails') and not got.get('notes')
                    else f"{got.get('fails', 0)} fails, {got.get('notes', 0)} notes")
            _out(f"  {name:<22} {got.get('measured', 0):>4} boxes   {kind}")
            all_of = got.get('faults', [])
            for fault in all_of:
                if fault.get('sev') == 'fail' or verbose:
                    _out(_line(fault))
            # Notes rolled up unless asked for. They are things worth knowing
            # and not things to do - an 18px button and a name shortened to
            # fit are both deliberate - and printed in full every run they
            # are what teaches a person to skip the whole report.
            rolled = {}
            for f in all_of:
                if f.get('sev') == 'note' and not verbose:
                    rolled[f['kind']] = rolled.get(f['kind'], 0) + 1
            if rolled:
                _out('      notes: ' + ', '.join(
                    f'{n}x {k}' for k, n in sorted(rolled.items()))
                    + '   (--notes to see them)')
        except Exception as e:
            report[name] = {'error': repr(e)}
            _out(f"  {name:<22}   FAILED TO REACH: {e!r}")
        finally:
            try:
                if leave:
                    leave(p)
            except Exception:
                pass

    ok = False
    try:
        _out(f"\nLAYOUT - {p.ask('innerWidth')}x{p.ask('innerHeight')}, "
             f"{time.strftime('%H:%M:%S')}\n")

        ok, missed = _prove(p, auditor)
        report['__self-test'] = {'ok': ok, 'missed': missed}
        _out(f"  {'self-test':<22} "
             + (f"all {len(WANTED)} kinds of fault caught"
                if ok else f"BROKEN - did not catch: {', '.join(missed)}"))

        for name, enter, leave in SCENES:
            visit(name, enter, leave)

        # The settings screens, one at a time, from inside the sheet.
        if not only or 'prefs' in only or 'settings' in only:
            try:
                _prefs(p)
                for slug in _settings_screens(p):
                    def enter(pp, slug=slug):
                        pp.click(f'.menu__row[data-goto="{slug}"]')

                    def leave(pp):
                        pp.click('#prefsBack')
                    visit('prefs-' + slug, enter, leave)
            except Exception as e:
                _out(f"  settings screens   FAILED: {e!r}")
            finally:
                _shut_prefs(p)

        states = {k: v for k, v in report.items() if not k.startswith('__')}
        fails = sum(r.get('fails', 0) for r in states.values())
        notes = sum(r.get('notes', 0) for r in states.values())
        clean = sum(1 for r in states.values()
                    if not r.get('fails') and not r.get('notes'))
        _out(f"\nLAYOUT: {fails} fails, {notes} notes across {len(states)} "
             f"states ({clean} clean). Pictures in {OUT}")
        if not ok:
            # Said last, where it cannot be missed: everything above this
            # line is worth nothing if the audit has stopped being able to
            # see.
            _out("  ...but the self-test failed, so a state reported clean "
                 "above means nothing. Fix uiaudit.js first.")
    finally:
        # In a finally because the usual way to read this is `| head`, and a
        # closed pipe kills the run mid-sentence. The file is what the next
        # question gets asked of, so it is written whatever happened.
        path = os.path.join(paths.STATE_DIR, 'ui-layout.json')
        try:
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(report, f, ensure_ascii=False, indent=2)
            _out(f"Full report: {path}")
        except Exception:
            pass
        try:
            window.destroy()
        except Exception:
            pass
