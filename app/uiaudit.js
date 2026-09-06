/* Relay — the layout audit.
 *
 * Injected into the real page by ui-layout.py, run against whatever is on
 * screen, and asked one question: is anything laid out wrong. Not "does it
 * look nice" — that is a person's job — but the small set of faults that are
 * never intentional and that a screenshot is bad at showing:
 *
 *   a label cut off, an element off the edge of a 400px window, two boxes
 *   sitting on top of each other, and edges that are almost but not quite
 *   lined up.
 *
 * That last one is the reason this exists. A 12px indent is a decision. A
 * 1px one is a mistake, and it is invisible in a picture and obvious in the
 * numbers, so the numbers are what we read.
 *
 * Everything here is measured, never guessed from the stylesheet: the
 * question is where the browser actually put things, at this window size,
 * with this text in it.
 */

window.__audit = (function () {
  'use strict';

  /* How wrong a thing has to be before it counts.
   *
   * NEAR is the band that makes an alignment fault. Below it are sub-pixel
   * artefacts the layout engine produces honestly - flex dividing 368px
   * three ways leaves thirds, and thirds are not a bug. Above it is a gap
   * somebody chose. Between the two there is nothing but mistakes. */
  var NEAR_MIN = 0.75;
  var NEAR_MAX = 4;
  var SLACK = 1;          // what counts as "over the edge" rather than rounding
  var TARGET = 24;        // Fluent's smallest comfortable hit area

  function px(n) { return Math.round(n * 100) / 100; }

  /* A name a person can find the element by. The nearest id above it, then
   * the way down to it - because `.row__name` on its own appears 140 times
   * and tells nobody anything. */
  function name(el) {
    var hops = [];
    var at = el;
    while (at && at !== document.body && hops.length < 4) {
      if (at.id) { hops.unshift('#' + at.id); break; }
      var cls = at.getAttribute && (at.getAttribute('class') || '');
      cls = cls.trim().split(/\s+/).filter(Boolean).slice(0, 2).join('.');
      hops.unshift(at.tagName.toLowerCase() + (cls ? '.' + cls : ''));
      at = at.parentElement;
    }
    return hops.join(' > ');
  }

  function says(el) {
    var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
    return t.length > 60 ? t.slice(0, 57) + '...' : t;
  }

  /* Own text, not the subtree's - for asking whether an empty-looking box is
   * actually empty or has words in it that nobody can see. */
  function ownText(el) {
    var out = '';
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 3) out += n.nodeValue;
    }
    return out.replace(/\s+/g, ' ').trim();
  }

  function scrolls(cs) {
    return cs.overflowY === 'auto' || cs.overflowY === 'scroll'
        || cs.overflowX === 'auto' || cs.overflowX === 'scroll';
  }

  function hidesOverflow(v) { return v === 'hidden' || v === 'clip'; }

  /* Does anything above this element scroll? If so, being below the fold is
   * where content is supposed to go, not a fault. */
  function underAScroller(el, boxes) {
    var at = el.parentElement;
    while (at && at !== document.documentElement) {
      var b = boxes.get(at);
      if (b && scrolls(b.cs) && (at.scrollHeight > at.clientHeight + SLACK
                                 || at.scrollWidth > at.clientWidth + SLACK)) {
        return true;
      }
      at = at.parentElement;
    }
    return false;
  }

  /* The nearest thing above this element that will cut it off, with its
   * inner box worked out in the same coordinates as everything else: the
   * padding box, which is what clipping actually respects, rather than the
   * border box getBoundingClientRect hands back. */
  function clipper(el, boxes) {
    var at = el.parentElement;
    while (at && at !== document.documentElement) {
      var b = boxes.get(at);
      if (!b) { at = at.parentElement; continue; }
      var cs = b.cs;
      if (cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
        var left = b.rect.left + parseFloat(cs.borderLeftWidth || 0);
        var top = b.rect.top + parseFloat(cs.borderTopWidth || 0);
        return {el: at, cs: cs, box: {
          left: left, top: top,
          right: left + at.clientWidth, bottom: top + at.clientHeight
        }};
      }
      at = at.parentElement;
    }
    return null;
  }

  var SKIP_TAGS = {SCRIPT: 1, STYLE: 1, SVG: 1, SYMBOL: 1, DEFS: 1,
                   CANVAS: 1, TEMPLATE: 1, BR: 1, HR: 1};

  return function audit(opts) {
    opts = opts || {};
    var faults = [];
    var boxes = new Map();      // el -> {rect, cs}
    var kids = new Map();       // parent el -> [el]
    var seen = 0;

    function fault(kind, sev, el, note, extra) {
      var f = {kind: kind, sev: sev, at: name(el), note: note};
      if (extra) for (var k in extra) f[k] = extra[k];
      faults.push(f);
    }

    /* A modal takes the page over: everything behind it is inert and dimmed,
     * and measuring it would report the same faults again under every sheet
     * that opens. Whatever is on top is what is being looked at. */
    var up = [].slice.call(document.querySelectorAll('dialog[open]'));
    var modal = up.filter(function (d) { return d.matches(':modal'); });
    var open = (modal.length ? modal : up).pop();
    var root = opts.scope ? document.querySelector(opts.scope)
                          : (open || document.body);
    if (!root) return {error: 'no such scope: ' + opts.scope};

    var vw = window.innerWidth, vh = window.innerHeight;

    /* ---- 1. walk what is actually on screen ------------------------- */
    (function walk(el) {
      for (var i = 0; i < el.children.length; i++) {
        var c = el.children[i];
        if (SKIP_TAGS[c.tagName]) continue;
        if (c.hidden || c.getAttribute('aria-hidden') === 'true') continue;
        if (c.classList.contains('sr')) continue;

        var cs = getComputedStyle(c);
        if (cs.display === 'none' || cs.visibility === 'hidden'
            || +cs.opacity < 0.05) continue;

        var r = c.getBoundingClientRect();
        if (r.width < 0.5 || r.height < 0.5) {
          /* A box with no size is only worth reporting if it had something
           * to say. An empty spacer is not a fault. */
          if (ownText(c)) {
            fault('collapsed', 'fail', c,
                  'has text but is ' + px(r.width) + 'x' + px(r.height),
                  {text: says(c)});
          }
          continue;
        }

        seen++;
        boxes.set(c, {rect: r, cs: cs});
        if (!kids.has(el)) kids.set(el, []);
        kids.get(el).push(c);
        walk(c);
      }
    })(root);

    /* ---- 2. one element at a time ----------------------------------- */
    boxes.forEach(function (b, el) {
      var r = b.rect, cs = b.cs;

      /* Clipping, but only where there are words to lose.
       *
       * The first version asked every box and drowned the report: the search
       * field alone is four stacked layers whose whole job is to hold a
       * gradient bigger than themselves and show a slice of it. A decorative
       * box that clips is a decorative box working. Text that clips is a
       * sentence the user cannot finish reading, and that is the fault this
       * is looking for - so it asks elements that carry their own text. */
      var mine = ownText(el);
      if (mine) {
        /* Sideways. With an ellipsis this is the design working; without one
         * the words simply stop, and nothing on screen admits it. */
        if (el.clientWidth > 0 && el.scrollWidth > el.clientWidth + SLACK
            && hidesOverflow(cs.overflowX)) {
          var ell = cs.textOverflow === 'ellipsis';
          fault('clipped-x', ell ? 'note' : 'fail', el,
                'content is ' + el.scrollWidth + 'px in a ' + el.clientWidth
                + 'px box' + (ell ? ', ellipsised' : ', cut with no ellipsis'),
                {text: says(el)});
        }

        /* Downwards, which no ellipsis excuses: the last line is simply gone
         * and the box looks complete. */
        if (el.clientHeight > 0 && el.scrollHeight > el.clientHeight + SLACK
            && hidesOverflow(cs.overflowY)) {
          fault('clipped-y', 'fail', el,
                'content is ' + el.scrollHeight + 'px tall in a '
                + el.clientHeight + 'px box', {text: says(el)});
        }

        /* The other way round: text that has been pushed out of the box that
         * clips it, so it is not shortened but gone. Only across the axis
         * that does not scroll - being below the fold of a list is where the
         * rest of a list lives. */
        var cut = clipper(el, boxes);
        if (cut) {
          var cb = cut.box, over = [];
          /* An axis you can scroll is an axis where "further along" is a
           * place, not an absence - so only the fixed one is asked. */
          if (cut.cs.overflowX !== 'auto' && cut.cs.overflowX !== 'scroll') {
            if (r.right > cb.right + SLACK) over.push('right by ' + px(r.right - cb.right) + 'px');
            if (r.left < cb.left - SLACK) over.push('left by ' + px(cb.left - r.left) + 'px');
          }
          if (cut.cs.overflowY !== 'auto' && cut.cs.overflowY !== 'scroll') {
            if (r.bottom > cb.bottom + SLACK) over.push('below by ' + px(r.bottom - cb.bottom) + 'px');
            if (r.top < cb.top - SLACK) over.push('above by ' + px(cb.top - r.top) + 'px');
          }
          if (over.length) {
            fault('outside-box', 'fail', el,
                  'sits outside ' + name(cut.el) + ', which clips: '
                  + over.join(' and '), {text: says(el)});
          }
        }
      }

      /* Off the side of the window. The body does not scroll sideways, so
       * this is not "further along", it is gone. */
      if (r.right > vw + SLACK || r.left < -SLACK) {
        fault('offscreen-x', 'fail', el,
              'spans ' + px(r.left) + '..' + px(r.right)
              + ' in a ' + vw + 'px window', {text: says(el)});
      }

      /* Below the fold with nothing to scroll it into view. */
      if (r.top > vh + SLACK || r.bottom > vh + SLACK) {
        if (!underAScroller(el, boxes)) {
          fault('offscreen-y', 'fail', el,
                'sits at ' + px(r.top) + '..' + px(r.bottom)
                + ' in a ' + vh + 'px window, and nothing above it scrolls',
                {text: says(el)});
        }
      }

      /* Too small to hit. A note, not a failure: some of these are
       * deliberate, and the size is the point of reporting it. */
      var hittable = el.matches(
        'button, a[href], input:not([type=hidden]), select, textarea,'
        + ' [role=tab], [role=button], [tabindex]:not([tabindex="-1"])');
      if (hittable && (r.width < TARGET || r.height < TARGET)) {
        fault('tiny-target', 'note', el,
              px(r.width) + 'x' + px(r.height) + ', under ' + TARGET + 'px',
              {text: says(el)});
      }
    });

    /* A column that is not one.
     *
     * Everything else here compares children of one parent. This compares
     * the same thing down a stack of rows, which is where the faults a
     * person actually points at live: the star at the end of every row in
     * the list is a column, and the row where it sits fourteen pixels left
     * of the rest is glaring on screen and invisible to anything measuring
     * one row at a time. Each row is correct by itself; the list is wrong.
     *
     * Only fixed-width things are asked, and that is the whole of the
     * safety here. A country's name is as wide as the name and where it
     * ends promises nothing, so it is skipped - but a 24px star that is
     * 24px in every row is a thing with a column, and rows that disagree
     * about where that column is disagree by mistake. Same reasoning for
     * "once per row": two badges in one row and one in the next is a row
     * carrying different cargo, not a column that slipped.
     */
    function columnsDown(all) {
      if (all.length < 3) return;

      /* Only rows of the same kind are a column between them. The list has
       * a "Fastest available" row above the countries with a gradient tile
       * where they have a flag, and its text starts 14px further in - which
       * is a decision about one special row, not a country out of line, and
       * reporting it every run is how a report gets skimmed.
       *
       * State classes do not make a different kind: a row is the same row
       * when it is the picked one or the one under the cursor. */
      var kinds = {};
      all.forEach(function (row) {
        var cls = (row.getAttribute('class') || '').trim().split(/\s+/)
          .filter(function (c) { return c && c.indexOf('is-') !== 0; })
          .sort().join('.');
        (kinds[cls] = kinds[cls] || []).push(row);
      });
      Object.keys(kinds).forEach(function (k) { oneKind(kinds[k]); });
    }

    function oneKind(rows) {
      if (rows.length < 3) return;

      var byClass = {};       // class token -> row index -> [elements]
      rows.forEach(function (row, ri) {
        var all = row.getElementsByTagName('*');
        for (var i = 0; i < all.length; i++) {
          var el = all[i];
          if (!boxes.has(el)) continue;   // not on screen: already accounted for
          var cls = (el.getAttribute('class') || '').trim();
          if (!cls) continue;
          var toks = cls.split(/\s+/);
          for (var t = 0; t < toks.length; t++) {
            var k = toks[t];
            if (!byClass[k]) byClass[k] = {};
            (byClass[k][ri] = byClass[k][ri] || []).push(el);
          }
        }
      });

      /* Two of the same thing per row is two columns, not a disqualifying
       * mess - the provider signs are exactly that. What cannot be compared
       * is rows carrying different numbers of them, because then the second
       * sign in one row and the second in the next are not the same column.
       * The list keeps that true by drawing an empty slot for a provider a
       * country does not have, which is what makes this checkable at all. */
      Object.keys(byClass).forEach(function (tok) {
        var perRow = byClass[tok];
        var where = Object.keys(perRow);
        if (where.length < 3) return;
        var n = perRow[where[0]].length;
        for (var i = 1; i < where.length; i++) {
          if (perRow[where[i]].length !== n) return;
        }
        for (var k = 0; k < n; k++) {
          var els = where.map(function (ri) { return perRow[ri][k]; });
          /* A class is only a column if it sits in the same place in every
           * row. `row__tag--none` is the counter-example that taught this:
           * it marks the empty provider slot, which is the first sign on a
           * row missing Surfshark and the second on a row missing
           * Windscribe. Two different columns wearing one name, and
           * comparing them reported a fault in a list that was correct.
           *
           * Counted from both ends, because a row that carries an extra
           * thing at the front - the flag a provider row does not have -
           * still lines its last two controls up with everyone else's. */
          if (!steady(els, 'start') && !steady(els, 'end')) continue;
          column(tok + (n > 1 ? ' #' + (k + 1) : ''), els);
        }
      });

      /* Same index among its parent's children, in every row. */
      function steady(els, from) {
        var at = null;
        for (var i = 0; i < els.length; i++) {
          var sibs = els[i].parentElement.children;
          var j = [].indexOf.call(sibs, els[i]);
          if (from === 'end') j = sibs.length - 1 - j;
          if (at === null) at = j;
          else if (at !== j) return false;
        }
        return true;
      }

      function column(tok, els) {
        var rs = els.map(function (e) { return boxes.get(e).rect; });
        var w = rs.map(function (r) { return r.width; });
        var varied = Math.max.apply(null, w) - Math.min.apply(null, w) > 0.5;

        var lefts = rs.map(function (r) { return px(r.left); });
        var spread = Math.max.apply(null, lefts) - Math.min.apply(null, lefts);
        if (spread <= 0.5) return;

        /* A box as wide as its own text is allowed to *end* anywhere - that
         * is what a long name does - but it is not allowed to *start*
         * anywhere. So when the widths differ, the left edge is only worth
         * complaining about if two other things hold: every one of these
         * sits in a parent that begins at the same x, and the text inside
         * is left-aligned. Then the only thing that can be moving them is
         * something centring the box, and a centred box in a column of
         * left-aligned rows wanders by exactly half the difference in text
         * length - which reads as rows that do not line up and is invisible
         * to anything comparing one row with itself.
         *
         * This is the fault the first version of this check walked past: it
         * skipped anything whose width varied, and a country name's width
         * always varies. */
        if (varied) {
          /* Anchored to its right edge instead, which is what a column of
           * numbers is: 533 over 91 over 7, all ending in the same place
           * and none of them starting in one. Ending together is being in a
           * column, so there is nothing to report. */
          var rights = rs.map(function (r) { return px(r.right); });
          if (Math.max.apply(null, rights)
              - Math.min.apply(null, rights) <= 0.5) return;

          var pl = els.map(function (e) {
            return px(e.parentElement.getBoundingClientRect().left);
          });
          if (Math.max.apply(null, pl) - Math.min.apply(null, pl) > 0.5) return;
          var how = boxes.get(els[0]).cs.textAlign;
          if (how !== 'left' && how !== 'start') return;
        }

        /* Which of them is the odd one. The fault is hung on the row that
         * broke ranks rather than on the first row in the list, because
         * "row one is 14px off row four" sends a person to the wrong row. */
        var tally = {};
        lefts.forEach(function (v) { tally[v] = (tally[v] || 0) + 1; });
        var norm = Object.keys(tally).sort(function (a, b) {
          return tally[b] - tally[a];
        })[0];
        for (var j = 0; j < els.length; j++) {
          if (lefts[j] === +norm) continue;
          fault('column-off', 'fail', els[j],
                '.' + tok + ' sits at x=' + lefts[j] + ', where '
                + tally[norm] + ' of ' + els.length + ' rows put it at '
                + norm + ' - ' + px(Math.abs(lefts[j] - norm))
                + 'px out of a column ' + els.length + ' rows deep',
                {text: says(els[j])});
        }
      }
    }

    /* ---- 3. children of one parent, against each other -------------- */
    kids.forEach(function (list, parent) {
      if (list.length < 2) return;
      var pcs = boxes.has(parent) ? boxes.get(parent).cs
                                  : getComputedStyle(parent);

      /* Flow only, for everything that follows.
       *
       * An absolutely positioned element was put where it is by hand - the
       * card's ⋯ button is pinned 10px from the corner and has no business
       * lining up with the paragraphs behind it, which is what the first run
       * of this reported four times over. It can still be measured on its
       * own; it just does not belong in a conversation about a stack. */
      var flow = list.filter(function (el) {
        var q = boxes.get(el).cs.position;
        return q === 'static' || q === 'relative';
      });
      if (flow.length < 2) return;
      list = flow;

      var rects = list.map(function (el) { return boxes.get(el).rect; });

      /* Which way this parent stacks. Read off the boxes rather than the
       * stylesheet: `display: flex` says nothing about what wrapping did. */
      var col = 0, row = 0;
      for (var i = 1; i < list.length; i++) {
        var a = rects[i - 1], b = rects[i];
        var overV = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        var overH = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        if (overV <= 0.5 && overH > 0.5) col++;
        else if (overH <= 0.5 && overV > 0.5) row++;
      }
      var dir = col > row ? 'col' : row > col ? 'row' : null;

      /* A wrapped run is neither. Six chips flowing onto four lines have
       * both kinds of neighbour - one beside, three below - and read as a
       * column by majority, at which point every question a column gets
       * asked is the wrong one: the right edges of two chips with different
       * names in them are meant to differ, the "gap" between two on the same
       * line is a negative number, and the count inside each is not a column
       * at all. Left in, this reported eight faults against a row of
       * companies that was laid out exactly as intended.
       *
       * Only overlap is asked of these, and it is asked above this line,
       * where it belongs: two chips on the same pixels is still wrong. */
      var wrapped = /flex|grid/.test(pcs.display)
        && /wrap/.test(pcs.flexWrap) && col > 0 && row > 0;
      if (wrapped) return;

      /* Overlap. Two siblings in flow on the same pixels is a layout that
       * has collapsed - something is taller than its parent believes, or a
       * negative margin went the wrong way. */
      for (var m = 0; m < list.length; m++) {
        for (var n = m + 1; n < list.length; n++) {
          var A = rects[m], B = rects[n];
          var ox = Math.min(A.right, B.right) - Math.max(A.left, B.left);
          var oy = Math.min(A.bottom, B.bottom) - Math.max(A.top, B.top);
          if (ox > 1.5 && oy > 1.5) {
            fault('overlap', 'fail', list[m],
                  'overlaps ' + name(list[n]) + ' by '
                  + px(ox) + 'x' + px(oy) + 'px');
          }
        }
      }

      if (dir === 'col') {
        /* Edges that nearly line up. Both sides, because a right edge is as
         * visible as a left one and only one of them is usually watched. */
        ['left', 'right'].forEach(function (side) {
          for (var i = 0; i < list.length; i++) {
            for (var j = i + 1; j < list.length; j++) {
              var d = Math.abs(rects[i][side] - rects[j][side]);
              if (d >= NEAR_MIN && d <= NEAR_MAX) {
                fault('edge-off', 'fail', list[i],
                      side + ' edge is ' + px(d) + 'px off '
                      + name(list[j]) + ' - lined up or clearly not, '
                      + 'never ' + px(d) + 'px',
                      {a: px(rects[i][side]), b: px(rects[j][side])});
              }
            }
          }
        });

        /* Uneven gaps down a stack of the same thing. Three rows 8px apart
         * and one 9px is a stray margin, and it reads as a wobble nobody
         * can name. Only for siblings that are the same kind of thing -
         * a heading followed by a list is meant to breathe differently. */
        var sig = list.map(function (el) {
          return el.tagName + '.' + (el.getAttribute('class') || '');
        });
        if (list.length >= 3 && sig.every(function (s) { return s === sig[0]; })) {
          var gaps = [];
          for (var g = 1; g < list.length; g++) {
            gaps.push(px(rects[g].top - rects[g - 1].bottom));
          }
          var lo = Math.min.apply(null, gaps), hi = Math.max.apply(null, gaps);
          if (hi - lo > 0.5) {
            fault('gap-uneven', 'fail', list[0],
                  list.length + ' siblings, gaps ' + gaps.join(', ')
                  + ' — one of them is not like the others');
          }
        }

        columnsDown(list);
      }

      if (dir === 'row') {
        /* A row told to centre its children, that then does not. When
         * align-items is anything else the centres are meant to differ, so
         * there is nothing to check. */
        if (pcs.alignItems === 'center') {
          for (var p = 0; p < list.length; p++) {
            for (var q = p + 1; q < list.length; q++) {
              var ca = (rects[p].top + rects[p].bottom) / 2;
              var cb = (rects[q].top + rects[q].bottom) / 2;
              var dd = Math.abs(ca - cb);
              if (dd >= NEAR_MIN && dd <= NEAR_MAX) {
                fault('mid-off', 'fail', list[p],
                      'centre is ' + px(dd) + 'px off ' + name(list[q])
                      + ', in a row that says align-items: center');
              }
            }
          }
        }
      }
    });

    /* One fault per element per kind. The pairwise checks above find the
     * same misalignment from both ends, and a list that says the same thing
     * twice gets skimmed. */
    var once = {}, kept = [];
    faults.forEach(function (f) {
      var k = f.kind + '|' + f.at + '|' + f.note;
      if (once[k]) return;
      once[k] = 1;
      kept.push(f);
    });

    return {
      scope: root.id ? '#' + root.id : (opts.scope || 'body'),
      window: vw + 'x' + vh,
      measured: seen,
      fails: kept.filter(function (f) { return f.sev === 'fail'; }).length,
      notes: kept.filter(function (f) { return f.sev === 'note'; }).length,
      faults: kept
    };
  };
})();
