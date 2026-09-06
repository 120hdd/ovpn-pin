"""Relay - how smoothly the window actually moves, measured rather than felt.

    python app/main.py --ui-perf

uilayout asks whether anything is laid out wrong. This asks the other
question people notice first: does it move without stuttering. Both drive the
real window, because a page rendered anywhere else is not the page - WebView2
composites through DWM and the blurs, the WebGL sphere and the staggered
entries all cost what they cost only here.

What it measures
----------------
Frame gaps, from inside the page. A rAF callback is scheduled every frame the
compositor produces, so the spacing between calls is the frame time as the
page experienced it. At 60Hz a clean frame is 16.7ms; anything past 33ms is a
frame the compositor missed and a stutter somebody can see.

Reported as the worst frame and the count over 33ms rather than as an
average. Averages hide exactly what is being looked for: a scene can average
17ms and still have one 300ms hitch in it, and the hitch is the whole
complaint.

What each scene does
--------------------
The sheets, opened and closed the way a person opens and closes them, and the
settings screens walked in turn - because that is the motion the app has most
of, and the entry animations are staggered per pane, which is where a queue
of them was costing three quarters of a second.

A scene is only as honest as its settling time. Each one waits for its own
animation to finish before the recorder stops, so a sheet whose motion is
cheap and whose *content* is expensive is not scored on the cheap half.
"""

import json
import os
import time

import paths

OUT = os.path.join(paths.STATE_DIR, 'ui-perf')

SETTLE = 0.35

# Injected once per scene. Records the gap between compositor frames and
# hands back the shape of the run rather than every sample - four hundred
# numbers across a python bridge is the measurement disturbing itself.
RECORDER = r"""
window.__perf = {
  start() {
    this.gaps = [];
    this.last = 0;
    this.on = true;
    const tick = (now) => {
      if (!this.on) return;
      if (this.last) this.gaps.push(now - this.last);
      this.last = now;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
    return true;
  },
  stop() {
    this.on = false;
    const g = this.gaps.slice().sort((a, b) => a - b);
    if (!g.length) return { frames: 0 };
    const at = (p) => g[Math.min(g.length - 1, Math.floor(g.length * p))];
    return {
      frames: g.length,
      median: +at(0.5).toFixed(1),
      p95: +at(0.95).toFixed(1),
      worst: +g[g.length - 1].toFixed(1),
      janky: g.filter((x) => x > 33).length,
    };
  },
};
null
"""


def _scenes(p):
    """What to do, in the order a person would do it.

    Each entry is a name and a function that performs the motion and waits
    for it. The waits are part of the measurement: stopping the recorder
    while a sheet is still animating scores the frames it managed and none of
    the ones it dropped.
    """
    def click(sel, wait=SETTLE):
        p.evaluate_js(f"(document.querySelector({sel!r})||{{}}).click?.()")
        time.sleep(wait)

    def screens():
        return p.evaluate_js(
            "Array.from(document.querySelectorAll('#prefsMenu .menu__row'))"
            ".map(r => r.dataset.goto)") or []

    def open_prefs():
        click('#settings', 0.6)

    def scene_picker():
        click('#pick', 0.6)
        click('#pickerClose', 0.6)

    def scene_prefs():
        open_prefs()
        click('#prefsClose', 0.6)

    def scene_screens():
        open_prefs()
        for slug in screens():
            click(f'#prefsMenu [data-goto="{slug}"]', 0.55)
            click('#prefsBack', 0.45)
        click('#prefsClose', 0.6)

    def scene_scroll():
        """The list, scrolled. Ninety rows with a flag bled into each one is
        the heaviest thing this app draws, and scrolling it is the one motion
        nobody can be distracted from."""
        click('#pick', 0.6)
        p.evaluate_js(
            "(() => { const l = document.getElementById('list');"
            " let y = 0; const step = () => { y += 40; l.scrollTop = y;"
            " if (y < 1600) requestAnimationFrame(step); }; step(); })()")
        time.sleep(1.4)
        click('#pickerClose', 0.6)

    def scene_idle():
        """Nothing pressed. What the window costs while it is only sitting
        there is the floor everything else is measured against."""
        time.sleep(1.2)

    def scene_picker_again():
        """The same motion a second time.

        Split from the first because they are different questions. The first
        open pays for whatever the list has not done yet - rasterising
        seventy-five flags, resolving three hundred sprite references - and
        that cost is paid once per launch. Every open after it is the one
        somebody does forty times an evening, and it is the one that has to
        be clean."""
        click('#pick', 0.6)
        click('#pickerClose', 0.6)

    def scene_rebuild():
        """Rebuilding the list in place, which is what finishing a test does:
        every row can change order, so the whole thing is thrown away and
        built again while somebody is looking at it."""
        click('#pick', 0.8)
        p.evaluate_js(
            "(() => { document.getElementById('list').dataset.key = '';"
            " drawList(''); })()")
        time.sleep(0.7)
        click('#pickerClose', 0.6)

    return [('idle', scene_idle),
            ('picker, first open', scene_picker),
            ('picker, opened again', scene_picker_again),
            ('rebuilding the list', scene_rebuild),
            ('settings open and close', scene_prefs),
            ('every settings screen', scene_screens),
            ('scrolling the list', scene_scroll)]


def _out(line=''):
    """Printed, unless nobody is listening. The usual way to read this is
    `| tail`, and a closed pipe would otherwise kill the run mid-table -
    taking the window down with it before the report is written."""
    try:
        print(line, flush=True)
    except Exception:
        pass


def run(window, only=None):
    os.makedirs(OUT, exist_ok=True)
    report = {}
    try:
        # The page has to have finished booting, or the first scene measures
        # a window that is still filling itself in and blames the animation.
        time.sleep(3.0)

        for name, act in _scenes(window):
            if only and only not in name:
                continue
            window.evaluate_js(RECORDER)
            window.evaluate_js('window.__perf.start()')
            try:
                act()
            except Exception as e:
                report[name] = {'error': str(e)}
                continue
            report[name] = window.evaluate_js('window.__perf.stop()') or {}

        _out()
        _out(f'{"scene":26} {"frames":>7} {"median":>7} {"p95":>7} '
             f'{"worst":>7} {"over 33ms":>10}')
        for name, got in report.items():
            if 'error' in got:
                _out(f'{name:26} {got["error"][:44]}')
                continue
            _out(f'{name:26} {got.get("frames", 0):7} '
                 f'{got.get("median", 0):7} {got.get("p95", 0):7} '
                 f'{got.get("worst", 0):7} {got.get("janky", 0):10}')

        bad = [k for k, v in report.items() if (v.get('janky') or 0) > 2]
        _out()
        _out('clean.' if not bad else 'stutters in: ' + ', '.join(bad))
    finally:
        # Written and the window closed whatever happened, the same as the
        # layout audit - this is a test, and a test that leaves a window on
        # somebody's desktop is one they have to go and tidy up after.
        path = os.path.join(OUT, 'perf.json')
        try:
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(report, f, indent=1)
            _out(f'written to {path}')
        except Exception:
            pass
        try:
            window.destroy()
        except Exception:
            pass
    return report
