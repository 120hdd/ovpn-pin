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


def _prefs(p):
    p.click('#settings', 1.0)


def _shut_prefs(p):
    p.do("document.getElementById('prefs').close()")


def _log(p):
    p.click('#logOpen', 0.6)


def _shut_log(p):
    p.do("document.getElementById('log').close()")


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
    ('prefs', _prefs, _shut_prefs),
    ('log', _log, _shut_log),
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
