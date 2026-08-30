---
name: ui-check
description: Test Relay's window - drive it, photograph it, and measure whether anything is laid out wrong (alignment, clipping, overlap, things off the edge). Use when changing app/ui/*, when asked to check the app's UI, layout, alignment or spacing, when asked to look at or screenshot a screen, or before claiming a UI change works.
---

# Testing Relay's UI

The UI is one page (`app/ui/index.html`, `app.js`, `app.css`) in a pywebview
window over WebView2, talking to the `Api` class in `app/main.py`. It cannot
be tested in a browser: there is no `window.pywebview.api` outside the app,
and half the page is drawn from what the backend answers.

So it is tested by running the real app and driving the real window. There
are two tools, and they answer different questions.

| | asks | run |
|---|---|---|
| `--ui-layout` | is anything laid out wrong | `python app/main.py --ui-layout` |
| `--ui-check` | does everything still do what it did | `python app/main.py --ui-check` |

Both open the window, drive it, close it, and leave pictures behind. Neither
connects to anything: a check that opened a real tunnel would move the
person's Windows proxy, and photographs are not worth that.

## Checking the layout

```bash
python app/main.py --ui-layout > /tmp/layout.txt 2>&1   # ~90s, then read it
python app/main.py --ui-layout picker                   # one scene, by name
python app/main.py --ui-layout --notes                  # notes in full too
```

**Redirect to a file rather than piping to `head`** - closing the pipe kills
the run partway. The full report is `.state/ui-layout.json` and the pictures
are `.state/ui-layout/<scene>.png`, one per state.

Read the output top down. The first line is the one that matters:

```
  self-test              all 7 kinds of fault caught
```

That is the audit being handed a deliberately broken page and having to name
every fault in it. If it says `BROKEN - did not catch: ...`, every "clean"
below it is meaningless and `app/uiaudit.js` is what to fix.

Then one line per state, `fails` expanded and `notes` rolled up:

- **fails** are things that are never intentional. Fix them or explain why
  they are not real.
- **notes** are worth knowing and not necessarily worth doing: a name
  ellipsised to fit, an 18px button. `--notes` prints them.

### What it can find

In `app/uiaudit.js`, and all of it measured from where the browser actually
put things:

| kind | what it means |
|---|---|
| `edge-off` | two stacked siblings whose edges are 0.75-4px apart. Lined up or clearly not; never 2px. **This is the one screenshots cannot show you.** |
| `mid-off` | a row that says `align-items: center` and then does not centre |
| `column-off` | the same control down a list of rows, in a different place in one of them. Every row is right on its own and the list is wrong - the other thing a screenshot hides. Only asked of fixed-width things that sit in the same position in every row |
| `overlap` | two siblings in flow sitting on the same pixels |
| `gap-uneven` | identical siblings with one gap unlike the others |
| `clipped-x/y` | text in a box too small for it (`fail` when nothing says so, `note` when ellipsised) |
| `outside-box` | text pushed outside the thing that clips it - not shortened, gone |
| `offscreen-x/y` | outside the window, with nothing scrolling to it |
| `collapsed` | has words, has no size |
| `tiny-target` | interactive and under 24px (always a note) |

It cannot tell you whether the design is any good. That is what the pictures
are for - open `.state/ui-layout/<scene>.png` when something reads oddly.

### Adding a state

A sheet nobody audits is a sheet that drifts. In `app/uilayout.py`, add to
`SCENES`: a name, an `enter` that gets there, a `leave` that puts it back.
Settings screens need nothing - they are read off the menu at runtime.

If you add a fault kind to `uiaudit.js`, add a way for the fixture in
`PROOF` to trigger it and its name to `WANTED`. A check that cannot fail is
not a check.

## Checking behaviour

`--ui-check` drives the same window through the picker, the settings stack,
the account panes and the sorts, and writes 141 observations plus 22
pictures to `.state/ui-check.json` and `.state/ui-check/`. It records what
it saw rather than judging it, so it is read rather than trusted: look for
`errors` and `errorsAfter` (both must be `[]`), `checkFailed` (must be
absent), then the keys near whatever you changed.

It is one long function in `main.py` and everything after a throw is
silently missing, so `checkFailed` means the run is partial, not merely
imperfect.

## Rules that cost something to learn

- **Never open a dialog and read a value in the same `evaluate_js`.**
  `showModal()` inside a synchronous call deadlocks WebView2's message loop
  and the window never comes back. `Page.do()` appends `;null` for this
  reason; `Page.ask()` is for reading. Two calls, with a sleep between them.
- **Photographs are `PrintWindow`, never a screen grab** (`app/uishot.py`).
  A grab of our rectangle catches whatever is actually on those pixels -
  early versions wrote somebody's chat window to disk under our name.
- **Put back what you changed.** A scene that leaves `state.mode` or a
  status line set makes every state after it a lie.
- **Stub the backend rather than reaching it** when the assertion is about
  the page. `--ui-check` replaces `api.connect` before clicking a country
  and restores it afterwards; copy that shape.
- **The window is ~384x621 inside.** Long place names are the usual way
  layout breaks, which is what the `main-crowded` scene is for.
- Console output is cp1252 on Windows and the page is full of characters it
  cannot encode. Print through `_out()`.
