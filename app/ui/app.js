/* The page holds no truth of its own.

   Every fact on screen came from the backend, and this file only decides how
   to say it. That matters more than usual: the whole product is a claim about
   where your traffic comes out, made to someone who cannot check it any other
   way. A number invented here, or left on screen after it stopped being true,
   is the one unforgivable bug.

   Fuse is a plain script rather than a module: pywebview serves local files
   over file://, where ES module imports are blocked outright, and the failure
   mode is a page that loads and does nothing at all. */

const $ = (id) => document.getElementById(id);

const state = {
  countries: [],
  picked: 'auto',
  mode: 'off',          // off | busy | on | fail
  realIp: null,
  fuse: null,
  cursor: 0,
  systemProxy: true,
  port: 8877,           // where the proxy listens; the backend owns the truth
  hasCredentials: true,
  sweep: null,          // null when idle, otherwise how the run is going
  plan: null,           // the last answer about what a test would cost
  pinning: null,        // the same two, for pinning
  pinPlan: null,
  providers: null,      // which providers the exit list is drawn from
  testing: false,       // a reachability test is running
  favourites: [],       // picker codes kept at the top of the list
  sortBy: 'ping',       // ping | name | load
  openCities: new Set(),  // countries showing their cities
  openExits: null,      // which row has its individual exits showing
  exits: {},            // and those exits, once fetched, by that row's code
};

const flagUrl = (code) => `url("flags/${code}.svg")`;

/* Seventy-five flags, eight hundred kilobytes, all asked for in the same
   frame the first time the list is drawn - which is the frame the sheet is
   also animating in. That was the stutter on the first open of the exit
   picker and only the first: afterwards they are in the cache and it is
   instant.

   So they are fetched while nothing is waiting for them. Six at a time rather
   than all at once, because seventy-five parallel reads off a disk is the
   same jam moved somewhere quieter, and started from an idle callback so it
   cannot land in front of anything the person is actually doing. */
function warmFlags(codes) {
  const queue = codes.slice();
  const idle = window.requestIdleCallback
    || ((fn) => setTimeout(() => fn({ timeRemaining: () => 8 }), 200));

  const pull = (deadline) => {
    while (queue.length && deadline.timeRemaining() > 2) {
      const img = new Image();
      img.src = `flags/${queue.shift()}.svg`;
    }
    if (queue.length) idle(pull);
  };
  for (let lane = 0; lane < 6 && queue.length; lane += 1) idle(pull);
}

/* One of the symbols inlined at the top of the page. Built rather than
   written as a string, because innerHTML here would take whatever a hostname
   or a company name happens to contain and put it in the document. */
function icon(href, className, title) {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', className || 'ico');
  const use = document.createElementNS(NS, 'use');
  use.setAttribute('href', href);
  svg.append(use);
  if (title) {
    const label = document.createElementNS(NS, 'title');
    label.textContent = title;
    svg.append(label);
  }
  return svg;
}

/* A deterministic Fibonacci sphere rendered as glass beads. Depth controls
   scale, blur and opacity, while the brighter sine-shaped crest makes the
   surface read as a travelling particle wave instead of a flat dot cloud. */
function initConnectionCore() {
  const canvas = $('orbCanvas');
  if (canvas && window.OrbCore && window.OrbCore.init(canvas)) return;

  /* No WebGL2 (blocked GPU, remote session): swap the canvas back out and
     fall back to the DOM bead sphere below. */
  let shell = $('blobParticles');
  if (!shell && canvas) {
    shell = document.createElement('span');
    shell.className = 'blob__body';
    shell.id = 'blobParticles';
    canvas.replaceWith(shell);
  }
  if (!shell || shell.childElementCount) return;

  const addParticle = ({ x, y, size, opacity, blur, dx, dy, glow, depth, color, wave = false }) => {
    const index = shell.childElementCount;
    const point = document.createElement('i');
    if (wave) point.className = 'blob__particle--wave';
    point.style.setProperty('--x', `${x.toFixed(2)}px`);
    point.style.setProperty('--y', `${y.toFixed(2)}px`);
    point.style.setProperty('--s', `${size.toFixed(2)}px`);
    point.style.setProperty('--o', opacity.toFixed(2));
    point.style.setProperty('--blur', `${blur.toFixed(2)}px`);
    point.style.setProperty('--dx', `${dx.toFixed(2)}px`);
    point.style.setProperty('--dy', `${dy.toFixed(2)}px`);
    point.style.setProperty('--glow', `${glow.toFixed(2)}px`);
    point.style.setProperty('--z', `${Math.round(depth * 120)}`);
    point.style.setProperty('--delay', `${(-index * 0.075).toFixed(2)}s`);
    point.style.setProperty('--duration', `${(4.3 + (index % 9) * 0.38).toFixed(2)}s`);
    point.style.setProperty('--dot-color', color);
    shell.append(point);
  };

  const golden = Math.PI * (3 - Math.sqrt(5));
  const shellCount = 84;
  for (let n = 0; n < shellCount; n += 1) {
    const vertical = 1 - ((n + 0.5) / shellCount) * 2;
    const ring = Math.sqrt(1 - vertical * vertical);
    const angle = n * golden + vertical * 0.82;
    const projectedX = Math.cos(angle) * ring;
    const depth = (Math.sin(angle) * ring + 1) / 2;
    const ripple = 1 + Math.sin(angle * 2.1 + vertical * 4.8) * 0.032;
    addParticle({
      x: projectedX * 59 * ripple,
      y: vertical * 59,
      size: 0.55 + depth * 1.15 + (n % 13 === 0 ? 0.5 : 0),
      opacity: 0.08 + depth * 0.44,
      blur: (1 - depth) * 0.64,
      dx: Math.cos(angle * 1.35) * 0.75,
      dy: Math.sin(angle * 1.62) * 0.85,
      glow: 0.8 + depth * 2.4,
      depth,
      color: depth > 0.5 ? 'var(--blob-b)' : 'var(--blob-c)',
    });
  }

  const bands = [19, 21, 19];
  bands.forEach((count, bandIndex) => {
    const band = bandIndex - 1;
    for (let n = 0; n < count; n += 1) {
      const horizontal = -0.91 + (n / (count - 1)) * 1.82;
      const jitter = Math.sin((n + 1) * (bandIndex + 2) * 1.71) * 0.012;
      const waveY = Math.sin(horizontal * 4.25 + band * 0.42) * 0.17
        - horizontal * 0.14
        + band * 0.058
        + jitter;
      const depth = 0.67 + (Math.cos(horizontal * 2.8 + band * 0.35) + 1) * 0.12;
      addParticle({
        x: (horizontal + jitter * 0.6) * 59,
        y: waveY * 59,
        size: 2.35 + depth * 1.35 + (1 - Math.abs(horizontal)) * 0.72 + (n % 6 === 0 ? 0.55 : 0),
        opacity: Math.min(0.98, 0.64 + depth * 0.35),
        blur: (1 - depth) * 0.2,
        dx: Math.cos(horizontal * 5.2 + band) * 1.65,
        dy: Math.sin(horizontal * 4.1 - band) * 1.45,
        glow: 5.2 + depth * 4.2,
        depth: Math.min(1, depth + 0.14),
        color: band === 0 ? 'var(--blob-a)' : (band > 0 ? 'var(--blob-b)' : 'var(--blob-c)'),
        wave: true,
      });
    }
  });
}

/* ------------------------------------------------------------- rendering */

function setIp(el, value, fresh) {
  if (el.textContent === value) return;
  el.textContent = value;
  if (fresh) {
    el.classList.remove('fresh');
    void el.offsetWidth;
    el.classList.add('fresh');
  }
}

function setStatus(word, kind, where, host) {
  const s = $('status');
  if (s.textContent !== word) {
    s.classList.remove('fresh');
    void s.offsetWidth;
    s.classList.add('fresh');
  }
  s.textContent = word;
  s.dataset.state = kind;
  $('statusWhere').innerHTML = where || '&nbsp;';
  $('statusHost').innerHTML = host || '&nbsp;';
}

function setHint(text, bad) {
  const el = $('hint');
  el.textContent = text || '';
  el.classList.toggle('is-bad', !!bad);
}

// The app files the United Kingdom under `uk`; Windscribe's filenames say
// `gb`. The engine folds them together, so anything reading a code out of a
// filename has to fold it the same way or it will look for a country that is
// not in the list.
const CANON = { gb: 'uk' };
const canonCode = (c) => CANON[(c || '').toLowerCase()] || (c || '').toLowerCase();

function nameOf(code) {
  // A named exit says the country and the host it was pinned from. The
  // filename carries both - "at-vie.ws.at-007.totallyacdn.com_1.2.3.4.ovpn" -
  // which is why it can be read back without the exits list to hand.
  if ((code || '').startsWith('file:')) {
    // The sweep writes its measured time onto the front of a filename, so
    // "05.2s-id-jak.prod..." begins with the time and not the country - and
    // reading the first two characters put "05" on the front of the app.
    const file = code.slice(5).replace(/^\d+\.\d+s-/, '');
    const c = state.countries.find((x) => x.code === canonCode(file.slice(0, 2)));
    const host = file.replace(/^[a-z]{2}-[a-z0-9]{3}\.(?:prod|ws)\./i, '')
      .replace(/_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$/, '')
      .split('.')[0];
    return `${c ? c.name : file.slice(0, 2).toUpperCase()} · ${host}`;
  }
  const [place, via] = (code || '').toLowerCase().split(':');
  const [where, city] = place.split('/');
  const c = state.countries.find((x) => x.code === where);
  let name = c ? c.name : (where || '').toUpperCase();
  if (city) {
    const found = (c && c.cityList || []).find((x) => x.code === city);
    name = found ? `${name} · ${found.name}` : `${name} · ${city.toUpperCase()}`;
  }
  return via ? `${name} · ${PROVIDER_NAMES[via] || via}` : name;
}

function render() {
  const act = $('act');
  const visualState = state.mode === 'fail' ? 'fail' : state.mode;
  act.dataset.mode = state.mode === 'fail' ? 'off' : state.mode;
  $('card').dataset.busy = String(state.mode === 'busy');
  $('card').dataset.state = visualState;
  $('hero').dataset.state = visualState;
  $('swap').dataset.state = visualState;
  $('routeLabel').textContent = ({
    off: 'READY TO RELAY',
    busy: 'NEGOTIATING ROUTE',
    on: 'PRIVATE ROUTE ACTIVE',
    fail: 'ROUTE UNAVAILABLE',
  })[visualState];
  $('pick').disabled = state.mode === 'busy';
  $('more').hidden = state.mode !== 'on';
  // Put away the moment the route goes, rather than left showing the last
  // figures it had. It opens again on its own when a reading arrives - which
  // is the only thing that proves there is anything to meter.
  if (state.mode !== 'on') flux.stop();
  if (state.mode !== 'on') {
    $('details').hidden = true;
    $('more').setAttribute('aria-expanded', 'false');
  }

  // Through nameOf, which knows the four shapes a pick can take. Looking
  // the code up in the country list only ever worked for the plainest of
  // them, so choosing France-through-Windscribe put the string
  // "fr:windscribe" on the front of the app.
  const where = String(state.picked || '').startsWith('file:')
    ? canonCode(state.picked.slice(5).replace(/^\d+\.\d+s-/, '').slice(0, 2))
    : String(state.picked || '').split(/[:/]/)[0];
  const c = state.countries.find((x) => x.code === where);
  $('pickLabel').textContent = state.picked === 'auto'
    ? 'Fastest available' : nameOf(state.picked);
  $('pickFlag').style.backgroundImage = c ? flagUrl(c.code) : '';
}

/* --------------------------------------------------------------- picker */

function openPicker() {
  const began = performance.now();
  $('search').value = '';
  $('searchMain').classList.remove('is-typed');
  state.cursor = 0;
  drawList('');
  $('picker').showModal();
  setTimeout(() => $('search').focus(), 30);
  // What a stutter actually is: one frame that took far longer than the rest.
  // Timing this function measured the part that was never slow, and timing to
  // the second frame afterwards still stopped the clock before the compositor
  // had finished. So the frames themselves are sampled for a second, and the
  // worst one is the number worth arguing about.
  if (window.__pickerMs === undefined) window.__pickerMs = [];
  const frames = [];
  let last = began;
  const sample = (now) => {
    frames.push(Math.round(now - last));
    last = now;
    if (now - began < 900) requestAnimationFrame(sample);
    else {
      const worst = Math.max(...frames);
      let at = 0;
      for (let i = 0, run = 0; i < frames.length; i += 1) {
        run += frames[i];
        if (frames[i] === worst) { at = run; break; }
      }
      window.__pickerMs.push({
        toFirstFrame: frames[0],
        worstFrame: worst,
        worstAtMs: at,          // how long after the click it happened
        frames: frames.length,
      });
    }
  };
  requestAnimationFrame(sample);
}

function drawList(query) {
  const list = $('list');
  const q = (query || '').trim();

  // Seventy-five rows, each with a flag behind it, rebuilt from nothing every
  // time the sheet opened - and the sheet's own entrance animation running
  // over the top of it. Nothing about that list changes between one open and
  // the next unless the query or the chosen country has, so it is only built
  // when one of them has.
  // The provider selection belongs in here too. What a country row says now
  // depends on which providers back it, and two different selections can
  // leave the same number of countries standing - so a key counting only
  // countries lets the list keep rows for a provider that has been switched
  // off, with counts from before it was.
  const key = `${q}|${state.picked}|${state.countries.length}`
    + `|${(state.providers || []).join(',')}`
    + `|${[...state.openCities].sort().join(',')}`
    + `|${state.sortBy}|${(state.favourites || []).join(',')}`;
  if (list.dataset.key === key) return;
  list.dataset.key = key;

  // Where the reader was. replaceChildren() empties the list, which drops
  // the scroll to nothing - so opening a country halfway down the list threw
  // the page back to the top and lost the row that had just been clicked.
  const wasAt = list.scrollTop;
  list.replaceChildren();

  if (!q) {
    const auto = document.createElement('button');
    auto.type = 'button';
    auto.className = 'row row--auto' + (state.picked === 'auto' ? ' is-picked' : '');
    auto.dataset.code = 'auto';
    const autoIcon = document.createElement('span');
    autoIcon.className = 'row__auto-icon';
    autoIcon.setAttribute('aria-hidden', 'true');
    autoIcon.textContent = '\u2726';
    const copy = document.createElement('span');
    copy.className = 'row__copy';
    const name = document.createElement('span');
    name.className = 'row__name';
    name.textContent = 'Fastest available';
    const sub = document.createElement('span');
    sub.className = 'row__sub';
    sub.textContent = 'Races every location and takes the first to answer';
    copy.append(name, sub);
    auto.append(autoIcon, copy);
    list.append(auto);
  }

  const found = sortCountries(
    q ? state.fuse.search(q).map((r) => r.item) : state.countries);
  if (!found.length) {
    const e = document.createElement('p');
    e.className = 'empty';
    e.textContent = `No country matches “${q}”.`;
    list.append(e);
    return;
  }

  /* One row per way in, when there is more than one.

     A country both providers reach is one place with two doors, and which
     door is a real choice: they are different companies at different
     addresses, and on this line one is often filtered where the other is
     not. Showing "France - 24 relays" and picking for you hides the only
     decision worth making there.

     The country's own row stays, and stays first, because it means
     "whichever answers" - which is what most people want most of the time.
     The rows under it are for when it is not. */

  /* The sign columns, worked out once for the whole list and from every
     country in it rather than from the ones a search left on screen - a
     column that appears and disappears as you type is worse than one that
     is sometimes empty. */
  const tagCols = tagColumns();

  const buildGroup = (c) => {
    /* One row per place, and a sign for who backs it.

       A country reached by both used to split into a row per provider, and
       the rows underneath said less than the sign does: "Surfshark, 23
       relays" with nothing measured about any of them, taking a line each
       and pushing the next country off the screen. Which provider an exit
       comes from is a fact about the row, not another row. */
    const out = [build(c)];

    /* The cities inside it, and only when asked for.

       Every country's cities laid out at once was sixty-nine rows of them
       above the fold, which is a list of cities pretending to be a list of
       countries. Windscribe's own client keeps them shut until you open one,
       and the plus on the row is the whole affordance.

       A country with one city is already that city; opening it would show
       the same place again with a smaller font. */
    const cityList = c.cityList || [];
    if (cityList.length > 1 && state.openCities.has(c.code)) {
      let at = 0;
      for (const city of cityList) {
        // Both providers in one city is two ways into one place, the same
        // as it is for a country - so it splits the same way rather than
        // merging into a row that cannot say which you would get.
        const r = buildCity(c, city);
        r.style.setProperty('--i', at++);
        out.push(r);
      }
    }
    return out;
  };

  const build = (c, via, heads) => {
    const code = via ? `${c.code}:${via}` : c.code;
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'row' + (state.picked === code ? ' is-picked' : '')
      + (via ? ' row--via' : '') + (heads ? ' row--heads' : '');
    row.dataset.code = code;
    row.style.setProperty('--flag', flagUrl(c.code));
    // The flag as a thing on the row rather than a wash behind it. A
    // quarter of the row tinted the colour of a flag is a decoration that
    // reads as a state - and with a time, a load and a verdict now on the
    // same line, the line needs the room more than it needs the picture.
    const chip = document.createElement('span');
    chip.className = via ? 'chip chip--dot' : 'chip';
    if (!via) chip.style.setProperty('--flag', flagUrl(c.code));
    row.append(chip);

    const copy = document.createElement('span');
    copy.className = 'row__copy';
    const name = document.createElement('span');
    name.className = 'row__name';
    name.textContent = c.name;
    const meta = document.createElement('span');
    meta.className = 'row__meta';
    // How many addresses a country has is what decides whether picking it
    // will work, so that is the number shown rather than a speed nobody can
    // act on.
    meta.textContent = `${c.code.toUpperCase()} \u00b7 ${c.count} relay${c.count === 1 ? '' : 's'}`;
    if (via) {
      // The provider is the name here, because the country is already said
      // by the row directly above it.
      name.textContent = PROVIDER_NAMES[via] || via;
      const n = (c.by || {})[via] || 0;
      const ok = (c.byOk || {})[via] || 0;
      const ping = (c.byPing || {})[via];
      // This provider's own tally. The country's would call a provider
      // nobody has asked about blocked, on the strength of the other one
      // having been measured.
      const tried = (c.byTested || {})[via] || 0;
      meta.textContent = `${n} relay${n === 1 ? '' : 's'}`
        + (ping !== undefined && ping !== null ? ` · ${msSaid(ping)}` : '')
        + (tried && !ok ? (tried >= n ? ' · blocked' : '') : '')
        + (tried && ok && ok < n ? ` · ${ok}/${n}` : '');
      row.dataset.state = !tried ? 'untested'
        : ok ? (ok < tried ? 'some' : 'ok')
          : (tried >= n ? 'blocked' : 'some');
    } else if (heads) {
      // Short, because the rows underneath say it better and this line
      // now has to hold a time as well.
      // "any" only when there is nothing better to say. Once there is a
      // time on the line it is the rows underneath that mean "any", and the
      // word was only pushing the time off the end.
      const said_ = reachSaid(c);
      meta.textContent = meta.textContent + (said_ || ' · any');
      row.dataset.state = reachState(c);
    } else {
      // What the last test found, after the count: a time when it answered,
      // and that it did not when it did not. Nothing at all before it has
      // been asked, because "untested" and "blocked" must not look alike.
      meta.textContent = meta.textContent + reachSaid(c);
      row.dataset.state = reachState(c);
    }
    copy.append(name, meta);
    // Which provider the exits behind this country actually come from.
    // Worth a tag rather than a number: with both switched on, "12 relays"
    // says nothing about which credential is about to open one, and the two
    // behave differently enough on this line to be worth telling apart.
    // Only on a plain row. Under a group the provider is the row's own name
    // and a tag repeating it is noise; on the group's head, tags would claim
    // one exit of each, which is what the rows below it say properly.
    let tagsFor = null;
    const by = c.by || {};
    if (tagCols.length && !via && !heads) {
      /* One slot per provider, always in the same order, and an empty one
         where a country has nobody.

         Packed tight, the signs said the wrong thing down the list: a
         country only Surfshark reaches put its S where every other row has
         its W, so the eye reading the column saw Windscribe, Windscribe,
         Windscribe and one of them was not. A row with one sign also
         dragged the star and the plus left by the width of the sign it did
         not have, which is the wobble in the first rows of the list.

         So the signs get columns, the way the star and the plus already do.
         An absent provider is a hole in its own column rather than an
         absence that moves everything after it. */
      const tags = document.createElement('span');
      tags.className = 'row__tags';
      for (const key of tagCols) {
        tags.append((by[key] || 0) > 0
          ? providerTag(key, by[key], (c.byTested || {})[key] || 0,
                        (c.byOk || {})[key] || 0, (c.byPing || {})[key])
          : tagGap());
      }
      row.dataset.tags = '1';
      tagsFor = tags;
    }
    // No tick. The chosen row is outlined instead - see .row.is-picked.
    row.append(copy);
    if (tagsFor) row.append(tagsFor);
    // A plus rather than a chevron, and inside the row rather than under it:
    // it is the same control Windscribe puts there, and a row that opens is
    // more obviously openable with a + on it than with a line beneath.
    if (!via) {
      const many = (c.cityList || []).length > 1;
      // A country with cities opens into them; one without opens straight
      // into its hosts, because there is no middle to show. Either way it is
      // the same plus in the same place - what it reveals is the row's
      // business, not the reader's.
      if (!heads) row.append(starFor(code));
      if (many || c.count > 1) {
        row.append(expander(many ? 'expand' : 'exits', c.code,
                            many ? state.openCities.has(c.code)
                                 : state.openExits === c.code));
      } else {
        row.append(slot());
      }
    } else if (!heads) {
      row.append(starFor(code), slot());
    }
    return row;
  };

  // A screenful now, the rest once the frame is over. Seventy-five of these
  // built in one go was measured at about 165ms of the first open's stutter -
  // and sixty-seven of them are below the fold, where nobody is waiting for
  // them. content-visibility already stops those being painted; this stops
  // them being built in the frame that matters.
  $('listCount').textContent = String(found.length);

  const AT_ONCE = 14;
  for (const c of found.slice(0, AT_ONCE)) list.append(...buildGroup(c));
  markCursor();
  // Restored after the first batch, and again once the tail lands: the list
  // is not tall enough to hold the old position until the rest of it is
  // there, and a scrollTop set past the end is silently clamped.
  if (wasAt) list.scrollTop = wasAt;

  // And the tail a handful at a time. Appending all sixty-one in one idle
  // callback only moved the long frame later - the sheet arrived instantly
  // and then froze for a fifth of a second while they landed. No single
  // batch is now big enough to miss a frame.
  const rest = found.slice(AT_ONCE);
  if (!rest.length) return;
  const later = window.requestIdleCallback || ((fn) => setTimeout(fn, 16));
  let at = 0;
  const more = () => {
    // Only while this is still the list on screen. A search typed while the
    // tail was queued would otherwise have the old countries land under it.
    if (list.dataset.key !== key) return;
    const batch = document.createDocumentFragment();
    for (const c of rest.slice(at, at + AT_ONCE)) batch.append(...buildGroup(c));
    list.append(batch);
    if (wasAt && list.scrollTop !== wasAt) list.scrollTop = wasAt;
    at += AT_ONCE;
    if (at < rest.length) later(more);
  };
  later(more);
}

function rows() { return Array.from($('list').querySelectorAll('.row')); }

function markCursor() {
  const all = rows();
  if (!all.length) return;
  state.cursor = Math.max(0, Math.min(state.cursor, all.length - 1));
  all.forEach((r, i) => r.classList.toggle('is-cursor', i === state.cursor));
  all[state.cursor].scrollIntoView({ block: 'nearest' });
}

async function choose(code) {
  state.picked = code;
  $('picker').close();
  render();
  window.pywebview.api.remember(code);

  /* And connect to it, because that is what picking one is for.

     It used to close the sheet and leave the Connect button lit, which is a
     second decision about a choice already made - and the sheet was opened
     from that same button, so the round trip was: press Connect, choose a
     place, press Connect. Choosing is the answer to the question the button
     asked.

     Already connected, it moves rather than stopping: disconnect first, then
     connect to the new one, so that picking somewhere else while a tunnel is
     up does the obvious thing instead of nothing. */
  if (state.mode === 'busy') return;
  if (state.mode === 'on') {
    setStatus('SWITCHING', 'busy', 'Leaving the old one', '');
    try {
      await window.pywebview.api.disconnect();
    } catch (_) { /* going anyway */ }
  }
  state.mode = 'busy';
  render();
  setStatus('CONNECTING', 'busy', 'Looking for a server', '');
  setHint('');
  const r = await window.pywebview.api.connect(code);
  if (!r.ok) { state.mode = 'off'; render(); }
}

/* --------------------------------------------------- events from the app */

const STAGES = {
  probing: 'Looking for a server',
  starting: 'Opening the connection',
  routing: 'Pointing Windows at it',
  verifying: 'Checking it really works',
};

window.onProgress = (p) => {
  const detail = (p.phase === 'probing' && p.total)
    ? `${p.asked || 0} of ${p.total} asked` : '';
  setStatus('CONNECTING', 'busy', STAGES[p.phase] || 'Working', detail);
};

window.onConnected = (status) => {
  state.mode = 'on';
  // The status carries the port it is really on, and after a move that is
  // the newer of the two answers - this one comes from the engine, while
  // state.port is whatever the last painted sheet was told.
  if (status.port) state.port = status.port;
  const e = status.exit || {};
  const seen = e.seen_as || {};

  // Until the independent check answers there is no verified exit address,
  // and e.ip is the PROXY's address, not the one websites see - they differ.
  // Showing it here would put a number under "Seen as" that nothing has
  // checked, which is the one thing this readout must never do.
  const confirmed = seen.ip || null;
  const measured = (seen.country || '').toLowerCase();
  const claimed = (e.country || '').toLowerCase();
  const place = measured ? nameOf(measured) : nameOf(claimed);

  setIp($('exitIp'), confirmed || (e.unconfirmed ? 'unconfirmed' : 'checking…'),
        !!confirmed);
  $('exitPlace').textContent = measured ? place : `${place} (checking)`;
  setStatus('CONNECTED', 'on', place, e.host || '');
  $('detIn').textContent = `${e.ip || '—'}:443`;
  $('detOut').textContent = confirmed || '—';
  $('detVia').textContent = e.host || '—';

  setHint(measured && claimed && measured !== claimed
    ? `Sold as ${nameOf(claimed)}, but its traffic really comes out in ${place}. What websites see is the second one.`
    : (e.unconfirmed
        ? 'Connected, but the independent check did not answer. If pages load, it is fine.'
        : (state.systemProxy
            ? 'Everything on this PC now goes through this connection.'
            : `Serving on 127.0.0.1:${state.port}. Windows was left alone, so point what you want at it.`)));
  render();
  // The push comes once a second; this is so a window opened onto a
  // connection that was already up does not sit with an empty meter for the
  // first of those seconds.
  window.pywebview.api.traffic().then(window.onTraffic, () => {});
};

window.onFailed = (err) => {
  state.mode = 'fail';
  setIp($('exitIp'), '—', false);
  $('exitPlace').textContent = 'not connected';
  setStatus('NOT CONNECTED', 'fail', 'Could not connect', '');
  const say = {
    'all-refused': state.picked === 'auto'
      ? 'No server accepted just now. That happens — wait a moment and try again.'
      : `No ${nameOf(state.picked)} server accepted just now. Try again, or use Fastest available.`,
    'no-servers': 'No servers in that folder for that country.',
    'no-credentials': 'The username and password file is missing, so there is nothing to sign in with.',
    // Its own message, because the fix is a different one: these exits were
    // fetched from Windscribe and only its credential opens them, and that
    // credential is signed in for rather than typed.
    'no-windscribe-credentials':
      'These are Windscribe servers, and there is no Windscribe credential on '
      + 'file. Sign in to Windscribe in Settings.',
    'cancelled': 'Cancelled.',
    'did-not-start': 'The connection opened but did not come up. Try once more.',
    // Only from a port change: the old worker was stopped and the new one
    // never answered, so there is nothing up and the sheet has the detail.
    'moved-and-died': 'The port changed, but the connection did not come back up on it. Connect again.',
  };
  setHint(say[err.kind] || 'Could not connect. Try once more.', err.kind !== 'cancelled');
  render();
};

window.onDisconnected = (info) => {
  state.mode = 'off';
  setIp($('exitIp'), '—', false);
  $('exitPlace').textContent = 'not connected';
  setStatus('DISCONNECTED', 'off', '', '');
  setHint(info && info.minutes
    ? `Windows is back to normal. Routed for ${info.minutes} minute${info.minutes === 1 ? '' : 's'}.`
    : 'Windows is back to normal.');
  render();
};

window.onRealIp = (info) => {
  state.realIp = info && info.ip;
  setIp($('realIp'), (info && info.ip) || 'unknown', true);
  $('realPlace').textContent = (info && info.country)
    ? nameOf(info.country) : 'could not check';
};

/* ----------------------------------------------------------------- flux */

/* The meter under the core: how much has gone each way, and how fast it is
   going right now.

   Every number here came from the worker process, which counts the bytes it
   forwards. Nothing on this page adds anything up - the totals are read, not
   accumulated - so a window closed and reopened onto a live connection shows
   the same figures it would have shown had it never been away.

   Kilobytes are 1024 here, not 1000. This is the one app on the machine
   whose numbers a person will hold up next to Explorer's and Task Manager's,
   and being right by the standard while disagreeing with everything they can
   compare against is a way of being wrong. */

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/* Three significant figures, always - 4.82 MB, 48.2 MB, 482 MB - so the
   figure stays the same width as it grows and the eye is not dragged sideways
   once a minute by a column that got longer. Bytes are whole: there is no
   such thing as 4.82 bytes. */
function splitBytes(n) {
  let value = Math.max(0, n || 0);
  let unit = 0;
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) { value /= 1024; unit += 1; }
  const digits = unit === 0 ? 0 : (value >= 100 ? 0 : (value >= 10 ? 1 : 2));
  return [value.toFixed(digits), BYTE_UNITS[unit]];
}

/* Under half a kilobyte a second is a connection with nothing on it - a
   keep-alive, a clock sync - and printing "0 KB/s" for it invites the reader
   to wonder what broke. It did not break; nothing is being asked of it. */
function rateWords(bps) {
  if (!(bps > 512)) return 'idle';
  const [value, unit] = splitBytes(bps);
  return `${value} ${unit}/s`;
}

const flux = {
  el: $('flux'),
  canvas: $('fluxWave'),
  ctx: null,
  legs: null,
  live: false,

  /* One point per reading and not one more. An earlier version of this drew
     ten points a second by interpolating between readings and then rolled a
     travelling sine over the result to keep it moving. It looked like the
     sea, which is exactly what was wrong with it: every crest on screen was
     something this file had invented, and a meter that decorates its own
     line has stopped being a meter.

     So the structure here is the plain one, done properly. Three things,
     each doing one job:

       - a filter, so the line is calm. An exponential moving average over
         the readings, which is what every throughput graph worth reading
         uses and which is a statement about the data rather than an effect
         laid over it.
       - an interpolation, so the line is smooth. Monotone cubic - Fritsch
         and Carlson's - which passes through every point exactly and cannot
         overshoot between them. A Catmull-Rom or a plain quadratic will
         invent a bump wherever the data turns sharply, and an invented bump
         is the same lie as the sine was, just quieter.
       - a scroll, so the line is alive. Sub-pixel, from the clock, so it
         glides continuously between readings that arrive once a second.

     Nothing moves that the connection did not move. */
  span: 60,                  /* a minute of readings, at one a second */
  every: 1000,               /* how far apart they are meant to be */
  pts: [],                   /* the filtered readings, newest last */
  at: 0,                     /* when the newest one landed */

  /* How much of each new reading to believe at once. Two thirds: enough that
     a real change shows up inside two seconds, little enough that the
     second-to-second jitter of a download does not shake the line. */
  SMOOTH: 0.66,

  /* What is on screen against what was last reported. Readings land once a
     second and bytes do not arrive in once-a-second lumps, so the figures
     are walked toward the truth rather than dropped onto it. */
  shown: { down: 0, up: 0, downRate: 0, upRate: 0 },
  want: { down: 0, up: 0, downRate: 0, upRate: 0 },

  /* Each half of the band is scaled to its own peak. Shared, upload would be
     a flat line under every download that ever happened - true, and useless
     to have drawn. The two totals beside it are what carry the magnitudes;
     the wave carries the shape. */
  peak: { down: 32768, up: 8192 },
  floor: { down: 32768, up: 8192 },

  frame: null,
  said: {},
  box: { w: 0, h: 0 },
  ink: null,
  drawn: 0,

  arm() {
    if (!this.legs) {
      this.legs = {
        down: this.dress('fluxDownSum'),
        up: this.dress('fluxUpSum'),
        downRate: $('fluxDownRate'),
        upRate: $('fluxUpRate'),
      };
      this.ctx = this.canvas.getContext('2d');
      // Both of these are layout reads, and the loop below writes text on
      // the way past. Reading the canvas's size and the palette inside the
      // frame meant a forced reflow every frame to learn two things that
      // change when the window is resized and never otherwise. The observer
      // reports the size when it changes; the palette is read once.
      const skin = getComputedStyle(this.el);
      this.ink = {
        down: skin.getPropertyValue('--flux-down').trim() || '#61efbf',
        up: skin.getPropertyValue('--flux-up').trim() || '#9c8cff',
      };
      new ResizeObserver((seen) => {
        const r = seen[0].contentRect;
        this.box = { w: Math.round(r.width), h: Math.round(r.height) };
      }).observe(this.canvas);
      const now = this.canvas.getBoundingClientRect();
      this.box = { w: Math.round(now.width), h: Math.round(now.height) };
    }
    if (this.live) return;
    this.live = true;
    // Filled with silence rather than started empty. An empty buffer draws a
    // stub of a line in the right-hand corner and nothing else for the first
    // minute, which looks like an instrument that has not warmed up. Zeroes
    // are also the truth: the route came up a moment ago and nothing had
    // gone through it before that.
    this.pts = Array.from({ length: this.span }, () => ({ d: 0, u: 0 }));
    this.at = performance.now();
    this.shown = { down: 0, up: 0, downRate: 0, upRate: 0 };
    this.want = { down: 0, up: 0, downRate: 0, upRate: 0 };
    this.peak = { down: this.floor.down, up: this.floor.up };
    this.said = {};
    this.el.dataset.live = 'true';
    this.paint();
    this.run();
  },

  /* The digits and the unit are separate elements so the unit can be set
     smaller without the number being set in two sizes. Built once here
     rather than written into the page as markup on every reading - a
     figure that changes once a second is not a place to be reparsing HTML. */
  dress(id) {
    const host = $(id);
    host.textContent = '';
    const digits = document.createElement('span');
    const unit = document.createElement('i');
    unit.className = 'flux__unit';
    host.append(digits, unit);
    return { digits, unit };
  },

  stop() {
    if (!this.live) return;
    this.live = false;
    this.el.dataset.live = 'false';
    if (this.frame) { cancelAnimationFrame(this.frame); this.frame = null; }
    setFlow(0);
  },

  /* A reading from the worker: one point on the line, filtered, and the two
     totals, which are the worker's own count and are simply believed. */
  take(t) {
    this.arm();
    const down = Math.max(0, t.downRate || 0);
    const up = Math.max(0, t.upRate || 0);
    const was = this.pts[this.pts.length - 1] || { d: 0, u: 0 };
    this.pts.push({ d: was.d + (down - was.d) * this.SMOOTH,
                    u: was.u + (up - was.u) * this.SMOOTH });
    while (this.pts.length > this.span) this.pts.shift();
    this.at = performance.now();

    this.want = { down: t.down || 0, up: t.up || 0, downRate: down, upRate: up };
    // A total that went backwards is a new worker on a new port, not a
    // correction - there is nothing to ease toward, so it is taken whole.
    if (this.want.down < this.shown.down) this.shown.down = this.want.down;
    if (this.want.up < this.shown.up) this.shown.up = this.want.up;
    setFlow(pace(down + up));
    if (slowMotion.matches) { this.settle(1); this.paint(); }
  },

  /* Walk the shown figures toward the reported ones. `k` is how much of the
     remaining gap to close, worked out from the frame time so the pace is
     the same whether this is running at 30fps or 144. */
  settle(k) {
    for (const key of ['down', 'up', 'downRate', 'upRate']) {
      const gap = this.want[key] - this.shown[key];
      this.shown[key] += Math.abs(gap) < 0.5 ? gap : gap * k;
    }
  },

  paint() {
    const [dn, dunit] = splitBytes(this.shown.down);
    const [un, uunit] = splitBytes(this.shown.up);
    this.say('down', this.legs.down.digits, dn);
    this.say('dunit', this.legs.down.unit, dunit);
    this.say('up', this.legs.up.digits, un);
    this.say('uunit', this.legs.up.unit, uunit);
    this.say('drate', this.legs.downRate, rateWords(this.shown.downRate));
    this.say('urate', this.legs.upRate, rateWords(this.shown.upRate));
    this.draw();
  },

  /* Written only when it changed. At sixty frames a second, assigning six
     identical strings back into the document is sixty layout invalidations
     an app spends on nothing. */
  say(key, el, text) {
    if (this.said[key] === text) return;
    this.said[key] = text;
    el.textContent = text;
  },

  run() {
    if (slowMotion.matches) return;
    let last = performance.now();
    const tick = (now) => {
      this.frame = requestAnimationFrame(tick);
      if (!this.live) return;
      if (document.hidden) { last = now; return; }
      const dt = Math.min(now - last, 250) / 1000;
      last = now;
      this.settle(1 - Math.exp(-dt * 7));
      this.paint();
    };
    this.frame = requestAnimationFrame(tick);
  },

  draw() {
    const ctx = this.ctx;
    if (!ctx) return;
    const { w, h } = this.box;
    if (!w || !h) return;
    const dpr = Math.min(devicePixelRatio || 1, 2);
    if (this.canvas.width !== w * dpr || this.canvas.height !== h * dpr) {
      this.canvas.width = w * dpr;
      this.canvas.height = h * dpr;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    /* The rule sits low, not in the middle. Download gets three fifths of
       the band because download is what the question is usually about. */
    const rule = Math.round(h * 0.62) + 0.5;
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.075)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, rule);
    ctx.lineTo(w, rule);
    ctx.stroke();

    /* The scale is eased, and asymmetrically: it reaches a new peak in about
       a second and lets go of an old one over the best part of a minute. A
       scale that tracked the peak exactly would rescale the whole line the
       moment the tallest point in it scrolled off the left-hand edge, which
       reads as the line twitching for no reason. */
    const dt = Math.min(0.25, (performance.now() - this.drawn) / 1000) || 0;
    this.drawn = performance.now();
    for (const [key, pick] of [['down', (s) => s.d], ['up', (s) => s.u]]) {
      let top = 0;
      for (let i = 0; i < this.pts.length; i += 1) {
        top = Math.max(top, pick(this.pts[i]));
      }
      const target = Math.max(this.floor[key], top * 1.15);
      const k = 1 - Math.exp(-dt * (target > this.peak[key] ? 3 : 0.35));
      this.peak[key] += (target - this.peak[key]) * k;
    }

    /* How far between two readings we are. The whole line is shifted left by
       this much of one step, which is what makes it glide rather than jump
       once a second. */
    const frac = slowMotion.matches
      ? 0 : Math.min(1, (performance.now() - this.at) / this.every);
    const step = w / (this.span - 1);

    this.band(ctx, w, rule, rule - 3, (s) => s.d, this.peak.down,
              this.ink.down, 0.34, 0.92, step, frac, -1);
    this.band(ctx, w, rule, h - rule - 2, (s) => s.u, this.peak.up,
              this.ink.up, 0.24, 0.76, step, frac, 1);

    /* Where the line is now. The one lit thing in the band, and the only
       reason the eye knows which end is the present. */
    const head = this.pts[this.pts.length - 1];
    if (!slowMotion.matches && head && head.d > 512) {
      const y = rule - Math.min(1, head.d / this.peak.down) * (rule - 3);
      ctx.fillStyle = this.ink.down;
      ctx.shadowColor = this.ink.down;
      ctx.shadowBlur = 7;
      ctx.beginPath();
      ctx.arc(w - frac * step, y, 1.6, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    }

    /* The trace comes out of nothing on the left rather than being sliced
       off by the edge of the canvas. */
    const fade = ctx.createLinearGradient(0, 0, w * 0.24, 0);
    fade.addColorStop(0, 'rgba(0, 0, 0, 1)');
    fade.addColorStop(1, 'rgba(0, 0, 0, 0)');
    ctx.globalCompositeOperation = 'destination-out';
    ctx.fillStyle = fade;
    ctx.fillRect(0, 0, w * 0.24, h);
    ctx.globalCompositeOperation = 'source-over';
  },

  /* One direction: a filled area off the rule with the trace on top of it.
     `sign` is -1 for the half that grows upward and 1 for the half that
     hangs below, which is the only difference between them. */
  band(ctx, w, rule, room, pick, peak, colour, wash, ink, step, frac, sign) {
    const n = this.pts.length;
    if (n < 2) return;
    const ys = new Array(n);
    for (let i = 0; i < n; i += 1) {
      ys[i] = rule + sign * Math.min(1, pick(this.pts[i]) / peak) * room;
    }
    // The newest reading sits on the right-hand edge the moment it lands and
    // has slid one step left by the time the next one does.
    const x0 = w - (n - 1) * step - frac * step;

    const trace = () => {
      ctx.beginPath();
      curve(ctx, x0, step, ys);
    };

    const grad = ctx.createLinearGradient(0, rule + sign * room, 0, rule);
    grad.addColorStop(0, tint(colour, wash));
    grad.addColorStop(1, tint(colour, 0));
    trace();
    // Closed along the rule. The last point is up to one step short of the
    // right edge while a reading is in flight, so the fill is carried across
    // that gap flat rather than sloping down into the corner.
    ctx.lineTo(w, ys[n - 1]);
    ctx.lineTo(w, rule);
    ctx.lineTo(x0, rule);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    trace();
    ctx.lineTo(w, ys[n - 1]);
    ctx.strokeStyle = tint(colour, ink);
    ctx.lineWidth = 1.25;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.stroke();
  },
};

/* Monotone cubic through evenly spaced points - Fritsch & Carlson, 1980.
   The tangent at each point is the average of the slopes either side of it,
   then held back to three times the smaller of the two; that limit is the
   whole trick, and it is what stops the curve rising above a peak or dipping
   below a trough that the data never went to.

   The alternative - a Catmull-Rom, or the quadratic-through-midpoints that
   is the usual two-line answer - is smoother to write and wrong in exactly
   the way that matters here: it overshoots. On a graph whose entire job is
   to say how much went through, a curve that bulges past the highest reading
   is drawing traffic that never happened. */
function curve(ctx, x0, step, ys) {
  const n = ys.length;
  const slope = new Array(n - 1);
  for (let i = 0; i < n - 1; i += 1) slope[i] = (ys[i + 1] - ys[i]) / step;

  const m = new Array(n);
  m[0] = slope[0];
  m[n - 1] = slope[n - 2];
  for (let i = 1; i < n - 1; i += 1) {
    if (slope[i - 1] * slope[i] <= 0) {
      m[i] = 0;                       /* a turning point stays a turning point */
    } else {
      const t = (slope[i - 1] + slope[i]) / 2;
      const cap = 3 * Math.min(Math.abs(slope[i - 1]), Math.abs(slope[i]));
      m[i] = Math.sign(t) * Math.min(Math.abs(t), cap);
    }
  }

  ctx.moveTo(x0, ys[0]);
  const third = step / 3;
  for (let i = 0; i < n - 1; i += 1) {
    const x = x0 + i * step;
    ctx.bezierCurveTo(x + third, ys[i] + m[i] * third,
                      x + step - third, ys[i + 1] - m[i + 1] * third,
                      x + step, ys[i + 1]);
  }
}

/* #61efbf at four tenths. Written out rather than handed to the canvas as a
   colour with an alpha channel, because the palette is authored as six-digit
   hex in the stylesheet and reading it back is the only way these stay in
   step with it. */
function tint(hex, alpha) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/* How hard the line is working, from nothing to flat out, on a log scale -
   the difference between silence and a trickle matters as much as the
   difference between a trickle and a torrent, and on a straight scale the
   first one is invisible. Full at about sixteen megabytes a second. */
function pace(bps) {
  return Math.max(0, Math.min(1, Math.log10(1 + bps / 2048) / Math.log10(8193)));
}

/* The core answers the meter. The shader reads this to quicken the goo and
   the stylesheet reads it to swell the two satellites - so the connection
   visibly works harder without a second number being printed anywhere. */
function setFlow(v) {
  window.__flow = v;
  $('hero').style.setProperty('--flow', v.toFixed(3));
}

const slowMotion = matchMedia('(prefers-reduced-motion: reduce)');

window.onTraffic = (t) => {
  if (!t || !t.live || state.mode !== 'on') {
    flux.stop();
    $('logOpen').dataset.live = 'false';
    return;
  }
  flux.take(t);
  // The dot on the header icon, so the log announces itself as somewhere
  // worth looking without anything being opened to find that out.
  $('logOpen').dataset.live =
    String((t.downRate || 0) + (t.upRate || 0) > 512);
};

/* ------------------------------------------------------------------ log */

/* What is using the connection, and where it went.

   Every row came from the worker's ledger, which counts the same bytes the
   meter counts, at the same moment and under the same lock. Nothing here is
   estimated and nothing is inferred from anything else on the page.

   Two groupings over the one set of rows. The worker keys its ledger by
   destination *and* program together, which is what makes both possible
   without asking it twice: grouped one way it says where the traffic went,
   grouped the other it says what sent it. A single flat list would have had
   to pick one of those questions and leave the other unanswered.

   Hostnames and program names are whatever a program on this machine asked
   for, so they are put into the page as text nodes and never as markup. */

const log = {
  by: 'host',            /* or 'app' */
  poll: null,
  rows: [],
  open: false,

  show() {
    if (this.open) return;
    this.open = true;
    $('log').showModal();
    this.slide();
    this.pull();
    // The worker rewrites the list every other second, so asking faster
    // would be reading the same answer twice.
    this.poll = setInterval(() => this.pull(), 2000);
  },

  hide() {
    this.open = false;
    clearInterval(this.poll);
    this.poll = null;
    $('log').close();
  },

  async pull() {
    let said = null;
    try {
      said = await window.pywebview.api.hosts();
    } catch (err) {
      said = null;
    }
    if (!this.open) return;
    this.rows = (said && said.live && said.rows) || [];
    this.draw(said);
  },

  group() {
    if (this.by === 'host') {
      // Already one row per destination and program. Rows for the same host
      // from two different programs are folded together here, and the
      // programs become the caption.
      return this.fold((r) => r.host, (r) => r.app);
    }
    return this.fold((r) => r.app || 'Not identified', (r) => r.host);
  },

  /* Rows keyed by `name`, remembering the distinct `other` values that went
     into each - which is what the second line of the row says. */
  fold(name, other) {
    const out = new Map();
    for (const r of this.rows) {
      const key = name(r);
      let g = out.get(key);
      if (!g) {
        g = { key, up: 0, down: 0, hits: 0, live: 0, others: new Set() };
        out.set(key, g);
      }
      g.up += r.up;
      g.down += r.down;
      g.hits += r.hits;
      g.live += r.live;
      const o = other(r);
      if (o) g.others.add(o);
    }
    return [...out.values()].sort((a, b) => (b.up + b.down) - (a.up + a.down));
  },

  draw(said) {
    const list = $('logList');
    const groups = this.group();
    list.textContent = '';

    if (!groups.length) {
      const p = document.createElement('p');
      p.className = 'lnone';
      p.textContent = said && said.live
        ? 'Nothing has gone through yet. Open something and it will show up here.'
        : 'Nothing is routed right now. Connect, and this fills in as your programs start talking.';
      list.append(p);
      $('logSum').textContent = '—';
      $('logFoot').textContent = '';
      $('logOpen').dataset.live = 'false';
      return;
    }

    const top = groups[0].up + groups[0].down;
    let up = 0;
    let down = 0;
    let live = 0;
    for (const g of groups) { up += g.up; down += g.down; live += g.live; }

    // Short words and no spaces inside the figures. This shares a line with
    // the two tabs in a 400px window, and the first thing that went was the
    // upload total off the right-hand end where nobody could see it had.
    const [dn, du] = splitBytes(down);
    const [un, uu] = splitBytes(up);
    $('logSum').textContent =
      `${groups.length} ${this.by === 'host' ? 'hosts' : 'programs'}`
      + `  ·  ↓${dn}${du}  ↑${un}${uu}`;
    $('logOpen').dataset.live = live > 0 ? 'true' : 'false';

    for (const g of groups) list.append(this.row(g, top));

    // Said only when it is true. The worker keeps the hundred busiest pairs
    // and nothing else, and a list that quietly stopped at a hundred while
    // looking complete is the kind of thing this app is meant not to do.
    //
    // Counted in the worker's own rows rather than in the groups above: the
    // groups fold two rows into one wherever a host was reached by two
    // programs, so a number taken from them would not be the number the
    // worker left out.
    const missing = ((said && said.total) || 0) - this.rows.length;
    $('logFoot').textContent = missing > 0
      ? `${missing} quieter ${missing === 1 ? 'one is' : 'ones are'} counted in the meter but not listed here.`
      : '';
  },

  row(g, top) {
    const el = document.createElement('div');
    el.className = 'lrow';
    el.style.setProperty('--share',
      `${Math.max(1.5, ((g.up + g.down) / (top || 1)) * 100).toFixed(1)}%`);

    const name = document.createElement('p');
    name.className = 'lrow__name';
    if (g.live > 0) {
      const dot = document.createElement('i');
      dot.className = 'lrow__live';
      name.append(dot);
    }
    const who = document.createElement('span');
    who.className = 'lrow__who';
    who.textContent = g.key;
    name.append(who);

    const [sn, su] = splitBytes(g.up + g.down);
    const sum = document.createElement('p');
    sum.className = 'lrow__sum mono';
    sum.textContent = `${sn} ${su}`;

    const sub = document.createElement('p');
    sub.className = 'lrow__sub';
    sub.textContent = this.caption(g);

    const [dn, du] = splitBytes(g.down);
    const [un, uu] = splitBytes(g.up);
    const split = document.createElement('p');
    split.className = 'lrow__split mono';
    const d = document.createElement('b');
    d.textContent = `↓${dn}${du}`;
    const u = document.createElement('i');
    u.textContent = `↑${un}${uu}`;
    split.append(d, document.createTextNode('  '), u);

    el.append(name, sum, sub, split);
    return el;
  },

  /* The other half of the pair, and how many times it was asked for. One
     name when there is one, a count when there are several - "4 programs"
     says as much as four names would in a row that is 200px wide. */
  caption(g) {
    const many = g.others.size;
    const asked = `${g.hits} connection${g.hits === 1 ? '' : 's'}`;
    if (!many) {
      return this.by === 'host'
        ? `not identified · ${asked}` : asked;
    }
    if (many === 1) return `${[...g.others][0]} · ${asked}`;
    const what = this.by === 'host' ? 'programs' : 'destinations';
    return `${many} ${what} · ${asked}`;
  },

  pick(by) {
    if (this.by === by) return;
    this.by = by;
    for (const b of $('logPair').querySelectorAll('.pair__opt')) {
      b.setAttribute('aria-selected', String(b.dataset.by === by));
    }
    this.slide();
    this.draw({ live: true, total: this.rows.length });
  },

  /* The lit backing, moved to sit under whichever word is chosen. Measured
     rather than hard-coded: the two words are different lengths, and they
     are different lengths again in every language this is ever translated
     into. */
  slide() {
    const chosen = $('logPair').querySelector('[aria-selected="true"]');
    const bar = $('logPair').querySelector('.pair__slide');
    if (!chosen || !bar) return;
    bar.style.width = `${chosen.offsetWidth}px`;
    bar.style.transform = `translateX(${chosen.offsetLeft - 2}px)`;
  },
};

/* -------------------------------------------------------------- actions */

async function onAct() {
  if (state.mode === 'busy') {
    setStatus('CANCELLING', 'off', 'Stopping', '');
    await window.pywebview.api.cancel();
    return;
  }
  if (state.mode === 'on') {
    setStatus('DISCONNECTING', 'off', 'Putting Windows back', '');
    const r = await window.pywebview.api.disconnect();
    window.onDisconnected(r && r.session);
    return;
  }
  state.mode = 'busy';
  render();
  setStatus('CONNECTING', 'busy', 'Looking for a server', '');
  setHint('');
  const r = await window.pywebview.api.connect(state.picked);
  if (!r.ok) { state.mode = 'off'; render(); }
}

async function copyIp(el) {
  const text = (el.textContent || '').trim();
  if (!text || text === '—' || text === '…' || text === 'unknown') return;
  try {
    await navigator.clipboard.writeText(text);
  } catch (err) {
    await window.pywebview.api.copy(text);
  }
  el.classList.add('copied');
  setTimeout(() => el.classList.remove('copied'), 700);
}

/* ---------------------------------------------------------------- prefs */

function paintPrefs(info) {
  $('sysProxy').setAttribute('aria-checked', String(state.systemProxy));
  if (info.port) state.port = info.port;
  paintPort();
  $('folderPath').textContent = info.folder || '—';
  $('folderCount').textContent =
    `${info.serverCount || 0} servers · ${state.countries.length} countries`;
  $('aboutPaths').textContent = info.about || '';

  // Whether anything can connect at all. What is signed in as what is the
  // roster's business now, and it paints itself - but the rest of the window
  // still needs to know whether there is a credential behind the button.
  state.hasCredentials = info.hasCredentials !== false;
}

function said(el, text, kind) {
  el.textContent = text || '';
  el.classList.toggle('is-bad', kind === 'bad');
  el.classList.toggle('is-good', kind === 'good');
}

async function refreshPrefs() {
  const info = await window.pywebview.api.info();
  state.countries = info.countries || state.countries;
  state.fuse = new Fuse(state.countries, {
    keys: ['name', 'code', 'alias'], threshold: 0.4, ignoreLocation: true,
  });
  paintPrefs(info);
  state.favourites = info.favourites || state.favourites;
  state.sortBy = info.sortBy || state.sortBy;
  paintSort();
  paintUse(info);
  // Its own call, and allowed to fail on its own. Who this app signs in as
  // is a question for the roster, and folding it into info() would mean an
  // unreadable accounts file could keep the rest of the sheet from painting.
  acctRefresh().catch(() => {});
  render();
  return info;
}

/* ---------------------------------------------------------------- port */

function paintPort() {
  $('routeAddr').textContent = `127.0.0.1:${state.port}`;
  // Never while it is being typed in. The field is committed on leaving it,
  // not on every keystroke - moving a live connection is a worker restart,
  // and doing that four times to type "9050" would be four restarts.
  if (document.activeElement !== $('listenPort')) {
    $('listenPort').value = String(state.port);
  }
}

async function commitPort() {
  const typed = $('listenPort').value.trim();
  if (typed === String(state.port)) { said($('portSaid'), ''); return; }

  const moving = state.mode === 'on';
  if (moving) said($('portSaid'), 'Moving the connection…');
  const r = await window.pywebview.api.setPort(typed);

  // Whatever happened, the field goes back to the port that is really being
  // listened on. A refused number left sitting in the box is a window
  // claiming a port nothing is on.
  state.port = r.port || state.port;
  $('listenPort').value = String(state.port);
  paintPort();

  if (!r.ok) { said($('portSaid'), r.error, 'bad'); return; }
  if (r.moved) {
    said($('portSaid'), `Moved. Windows is now pointed at 127.0.0.1:${state.port}.`, 'good');
  } else if (r.busy) {
    said($('portSaid'),
         `Saved, but something already holds ${state.port} — until that stops, `
         + 'this will not come up.', 'bad');
  } else {
    said($('portSaid'), `Saved. It will listen on ${state.port}.`, 'good');
  }
}

/* ------------------------------------------------------------- sign-in */

/* ------------------------------------------ pinning them to real addresses */

/* The line under the middle node of the flow. It is the only one of the three
   that can be wrong, so it is the only one that says anything. */
const PIN_DIRECT_SAID =
  'No proxy at all. Where DNS is answered honestly this is all you need; '
  + 'where it is not, this stops and says so rather than writing an address '
  + 'that was invented to send you nowhere.';
const PIN_PROXY_SAID =
  'v2rayN, Nekoray, Clash and sing-box all listen on 10808 unless you moved '
  + 'them. Only the DoH lookups go this way — nothing else does.';

function lockPin(locked) {
  for (const id of ['pinPick', 'pinOutPick', 'pinPort', 'pinMax', 'pinTest',
                    'pinMaxUp', 'pinMaxDown']) {
    $(id).disabled = locked;
  }
  for (const b of $('pinRoute').querySelectorAll('.seg__opt')) b.disabled = locked;
}

/* The reason a config produced nothing, in the width there is for it. The
   whole sentence stays on the row as its title - this is the column, not the
   explanation. */
function shortWhy(detail) {
  const said = (detail || '').toLowerCase();
  if (said.startsWith('could not resolve')) return 'not resolved';
  if (said.startsWith('no remote line')) return 'no remote';
  return said.split(',')[0].slice(0, 20) || 'skipped';
}

const PIN_MARK = {
  reachable: { href: '#i-check', className: 'is-served', title: 'answers' },
  unreachable: { href: '#i-x', className: 'is-quiet', title: 'no answer' },
  skipped: { href: '#i-x', className: 'is-refused', title: 'nothing written' },
  forged: { href: '#i-x', className: 'is-refused', title: 'thrown away' },
};

function pinRow(outcome, name, note, title) {
  const li = document.createElement('li');
  li.dataset.outcome = outcome;
  if (title) li.title = title;

  const who = document.createElement('span');
  who.className = 'tally__name';
  who.textContent = name;

  const what = document.createElement('span');
  what.className = 'tally__took mono';
  what.textContent = note;

  li.append(who, what);
  // Colour alone does not say reachable from not, so the ones that were
  // knocked on carry a mark as well.
  const mark = PIN_MARK[outcome];
  if (mark) li.append(icon(mark.href, `ico tally__mark ${mark.className}`,
                           mark.title));
  $('pinTally').prepend(li);
}

function drawPin(p) {
  const meter = $('pinMeter');

  if (p.phase === 'starting') {
    state.pinning = { total: p.total || 0, done: 0 };
    $('pinTally').replaceChildren();
    $('pinTally').hidden = false;
    $('pinUse').hidden = true;
    meter.hidden = false;
    $('pinBar').style.width = '0%';
    $('pinGo').dataset.mode = 'running';
    lockPin(true);
    said($('pinSaid'), p.route === 'proxy'
      ? `Asking through 127.0.0.1:${p.port}…`
      : 'Asking directly…');
    return;
  }

  const run = state.pinning || (state.pinning = { total: 0, done: 0 });

  if (p.phase === 'route') {
    // It answered, which is more than the socket test knew: something is
    // there and it carried a real lookup.
    $('pinRouteStep').dataset.live = 'on';
    said($('pinRouteSaid'), p.via === 'direct'
      ? 'Answering directly.' : `Going through ${p.via}.`, 'good');
    return;
  }

  if (p.phase === 'resolving') {
    run.total = p.total || run.total;
    said($('pinSaid'), `Reading ${run.total} config${run.total === 1 ? '' : 's'}…`);
    return;
  }

  if (p.phase === 'forged') {
    // The censor caught in the act, and the whole reason this exists. It
    // belongs on the list, not in a line the next event overwrites.
    pinRow('forged', p.host, p.addresses,
           `${p.host} was answered with ${p.addresses}, which is not a public `
           + 'address. Thrown away.');
    return;
  }

  if (p.phase === 'result') {
    run.done = p.done;
    run.total = p.total || run.total;
    $('pinBar').style.width =
      `${Math.round((run.done / Math.max(1, run.total)) * 100)}%`;
    if (p.outcome === 'skipped') {
      pinRow('skipped', p.source, shortWhy(p.detail),
             `${p.source} — ${p.detail}`);
    } else {
      pinRow(p.outcome, p.name, p.ip,
             p.outcome === 'udp'
               ? `${p.ip}:${p.port} over UDP, which cannot be knocked on: `
                 + 'silence and success look the same.'
               : `${p.ip}:${p.port}`);
    }
    said($('pinSaid'), `${run.done} of ${run.total} read`);
    return;
  }

  if (p.phase === 'finished') {
    state.pinning = null;
    $('pinGo').dataset.mode = 'idle';
    lockPin(false);
    meter.hidden = true;
    if (state.pinPlan) state.pinPlan.existing = p.inFolder;
    drawPinUse(p.out, p.inFolder);

    if (p.error) {
      said($('pinSaid'), p.error, 'bad');
      $('pinRouteStep').dataset.live = 'off';
    } else if (p.cancelled) {
      said($('pinSaid'),
           `Stopped. ${p.written} file${p.written === 1 ? '' : 's'} were `
           + 'written before it was, and they are as good as any other.');
    } else {
      const dead = p.unreachable
        ? ` ${p.unreachable} address${p.unreachable === 1 ? '' : 'es'} `
          + 'did not answer.' : '';
      const lost = p.skipped
        ? `, ${p.skipped} could not be resolved` : '';
      said($('pinSaid'),
           `Done. ${p.written} file${p.written === 1 ? '' : 's'} from `
           + `${p.read} config${p.read === 1 ? '' : 's'}${lost}.${dead}`,
           p.written ? 'good' : 'bad');
    }
    // The folder it just wrote into is one the sweep could be pointed at, and
    // possibly the one the app already connects through.
    refreshSweep();
  }
}

window.onPin = drawPin;

/* One chip, and it is a verb: a pinned folder is worth nothing until
   something is reading from it. */
function drawPinUse(folder, count) {
  const list = $('pinUse');
  list.replaceChildren();
  list.hidden = !(folder && count);
  if (list.hidden) return;
  const mine = folder === $('folderPath').textContent;
  const li = document.createElement('li');
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'chip' + (mine ? ' is-current' : '');
  b.dataset.folder = folder;
  b.disabled = mine;
  b.title = mine ? `Already connecting through these — ${folder}`
                 : `Connect through these instead — ${folder}`;
  const name = document.createElement('span');
  name.textContent = mine ? 'Connecting through these' : 'Connect through these';
  const n = document.createElement('span');
  n.className = 'chip__count';
  n.textContent = String(count);
  b.append(name, n);
  li.append(b);
  list.append(li);
}

function paintPinPlan(plan) {
  state.pinPlan = plan;
  $('pinFolder').textContent = plan.folder || '—';
  $('pinOut').textContent = plan.out || '—';
  $('pinCount').textContent = plan.total
    ? `${plan.total} waiting` : 'nothing waiting';

  $('pinRoute').dataset.route = plan.route;
  for (const b of $('pinRoute').querySelectorAll('.seg__opt')) {
    b.setAttribute('aria-pressed', String(b.dataset.route === plan.route));
  }
  $('pinPortWrap').hidden = plan.route !== 'proxy';
  // Never while it is being typed in: the answer to a keystroke arrives after
  // the next one, and a field that rewrites itself under the cursor cannot be
  // typed in at all.
  if (document.activeElement !== $('pinPort')) $('pinPort').value = String(plan.port);
  if (document.activeElement !== $('pinMax')) $('pinMax').value = String(plan.maxIps);
  $('pinTest').setAttribute('aria-checked', String(plan.test !== false));
  drawPinUse(plan.out, plan.existing);

  const blockers = plan.blockers || [];
  const proxyGone = blockers.find((b) => b.kind === 'no-proxy');

  if (plan.route !== 'proxy') {
    $('pinRouteStep').removeAttribute('data-live');
    said($('pinRouteSaid'), PIN_DIRECT_SAID);
  } else if (!plan.checked) {
    // Nothing was asked, so nothing is claimed. The node goes blank rather
    // than keeping an answer about a port that may have changed since.
    $('pinRouteStep').removeAttribute('data-live');
    said($('pinRouteSaid'), PIN_PROXY_SAID);
  } else if (proxyGone) {
    $('pinRouteStep').dataset.live = 'off';
    said($('pinRouteSaid'), proxyGone.say, 'bad');
  } else {
    $('pinRouteStep').dataset.live = 'on';
    said($('pinRouteSaid'),
         `Something is listening on 127.0.0.1:${plan.port}, so the lookups `
         + 'have somewhere to go.', 'good');
  }

  $('pinGo').disabled = blockers.length > 0;
  const other = blockers.find((b) => b.kind !== 'no-proxy');
  if (other) {
    said($('pinSaid'), other.say, 'bad');
  } else if (proxyGone) {
    said($('pinSaid'), '');            // the line above the button has it
  } else {
    said($('pinSaid'),
         `${plan.total} config${plan.total === 1 ? '' : 's'} · up to `
         + `${plan.most} file${plan.most === 1 ? '' : 's'} out · about `
         + `${plan.minutes} minute${plan.minutes === 1 ? '' : 's'}.`);
  }
}

async function refreshPin(check) {
  if (state.pinning) return;           // it is running; leave the readout alone
  paintPinPlan(await window.pywebview.api.pinPlan(null, null, !check));
}

/* The socket test is the slow half of a plan, so it is kept off the keystroke
   path - and kept from getting out of order with itself. Typing four digits
   would otherwise be four connection attempts whose answers can land in any
   order, and the last one to arrive would be the one believed. */
let pinAsked = 0;
async function checkPinProxy() {
  const mine = ++pinAsked;
  const plan = await window.pywebview.api.pinPlan(null, null, false);
  if (mine === pinAsked && !state.pinning) paintPinPlan(plan);
}

let pinPortTimer = null;
function commitPinPort() {
  clearTimeout(pinPortTimer);
  pinPortTimer = setTimeout(async () => {
    paintPinPlan(await window.pywebview.api.setPinRoute(null, $('pinPort').value));
    checkPinProxy();
  }, 350);
}

/* -------------------------------------------------- timing the servers */

const SWEEP_MINUTES_EACH = 0.25;   // the sweep script's own reckoning

function minutesLeft(done, total, elapsed) {
  // Measured once there is anything to measure from, and the script's flat
  // guess before that. Three servers in, this line is worth more than the
  // guess, because how long a server takes is mostly a property of the line
  // it is being reached over.
  const each = done >= 3 ? (elapsed / done) / 60 : SWEEP_MINUTES_EACH;
  return Math.max(1, Math.ceil((total - done) * each));
}

function drawSweep(p) {
  const meter = $('sweepMeter');
  const tally = $('sweepTally');

  if (p.phase === 'elevating') {
    state.sweep = { started: Date.now() / 1000, total: p.total || 0, done: 0 };
    tally.replaceChildren();
    tally.hidden = false;
    meter.hidden = false;
    $('sweepBar').style.width = '0%';
    $('sweepGo').dataset.mode = 'running';
    $('sweepPick').disabled = true;
    $('sweepSites').disabled = true;
    $('sweepFirst').disabled = true;
    lockChoices(true);
    said($('sweepSaid'), 'Waiting for Windows to allow it…');
    return;
  }

  const run = state.sweep || (state.sweep = { started: Date.now() / 1000, total: 0, done: 0 });

  if (p.phase === 'testing') {
    run.done = p.done;
    run.total = p.total;
    $('sweepBar').style.width = `${Math.round((p.done / Math.max(1, p.total)) * 100)}%`;
    const left = minutesLeft(p.done - 1, p.total, Date.now() / 1000 - run.started);
    said($('sweepSaid'),
         `${p.done} of ${p.total} · about ${left} minute${left === 1 ? '' : 's'} left`);
    return;
  }

  if (p.phase === 'result') {
    // Rows are rewritten as more is learned about the same server - the
    // verdict lands after the handshake time, and each named site after that
    // - so a row is replaced rather than added twice.
    const key = `${run.done}:${p.name}`;
    const li = tally.firstElementChild && tally.firstElementChild.dataset.key === key
      ? tally.firstElementChild : document.createElement('li');
    li.replaceChildren();
    li.dataset.key = key;
    li.dataset.outcome = p.outcome;

    const name = document.createElement('span');
    name.className = 'tally__name';
    name.textContent = p.name || '—';

    const sites = document.createElement('span');
    sites.className = 'tally__sites';
    for (const s of p.sites || []) {
      sites.append(icon(s.served ? '#i-check' : '#i-x',
                        `ico ${s.served ? 'is-served' : 'is-refused'}`,
                        `${s.host} — ${s.served ? 'served' : 'refused'}`));
    }

    const took = document.createElement('span');
    took.className = 'tally__took';
    took.textContent = p.outcome === 'up'
      ? `${p.seconds != null ? p.seconds.toFixed(1) : '?'}s${p.verdict ? ` · ${p.verdict}` : ''}`
      : ({ noconnect: 'would not come up',
           unreachable: 'no answer',
           noroute: 'no route' })[p.outcome] || p.outcome;

    li.append(name, sites, took);
    if (li.parentNode !== tally) tally.prepend(li);
    return;
  }

  if (p.phase === 'finished') {
    state.sweep = null;
    $('sweepGo').dataset.mode = 'idle';
    $('sweepPick').disabled = false;
    $('sweepSites').disabled = false;
    $('sweepFirst').disabled = false;
    lockChoices(false);
    meter.hidden = true;
    drawSiteFolders(p.siteFolders, $('folderPath').textContent);
    if (p.error) {
      said($('sweepSaid'), p.error, 'bad');
    } else if (p.cancelled) {
      said($('sweepSaid'),
           `Stopped. ${p.worked} of ${p.tested} tested so far came up.`);
    } else {
      said($('sweepSaid'),
           `Done. ${p.worked} of ${p.tested} came up`
           + (p.quickest != null ? `, quickest in ${p.quickest.toFixed(1)}s.` : '.'),
           'good');
    }
    // The folder it just wrote into is the one the list is read from, so the
    // countries and their times have changed underneath us.
    refreshPrefs();
  }
}

window.onSweep = drawSweep;

function drawSiteFolders(folders, current) {
  const list = $('siteFolders');
  list.replaceChildren();
  list.hidden = !(folders && folders.length);
  for (const f of folders || []) {
    const li = document.createElement('li');
    const b = document.createElement('button');
    const mine = f.folder === current;
    b.type = 'button';
    b.className = 'chip' + (mine ? ' is-current' : '');
    b.dataset.folder = f.folder;
    b.disabled = mine;
    b.title = mine
      ? `Already connecting through these — ${f.folder}`
      : `Connect only through the servers that reached this — ${f.folder}`;
    const name = document.createElement('span');
    name.textContent = f.tag;
    const count = document.createElement('span');
    count.className = 'chip__count';
    count.textContent = mine ? `${f.count} · in use` : `${f.count}`;
    b.append(name, count);
    li.append(b);
    list.append(li);
  }
}

// Once it is running the choices are settled, and a control that still looks
// live but changes nothing is worse than one that is plainly out of reach.
function lockChoices(locked) {
  for (const el of document.querySelectorAll('#sweepScope .scope__row, '
                                             + '#sweepLords .chip, #lookUpOwners')) {
    el.disabled = locked;
  }
}

function drawScope(plan) {
  for (const row of $('sweepScope').querySelectorAll('.scope__row')) {
    const scope = row.dataset.scope;
    row.setAttribute('aria-pressed', String(scope === plan.scope));
    // The number is what changed, so the number is what says so.
    const cell = row.querySelector('.scope__count');
    const now = (plan.scopeCounts || {})[scope];
    const text = now == null ? '—' : String(now);
    if (cell.textContent !== text) {
      cell.textContent = text;
      cell.classList.remove('turned');
      void cell.offsetWidth;
      cell.classList.add('turned');
    }
    const n = now;
    // A grouping that comes to nothing is not a choice. Grouping by company
    // on a folder whose addresses have never been looked up is the case that
    // matters: it reads zero, and offering it would be offering a dead end.
    const dead = !state.sweep && n === 0;
    row.disabled = !!state.sweep || dead;
    row.title = dead && scope.startsWith('company')
      ? 'No address here has been looked up, so there are no companies to group by'
      : '';
  }
}

function drawLandlords(plan) {
  const list = $('sweepLords');
  const chosen = new Set(plan.chosen || []);
  list.replaceChildren();

  for (const c of plan.companies || []) {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip';
    b.dataset.lord = c.name;
    b.setAttribute('aria-pressed', String(chosen.has(c.name)));
    b.title = c.countries.length
      ? `${c.count} addresses in ${c.countries.slice(0, 6).join(', ')}`
      : `${c.count} addresses`;

    const owner = document.createElement('span');
    owner.textContent = c.owner;
    const asn = document.createElement('span');
    asn.className = 'chip__asn';
    asn.textContent = c.asn || '';
    const count = document.createElement('span');
    count.className = 'chip__count';
    count.textContent = c.count;

    b.append(owner, asn, count);
    li.append(b);
    list.append(li);
  }

  const traced = (plan.companies || []).length;
  const untraced = plan.untraced || 0;
  $('lookUpRow').hidden = !untraced;
  if (!traced && !untraced) {
    said($('lordsSaid'), '');
  } else if (untraced) {
    // Named rather than glossed over: choosing any company at all leaves
    // these out, so a count that quietly shrinks would be the app hiding its
    // own arithmetic.
    said($('lordsSaid'),
         `${traced} compan${traced === 1 ? 'y' : 'ies'} · ${untraced} address`
         + `${untraced === 1 ? '' : 'es'} not looked up yet, and picking any `
         + `company leaves ${untraced === 1 ? 'it' : 'them'} out.`);
  } else {
    said($('lordsSaid'),
         `${traced} compan${traced === 1 ? 'y' : 'ies'}, every address traced.`);
  }
}

function paintSweepPlan(plan) {
  state.plan = plan;
  $('sweepFolder').textContent = plan.folder || '—';
  if (document.activeElement !== $('sweepSites')) {
    $('sweepSites').value = (plan.sites || []).join(', ');
  }
  if (document.activeElement !== $('sweepFirst')) {
    $('sweepFirst').value = plan.first ? String(plan.first) : '';
  }
  drawScope(plan);
  drawLandlords(plan);
  drawSiteFolders(plan.siteFolders, plan.into);

  const blocked = (plan.blockers || [])[0];
  $('sweepGo').disabled = !!blocked || !plan.count;
  if (blocked) {
    said($('sweepSaid'), blocked.say, 'bad');
    return;
  }
  // Nothing to test, and never just "0 servers" - the number on its own reads
  // as an empty folder, when the usual cause is a grouping that has nothing to
  // group by yet.
  if (!plan.count) {
    said($('sweepSaid'),
         (plan.companies || []).length
           ? 'Those choices leave no servers to test.'
           : 'Nothing here has been looked up yet, so there are no companies '
             + 'to group by. Look the rest up, or test every address.',
         'bad');
    return;
  }
  const named = (plan.sites || []).length;
  // The folder's own number stays in view whenever the choices have cut it
  // down, because "16 servers" on its own reads like the folder only has 16.
  const of = plan.total && plan.total !== plan.count ? ` of ${plan.total}` : '';
  said($('sweepSaid'),
       `${plan.count}${of} servers · about ${plan.minutes} minute`
       + `${plan.minutes === 1 ? '' : 's'}, and the connection drops for each one.`
       + (named ? ` Each one is also asked for ${named} site${named === 1 ? '' : 's'}.` : ''));
}

async function refreshSweep(folder) {
  if (state.sweep) return;             // it is running; leave the readout alone
  paintSweepPlan(await window.pywebview.api.sweepPlan(folder || null));
}

/* ---------------------------------------------------------------- start */

async function boot() {
  const info = await window.pywebview.api.boot();
  state.countries = info.countries || [];
  state.picked = info.picked || 'auto';
  state.systemProxy = info.systemProxy !== false;
  state.port = info.port || state.port;
  state.fuse = new Fuse(state.countries, {
    keys: ['name', 'code', 'alias'], threshold: 0.4, ignoreLocation: true,
  });
  paintPrefs(info);

  if (!info.hasCredentials) {
    setStatus('NOT SET UP', 'fail', 'No sign-in details yet', '');
    setHint('Put your Surfshark service username and password in Settings, and this can connect.', true);
    $('act').disabled = true;
    render();
    // Opened rather than pointed at. There is exactly one thing to do from
    // here and one place to do it, and making somebody find the cog first is
    // asking them to guess at a step the app already knows.
    $('prefs').showModal();
    refreshSweep();
    refreshPin(true);
    // Straight at the one thing there is to do from here.
    setTimeout(() => $('acctAdd').focus(), 60);
    return;
  }
  if (info.recovered) {
    setHint('The app did not close properly last time. Windows proxy settings have been put back.');
  }

  const s = info.status || {};
  if (s.state === 'on') {
    window.onConnected(s);
  } else {
    state.mode = 'off';
    setStatus('DISCONNECTED', 'off', '', '');
    if (!info.recovered) setHint(`${state.countries.length} countries ready.`);
    render();
  }
  // Asked for once the window is already usable: it is a network round trip
  // and nothing on screen depends on it arriving.
  window.pywebview.api.whoami();
  warmFlags(state.countries.map((c) => c.code));
  // Built now, while nothing is waiting for it, rather than in the frame the
  // sheet is animating in. The dialog is closed, so none of this paints - it
  // is the DOM work that is being moved, and the flags are being fetched
  // alongside it by the line above.
  drawList('');
}

/* --------------------------------------------------------------- wiring */

$('act').addEventListener('click', onAct);
$('pick').addEventListener('click', openPicker);
$('pickerClose').addEventListener('click', () => $('picker').close());
$('search').addEventListener('input', (e) => {
  state.cursor = 0;
  // The wash over the placeholder is only there to soften a hint. Once there
  // is something to read it would be softening that instead.
  $('searchMain').classList.toggle('is-typed', e.target.value.length > 0);
  drawList(e.target.value);
});
$('realIp').addEventListener('click', (e) => copyIp(e.currentTarget));
$('exitIp').addEventListener('click', (e) => copyIp(e.currentTarget));

$('logOpen').addEventListener('click', () => log.show());
$('logClose').addEventListener('click', () => log.hide());
// Esc closes a <dialog> without going through the button, and a poll left
// running against a sheet nobody is looking at is a request every two
// seconds for the rest of the afternoon.
$('log').addEventListener('close', () => log.hide());
$('logPair').addEventListener('click', (e) => {
  const opt = e.target.closest('.pair__opt');
  if (opt) log.pick(opt.dataset.by);
});

$('more').addEventListener('click', () => {
  const open = $('details').hidden;
  $('details').hidden = !open;
  $('more').setAttribute('aria-expanded', String(open));
});

// One listener on the list rather than one per row, so a re-render cannot
// leave a stale handler behind.
$('list').addEventListener('click', (e) => {
  // The star first, because it lives inside a row that would otherwise take
  // the click and connect somewhere.
  const star = e.target.closest('[data-star]');
  if (star) { e.stopPropagation(); toggleFavourite(star.dataset.star); return; }
  const hosts = e.target.closest('[data-exits]');
  if (hosts) {
    e.stopPropagation();
    toggleExits(hosts.dataset.exits, hosts.closest('.row'));
    return;
  }
  const open = e.target.closest('[data-expand]');
  if (open) {
    e.stopPropagation();
    const code = open.dataset.expand;
    if (state.openCities.has(code)) state.openCities.delete(code);
    else state.openCities.add(code);
    $('list').dataset.key = '';
    drawList($('search').value.trim());
    return;
  }
  // An exit row picks that one exit; the row that opens them is not a pick
  // at all and is handled where the opening is.
  const exit = e.target.closest('.exit');
  if (exit) { choose(exit.dataset.code); return; }
  const row = e.target.closest('.row');
  if (row) choose(row.dataset.code);
});

$('picker').addEventListener('keydown', (e) => {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    state.cursor += e.key === 'ArrowDown' ? 1 : -1;
    markCursor();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    const r = rows()[state.cursor];
    if (r) choose(r.dataset.code);
  }
});

// The hint is visible in the search field, so make it real: while the
// location sheet is open, / returns focus to search from anywhere in it.
window.addEventListener('keydown', (e) => {
  if (e.key === '/' && $('picker').open && e.target !== $('search')) {
    e.preventDefault();
    $('search').focus();
  }
});

$('settings').addEventListener('click', async () => {
  await refreshPrefs();
  $('prefs').showModal();
  // After the sheet is up rather than before it. One shells out to PowerShell
  // to ask whether another VPN holds the default route and the other opens a
  // socket to the proxy; waiting on either to show a settings sheet would be
  // a second of nothing.
  refreshSweep();
  refreshPin(true);
});
$('prefsClose').addEventListener('click', () => $('prefs').close());


$('pinPick').addEventListener('click', async () => {
  const r = await window.pywebview.api.choosePinFolder();
  if (r && r.ok) { paintPinPlan(r); checkPinProxy(); }
  else if (r && r.error) said($('pinSaid'), r.error, 'bad');
});

$('pinOutPick').addEventListener('click', async () => {
  const r = await window.pywebview.api.choosePinOut();
  if (r && r.ok) { paintPinPlan(r); checkPinProxy(); }
  else if (r && r.error) said($('pinSaid'), r.error, 'bad');
});

$('pinRoute').addEventListener('click', async (e) => {
  const opt = e.target.closest('.seg__opt');
  if (!opt || state.pinning) return;
  // Moved before the round trip, so the switch answers the click rather than
  // the answer to it.
  $('pinRoute').dataset.route = opt.dataset.route;
  paintPinPlan(await window.pywebview.api.setPinRoute(opt.dataset.route));
  if (opt.dataset.route === 'proxy') checkPinProxy();
});

$('pinPort').addEventListener('input', commitPinPort);
$('pinPort').addEventListener('change', commitPinPort);
$('pinPort').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); $('pinPort').blur(); }
});

async function commitPinMax() {
  const n = parseInt($('pinMax').value, 10);
  paintPinPlan(await window.pywebview.api.setPinRoute(
    null, null, Number.isFinite(n) ? n : 4));
}

$('pinMax').addEventListener('change', commitPinMax);

/* Same pair of chevrons as the sweep's, and the same reason for existing.
   Unlike that one there is no "all" here - one address per config is the
   floor, because a config with no address in it is not a config. */
function stepPinMax(by) {
  const field = $('pinMax');
  const now = parseInt(field.value, 10);
  const from = Number.isFinite(now) ? now : 4;
  field.value = String(Math.min(32, Math.max(1, from + by)));
  commitPinMax();
}

$('pinMaxUp').addEventListener('click', () => stepPinMax(1));
$('pinMaxDown').addEventListener('click', () => stepPinMax(-1));

$('pinTest').addEventListener('click', async () => {
  const on = $('pinTest').getAttribute('aria-checked') !== 'true';
  $('pinTest').setAttribute('aria-checked', String(on));
  paintPinPlan(await window.pywebview.api.setPinRoute(null, null, null, on));
});

$('pinUse').addEventListener('click', async (e) => {
  const chip = e.target.closest('.chip');
  if (!chip || chip.disabled) return;
  const r = await window.pywebview.api.usePinnedFolder(chip.dataset.folder);
  if (!r || !r.ok) {
    said($('pinSaid'), (r && r.error) || 'That folder could not be used.', 'bad');
    return;
  }
  await refreshPrefs();
  await refreshSweep();
  drawPinUse(chip.dataset.folder, r.count);
  setHint(`Now connecting through the ${r.count} pinned servers.`);
});

$('pinGo').addEventListener('click', async () => {
  if (state.pinning) {
    said($('pinSaid'), 'Stopping…');
    await window.pywebview.api.cancelPin();
    return;
  }
  const r = await window.pywebview.api.startPin();
  if (!r.ok) said($('pinSaid'), r.error, 'bad');
});

$('sweepPick').addEventListener('click', async () => {
  const r = await window.pywebview.api.chooseSweepFolder();
  if (r && r.ok) paintSweepPlan(r);
  else if (r && r.error) said($('sweepSaid'), r.error, 'bad');
});

// Saved on the way out of the field rather than on every keystroke, and read
// back cleaned - the sweep drops anything that is not a hostname without
// saying so, and a site silently never tested is worse than one refused out
// loud.
async function commitSites() {
  const r = await window.pywebview.api.saveSites($('sweepSites').value);
  if (!r || !r.ok) return;
  $('sweepSites').value = r.sites.join(', ');
  await refreshSweep($('sweepFolder').textContent);
  if (r.dropped && r.dropped.length) {
    said($('sweepSaid'),
         `Not a site name, so it will not be tested: ${r.dropped.join(', ')}`,
         'bad');
  }
}

$('sweepScope').addEventListener('click', async (e) => {
  const row = e.target.closest('.scope__row');
  if (!row || state.sweep) return;
  paintSweepPlan(await window.pywebview.api.setSweepScope(row.dataset.scope));
});

$('sweepLords').addEventListener('click', async (e) => {
  const chip = e.target.closest('.chip');
  if (!chip || state.sweep) return;
  // Read off the chips rather than out of the last answer: the DOM is what
  // the person is looking at, and it is never behind.
  const picked = new Set(Array.from(
    $('sweepLords').querySelectorAll('.chip[aria-pressed="true"]'))
    .map((c) => c.dataset.lord));
  const lord = chip.dataset.lord;
  if (picked.has(lord)) picked.delete(lord); else picked.add(lord);
  // Marked before the round trip, so a click lands the moment it is made and
  // the answer only confirms it.
  chip.setAttribute('aria-pressed', String(picked.has(lord)));
  // Only the companies. The scope is the backend's to remember - resending it
  // from the last answer we were handed put it back to whatever it had been
  // whenever that answer was still in flight, which is exactly the moment
  // somebody clicks a second thing.
  paintSweepPlan(await window.pywebview.api.setSweepScope(
    null, Array.from(picked)));
});

async function commitFirst() {
  const n = parseInt($('sweepFirst').value, 10);
  paintSweepPlan(await window.pywebview.api.setSweepScope(
    null, null, Number.isFinite(n) && n > 0 ? n : 0));
}

$('sweepFirst').addEventListener('change', commitFirst);

/* The two chevrons. Empty means "all of them", so stepping up from empty
   starts at the whole selection rather than at one - nudging a cap you have
   not set should offer you the number you already have, not restart the
   count. Stepping down to zero clears it back to empty for the same reason. */
function stepFirst(by) {
  const field = $('sweepFirst');
  const now = parseInt(field.value, 10);
  const from = Number.isFinite(now) && now > 0
    ? now : ((state.plan && state.plan.count) || 0);
  const next = Math.max(0, from + by);
  field.value = next > 0 ? String(next) : '';
  commitFirst();
}

$('sweepFirstUp').addEventListener('click', () => stepFirst(1));
$('sweepFirstDown').addEventListener('click', () => stepFirst(-1));

$('lookUpOwners').addEventListener('click', async () => {
  const btn = $('lookUpOwners');
  btn.disabled = true;
  said($('lordsSaid'), 'Asking who those addresses are rented from…');
  btn.dataset.busy = 'true';
  const r = await window.pywebview.api.lookUpOwners($('sweepFolder').textContent);
  btn.disabled = false;
  if (!r || !r.ok) {
    said($('lordsSaid'), (r && r.error) || 'The lookup did not answer.', 'bad');
    return;
  }
  paintSweepPlan(r.plan);
  if (r.unknown) {
    said($('lordsSaid'),
         `Asked about ${r.asked}. ${r.unknown} still cannot be named — `
         + 'some addresses never resolve, and those are left out of any '
         + 'company you pick.');
  }
});

$('sweepSites').addEventListener('change', commitSites);
$('sweepSites').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); $('sweepSites').blur(); }
});

$('siteFolders').addEventListener('click', async (e) => {
  const chip = e.target.closest('.chip');
  if (!chip || chip.disabled) return;
  const r = await window.pywebview.api.useSiteFolder(chip.dataset.folder);
  if (!r || !r.ok) {
    said($('sweepSaid'), (r && r.error) || 'That folder could not be used.', 'bad');
    return;
  }
  await refreshPrefs();
  await refreshSweep();
  setHint(`Now connecting only through the ${r.count} servers that reached that site.`);
});

$('sweepGo').addEventListener('click', async () => {
  if (state.sweep) {
    said($('sweepSaid'), 'Stopping, and putting the tunnel back…');
    await window.pywebview.api.cancelSweep();
    return;
  }
  const folder = $('sweepFolder').textContent.trim();
  const r = await window.pywebview.api.startSweep(folder === '—' ? null : folder);
  if (!r.ok) said($('sweepSaid'), r.error, 'bad');
});

$('sysProxy').addEventListener('click', async () => {
  state.systemProxy = !state.systemProxy;
  $('sysProxy').setAttribute('aria-checked', String(state.systemProxy));
  await window.pywebview.api.setSystemProxy(state.systemProxy);
});

/* change, not input, and not blur either. Every other port box in this sheet
   commits as you type because the worst it costs is a socket test; this one
   restarts a running proxy and re-points Windows, so it waits until you have
   finished saying what you meant. blur alongside change would send the same
   move twice, and the second answer would land on top of what the first
   said. */
$('listenPort').addEventListener('change', commitPort);
$('listenPort').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); $('listenPort').blur(); }
  if (e.key === 'Escape') { $('listenPort').value = String(state.port); $('listenPort').blur(); }
});

$('chooseFolder').addEventListener('click', async () => {
  const r = await window.pywebview.api.chooseFolder();
  if (r && r.ok) {
    await refreshPrefs();
    setHint(`Now reading servers from ${r.folder}`);
  } else if (r && r.error) {
    setHint(r.error, true);
  }
});

$('resetFolder').addEventListener('click', async () => {
  await window.pywebview.api.resetFolder();
  await refreshPrefs();
  setHint('Back to the servers that came with the app.');
});

initConnectionCore();
// Before boot, because it only rearranges markup that is already on the page
// and a settings sheet opened in the first second should already have it.
wireInfoButtons();
window.addEventListener('pywebviewready', boot);
if (window.pywebview && window.pywebview.api) boot();

/* Read by the automated check, which asserts on real hit-testing rather than
   on handlers existing - an earlier suite called .click() directly, which
   bypasses hit-testing and so passed happily on a window where every control
   was covered by an invisible panel. */
window.__probe = () => ({
  countries: state.countries.length,
  mode: state.mode,
  picked: state.picked,
  pickerOpen: $('picker').open,
  prefsOpen: $('prefs').open,
  systemProxy: state.systemProxy,
  port: state.port,
  hasCredentials: state.hasCredentials,
  sweeping: !!state.sweep,
  sweepScope: state.plan && state.plan.scope,
  sweepChosen: (state.plan && state.plan.chosen) || [],
  sweepCount: state.plan && state.plan.count,
  sweepMinutes: state.plan && state.plan.minutes,
  sweepCompanies: ((state.plan && state.plan.companies) || []).length,
  pinning: !!state.pinning,
  pinRoute: state.pinPlan && state.pinPlan.route,
  pinPort: state.pinPlan && state.pinPlan.port,
  pinTotal: state.pinPlan && state.pinPlan.total,
  pinMaxIps: state.pinPlan && state.pinPlan.maxIps,
  pinBlocked: ((state.pinPlan && state.pinPlan.blockers) || []).map((b) => b.kind),
  fluxLive: flux.live,
  logOpen: $('log').open,
  logBy: log.by,
  logRows: $('logList').querySelectorAll('.lrow').length,
  fluxDown: $('fluxDownSum').textContent,
  fluxUp: $('fluxUpSum').textContent,
  // Asserted on by the automated check: whatever else this page does, a
  // password must never be sitting in the DOM after it has been used. One
  // box now rather than two, which is most of why the panes were merged.
  passwordInDom: $('acctPass').value.length > 0,
});

/* ---------------------------------------------------------- Windscribe */

/* The one provider that cannot be a text box.

   Windscribe hands out a different credential per client type and only the
   browser extension's opens the proxy, so there is nothing to paste from a
   settings page - it has to be logged in for, and the login is behind a
   slider captcha.

   The captcha is drawn here from the two images the API sends. What the API
   wants back is where the piece was let go and the path the pointer took
   getting there, and both come from the actual drag: the trail is the half
   that is really being asked about, since where the slider stopped is easy
   and how a hand got there is not. Nothing in this file generates either. */

const ws = {
  token: null,        // the secure token this puzzle belongs to
  scale: 1,           // drawn width / natural width, to undo on the way out
  span: 0,            // how far the knob can travel, in rail pixels
  left: 0,            // where it is now, in rail pixels
  trailX: [],
  trailY: [],
  solved: false,
  sending: false,     // one submit at a time; the gesture can fire twice
};

const WS_TRAIL_MAX = 50;

function wsHideCaptcha() {
  const dlg = $('wsCapDlg');
  if (dlg.open) dlg.close();
  $('wsCapAnswerWrap').hidden = true;
  $('wsCapSend').hidden = true;
  $('wsCapAnswer').value = '';
  said($('wsCapErr'), '');
  ws.token = null;
  ws.solved = false;
  ws.sending = false;
  ws.trailX = [];
  ws.trailY = [];
}

function wsShowCaptcha(captcha) {
  const ascii = $('wsCapAscii');
  const stage = $('wsCapStage');
  const rail = $('wsCapRail');

  ws.trailX = [];
  ws.trailY = [];
  ws.left = 0;
  ws.span = 0;
  ws.solved = false;
  ws.sending = false;
  said($('wsCapErr'), '');

  if (captcha.kind === 'ascii') {
    // No image to place, so no slider either - the answer is read off the
    // drawing and typed, and typing has no moment that means "done". That
    // kind, and only that kind, needs a button.
    ascii.hidden = false;
    ascii.textContent = captcha.art || '';
    stage.hidden = true;
    rail.hidden = true;
    $('wsCapAnswerWrap').hidden = false;
    $('wsCapAnswer').value = '';
    $('wsCapSend').hidden = false;
    said($('wsCapSaid'), 'Type what the drawing says.');
    wsOpenCaptcha();
    $('wsCapAnswer').focus();
    return;
  }

  ascii.hidden = true;
  $('wsCapAnswerWrap').hidden = true;
  $('wsCapSend').hidden = true;
  stage.hidden = false;
  rail.hidden = false;
  said($('wsCapSaid'), 'Drag the piece into the gap, then let go.');

  const bg = $('wsCapBg');
  const pc = $('wsCapPc');
  $('wsCapKnob').style.transform = 'translateX(0px)';
  pc.style.transform = 'translateX(0px)';

  const fit = () => {
    // The solution is measured in the background's own pixels, so the ratio
    // between that and the width it is actually drawn at is the only thing
    // standing between a correct drag and a rejected one. Recomputed rather
    // than assumed, because the dialog animates open.
    const drawn = stage.getBoundingClientRect().width;
    if (!drawn) return;                 // not laid out yet; nothing to measure
    ws.scale = drawn / (bg.naturalWidth || drawn);
    pc.style.top = Math.round((captcha.top || 0) * ws.scale) + 'px';
    if (pc.naturalWidth) {
      const wide = Math.round(pc.naturalWidth * ws.scale);
      pc.style.width = wide + 'px';
      // Held rather than measured again mid-drag. Taken live, this is the
      // one number that can quietly be wrong: before the piece has decoded
      // its width reads 0, and a span measured against the stage alone lets
      // the piece be dragged off the end - while a stage that is not laid
      // out yet reads 0 the other way and pins every drag at zero, which
      // looks exactly like a broken puzzle.
      ws.span = Math.max(0, drawn - wide);
    }
  };
  // Both images matter to the fit and they land in whichever order they
  // decode in - the piece's own width is what the drag is clamped against,
  // so a fit that ran before it arrived would leave the span unset.
  bg.onload = fit;
  pc.onload = fit;
  bg.src = 'data:image/png;base64,' + (captcha.background || '');
  pc.src = 'data:image/png;base64,' + (captcha.slider || '');

  wsOpenCaptcha();
  // The dialog has to be open before the stage has a width, and the images
  // may already have decoded by then - in which case neither onload will
  // fire again and nothing would ever set the span.
  requestAnimationFrame(fit);
}

function wsOpenCaptcha() {
  const dlg = $('wsCapDlg');
  if (!dlg.open) dlg.showModal();
}

/* The piece is the handle, and the rail below only reports where it got to.

   That split is not a style decision - it is what the trail means. The x
   values sent are the *piece's* position, clamped, not wherever the pointer
   happened to be, and the y values are measured from the top of the picture.
   Dragging the rail instead would produce numbers in the rail's coordinates,
   which are a different width and a different origin, and the puzzle would
   be refused with nothing on screen to explain why. */
(function wsDrag() {
  const stage = $('wsCapStage');
  const pc = $('wsCapPc');
  const rail = $('wsCapRail');
  if (!stage || !pc) return;
  let held = false;
  let grabbed = 0;

  const move = (e) => {
    if (!held) return;
    const box = stage.getBoundingClientRect();
    // A stage with no width is one that is not on screen, and every number
    // taken from it would be a lie recorded into the trail.
    if (!box.width) return;
    const span = ws.span || Math.max(0, box.width - pc.offsetWidth);
    ws.left = Math.max(0, Math.min(span, e.clientX - box.left - grabbed));
    pc.style.transform = 'translateX(' + ws.left + 'px)';
    $('wsCapKnob').style.transform = 'translateX(' + ws.left + 'px)';
    // Where the piece is, and how high the hand was holding it - both as
    // whole numbers, both relative to the picture.
    ws.trailX.push(Math.round(ws.left));
    ws.trailY.push(Math.round(e.clientY - box.top));
    // The last fifty are what gets sent, so that a long drag arrives as its
    // ending rather than its beginning.
    if (ws.trailX.length > WS_TRAIL_MAX) { ws.trailX.shift(); ws.trailY.shift(); }
  };

  const up = () => {
    if (!held) return;
    held = false;
    stage.classList.remove('is-held');
    ws.solved = true;
    // Letting go IS the answer. Waiting for a second press on a button
    // somewhere else was the whole of "I dropped it in the gap and nothing
    // happened" - and while it waited, the token quietly went stale.
    wsSubmit();
  };

  /* Either handle starts the same drag.

     The piece is the obvious one. The rail is there because a bar with a
     knob on it reads as draggable whatever the instructions say, and a
     control that looks draggable and is not is a control that appears
     broken. Both write the same ws.left in the same coordinate space, so
     what gets sent does not depend on which one was used - and the rail's
     grab offset is taken against the *stage*, not against the rail, for
     exactly that reason. */
  const begin = (handle) => (e) => {
    if (ws.sending) return;
    const box = stage.getBoundingClientRect();
    grabbed = e.clientX - box.left - ws.left;
    held = true;
    // Cleared here rather than carried over: a second attempt at the same
    // puzzle would otherwise send the first attempt's path in front of it.
    ws.trailX = [];
    ws.trailY = [];
    stage.classList.add('is-held');
    // Captured, so a drag that leaves the handle - which every drag does,
    // the pointer runs ahead of it - keeps arriving. Guarded because it
    // throws for a pointer the browser is not already tracking, and an
    // exception here would end the gesture on its first move.
    try { handle.setPointerCapture(e.pointerId); } catch (_) { /* not fatal */ }
    e.preventDefault();
  };

  for (const handle of [pc, rail]) {
    if (!handle) continue;
    handle.addEventListener('pointerdown', begin(handle));
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
    handle.addEventListener('pointercancel', up);
  }
})();

/* What the API is told the answer is: the piece's travel converted back out
   of drawn pixels into the background's own, which is the space the puzzle
   was cut in. */
function wsSolution() {
  return Math.round(ws.left / (ws.scale || 1));
}

/* -- sending it ---------------------------------------------------------- */

/* The second half of the sign-in, run by the gesture rather than by a click.
   The name and password are not passed from here: they stayed on the Python
   side when the puzzle was fetched, so they cross the bridge once. */
async function wsSubmit() {
  if (ws.sending || !ws.token) return;
  ws.sending = true;
  const ascii = !$('wsCapAscii').hidden;
  said($('wsCapSaid'), 'Checking...');
  said($('wsCapErr'), '');

  const r = await window.pywebview.api.windscribeFinish(
    ws.token,
    // The drawn puzzle answers with where it was let go; the text one
    // answers with what was typed.
    ascii ? $('wsCapAnswer').value.trim() : wsSolution(),
    ws.trailX, ws.trailY, $('acctTwo').value.trim());

  if (!r.ok) {
    // The token and the puzzle are both spent now, whatever went wrong -
    // reusing either gets a fresh rejection that looks like a wrong password.
    // So the dialog closes and the pane says what happened, with the reason
    // where the rest of the sign-in's answers appear.
    wsHideCaptcha();
    said($('acctNewSaid'), r.why ? r.error + ' (' + r.why + ')' : r.error, 'bad');
    // Deliberately not retried on its own. note.md records an account
    // blocked after about seventy security alerts, and the way to get there
    // is something that tries again without being asked.
    acctBusy(false);
    await acctRefresh();
    return;
  }

  wsHideCaptcha();
  acctBusy(false);
  acctShowForm(false);
  if (r.credentials) {
    said($('acctSaid'), 'Signed in as ' + r.username + '. The proxy credential '
      + 'is on file' + (r.remembered ? ' and the password is remembered' : '')
      + ' - now press Get servers.', 'good');
  } else {
    said($('acctSaid'), 'Signed in as ' + r.username + ', but the proxy '
      + 'credential did not come back: ' + r.error, 'bad');
  }
  await acctRefresh();
}

$('wsCapSend').addEventListener('click', wsSubmit);

$('wsCapClose').addEventListener('click', () => {
  wsHideCaptcha();
  acctBusy(false);
  said($('acctNewSaid'), 'Sign-in stopped. Nothing was sent.');
});

// Escape closes a <dialog> on its own; this keeps the rest of the state in
// step with that rather than leaving a spent token behind.
$('wsCapDlg').addEventListener('close', () => {
  if (ws.sending) return;
  ws.token = null;
  ws.solved = false;
  acctBusy(false);
});

/* -- signing in ---------------------------------------------------------- */


/* ------------------------------------------------------------ the whys */

/* Every pane used to open with a paragraph explaining itself. Seven of them
   in a row turned a settings sheet into a page of documentation, where the
   control you came for was three sentences down and the six you did not come
   for were between you and it.

   The paragraphs are not deleted - they are the reason each setting is worth
   having, and losing them would be losing the argument. They move into the
   title, one hover away.

   Only the pane's own description moves. The ones marked --after and --tight
   are corrections about the control beside them ("this port answers both",
   "being blocked is mostly a property of the address"), and those belong
   where they are: read at the moment they apply, not looked up. */
function wireInfoButtons() {
  const whys = document.querySelectorAll(
    '.pane__why:not(.pane__why--after):not(.pane__why--tight)');
  for (const why of whys) {
    const pane = why.closest('.pane');
    const title = pane && pane.querySelector('.pane__title');
    if (!title) continue;

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'info';
    btn.setAttribute('aria-label', 'Why this is here');
    const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    icon.setAttribute('class', 'ico');
    icon.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#i-info');
    icon.appendChild(use);
    const bubble = document.createElement('span');
    bubble.className = 'info__bubble';
    bubble.setAttribute('role', 'tooltip');
    // The paragraph's own markup, not its text - several of them bold the
    // word that the whole sentence turns on.
    bubble.innerHTML = why.innerHTML;
    btn.append(icon, bubble);

    /* Inside the title, not after it.
       Two kinds of pane hold these. In one the title is a flex item and a
       button after it lands beside it; in the other the title is a block in
       a column, and a button after a block starts a line of its own - so
       the (i) sat under the heading, 2px left of everything, in every pane
       laid out that way. Inside the heading it is part of the line in both,
       which is what "moved into the title" was supposed to mean. */
    title.append(btn);
    why.remove();

    // A bubble centred on a button two pixels from the right edge hangs off
    // the window. Measured on first hover rather than guessed at, because
    // where the button lands depends on how long the title is.
    btn.addEventListener('pointerenter', () => {
      btn.classList.remove('info--right');
      const box = bubble.getBoundingClientRect();
      if (box.right > window.innerWidth - 8) btn.classList.add('info--right');
    }, { once: false });
  }
}

/* ------------------------------------------------------------- accounts */

/* One roster for both providers. They were two panes doing the same job -
   "which account is this app using" - and being two is what made holding
   one of each look like the natural state of things rather than a limit.

   What genuinely differs is only how an account is proved: Surfshark hands
   out a service credential you can paste, Windscribe issues one per client
   and has to be signed in to. So that is the only place the form differs. */

const acct = {
  provider: 'surfshark',   // which one the add-form is currently for
  rows: [],
};

function acctBusy(on) {
  for (const id of ['acctAdd', 'acctSave', 'acctCancel', 'acctUser',
                    'acctPass', 'acctTwo', 'acctLabel', 'wsGet', 'wsRefresh',
                    'ssGet']) {
    const el = $(id);
    if (el) el.disabled = on;
  }
  for (const b of document.querySelectorAll('#acctList button')) b.disabled = on;
}

function acctPaint() {
  const list = $('acctList');
  list.textContent = '';

  if (!acct.rows.length) {
    const none = document.createElement('p');
    none.className = 'acct__none';
    none.textContent = 'No accounts yet. Add one and this app has something '
      + 'to connect with.';
    list.appendChild(none);
  }

  for (const row of acct.rows) {
    const el = document.createElement('div');
    el.className = 'acct__row';
    el.dataset.provider = row.provider;
    el.dataset.active = row.active ? '1' : '0';

    const tag = document.createElement('span');
    tag.className = 'acct__tag';
    tag.textContent = row.providerName;

    const who = document.createElement('span');
    who.className = 'acct__who';
    const name = document.createElement('span');
    name.className = 'acct__name';
    name.textContent = row.label;
    const sub = document.createElement('span');
    sub.className = 'acct__sub';
    // What is true of it, in the order it matters: whether it is the one in
    // use, then whether it can be used without typing anything again.
    sub.textContent = [
      row.active ? 'in use' : null,
      row.username && row.username !== row.label ? row.username : null,
      row.provider === 'windscribe' && row.signedIn ? 'signed in' : null,
      row.hasPassword ? 'password remembered' : null,
    ].filter(Boolean).join(' · ') || 'not set up';
    who.append(name, sub);

    const act = document.createElement('span');
    act.className = 'acct__act';
    if (!row.active) {
      const use = document.createElement('button');
      use.className = 'btn btn--quiet btn--auto';
      use.type = 'button';
      use.textContent = 'Use';
      use.addEventListener('click', () => acctUse(row.id));
      act.appendChild(use);
    }
    const drop = document.createElement('button');
    drop.className = 'btn btn--quiet btn--auto';
    drop.type = 'button';
    drop.textContent = 'Remove';
    drop.addEventListener('click', () => acctRemove(row.id, row.label));
    act.appendChild(drop);

    el.append(tag, who, act);
    list.appendChild(el);
  }

  // The pill counts what is usable, not what is listed - a roster entry with
  // nothing behind it is not an account this app can connect with.
  const usable = acct.rows.filter((r) => r.signedIn).length;
  const pill = $('acctPill');
  pill.dataset.state = usable ? 'on' : 'off';
  pill.textContent = usable ? `${usable} ready` : 'none';

  // Windscribe's fleet is a download rather than a folder somebody already
  // has, so its two buttons only mean anything while one is in use.
  const ws = acct.rows.find((r) => r.provider === 'windscribe' && r.active);
  $('acctWsTools').hidden = !ws;

  // Surfshark's is a download rather than a fetch for the same reason, and
  // it has one button rather than two: the credential never expires, so
  // there is nothing to refresh.
  const ss = acct.rows.find((r) => r.provider === 'surfshark' && r.active);
  $('acctSsTools').hidden = !ss;
}

async function acctRefresh() {
  const r = await window.pywebview.api.accountsList();
  acct.rows = (r && r.accounts) || [];
  acctPaint();
  return acct.rows;
}

/* -- adding one -------------------------------------------------------- */

function acctShowForm(on) {
  const dlg = $('acctDlg');
  if (!on) {
    if (dlg.open) dlg.close();
    return;
  }
  $('acctUser').value = '';
  $('acctPass').value = '';
  $('acctTwo').value = '';
  $('acctLabel').value = '';
  said($('acctNewSaid'), '');
  acctSetProvider(acct.provider);
  if (!dlg.open) dlg.showModal();
  $('acctUser').focus();
}

function acctSetProvider(which) {
  acct.provider = which;
  for (const opt of $('acctWhich').querySelectorAll('.pair__opt')) {
    opt.setAttribute('aria-selected', String(opt.dataset.provider === which));
  }
  // Surfshark's is a service credential off a web page; Windscribe's is the
  // account you log in with. Saying which is the difference between pasting
  // the right thing and being refused with no idea why.
  $('acctUserCap').textContent = which === 'windscribe'
    ? 'Username or email' : 'Service username';
  // Two-factor is a login thing, and only one of these is a login.
  $('acctTwoWrap').hidden = which !== 'windscribe';
  said($('acctNewSaid'), which === 'windscribe'
    ? 'Signing in fetches a puzzle to solve. It is asked once.'
    : 'From the manual-setup page — not the email you log in with.');
}

$('acctWhich').addEventListener('click', (e) => {
  const opt = e.target.closest('.pair__opt');
  if (opt) acctSetProvider(opt.dataset.provider);
});

$('acctAdd').addEventListener('click', () => acctShowForm(true));
$('acctCancel').addEventListener('click', () => acctShowForm(false));
$('acctDlgClose').addEventListener('click', () => acctShowForm(false));

$('acctSave').addEventListener('click', async () => {
  const user = $('acctUser').value.trim();
  const pass = $('acctPass').value;
  const label = $('acctLabel').value.trim();
  if (!user) { said($('acctNewSaid'), 'Enter the username.', 'bad'); return; }
  if (!pass) { said($('acctNewSaid'), 'Enter the password.', 'bad'); return; }

  acctBusy(true);
  if (acct.provider === 'surfshark') {
    said($('acctNewSaid'), 'Saving…');
    const r = await window.pywebview.api.accountAdd('surfshark', label, user, pass);
    acctBusy(false);
    if (!r.ok) { said($('acctNewSaid'), r.error, 'bad'); return; }
    $('acctPass').value = '';
    acctShowForm(false);
    said($('acctSaid'), `Saved and in use: ${r.label}.`, 'good');
    await acctRefresh();
    await refreshPrefs();
    return;
  }

  // Windscribe: the puzzle stands between here and an account.
  said($('acctNewSaid'), 'Asking Windscribe…');
  const r = await window.pywebview.api.accountAdd(
    'windscribe', label, user, pass);
  if (!r.ok) {
    acctBusy(false);
    said($('acctNewSaid'), r.error, 'bad');
    return;
  }
  $('acctPass').value = '';
  ws.token = r.token;
  if (r.captcha) {
    wsShowCaptcha(r.captcha);
    said($('acctNewSaid'), 'Solve the puzzle to finish.');
  } else {
    said($('acctNewSaid'), 'Signing in…');
    await wsSubmit();
  }
});

/* -- using and dropping ------------------------------------------------ */

async function acctUse(id) {
  acctBusy(true);
  said($('acctSaid'), 'Switching…');
  const r = await window.pywebview.api.accountUse(id);
  acctBusy(false);
  if (!r.ok) { said($('acctSaid'), r.error, 'bad'); return; }
  said($('acctSaid'), `Now using ${r.label}.`, 'good');
  await acctRefresh();
  await refreshPrefs();
}

async function acctRemove(id, label) {
  acctBusy(true);
  const r = await window.pywebview.api.accountRemove(id);
  acctBusy(false);
  if (!r.ok) { said($('acctSaid'), r.error, 'bad'); return; }
  said($('acctSaid'), `Removed ${label}.`);
  await acctRefresh();
  await refreshPrefs();
}

/* -- fetching a fleet, per provider ------------------------------------ */

/* Surfshark publishes its cluster list, and every config it hands out is the
   same eighty lines with one name changed - so the folder somebody downloads
   by hand is a list this can ask for. Additive: what is already in the
   folder is left alone, because it may be theirs. */
$('ssGet').addEventListener('click', async () => {
  acctBusy(true);
  said($('ssSaid'), 'Fetching the server list…');
  const r = await window.pywebview.api.surfsharkServers();
  acctBusy(false);
  if (!r.ok) { said($('ssSaid'), r.error, 'bad'); return; }
  const gone = (r.gone || []).length;
  said($('ssSaid'),
    (r.added
      ? `${r.added} added, ${r.kept} already here`
      : `Nothing missing — all ${r.kept} are already here`)
    + `. ${r.total} servers across ${r.countries} countries in ${r.folder}.`
    + (r.added ? ' They are hostnames, so pin them next — the Servers '
      + 'group below does it.' : '')
    + (gone ? ` ${gone} on disk ${gone === 1 ? 'is' : 'are'} no longer `
      + 'published; nothing was deleted.' : ''),
    'good');
});

/* -- the two Windscribe-only buttons ----------------------------------- */

$('wsGet').addEventListener('click', async () => {
  acctBusy(true);
  said($('wsSaid'), 'Fetching the server list…');
  const r = await window.pywebview.api.windscribeServers();
  acctBusy(false);
  if (!r.ok) { said($('wsSaid'), r.error, 'bad'); return; }
  said($('wsSaid'), `${r.written} servers across ${r.countries} countries `
    + `written to ${r.folder}. They are hostnames, so pin them next — the `
    + 'Servers group below does it.', 'good');
});

$('wsRefresh').addEventListener('click', async () => {
  acctBusy(true);
  said($('wsSaid'), 'Asking for a fresh credential…');
  const r = await window.pywebview.api.windscribeRefresh();
  acctBusy(false);
  if (!r.ok) {
    said($('wsSaid'), r.error + ' — the session may have expired; sign in '
      + 'again to get a new one.', 'bad');
    return;
  }
  said($('wsSaid'), 'Still good — the session fetched a working credential '
    + 'with no puzzle.', 'good');
  await acctRefresh();
});

$('acctPeek').addEventListener('click', () => {
  const field = $('acctPass');
  const showing = field.type === 'text';
  field.type = showing ? 'password' : 'text';
  $('acctPeek').querySelector('use')
    .setAttribute('href', showing ? '#i-eye' : '#i-eye-off');
  $('acctPeek').setAttribute('aria-pressed', String(!showing));
  $('acctPeek').setAttribute('aria-label',
    showing ? 'Show the password' : 'Hide the password');
});

/* ------------------------------------------------ settings, as a stack */

/* Four screens behind a list, rather than four dropdowns in a scroll.

   The difference is what you are looking at when the sheet opens. Collapsed
   sections still put every heading, every chevron and the top of whichever
   one was left open in front of you at once; a list of four things is four
   things. And going back is a place to go back to, which an accordion never
   has - closing a section leaves you wherever the page had scrolled to.

   The screens are all in the DOM the whole time. They hold live controls
   with state in them - a sweep running, a pin part-done - and rebuilding
   one on the way in would throw that away. */

function showScreen(slug) {
  for (const s of document.querySelectorAll('.screen')) {
    s.hidden = s.dataset.screen !== slug;
  }
  const found = document.querySelector(`.screen[data-screen="${slug}"]`);
  $('prefsMenu').hidden = !!found;
  $('prefsBack').hidden = !found;
  // The title says where you are, so that the one piece of chrome that
  // moves is not the only clue.
  const row = document.querySelector(`.menu__row[data-goto="${slug}"]`);
  $('prefsTitle').textContent = row
    ? row.querySelector('.menu__name').textContent : 'Settings';
  if (found) found.scrollTop = 0;
  $('prefsBody').scrollTop = 0;
  state.screen = found ? slug : null;
}

function showSettingsMenu() {
  showScreen(null);
}

$('prefsMenu').addEventListener('click', (e) => {
  const row = e.target.closest('.menu__row');
  if (row) showScreen(row.dataset.goto);
});

$('prefsBack').addEventListener('click', showSettingsMenu);

// Escape backs out one level rather than closing the whole sheet from three
// screens deep, which is the behaviour a stack implies.
$('prefs').addEventListener('cancel', (e) => {
  if (state.screen) {
    e.preventDefault();
    showSettingsMenu();
  }
});

/* ------------------------------------------- which providers to use */

/* Separate from the roster on purpose. "Which accounts exist" and "which of
   them am I connecting through today" are different questions, and folding
   them together would mean the only way to stop using a provider was to sign
   out of it - which throws away the session that took a captcha to get. */

const PROVIDER_NAMES = { surfshark: 'Surfshark', windscribe: 'Windscribe' };

function paintUse(info) {
  const per = (info && info.providerState) || {};
  const usable = Object.keys(per).filter((k) => per[k].usable);
  const chosen = (info && info.providers) || usable;
  state.providers = chosen;

  for (const key of ['surfshark', 'windscribe']) {
    const cap = key[0].toUpperCase() + key.slice(1);
    const box = $('use' + cap);
    const count = $('use' + cap + 'N');
    const has = per[key] || { servers: 0, account: false, usable: false };
    box.checked = has.usable && chosen.includes(key);
    // A tick that can be put in a box that then refuses is worse than a box
    // that says why it is empty.
    box.disabled = !has.usable;
    box.closest('.use__opt').dataset.empty = has.usable ? '0' : '1';
    // Which half is missing, because they want opposite things doing about
    // them: one needs an account added, the other needs servers fetched.
    // "Unavailable" would say neither, and leaving the exit count showing
    // for a provider with no account is what made a removed account look
    // like it was still there.
    count.textContent = has.usable ? String(has.servers)
      : !has.account ? 'no account'
        : 'none pinned';
  }
}

async function commitUse() {
  const want = ['surfshark', 'windscribe']
    .filter((k) => $('use' + k[0].toUpperCase() + k.slice(1)).checked);
  said($('useSaid'), 'Applying…');
  const r = await window.pywebview.api.setProviders(want);
  if (!r.ok) {
    said($('useSaid'), r.error, 'bad');
    // Put the boxes back to what is really in force, so the screen never
    // shows a choice that was refused.
    await refreshPrefs();
    return;
  }
  const names = r.providers.map((p) => PROVIDER_NAMES[p] || p).join(' and ');
  said($('useSaid'), `${r.serverCount} exits from ${names}.`, 'good');
  state.providers = r.providers;
  state.countries = r.countries || state.countries;

  // A pick naming a provider that has just been switched off would ask for
  // exits the engine has been told not to offer, and come back "no servers
  // in that folder for that country" - which is true, and no help at all.
  // The country is still a good answer, so it falls back to that.
  const [where, via] = String(state.picked || '').split(':');
  if (via && !r.providers.includes(via)) {
    state.picked = where;
    await window.pywebview.api.remember(where);
  }
  render();
}

$('useOpts').addEventListener('change', commitUse);

/* -------------------------------------------- which of them actually answer */

/* An exit that is filtered on this line looks exactly like one that is
   merely slow. The only way to tell them apart is to ask - which the connect
   race does every single time and then throws away, so the answer was always
   "try it and see", spending the same seconds and learning nothing.

   Asking once and keeping the answer turns the list from a list of places
   into a list of places that work, in the order they answered. */

function msSaid(ms) {
  if (ms === null || ms === undefined) return '';
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} s` : `${ms} ms`;
}

/* What a row says about itself once it has been tested: nothing at all
   before, a time when it answered, and why not when it did not. Kept short -
   this sits after the relay count on one line. */
/* "Blocked here" has to mean everything here was asked and nothing answered.

   Anything less is a guess wearing a fact's clothes: a country with three
   exits where one was measured and refused was being called blocked while
   two of them had never been asked at all. If some are still unasked it says
   how many answered and leaves the rest open. */
function reachSaid(c) {
  if (!c || !c.tested) return '';
  // The ratio has moved to the sign, which is beside the name and coloured
  // by it. Saying it here as well cost the end of the line - "PL - 12
  // relays - 139 ms - ..." with the number that mattered cut off - to
  // repeat what a green letter already said.
  if (!c.ok) return c.tested >= c.count ? ' · blocked' : '';
  return c.ping === null ? '' : ` · ${msSaid(c.ping)}`;
}

function reachState(c) {
  if (!c || !c.tested) return 'untested';
  if (!c.ok) return c.tested >= c.count ? 'blocked' : 'some';
  return c.ok < c.tested ? 'some' : 'ok';
}

/* -- running one ------------------------------------------------------- */

async function startReach() {
  if (state.testing) {
    await window.pywebview.api.cancelReach();
    return;
  }
  const r = await window.pywebview.api.testReach();
  if (!r.ok) { said($('reachSaid'), r.error, 'bad'); return; }
  state.testing = true;
  $('reachGo').dataset.busy = 'true';
  $('reachGo').setAttribute('aria-label', 'Stop timing');
  said($('reachSaid'), `Asking ${r.total}…`);
}

/* Each result as it lands, written straight onto the row it belongs to.

   Redrawing the list per result would be 125 rebuilds of ninety rows, and
   would also re-sort under the reader's hands halfway through - so the row's
   own text is patched and the order is left until the run is over. */
function patchRow(file, rec) {
  /* The exit's own row first, if it happens to be open. This is the one the
     eye is on while a test runs - a list of servers reading "not tested"
     while their country's time is being rewritten above them is the app
     disagreeing with itself in public. */
  const ex = document.querySelector(
    `.exit[data-code="file:${(window.CSS && CSS.escape) ? CSS.escape(file) : file}"]`);
  if (ex) {
    ex.dataset.state = rec.ok === true ? 'ok'
      : rec.ok === false ? 'blocked' : 'untested';
    const cell = ex.querySelector('.exit__ping');
    if (cell) {
      cell.textContent = rec.ok === false ? (rec.why || 'no answer')
        : rec.ms !== null && rec.ms !== undefined ? msSaid(rec.ms)
          : 'not tested';
      cell.title = rec.why || '';
    }
    const sign = ex.querySelector('.row__tag');
    if (sign) sign.dataset.state = ex.dataset.state;
  }

  const country = canonCode(file.replace(/^\d+\.\d+s-/, '').slice(0, 2));
  const row = document.querySelector(`.row[data-code="${country}"] .row__meta`);
  if (!row) return;
  if (rec.ms !== null && rec.ms !== undefined) {
    const was = row.dataset.best ? Number(row.dataset.best) : null;
    if (was === null || rec.ms < was) {
      row.dataset.best = String(rec.ms);
      const base = row.textContent.split(' · ')[0];
      row.textContent = `${base} · ${msSaid(rec.ms)}`;
    }
  } else if (rec.ok === false && !row.dataset.best) {
    const base = row.textContent.split(' · ')[0];
    row.textContent = `${base} · no answer`;
  }
}

window.onReach = (p) => {
  // Eight at a time, so the count moves in steps rather than smoothly. It is
  // still the only honest thing to show: a bar would have to guess at how
  // long the ones still in flight are going to take, and the slow ones are
  // exactly the ones that are about to time out.
  const what = p.phase === 'pinging' ? 'timed' : 'checked';
  said($('reachSaid'), `${p.done} of ${p.total} ${what}…`);
  $('reachBar').style.setProperty('--at', `${(p.done / (p.total || 1)) * 100}%`);
  if (p.file && p.result) patchRow(p.file, p.result);
};

window.onReachDone = (r) => {
  state.testing = false;
  $('reachGo').dataset.busy = 'false';
  $('reachGo').setAttribute('aria-label', 'Time every exit');
  $('reachBar').style.setProperty('--at', '0%');
  if (!r.ok) { said($('reachSaid'), r.error || 'Could not test.', 'bad'); return; }
  state.countries = r.countries || state.countries;
  const wasOpen = state.openExits;
  state.exits = {};            // measured again, so the old detail is stale
  said($('reachSaid'),
    r.cancelled ? `Stopped after ${r.tested}. ${r.ok} answered.`
      : `${r.ok} of ${r.tested} answered.`,
    r.ok ? 'good' : 'bad');
  // The list is rebuilt rather than patched: every row's order can change,
  // because answering ones sort above blocked ones.
  $('list').dataset.key = '';
  drawList($('search').value.trim());
  // And re-open whatever was open, against the new answers. Clearing the
  // cache only stops the *next* open being stale; the one already on screen
  // stays exactly as it was until it is rebuilt.
  if (wasOpen) {
    state.openExits = null;
    const holder = document.querySelector(`[data-exits="${wasOpen}"]`);
    if (holder) toggleExits(wasOpen, holder.closest('.row'));
  }
  render();
};

$('reachGo').addEventListener('click', startReach);

/* -- the exits behind one row ------------------------------------------ */

/* Countries are what the list is, because ninety-one endpoints is not
   something anybody reads. But once each exit has a measured time, the
   individual ones are worth being able to look at - "why is this country
   slow" and "is this one blocked" are questions about a server. */

async function toggleExits(code, after) {
  const open = state.openExits === code;
  state.openExits = open ? null : code;
  for (const el of document.querySelectorAll('.exits')) el.remove();
  for (const b of document.querySelectorAll('[data-exits]')) {
    const on = b.dataset.exits === state.openExits;
    b.dataset.on = String(on);
    b.textContent = on ? '−' : '+';
  }
  if (open) return;

  const [where, via] = code.split(':');
  let list = state.exits[code];
  if (!list) {
    const r = await window.pywebview.api.exitsIn(where, via || null);
    list = (r && r.exits) || [];
    state.exits[code] = list;
  }
  if (state.openExits !== code) return;   // toggled again while it loaded

  const box = document.createElement('div');
  box.className = 'exits';
  // Whether every row here carries the same box name, in which case it
  // tells nobody anything.
  const shared = new Set(list.map(
    (x) => (x.host || x.file).split('.')[0])).size < list.length;
  let at = 0;
  for (const x of list) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'exit';
    row.style.setProperty('--i', at++);
    row.dataset.code = `file:${x.file}`;
    row.dataset.state = x.ok === true ? 'ok' : x.ok === false ? 'blocked' : 'untested';

    // Which provider this exit is, first, because "es-006" and "es-mad"
    // are the same kind of nothing until you know one is Windscribe's
    // numbering and the other is Surfshark's.
    const sign = document.createElement('span');
    sign.className = 'row__tag';
    sign.dataset.provider = x.provider;
    sign.dataset.state = x.ok === true ? 'ok'
      : x.ok === false ? 'blocked' : 'untested';
    sign.textContent = (PROVIDER_NAMES[x.provider] || x.provider).slice(0, 1);
    sign.title = PROVIDER_NAMES[x.provider] || x.provider;

    const name = document.createElement('span');
    name.className = 'exit__name';
    // The place, then the provider's own name for the box. Six rows reading
    // "es-mad" told you nothing about which of them you were looking at;
    // "Madrid · es-mad" at least says what the six have in common, and the
    // address beside it is what tells them apart.
    const short = (x.host || x.file).split('.')[0] || x.host;
    const where = x.nick ? `${x.cityName} ${x.nick}` : x.cityName;
    // The box's own name only when it distinguishes one row from another.
    // Surfshark pins several addresses to one host, so a column of them read
    // "Jakarta - id-jak" eight times over: the city said eight times, and
    // the address - the only thing that differed - crowded to the edge.
    name.textContent = (where && shared) ? where
      : where && where !== short ? `${where} · ${short}` : short;
    name.title = x.host || '';

    const meta = document.createElement('span');
    meta.className = 'exit__meta';
    meta.textContent = x.ip;

    const said_ = document.createElement('span');
    said_.className = 'exit__ping';
    said_.textContent = x.ok === true ? msSaid(x.ms)
      : x.ok === false ? (x.why || 'no answer')
        : 'not tested';
    if (x.ok === false) said_.title = x.why || '';

    row.append(sign, name, meta, said_);
    box.append(row);
  }
  after.insertAdjacentElement('afterend', box);
}



/* ------------------------------------------------ cities, stars and order */

/* Windscribe's own client groups country -> city, and the city is the level
   worth having: "Paris" is a place somebody means, where
   "fr-030.totallyacdn.com" is an address it happens to be at that week. It
   also carries the things their list carries and ours was throwing away -
   the nickname every group has, how loaded it is, whether the link is 10
   Gbps, whether P2P is allowed - all of which we already download. */

function loadSaid(pc) {
  if (pc === null || pc === undefined) return '';
  return `${pc}%`;
}

/* A star that is not a button, because the row it sits in is one. Clicks on
   it are caught by the list's own listener before the row sees them. */
function starFor(code) {
  const star = document.createElement('span');
  star.className = 'star';
  star.dataset.star = code;
  star.dataset.on = String(isFavourite(code));
  star.setAttribute('role', 'button');
  star.setAttribute('aria-label', 'Keep this one at the top');
  star.textContent = isFavourite(code) ? '★' : '☆';
  return star;
}

/* The sign that says who backs a place, and how that provider is doing there.

   It replaces a row per provider. The row said "Surfshark - 23 relays" and
   nothing else, because nothing about those twenty-three had been measured;
   the sign says the same thing in one letter and says it beside the name,
   where the eye already is. Its colour is that provider's own tally and not
   the country's, which is the distinction that had every Surfshark row
   reading blocked on the strength of Windscribe having been tested. */
function providerTag(key, count, tested, ok, ping) {
  const t = document.createElement('span');
  t.className = 'row__tag';
  t.dataset.provider = key;
  t.dataset.state = !tested ? 'untested'
    : ok ? (ok < tested ? 'some' : 'ok')
      : (tested >= count ? 'blocked' : 'some');
  t.textContent = (PROVIDER_NAMES[key] || key).slice(0, 1);
  const name = PROVIDER_NAMES[key] || key;
  const said_ = !tested ? 'not tested yet'
    : ok ? `${ok} of ${tested} answered`
      + (ping !== undefined && ping !== null ? `, best ${msSaid(ping)}` : '')
      : `none of ${tested} answered`;
  t.title = `${name} · ${count} relay${count === 1 ? '' : 's'} · ${said_}`;
  return t;
}

/* Which providers this list has signs for, in the order they are always
   drawn in. Only the ones that actually back something: a column standing
   empty down the whole list is 18px of nothing on every row. */
function tagColumns() {
  const seen = new Set();
  for (const c of state.countries || []) {
    const by = c.by || {};
    for (const key of Object.keys(by)) if (by[key] > 0) seen.add(key);
  }
  const known = Object.keys(PROVIDER_NAMES).filter((k) => seen.has(k));
  const rest = [...seen].filter((k) => !(k in PROVIDER_NAMES)).sort();
  return known.concat(rest);
}

/* A provider's column, where this country has no such provider. It holds the
   space and says nothing - the row__tag--none rule takes the box away and
   leaves the width.

   Empty and unmarked rather than aria-hidden: a span with no text and no
   role is not announced anyway, and hiding it would take it out of the
   layout audit as well, which is the one thing that can tell us these
   columns have stopped lining up. */
function tagGap() {
  const el = document.createElement('span');
  el.className = 'row__tag row__tag--none';
  return el;
}

function isFavourite(code) {
  return (state.favourites || []).includes(code);
}

async function toggleFavourite(code) {
  const r = await window.pywebview.api.toggleFavourite(code);
  if (!r.ok) return;
  state.favourites = r.codes;
  $('list').dataset.key = '';
  drawList($('search').value.trim());
}

/* The order. Answering-first stays underneath all three, because a blocked
   exit is not a good answer to "sort by name" either - and starred places
   come above everything, which is the whole point of starring one. */
function sortCountries(list) {
  const kind = state.sortBy || 'ping';
  const dead = (c) => (c.tested && !c.ok ? 1 : 0);
  const fav = (c) => (isFavourite(c.code) ? 0 : 1);
  const cmp = {
    ping: (a, b) => (a.ping === null) - (b.ping === null)
      || (a.ping || 0) - (b.ping || 0) || a.name.localeCompare(b.name),
    name: (a, b) => a.name.localeCompare(b.name),
    load: (a, b) => {
      const la = cityLoad(a);
      const lb = cityLoad(b);
      return (la === null) - (lb === null) || (la || 0) - (lb || 0)
        || a.name.localeCompare(b.name);
    },
  }[kind];
  return list.slice().sort((a, b) =>
    fav(a) - fav(b) || dead(a) - dead(b) || cmp(a, b));
}

function cityLoad(c) {
  const loads = (c.cityList || []).map((x) => x.load)
    .filter((x) => x !== null && x !== undefined);
  return loads.length ? Math.min(...loads) : null;
}

/* -- a city row -------------------------------------------------------- */

/* The plus on a row. `kind` is what it opens - the cities inside a country,
   or the hosts behind one place - and the list catches it before the row it
   sits in, which is the only reason a control can live inside a button. */
/* An empty one of the same size. Rows without a plus would otherwise pull
   their star a control's width to the right, and nothing down the right-hand
   edge of the list would line up with anything. */
function slot() {
  const el = document.createElement('span');
  el.className = 'expand expand--empty';
  return el;
}

function expander(kind, code, on) {
  const el = document.createElement('span');
  el.className = 'expand';
  el.dataset[kind === 'expand' ? 'expand' : 'exits'] = code;
  el.dataset.on = String(!!on);
  el.setAttribute('role', 'button');
  el.setAttribute('aria-label', kind === 'expand'
    ? 'Show the cities in this country' : 'Show the servers here');
  el.textContent = on ? '−' : '+';
  return el;
}

function buildCity(c, city, via) {
  const code = `${c.code}/${city.code}` + (via ? `:${via}` : '');
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'row row--city' + (state.picked === code ? ' is-picked' : '');
  row.dataset.code = code;
  row.dataset.state = !city.tested ? 'untested'
    : city.ok ? (city.ok < city.tested ? 'some' : 'ok')
      : (city.tested >= city.count ? 'blocked' : 'some');

  // A dot where the country has its flag, so the names line up in one
  // column rather than stepping in and out under it.
  const dot = document.createElement('span');
  dot.className = 'chip chip--dot';
  row.append(dot);

  const copy = document.createElement('span');
  copy.className = 'row__copy';

  const name = document.createElement('span');
  name.className = 'row__name';
  name.textContent = city.name;
  if (via) {
    const who = document.createElement('i');
    who.className = 'row__via';
    who.textContent = PROVIDER_NAMES[via] || via;
    name.append(' ', who);
  }
  // Windscribe names every one of its cities - Paris is Seine, Dallas is
  // Ranch, South Bend is Hawkins - and it is the only part of their list
  // that is theirs rather than a fact about geography. Worth keeping.
  if (city.nick) {
    const nick = document.createElement('i');
    nick.className = 'row__nick';
    nick.textContent = city.nick;
    name.append(' ', nick);
  }

  const meta = document.createElement('span');
  meta.className = 'row__meta';
  const n = via ? (city.by || {})[via] || 0 : city.count;
  const bits = [`${n} relay${n === 1 ? '' : 's'}`];
  if (city.ping !== null && city.ping !== undefined) bits.push(msSaid(city.ping));
  if (city.tested && !city.ok && city.tested >= city.count) {
    bits.push('blocked');
  }
  meta.textContent = bits.join(' · ');
  copy.append(name, meta);

  const marks = document.createElement('span');
  marks.className = 'row__marks';
  // Load is the number that decides between two cities that both answer, and
  // the one that goes stale fastest - it was read when the list was fetched.
  if (city.load !== null && city.load !== undefined) {
    const load = document.createElement('span');
    load.className = 'load';
    load.dataset.level = city.load >= 60 ? 'high' : city.load >= 25 ? 'mid' : 'low';
    load.style.setProperty('--at', `${Math.min(100, city.load)}%`);
    load.title = `${loadSaid(city.load)} loaded when the list was fetched`;
    marks.append(load);
  }
  if (city.gbps === 10) {
    const fast = document.createElement('span');
    fast.className = 'mark';
    fast.textContent = '10G';
    fast.title = '10 Gbps link';
    marks.append(fast);
  }
  if (city.p2p) {
    const p2p = document.createElement('span');
    p2p.className = 'mark';
    p2p.textContent = 'P2P';
    p2p.title = 'P2P allowed here';
    marks.append(p2p);
  }

  const from = Object.keys(city.by || {}).filter((k) => city.by[k] > 0).sort();
  if (from.length) {
    const tags = document.createElement('span');
    tags.className = 'row__tags';
    for (const key of from) {
      // A city's own numbers are not split per provider, so the sign says
      // how many are there and leaves the verdict to the country's.
      tags.append(providerTag(key, city.by[key], 0, 0, null));
    }
    marks.append(tags);
  }
  row.append(copy, marks, starFor(code),
             expander('exits', code, state.openExits === code));
  return row;
}

/* -- the controls above the list --------------------------------------- */

function paintSort() {
  for (const b of $('sortBy').querySelectorAll('.pair__opt')) {
    b.setAttribute('aria-selected', String(b.dataset.sort === (state.sortBy || 'ping')));
  }
}

$('sortBy').addEventListener('click', async (e) => {
  const opt = e.target.closest('.pair__opt');
  if (!opt) return;
  state.sortBy = opt.dataset.sort;
  paintSort();
  $('list').dataset.key = '';
  drawList($('search').value.trim());
  await window.pywebview.api.setSort(state.sortBy);
});
