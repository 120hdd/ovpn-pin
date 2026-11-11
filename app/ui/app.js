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

/* Reduced motion, read through gsap.matchMedia() so it follows the setting
   live. Everything that would otherwise tween asks CALM first. */
let CALM = false;
const liveLoop = { tw: null };   // the live dot's halo loop (syncLive)
if (window.gsap) {
  gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => {
    CALM = true;
    syncLive();   // the one perpetual loop stops
    return () => { CALM = false; syncLive(); };
  });
}

/* The live dot beside the status word: there while the route is up, its halo
   ring breathing out once every 1.4 s. The only loop in the app that never
   ends on its own, so it is started and stopped with the connection and not
   run at all under reduced motion. */
function syncLive() {
  const halo = document.querySelector('#liveDot .live__halo');
  if (!halo || !window.gsap) return;
  const on = document.documentElement.dataset.conn === 'on';
  if (on && !CALM) {
    if (!liveLoop.tw) {
      liveLoop.tw = gsap.fromTo(halo, { scale: 1, autoAlpha: 0.6 },
        { scale: 2.4, autoAlpha: 0, duration: 1.4, ease: 'power1.out', repeat: -1 });
    }
  } else if (liveLoop.tw) {
    liveLoop.tw.kill();
    liveLoop.tw = null;
    gsap.set(halo, { clearProps: 'transform,opacity,visibility' });
  }
}

const state = {
  countries: [],
  picked: 'auto',
  mode: 'off',          // off | busy | on | fail
  realIp: null,
  fuse: null,
  cursor: 0,
  systemProxy: true,
  routeWith: 'proxy',   // proxy | tun - how Windows is routed, when it is
  tunReady: true,       // whether this copy has sing-box.exe to do it with
  elevated: false,      // already administrator, so no UAC prompt to warn of
  split: null,          // the split tunnel in force; null on a backend without one
  splitLists: null,     // how big the Iranian lists are, as the backend counted them
  port: 8877,           // where the proxy listens; the backend owns the truth
  hasCredentials: true,
  sweep: null,          // null when idle, otherwise how the run is going
  plan: null,           // the last answer about what a test would cost
  pinning: null,        // the same two, for pinning
  pinPlan: null,
  providers: null,      // which providers the exit list is drawn from
  testing: null,        // which row is being tested, if any
  favourites: [],       // picker codes kept at the top of the list
  sortBy: 'ping',       // ping | name | load
  openCities: new Set(),  // countries showing their cities
  openContinents: new Set(),  // continents opened in the picker this time
  openExits: null,      // which row has its individual exits showing
  exits: {},            // and those exits, once fetched, by that row's code
  sources: null,        // which pools there are, and which one is in use
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

/* The world (atlas.js) behind the home screen. Without WebGL it hides its
   canvas and the home screen is type on the page alone. */
function initConnectionCore() {
  const canvas = $('atlasCanvas');
  if (!canvas || !window.Atlas) return;
  window.Atlas.init(canvas, { calm: () => CALM });
}

/* ------------------------------------------------------------- rendering */

/* Characters that settle into place left to right while the rest still
   shuffle: the connect moment's one piece of theatre, on the status word,
   the country and the address. Spaces and punctuation hold still so the
   shape of the line is there from the first frame. Not on Persian, whose
   letters join and would come apart, and not under reduced motion. */
const SCRAMBLE_ABC = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
function scramble(el, text, pool = SCRAMBLE_ABC, duration = 0.7) {
  if (el.__scramble) el.__scramble.kill();
  el.__scramble = null;
  if (!window.gsap || CALM || /[؀-ۿ]/.test(text)) { el.textContent = text; return; }
  const p = { v: 0 };
  el.__scramble = gsap.to(p, {
    v: 1, duration, ease: 'power2.out',
    onUpdate() {
      const settled = Math.floor(p.v * text.length);
      let out = text.slice(0, settled);
      for (let i = settled; i < text.length; i += 1) {
        out += /[\s.:\-·]/.test(text[i]) ? text[i] : pool[(Math.random() * pool.length) | 0];
      }
      el.textContent = out;
    },
    onComplete() { el.textContent = text; el.__scramble = null; },
  });
}

function setIp(el, value, fresh) {
  if (el.textContent === value && !el.__scramble) return;
  el.textContent = value;
  fitText(el);
  if (fresh && /^[0-9a-f.:]+$/i.test(value)) scramble(el, value, '0123456789', 0.6);
  if (fresh) {
    el.classList.remove('fresh');
    void el.offsetWidth;
    el.classList.add('fresh');
  }
}

/* An address too long for its box steps down in size until it fits, rather
   than losing its end to an ellipsis - an IPv6 address cut short is a
   different address. Half a pixel at a time, never below 9px. */
function fitText(el, floor = 9) {
  el.style.fontSize = '';
  let size = parseFloat(getComputedStyle(el).fontSize);
  while (el.scrollWidth > el.clientWidth + 0.5 && size > floor) {
    size -= 0.5;
    el.style.fontSize = `${size}px`;
  }
}

let refitting = 0;
window.addEventListener('resize', () => {
  clearTimeout(refitting);
  refitting = setTimeout(() => ['realIp', 'exitIp'].forEach((id) => fitText($(id))), 120);
});

function setStatus(word, kind, where, host) {
  const s = $('status');
  // Sentence case: the words are written in capitals in a dozen places, and
  // the screen says "Disconnected", not "DISCONNECTED".
  word = String(word || '').toLowerCase().replace(/^./, (c) => c.toUpperCase());
  if (s.textContent !== word) {
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
  const c = state.countries.find((x) => x.code === canonCode(where));
  let name = c ? c.name : (where || '').toUpperCase();
  if (city) {
    const found = (c && c.cityList || []).find((x) => x.code === city);
    name = found ? `${name} · ${found.name}` : `${name} · ${city.toUpperCase()}`;
  }
  return via ? `${name} · ${PROVIDER_NAMES[via] || via}` : name;
}

/* One screen, four conditions. `--state` is set once at the root and every
   part of the window that means "the connection" reads it; the status word,
   the button and the flag behind it all change in the same breath. */
let shownConn = null;

function render() {
  const act = $('act');
  const visualState = state.mode === 'fail' ? 'fail' : state.mode;
  document.documentElement.dataset.conn = visualState;
  syncLive();
  act.dataset.mode = state.mode === 'fail' ? 'off' : state.mode;
  // Owned here rather than switched off once in boot(). Adding an account is
  // a repaint and not a restart, so a button disabled at boot stayed disabled
  // for the rest of the session no matter what was signed in afterwards.
  act.disabled = !state.hasCredentials;
  $('card').dataset.busy = String(state.mode === 'busy');
  $('card').dataset.state = visualState;
  $('hero').dataset.state = visualState;
  const orb = $('globeHit');
  orb.setAttribute('aria-disabled', String(!state.hasCredentials));
  orb.setAttribute('aria-label', ({
    off: 'Connect', fail: 'Connect', busy: 'Cancel', on: 'Disconnect',
  })[visualState]);
  $('swap').dataset.state = visualState;
  $('pick').disabled = state.mode === 'busy';
  $('more').hidden = state.mode !== 'on';
  // Put away the moment the route goes, rather than left showing the last
  // figures it had. It opens again on its own when a reading arrives - which
  // is the only thing that proves there is anything to meter.
  if (state.mode !== 'on') flux.stop();
  if (state.mode !== 'on') {
    $('details').hidden = true;
    $('more').setAttribute('aria-expanded', 'false');
    $('statusClock').textContent = '';
  }

  // The location line. Connected, it is where the traffic really comes out;
  // otherwise it is the pick - read through nameOf, which knows the four
  // shapes a pick can take (looking the code up in the country list only
  // ever worked for the plainest, and put "fr:windscribe" on the screen).
  let code = null;
  let label;
  const any = !state.picked || state.picked === 'auto';
  if (state.mode === 'on' && state.live) {
    code = state.live.code;
    label = state.live.name;
  } else if (any) {
    label = 'Any country';
  } else {
    const where = String(state.picked).startsWith('file:')
      ? canonCode(state.picked.slice(5).replace(/^\d+\.\d+s-/, '').slice(0, 2))
      : String(state.picked).split(/[:/]/)[0];
    const c = state.countries.find((x) => x.code === where);
    code = c ? c.code : null;
    label = nameOf(state.picked);
  }
  // Arriving somewhere is announced; everything else just shows the name.
  const arrived = state.mode === 'on' && state.live && label !== shownLabel;
  shownLabel = label;
  if (arrived) scramble($('pickLabel'), label, SCRAMBLE_ABC, 0.8);
  else if (!$('pickLabel').__scramble) $('pickLabel').textContent = label;
  $('pick').dataset.any = (any && !(state.mode === 'on' && state.live)) ? '1' : '0';
  $('pickFlag').style.backgroundImage = code ? flagUrl(code) : '';

  const flagCode = state.mode === 'on' && state.live ? state.live.code : null;
  setBackdrop(flagCode);
  if (window.Atlas) window.Atlas.sync(flagCode, code);
  if (shownConn !== null && shownConn !== visualState) stateMoment(shownConn, visualState);
  shownConn = visualState;
}

/* The connected country's flag, dim, behind the whole home screen: cover,
   faded into the page by a linear gradient and darkened at the edges (both
   in CSS, on the layer). It comes in over 333ms on a decel curve when the
   route comes up and goes the same way when it does not; with reduced motion
   it is simply there or not. */
let backdropCode = null;

function setBackdrop(code) {
  code = code || null;
  if (code === backdropCode) return;
  backdropCode = code;
  const host = $('backdrop');
  const calm = CALM;
  for (const old of host.querySelectorAll('.backdrop__layer')) {
    if (calm || !window.gsap) old.remove();
    else gsap.to(old, { opacity: 0, duration: 0.333, ease: 'expo.out', overwrite: true,
                        onComplete: () => old.remove() });
  }
  if (!code) return;
  const probe = new Image();
  probe.onload = () => {
    if (backdropCode !== code) return;      // moved on while it loaded
    const layer = document.createElement('div');
    layer.className = 'backdrop__layer';
    const flag = document.createElement('i');
    flag.className = 'backdrop__flag';
    flag.style.backgroundImage = flagUrl(code);
    layer.append(flag);
    host.append(layer);
    if (!calm && window.gsap) {
      gsap.fromTo(layer, { opacity: 0 }, { opacity: 1, duration: 0.333, ease: 'expo.out' });
    }
  };
  probe.src = `flags/${code}.svg`;
}

/* ------------------------------------------------------ the connect moment */

/* The one orchestrated moment, one timeline. The status word arrives from
   just below in the new colour (the colour itself is the `--state` transition
   in CSS); the backdrop fade-in and the sparkline fade are started in the same
   render by their own owners (setBackdrop and the .flux rule), and the map
   by Atlas.sync (the camera's journey and the fills). Reduced motion:
   nothing tweens, it all just swaps. */
const moment = { tl: null };
let shownLabel = null;

function stateMoment() {
  if (moment.tl) { moment.tl.kill(); moment.tl = null; }
  if (!window.gsap || CALM) return;
  moment.tl = gsap.timeline({ defaults: { ease: 'expo.out' } });
  moment.tl.fromTo('#status', { y: 8, opacity: 0 },
                   { y: 0, opacity: 1, duration: 0.25, clearProps: 'transform,opacity' }, 0);
  const word = $('status');
  moment.tl.add(() => scramble(word, word.textContent, SCRAMBLE_ABC, 0.55), 0);
}

/* --------------------------------------------------------------- picker */

function openPicker() {
  const began = performance.now();
  state.freeze = null;
  $('list').dataset.key = '';
  maybeQuick();
  $('search').value = '';
  $('searchMain').classList.remove('is-typed');
  $('pickAny').hidden = !state.picked || state.picked === 'auto';
  state.cursor = 0;
  // Every open of the sheet starts with the continents shut: a continent,
  // then a country, then a server - three short choices instead of one long
  // scroll.
  state.openContinents.clear();
  drawList('');
  $('picker').showModal();
  slideSort();
  enterRows($('list').querySelectorAll('.grp'));
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

/* The countries are grouped by continent, in this order. A country the
   catalogue gave no continent to (or one this list has not heard of) goes
   under "Elsewhere" rather than vanishing. */
const CONTINENTS = ['Europe', 'Middle East', 'Asia', 'North America',
  'South America', 'Africa', 'Oceania', 'Antarctica', 'Elsewhere'];
const continentOf = (c) => (CONTINENTS.includes(c.continent) ? c.continent : 'Elsewhere');
// Each drawer's mark in the sprite (#i-ct-…), the same drawings as Android's.
const CONTINENT_MARK = {
  Europe: 'europe', 'Middle East': 'middle-east', Asia: 'asia',
  'North America': 'north-america', 'South America': 'south-america',
  Africa: 'africa', Oceania: 'oceania', Antarctica: 'antarctica',
  Elsewhere: 'elsewhere', Starred: 'starred',
};

/* What the list on screen was built from. Nothing about it changes between
   one open and the next unless the query or the chosen country has, so it is
   only built when one of them has. The provider selection belongs in here
   too: what a country row says depends on which providers back it. */
function listKey(q) {
  return `${q}|${state.picked}|${state.countries.length}`
    + `|${(state.providers || []).join(',')}`
    + `|${[...state.openCities].sort().join(',')}`
    + `|${[...state.openContinents].sort().join(',')}`
    + `|${state.sortBy}|${(state.favourites || []).join(',')}`;
}

function drawList(query) {
  const list = $('list');
  const q = (query || '').trim();
  const key = listKey(q);
  if (list.dataset.key === key) return;
  list.dataset.key = key;

  // Where the reader was. replaceChildren() empties the list, which drops
  // the scroll to nothing - so opening a country halfway down the list threw
  // the page back to the top and lost the row that had just been clicked.
  const wasAt = list.scrollTop;
  list.replaceChildren();

  const total = document.createElement('p');
  total.className = 'listtotal';
  const tally = document.createElement('b');
  tally.id = 'listCount';
  total.append(tally, ' locations');
  list.append(total);

  let found = sortCountries(
    q ? state.fuse.search(q).map((r) => r.item) : state.countries);
  // The order a quick run found the list in, held while its results land:
  // rows that move out from under the pointer are worse than numbers that
  // are briefly out of order. Fastest first puts them in place.
  if (!q && state.freeze) {
    const at = new Map(state.freeze.order.map((code, i) => [code, i]));
    const pos = (c) => (at.has(c.code) ? at.get(c.code) : 1e9);
    found = found.slice().sort((a, b) => pos(a) - pos(b));
  }
  if (!found.length) {
    tally.textContent = '0';
    const e = document.createElement('p');
    e.className = 'empty';
    e.textContent = `No country matches “${q}”.`;
    list.append(e);
    return;
  }

  /* The groups: starred places first when there are any, then continents in
     their order. Search runs across all of them and a group with nothing in
     it is not drawn. */
  const groups = [];
  const starred = found.filter(starredIn);
  if (starred.length) groups.push(['Starred', starred]);
  const others = found.filter((c) => !starredIn(c));
  for (const name of CONTINENTS) {
    const items = others.filter((c) => continentOf(c) === name);
    if (items.length) groups.push([name, items]);
  }

  // Fastest first is only honest about the countries that were timed; within
  // a group the rest go below their own heading, in alphabetical order.
  const byPing = !q && (state.sortBy || 'ping') === 'ping';
  const timed = (c) => (state.freeze ? state.freeze.tested.has(c.code) : !!c.tested);

  const jobs = [];
  for (const [name, items] of groups) {
    const sec = document.createElement('section');
    sec.className = 'grp';
    sec.dataset.group = name;
    // A continent is a drawer, shut until it is asked for. Starred places are
    // the short list on purpose and stay open, and so does everything while a
    // search is typed - a result inside a shut drawer is a result not found.
    const fixed = !!q || name === 'Starred';
    const open = fixed || state.openContinents.has(name);
    const head = document.createElement(fixed ? 'p' : 'button');
    head.className = 'gh';
    if (!fixed) {
      head.type = 'button';
      head.dataset.continent = name;
      head.setAttribute('aria-expanded', String(open));
    }
    // The continent's own mark in a well, then its name and how many.
    const well = document.createElement('span');
    well.className = 'gh__well';
    well.setAttribute('aria-hidden', 'true');
    well.append(icon(`#i-ct-${CONTINENT_MARK[name] || 'elsewhere'}`, 'ico'));
    const hn = document.createElement('span');
    hn.className = 'gh__name';
    hn.textContent = name;
    const hc = document.createElement('span');
    hc.className = 'gh__n';
    hc.textContent = String(items.length);
    head.append(well, hn, hc);
    if (!fixed) {
      const chev = icon('#i-chev-down', 'ico gh__chev');
      chev.setAttribute('aria-hidden', 'true');
      head.append(chev);
    }
    const body = document.createElement('div');
    body.className = 'grp__in';
    sec.append(head, body);
    sec.dataset.open = String(open);
    list.append(sec);
    if (!open) continue;

    let ordered = items;
    let untimedAt = -1;
    if (byPing) {
      const yes = items.filter(timed);
      const no = items.filter((c) => !timed(c))
        .sort((a, b) => a.name.localeCompare(b.name));
      if (yes.length && no.length) untimedAt = yes.length;
      ordered = yes.concat(no);
    }
    ordered.forEach((c, i) => jobs.push({
      sec: body, c, split: i === untimedAt ? ordered.length - untimedAt : 0,
    }));
  }

  const place = ({ sec, c, split }) => {
    if (split) {
      const p = document.createElement('p');
      p.className = 'listsplit';
      p.append('Not tested yet');
      const n = document.createElement('b');
      n.textContent = String(split);
      p.append(n);
      sec.append(p);
    }
    sec.append(countryCard(c));
  };

  tally.textContent = String(found.length);

  // A screenful now, the rest once the frame is over. Seventy-five of these
  // built in one go was measured at about 165ms of the first open's stutter -
  // and sixty-seven of them are below the fold, where nobody is waiting for
  // them. content-visibility already stops those being painted; this stops
  // them being built in the frame that matters.
  const AT_ONCE = 14;
  jobs.slice(0, AT_ONCE).forEach(place);
  markCursor();
  // Restored after the first batch, and again once the tail lands: the list
  // is not tall enough to hold the old position until the rest of it is
  // there, and a scrollTop set past the end is silently clamped.
  if (wasAt) list.scrollTop = wasAt;

  // And the tail a handful at a time, so no single batch is big enough to
  // miss a frame.
  const rest = jobs.slice(AT_ONCE);
  if (!rest.length) return;
  const later = window.requestIdleCallback || ((fn) => setTimeout(fn, 16));
  let at = 0;
  const more = () => {
    // Only while this is still the list on screen. A search typed while the
    // tail was queued would otherwise have the old countries land under it.
    if (list.dataset.key !== key) return;
    rest.slice(at, at + AT_ONCE).forEach(place);
    if (wasAt && list.scrollTop !== wasAt) list.scrollTop = wasAt;
    at += AT_ONCE;
    if (at < rest.length) later(more);
  };
  later(more);
}

/* A country: its row, and when it is open its cities underneath, in place.
   Lit when the pick is anywhere in it - a city or one server as much as the
   country - so the list says where you are without a tick. */
function countryCard(c) {
  const box = document.createElement('div');
  box.className = 'group' + (pickIsIn(c) ? ' is-in' : '');
  box.dataset.code = c.code;
  box.append(buildCountry(c));
  const cities = state.openCities.has(c.code) ? cityRows(c) : [];
  if (cities.length) {
    box.classList.add('is-open');
    const inner = document.createElement('div');
    inner.className = 'group__in';
    inner.append(...cities);
    box.append(inner);
  }
  return box;
}

/* Its cities, starred ones first and the rest in the order the core gave.
   A country with one city is already that city, so it has none to show. */
function cityRows(c) {
  const cityList = c.cityList || [];
  if (cityList.length <= 1) return [];
  const starred = (x) => (isFavourite(`${c.code}/${x.code}`) ? 0 : 1);
  return cityList.slice().sort((a, b) => starred(a) - starred(b))
    .map((city) => buildCity(c, city));
}

function buildCountry(c) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'row' + (state.picked === c.code ? ' is-picked' : '');
  row.dataset.code = c.code;
  row.dataset.state = reachState(c);
  row.style.setProperty('--flag', flagUrl(c.code));
  const chip = document.createElement('span');
  chip.className = 'flag';
  row.append(chip);

  const copy = document.createElement('span');
  copy.className = 'row__copy';
  const name = document.createElement('span');
  name.className = 'row__name';
  name.textContent = c.name;
  const meta = document.createElement('span');
  meta.className = 'row__meta';
  const cities = c.cityList || [];
  const places = cities.length > 1 ? `${cities.length} cities`
    : (cities[0] && cities[0].name) || '';
  // "11 relays" breaks before the number, never inside it, when the line has
  // to wrap.
  const line = [places, `${c.count} relay${c.count === 1 ? '' : 's'}`]
    .filter(Boolean).map((s) => s.replace(/ /g, ' ')).join(' · ');
  const count = document.createElement('span');
  count.className = 'row__count';
  count.textContent = line;
  meta.append(count);
  // Who runs these exits and what they offer: the provider's mark, then 10G
  // and P2P.
  const badges = document.createElement('span');
  badges.className = 'row__badges';
  const by = c.by || {};
  for (const k of Object.keys(by).filter((k) => by[k] > 0).sort()) {
    badges.append(providerTag(k, by[k], (c.byTested || {})[k] || 0,
                              (c.byOk || {})[k] || 0, (c.byPing || {})[k]));
  }
  for (const [has, word, why, kind] of [
    [cities.some((x) => x.gbps === 10), '10G', '10 Gbps link', 'fast'],
    [cities.some((x) => x.p2p), 'P2P', 'P2P allowed', 'p2p'],
  ]) {
    if (!has) continue;
    const m = document.createElement('span');
    m.className = 'mark';
    m.dataset.kind = kind;
    m.textContent = word;
    m.title = why;
    badges.append(m);
  }
  if (badges.childNodes.length) meta.append(badges);
  copy.append(name, meta);

  // The time, in mono at the trailing edge - and "Blocked" only where every
  // exit that was asked turned away. Nothing before it has been asked, because
  // "untested" and "blocked" must not look alike.
  const marks = document.createElement('span');
  marks.className = 'row__marks';
  const ping = document.createElement('span');
  ping.className = 'row__ping';
  if (c.tested && !c.ok && c.tested >= c.count) {
    ping.textContent = 'Blocked';
    ping.dataset.blocked = '1';
  } else if (c.tested && c.ok && c.ping !== null && c.ping !== undefined) {
    ping.textContent = msSaid(c.ping);
  }
  marks.append(ping);
  row.append(copy, marks);

  // A country with cities opens into them; one without opens straight into
  // its hosts, because there is no middle to show. Either way it is the same
  // chevron in the same place.
  const many = cities.length > 1;
  const opens = many || c.count > 1;
  row.append(railFor(c.code, opens ? (many ? 'expand' : 'exits') : null,
                     many ? state.openCities.has(c.code)
                          : state.openExits === c.code));
  return row;
}

/* Open or shut a country's cities where it stands: the list is not redrawn,
   the panel grows to its height and the rows already on screen stay put. */
function toggleCities(code) {
  const opening = !state.openCities.has(code);
  if (opening) state.openCities.add(code);
  else state.openCities.delete(code);
  const q = $('search').value.trim();
  const c = state.countries.find((x) => x.code === code);
  const group = $('list').querySelector(`.group[data-code="${CSS.escape(code)}"]`);
  if (!c || !group) { $('list').dataset.key = ''; drawList(q); return; }
  $('list').dataset.key = listKey(q);        // the next draw has nothing to do
  const handle = group.querySelector('[data-expand]');
  if (handle) handle.dataset.on = String(opening);
  const calm = CALM || !window.gsap;
  if (opening) {
    const inner = document.createElement('div');
    inner.className = 'group__in';
    inner.append(...cityRows(c));
    group.append(inner);
    group.classList.add('is-open');
    if (!calm) {
      gsap.fromTo(inner, { height: 0 },
                  { height: 'auto', duration: 0.25, ease: 'expo.out', clearProps: 'height' });
    }
  } else {
    const inner = group.querySelector('.group__in');
    const done = () => {
      if (inner) inner.remove();
      if (!group.querySelector('.exits')) group.classList.remove('is-open');
    };
    if (!inner || calm) done();
    else gsap.to(inner, { height: 0, duration: 0.167, ease: 'power2.in', onComplete: done });
  }
  markCursor();
}

/* Open or shut a continent. The list is drawn again with its countries in
   (or out), and the drawer's body grows from nothing to its height - or
   folds away first and is then left out. */
/* Rows arriving: up from 8px and in, 30 ms apart, the first twelve only -
   past those nobody is watching them land. */
function enterRows(nodes) {
  if (CALM || !window.gsap) return;
  const first = Array.from(nodes).slice(0, 12);
  if (!first.length) return;
  gsap.fromTo(first, { y: 8, autoAlpha: 0 },
    { y: 0, autoAlpha: 1, duration: 0.32, ease: 'power2.out', stagger: 0.03,
      clearProps: 'transform,opacity,visibility' });
}

function toggleContinent(name) {
  const opening = !state.openContinents.has(name);
  const q = $('search').value.trim();
  const sel = `.grp[data-group="${CSS.escape(name)}"] > .grp__in`;
  const calm = CALM || !window.gsap;
  const redraw = () => {
    // One drawer open at a time: the open one is the sheet's single lit item.
    if (opening) { state.openContinents.clear(); state.openContinents.add(name); }
    else state.openContinents.delete(name);
    $('list').dataset.key = '';
    drawList(q);
    const body = $('list').querySelector(sel);
    if (opening && body) {
      const head = body.previousElementSibling;
      if (head) head.scrollIntoView({ block: 'nearest' });
    }
    if (opening && body && !calm) {
      gsap.fromTo(body, { height: 0, opacity: 0 },
                  { height: 'auto', opacity: 1, duration: 0.3, ease: 'expo.out', clearProps: 'height,opacity' });
      enterRows(body.querySelectorAll('.row'));
    }
    // the cursor stays on the drawer just opened or shut, not back at the top
    const at = rows().findIndex((r) => r.dataset.continent === name);
    if (at >= 0) state.cursor = at;
    markCursor();
  };
  const body = $('list').querySelector(sel);
  if (!opening && body && body.childNodes.length && !calm) {
    gsap.to(body, { height: 0, opacity: 0, duration: 0.18, ease: 'power2.in', onComplete: redraw });
  } else redraw();
}

/* What the arrow keys walk: the drawers' headings as well as the rows, so a
   continent can be opened from the keyboard too. */
function rows() { return Array.from($('list').querySelectorAll('.row, .gh[data-continent]')); }

function markCursor() {
  const all = rows();
  if (!all.length) return;
  state.cursor = Math.max(0, Math.min(state.cursor, all.length - 1));
  all.forEach((r, i) => r.classList.toggle('is-cursor', i === state.cursor));
  all[state.cursor].scrollIntoView({ block: 'nearest' });
}

async function choose(code) {
  state.picked = code;
  shut($('picker'));
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
    setStatus('Switching', 'busy', 'Leaving the old one', '');
    try {
      await window.pywebview.api.disconnect();
    } catch (_) { /* going anyway */ }
  }
  state.mode = 'busy';
  render();
  setStatus('Connecting', 'busy', 'Looking for a server', '');
  setHint('');
  const r = await window.pywebview.api.connect(code);
  if (!r.ok) { state.mode = 'off'; render(); }
}

/* --------------------------------------------------- events from the app */

const STAGES = {
  probing: 'Looking for a server',
  starting: 'Opening the connection',
  routing: 'Pointing Windows at it',
  adapter: 'Bringing up the network adapter',
  verifying: 'Checking it really works',
};

window.onProgress = (p) => {
  const detail = (p.phase === 'probing' && p.total)
    ? `${p.asked || 0} of ${p.total} asked` : '';
  setStatus('Connecting', 'busy', STAGES[p.phase] || 'Working', detail);
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
  $('exitFlag').style.backgroundImage = (measured || claimed) ? flagUrl(canonCode(measured || claimed)) : '';
  // How long it has been up, ticking beside the place - the desktop's
  // version of the Android notification's timer.
  state.since = status.since || Date.now() / 1000;
  // The place the traffic comes out in goes on the location line (see
  // render), under its flag; the flag is also what fills the background.
  const liveCode = canonCode(measured || claimed);
  state.live = { code: liveCode, name: place };
  setStatus('Connected', 'on', measured ? '' : 'Checking where it comes out', e.host || '');
  lanPane.refresh();
  paintClock();
  $('detIn').textContent = `${e.ip || '—'}:443`;
  $('detOut').textContent = confirmed || '—';
  $('detVia').textContent = e.host || '—';

  setHint(measured && claimed && measured !== claimed
    ? `Sold as ${nameOf(claimed)}, but its traffic really comes out in ${place}. What websites see is the second one.`
    : (e.unconfirmed
        ? 'Connected, but the independent check did not answer. If pages load, it is fine.'
        : (status.tun
            ? 'Every program on this PC now goes through this connection, including the ones that ignore the proxy setting.'
            : (state.systemProxy
                ? 'Everything on this PC now goes through this connection.'
                : `Serving on 127.0.0.1:${state.port}. Windows was left alone, so point what you want at it.`))));
  render();
  // The push comes once a second; this is so a window opened onto a
  // connection that was already up does not sit with an empty meter for the
  // first of those seconds.
  window.pywebview.api.traffic().then(window.onTraffic, () => {});
};

function clockText(seconds) {
  const s = Math.max(0, Math.floor(seconds));
  const two = (n) => String(n).padStart(2, '0');
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
  return h ? `${h}:${two(m)}:${two(s % 60)}` : `${m}:${two(s % 60)}`;
}

function paintClock() {
  const el = document.getElementById('statusClock');
  if (!el || state.mode !== 'on' || !state.since) return;
  el.textContent = clockText(Date.now() / 1000 - state.since);
}
setInterval(paintClock, 1000);

window.onFailed = (err) => {
  state.mode = 'fail';
  setIp($('exitIp'), '—', false);
  $('exitPlace').textContent = 'not connected';
  $('exitFlag').style.backgroundImage = '';
  setStatus('Not connected', 'fail', 'Could not connect', '');
  // The detail arrives as "tun-failed: <what sing-box said>"; only the part
  // after the kind is worth reading.
  const tunWhy = (err.detail || '').replace(/^tun-[a-z]+:\s*/, '').slice(0, 160);
  const say = {
    'all-refused': state.picked === 'auto'
      ? 'No server accepted just now. That happens — wait a moment and try again.'
      : `No ${nameOf(state.picked)} server accepted just now. Try again, or let it take whichever answers first.`,
    // Not the same failure as the one above, and the wrong advice for it.
    // A 407 is the account being turned away, and it is the same account at
    // every address - so every exit saying it is one fact repeated, not a
    // bad moment. "Wait and try again" was what this used to say, and
    // waiting is the one thing that cannot help.
    'credentials-refused':
      // The count comes from the engine, which is the only thing that knows
      // it, and it is the whole of the evidence - one exit refusing proves
      // nothing, thirty of them proves this.
      `${(err.detail || '').replace(/^credentials-refused:\s*/, '')
        || 'Every server turned this account away'}. `
      + 'So this is the account rather than the servers. Check the service '
      + 'username and password on the provider\'s manual-setup page — they '
      + 'are not the email you log in with, and they change when you reset '
      + 'them there.',
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
    // Not "try once more", because trying again is the one thing that cannot
    // work: something else holds the port and will go on holding it. Almost
    // always a second copy of this app - quitting it from its tray is what
    // puts the port back and puts Windows back with it.
    'port-taken': `Port ${err.port || 'the one in Settings'} is held by `
      + 'something else, most likely another copy of this app that is still '
      + 'running. Quit that one from its tray icon, or give this one a '
      + 'different port in Settings, and connect again.',
    // Only from a port change: the old worker was stopped and the new one
    // never answered, so there is nothing up and the sheet has the detail.
    'moved-and-died': 'The port changed, but the connection did not come back up on it. Connect again.',
    // Tunnel mode only. The exit was found and is fine; it is the adapter
    // that did not come up, so the way out is a setting, not another server.
    'tun-missing': 'Tunnel mode needs sing-box.exe beside Relay, and it is not '
      + 'there. Switch Settings → Connection back to the proxy setting.',
    'tun-refused': 'Windows was not given administrator approval, so the '
      + 'network adapter was not brought up. Connect again and allow it, or '
      + 'use the proxy setting instead.',
    'tun-failed': tunWhy
      ? `The network adapter did not come up: ${tunWhy}`
      : 'The network adapter did not come up.',
  };
  // A failure without its own sentence says what it was, rather than
  // "try once more" over a reason nobody gets to read.
  const why = (err.detail || '').trim();
  setHint(say[err.kind]
    || (why ? `Could not connect: ${why.slice(0, 160)}` : 'Could not connect. Try once more.'),
  err.kind !== 'cancelled');
  render();
};

window.onDisconnected = (info) => {
  state.mode = 'off';
  setIp($('exitIp'), '—', false);
  $('exitPlace').textContent = 'not connected';
  $('exitFlag').style.backgroundImage = '';
  setStatus('Disconnected', 'off', '', '');
  setHint(info && info.minutes
    ? `Windows is back to normal. Routed for ${info.minutes} minute${info.minutes === 1 ? '' : 's'}.`
    : 'Windows is back to normal.');
  render();
};

/* The Window screen: the tray menu's switches, plus keeping the connection
   when the window closes. Each flip is saved at once; the answer repaints
   the switches, so a refusal cannot leave one showing a lie. */
function paintWindowOptions(opts) {
  for (const sw of document.querySelectorAll('.toggle[data-option]')) {
    sw.setAttribute('aria-checked', String(!!opts[sw.dataset.option]));
  }
  paintDrag(!!opts.lockWindow);
}

/* There is no Windows frame: the header, and each sheet's heading, are what
   the window is dragged by - unless its position is locked, in which case
   they are just a header. pywebview looks the regions up on every press, so
   adding and removing the class is all locking takes. */
for (const h of document.querySelectorAll('.sheet__heading')) h.classList.add('drag-zone');

/* A sheet covers the whole window, header and all, so each one carries the
   window's own minimise and close in a strip along its top - the strip is
   also something to drag the window by. */
for (const sheet of document.querySelectorAll('dialog.sheet')) {
  const bar = document.createElement('div');
  bar.className = 'winbar drag-zone';
  for (const [label, icon, act, extra] of [
    ['Minimise', '#i-minus', () => window.pywebview.api.minimizeWindow(), ''],
    ['Close', '#i-x', closeRequest, ' icon--shut'],
  ]) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `icon icon--win${extra}`;
    // Named for the window, so it is never mistaken for the sheet's own
    // close button by a screen reader or by anything finding buttons by name.
    b.setAttribute('aria-label', `${label} window`);
    b.innerHTML = `<svg class="ico" aria-hidden="true"><use href="${icon}"/></svg>`;
    b.addEventListener('click', act);
    bar.appendChild(b);
  }
  sheet.prepend(bar);
}

function paintDrag(locked) {
  for (const el of document.querySelectorAll('.drag-zone')) {
    el.classList.toggle('pywebview-drag-region', !locked);
  }
}
paintDrag(false);

$('winMin').addEventListener('click', () => window.pywebview.api.minimizeWindow());
$('winClose').addEventListener('click', () => closeRequest());

window.onWindowOptions = (opts) => paintWindowOptions(opts || {});

for (const sw of document.querySelectorAll('.toggle[data-option]')) {
  sw.addEventListener('click', async () => {
    const on = sw.getAttribute('aria-checked') !== 'true';
    sw.setAttribute('aria-checked', String(on));
    const r = await window.pywebview.api.setWindowOption(sw.dataset.option, on);
    if (r && r.window) paintWindowOptions(r.window);
  });
}

$('keepOnClose').addEventListener('click', async () => {
  const sw = $('keepOnClose');
  const on = sw.getAttribute('aria-checked') !== 'true';
  sw.setAttribute('aria-checked', String(on));
  const r = await window.pywebview.api.setKeepOnClose(on);
  if (r) sw.setAttribute('aria-checked', String(!!r.keepOnClose));
});

/* The tray menu asks through here instead of calling the API itself, so that
   connecting from it walks the same steps as the button - the busy state,
   the stages, switching away from a connection already up - and the window
   says the same thing afterwards. Off the call stack, because the caller is
   a synchronous evaluate_js and these are async and take seconds. */
window.onTray = (ask) => {
  setTimeout(() => {
    if (ask.do === 'act') onAct();
    else if (ask.do === 'pick' && ask.code) choose(ask.code);
  }, 0);
};

window.onRealIp = (info) => {
  state.realIp = info && info.ip;
  setIp($('realIp'), (info && info.ip) || 'unknown', true);
  $('realPlace').textContent = (info && info.country)
    ? nameOf(info.country) : 'could not check';
  $('realFlag').style.backgroundImage = info && info.country ? flagUrl(canonCode(info.country)) : '';
  // Where you are is where the map starts: your own country from above.
  if (info && info.country && window.Atlas) window.Atlas.home(info.country);
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

  tween: null,               /* the one GSAP tween that carries a reading in */
  prog: { k: 0 },
  from: { down: 0, up: 0, downRate: 0, upRate: 0 },
  frac: 0,                   /* how far the line has slid toward the next reading */
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
    this.frac = 0;
    this.el.dataset.live = 'true';
    this.paint();
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
    if (this.tween) { this.tween.kill(); this.tween = null; }
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

    // The palette is read again for every reading: it is one lookup a second,
    // and a theme change would otherwise leave the strokes in the old ink.
    const skin = getComputedStyle(this.el);
    this.ink = {
      down: skin.getPropertyValue('--flux-down').trim() || this.ink.down,
      up: skin.getPropertyValue('--flux-up').trim() || this.ink.up,
    };

    this.from = { ...this.shown };
    this.want = { down: t.down || 0, up: t.up || 0, downRate: down, upRate: up };
    // A total that went backwards is a new worker on a new port, not a
    // correction - there is nothing to ease toward, so it is taken whole.
    if (this.want.down < this.shown.down) this.shown.down = this.want.down;
    if (this.want.up < this.shown.up) this.shown.up = this.want.up;
    setFlow(pace(down + up));
    if (this.tween) { this.tween.kill(); this.tween = null; }
    if (CALM || !window.gsap) {
      this.settle(1); this.frac = 0; this.paint();
      return;
    }
    // One tween per reading, linear, one second: the line slides one step
    // left while the figures walk to the new ones. It replaces the frame
    // loop this used to run; nothing moves between readings.
    this.prog.k = 0;
    this.tween = gsap.to(this.prog, {
      k: 1, duration: this.every / 1000, ease: 'none',
      onUpdate: () => {
        const e = 1 - (1 - this.prog.k) ** 3;
        for (const key of ['down', 'up', 'downRate', 'upRate']) {
          this.shown[key] = this.from[key] + (this.want[key] - this.from[key]) * e;
        }
        this.frac = this.prog.k;
        this.paint();
      },
    });
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
    ctx.strokeStyle = tint(this.ink.up, 0.22);
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
    const frac = CALM ? 0 : this.frac;
    const step = w / (this.span - 1);

    this.band(ctx, w, rule, rule - 3, (s) => s.d, this.peak.down,
              this.ink.down, 0.10, 1, step, frac, -1);
    this.band(ctx, w, rule, h - rule - 2, (s) => s.u, this.peak.up,
              this.ink.up, 0.07, 1, step, frac, 1);

    /* Where the line is now: a plain dot in the stroke's own ink, so the eye
       knows which end is the present. No glow. */
    const head = this.pts[this.pts.length - 1];
    if (!CALM && head && head.d > 512) {
      const y = rule - Math.min(1, head.d / this.peak.down) * (rule - 3);
      ctx.fillStyle = this.ink.down;
      ctx.beginPath();
      ctx.arc(w - frac * step - 1.5, y, 2, 0, Math.PI * 2);
      ctx.fill();
    }

    /* The trace comes out of nothing on the left rather than being sliced
       off by the edge of the canvas. */
    const fade = ctx.createLinearGradient(0, 0, w * 0.2, 0);
    fade.addColorStop(0, 'rgba(0, 0, 0, 1)');
    fade.addColorStop(1, 'rgba(0, 0, 0, 0)');
    ctx.globalCompositeOperation = 'destination-out';
    ctx.fillStyle = fade;
    ctx.fillRect(0, 0, w * 0.2, h);
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
    ctx.lineWidth = 1.5;
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
  filter: '',
  poll: null,
  rows: [],
  open: false,
  els: new Map(),        /* group key -> its row, so a refresh moves rows instead of remaking them */

  show() {
    if (this.open) return;
    this.open = true;
    $('log').showModal();
    this.slide();
    this.heads();
    this.pull();
    // The worker rewrites the list every other second, so asking faster
    // would be reading the same answer twice.
    this.poll = setInterval(() => this.pull(), 2000);
  },

  hide() {
    this.open = false;
    clearInterval(this.poll);
    this.poll = null;
    shut($('log'));
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
      // programs become the second column.
      return this.fold((r) => r.host, (r) => r.app);
    }
    return this.fold((r) => r.app || 'Not identified', (r) => r.host);
  },

  /* Rows keyed by `name`, remembering the distinct `other` values that went
     into each - which is what the second column says. */
  fold(name, other) {
    const out = new Map();
    for (const r of this.rows) {
      const key = name(r);
      let g = out.get(key);
      if (!g) {
        g = { key, up: 0, down: 0, hits: 0, live: 0, direct: 0, n: 0, others: new Set() };
        out.set(key, g);
      }
      g.up += r.up;
      g.down += r.down;
      g.hits += r.hits;
      g.live += r.live;
      g.n += 1;
      if (r.direct) g.direct += 1;
      const o = other(r);
      if (o) g.others.add(o);
    }
    return [...out.values()].sort((a, b) => (b.up + b.down) - (a.up + a.down));
  },

  heads() {
    $('logColA').textContent = this.by === 'host' ? 'Host' : 'Program';
    $('logColB').textContent = this.by === 'host' ? 'Program' : 'Hosts';
  },

  matches(g) {
    const q = this.filter.trim().toLowerCase();
    if (!q) return true;
    if (g.key.toLowerCase().includes(q)) return true;
    for (const o of g.others) if (o.toLowerCase().includes(q)) return true;
    return false;
  },

  draw(said) {
    const list = $('logList');
    const all = this.group();
    const groups = all.filter((g) => this.matches(g));
    for (const n of list.querySelectorAll('.lnone')) n.remove();

    // The dot on the header icon is about the whole ledger, not the filter.
    let anyLive = 0;
    for (const g of all) anyLive += g.live;
    $('logOpen').dataset.live = anyLive > 0 ? 'true' : 'false';

    if (!groups.length) {
      for (const el of this.els.values()) el.remove();
      this.els.clear();
      const p = document.createElement('p');
      p.className = 'lnone';
      p.textContent = all.length
        ? 'Nothing matches that.'
        : said && said.live
          ? 'Nothing has gone through yet. Open something and it will show up here.'
          : 'Nothing is routed right now. Connect, and this fills in as your programs start talking.';
      list.append(p);
      $('logSum').textContent = '—';
      $('logFoot').textContent = '';
      return;
    }

    const top = groups[0].up + groups[0].down;
    let up = 0;
    let down = 0;
    for (const g of groups) { up += g.up; down += g.down; }

    const [dn, du] = splitBytes(down);
    const [un, uu] = splitBytes(up);
    $('logSum').textContent =
      `${groups.length} ${this.by === 'host' ? 'hosts' : 'programs'}`
      + `  ·  ↓ ${dn} ${du}  ↑ ${un} ${uu}`;

    const keep = new Set();
    groups.forEach((g, i) => {
      keep.add(g.key);
      let el = this.els.get(g.key);
      const fresh = !el;
      if (fresh) { el = this.make(); this.els.set(g.key, el); }
      this.fill(el, g, top, fresh);
      // Only touch a row that is out of place, so the browser is not asked
      // to relayout a hundred rows every two seconds.
      if (list.children[i] !== el) list.insertBefore(el, list.children[i] || null);
    });
    for (const [k, el] of this.els) {
      if (!keep.has(k)) { el.remove(); this.els.delete(k); }
    }

    // Said only when it is true. The worker keeps the hundred busiest pairs
    // and nothing else, and a list that quietly stopped at a hundred while
    // looking complete is the kind of thing this app is meant not to do.
    const missing = ((said && said.total) || 0) - this.rows.length;
    $('logFoot').textContent = missing > 0
      ? `${missing} quieter ${missing === 1 ? 'one is' : 'ones are'} counted in the meter but not listed here.`
      : '';
  },

  make() {
    const el = document.createElement('div');
    el.className = 'lrow';
    el.setAttribute('role', 'row');
    const name = document.createElement('p');
    name.className = 'lrow__name';
    const dot = document.createElement('i');
    dot.className = 'lrow__live';
    dot.title = 'Open now';
    const who = document.createElement('span');
    who.className = 'lrow__who';
    const tag = document.createElement('span');
    tag.className = 'lrow__tag';
    tag.textContent = 'direct';
    tag.title = 'Went straight out, around the exit (split tunnel)';
    name.append(dot, who, tag);
    const sub = document.createElement('p');
    sub.className = 'lrow__sub';
    const dn = document.createElement('p');
    dn.className = 'lrow__num mono';
    const up = document.createElement('p');
    up.className = 'lrow__num mono';
    const bar = document.createElement('i');
    bar.className = 'lrow__bar';
    el.append(name, sub, dn, up, bar);
    return el;
  },

  fill(el, g, top, fresh) {
    el.dataset.by = this.by;
    el.dataset.live = g.live > 0 ? '1' : '0';
    el.dataset.direct = g.direct > 0 && g.direct === g.n ? '1' : '0';
    const [name, sub, dn, up, bar] = el.children;
    name.querySelector('.lrow__who').textContent = g.key;
    sub.textContent = this.caption(g);
    sub.title = [...g.others].join('\n');
    el.title = `${g.hits} connection${g.hits === 1 ? '' : 's'}`;
    const [d, du] = splitBytes(g.down);
    const [u, uu] = splitBytes(g.up);
    dn.textContent = `${d} ${du}`;
    up.textContent = `${u} ${uu}`;
    // Width as a share of the busiest row, never under 2% so a quiet host is
    // still a mark rather than nothing.
    const share = Math.max(0.02, (g.up + g.down) / (top || 1));
    if (fresh || CALM || !window.gsap) {
      if (window.gsap) gsap.set(bar, { scaleX: share });
      else bar.style.transform = `scaleX(${share})`;
    } else {
      gsap.to(bar, { scaleX: share, duration: 0.25, ease: 'expo.out', overwrite: true });
    }
  },

  /* The other half of the pair. One name when there is one, a count when
     there are several. */
  caption(g) {
    const many = g.others.size;
    if (!many) return this.by === 'host' ? 'not identified' : '—';
    if (many === 1) return [...g.others][0];
    return `${many} ${this.by === 'host' ? 'programs' : 'hosts'}`;
  },

  pick(by) {
    if (this.by === by) return;
    this.by = by;
    for (const b of $('logPair').querySelectorAll('.pair__opt')) {
      b.setAttribute('aria-selected', String(b.dataset.by === by));
    }
    this.slide();
    this.heads();
    for (const el of this.els.values()) el.remove();
    this.els.clear();
    this.draw({ live: true, total: this.rows.length });
  },

  /* The lit backing, moved to sit under whichever word is chosen. Measured
     rather than hard-coded: the two words are different lengths. */
  slide() {
    const chosen = $('logPair').querySelector('[aria-selected="true"]');
    const bar = $('logPair').querySelector('.pair__slide');
    if (!chosen || !bar) return;
    bar.style.width = `${chosen.offsetWidth}px`;
    bar.style.transform = `translateX(${chosen.offsetLeft - 2}px)`;
  },
};

$('logFilter').addEventListener('input', (e) => {
  log.filter = e.target.value;
  log.draw({ live: true, total: log.rows.length });
});

/* -------------------------------------------------------------- actions */

async function onAct() {
  if (state.mode === 'busy') {
    setStatus('Cancelling', 'off', 'Stopping', '');
    await window.pywebview.api.cancel();
    return;
  }
  if (state.mode === 'on') {
    setStatus('Disconnecting', 'off', 'Putting Windows back', '');
    const r = await window.pywebview.api.disconnect();
    window.onDisconnected(r && r.session);
    return;
  }
  state.mode = 'busy';
  render();
  setStatus('Connecting', 'busy', 'Looking for a server', '');
  setHint('');
  const r = await window.pywebview.api.connect(state.picked);
  if (!r.ok) { state.mode = 'off'; render(); }
}

/* The clipboard, and a brief mark on whatever was clicked to say so. Split
   from copyIp because the thing that gets the mark is not always the thing
   that holds the text - for the install command it is the button beside it. */
async function copyText(el, text) {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
  } catch (err) {
    await window.pywebview.api.copy(text);
  }
  el.classList.add('copied');
  setTimeout(() => el.classList.remove('copied'), 700);
}

async function copyIp(el) {
  const text = (el.textContent || '').trim();
  if (!text || text === '—' || text === '…' || text === 'unknown') return;
  await copyText(el, text);
}

/* ------------------------------------------------------------ the way out */

/* One sentence each, and each one says what it costs as well as what it
   gives. A strip of three words with no explanation would make the middle
   and the right look like settings; they are three different machines
   carrying the traffic, and the difference is worth a line. */
const WAY_SAID = {
  surfshark: 'Surfshark’s own proxy. Nothing to set up, and every '
    + 'country below is available. Uploads are the catch — on this line '
    + 'they crawl or stall outright.',
  single: 'Your own server, reached through a CDN. The quickest and steadiest '
    + 'of the three. No country to choose: the server is where it is.',
  multi: 'Your server, handing the last leg to a Surfshark exit. Sites see '
    + 'that country’s address, and uploads keep your server’s speed.',
};

const WAY_NEEDS_TUNNEL = 'Set the domain and passwords under Settings → '
  + 'Your own tunnel first.';

/* Which ways out can work right now. A provider needs an account and its
   servers, your server needs the tunnel set up, and server + exit needs
   both - so in practice the strip is either all three or nothing to choose. */
function waysOpen() {
  const provider = !!state.hasCredentials && (state.serverCount || 0) > 0;
  const tunnel = !!state.tunnelReady
    || !!(state.tunnel && state.tunnel.domain && state.tunnel.hasPassword);
  return ['surfshark', 'single', 'multi'].filter((w) =>
    w === 'surfshark' ? provider : w === 'single' ? tunnel : tunnel && provider);
}

function paintWay() {
  const way = state.way || 'surfshark';
  const ready = way === 'surfshark' || (state.tunnel && state.tunnel.domain);

  // Only once the window knows both halves - before info() lands, nothing
  // here can tell a tunnel that is not set up from one not yet asked about.
  if (state.serverCount !== undefined && state.tunnelReady !== undefined) {
    const open = waysOpen();
    for (const b of $('way').querySelectorAll('.dd__opt')) {
      b.hidden = !open.includes(b.dataset.way);
    }
    // One way left is nothing to choose, so the menu is not drawn at all.
    $('wayWrap').querySelector('.way__row').hidden = open.length < 2;
    if (open.length && !open.includes(way)) {
      chooseWay(open[0]);
      return;
    }
  }

  $('way').dataset.way = way;
  $('wayWrap').dataset.way = way;
  wayMenu.sync(way);
  // The picker belongs to the two ways that have a country in them.
  const stack = document.querySelector('.stack');
  if (stack) stack.dataset.way = way;

  $('wayWhy').textContent = WAY_SAID[way];
  const note = $('wayNote');
  said(note, ready ? '' : WAY_NEEDS_TUNNEL, ready ? '' : 'bad');
  note.classList.remove('is-swapping');
  void note.offsetWidth;                       // restart it, do not queue it
  note.classList.add('is-swapping');
}

async function chooseWay(way) {
  if (way === state.way) return;
  // Moved before the round trip, so the strip answers the tap rather than
  // the disk. Nothing is carried yet, so there is nothing to put back if
  // the write fails - and it says so if it does.
  state.way = way;
  paintWay();
  const r = await window.pywebview.api.setMode(way);
  if (!r || !r.ok) said($('wayNote'), 'That could not be saved.', 'bad');
}

/* ------------------------------------------------------- share on Wi-Fi */

/* The proxy, offered to other devices on the same network.

   Off, it listens on this machine only. On, it listens on the LAN too, which
   needs a Windows Firewall rule - the backend asks for it with a UAC prompt,
   so the page says so before the click. What the other device should type is
   read from lanShareState(), which shells out to PowerShell and takes one to
   six seconds, so it is asked on opening, on Connected, and every ten
   seconds while this screen is the one in view - never faster, never when
   nobody is looking. */
const lanPane = (() => {
  const api = () => window.pywebview.api;
  let on = false;
  let busy = false;        // a switch press is out
  let asking = false;      // a lanShareState is out
  let applied = null;      // 'now' | 'next' from the last switch press
  let snap = null;         // the last lanShareState answer
  let timer = null;
  let note = null;         // {text, kind} from the last press, until the next

  const supported = () => typeof api().setLanShare === 'function'
    && typeof api().lanShareState === 'function';

  function visible() {
    return state.screen === 'connection' && $('prefs').open;
  }

  function lines() {
    const out = [];
    if (!snap) return out;
    const fw = snap.firewall;
    if (fw === 'ok') out.push(['Windows lets other devices in.', '']);
    else if (fw === 'missing') {
      out.push(['Windows is blocking other devices. A firewall rule for Relay is missing.', 'bad']);
    } else if (fw === 'public-network') {
      out.push(['Windows treats this Wi-Fi as Public and blocks other devices — set it to Private in Windows settings.', 'bad']);
    } else out.push(['Could not check the Windows firewall.', '']);
    return out;
  }

  function paint() {
    const row = $('lanRow');
    const sw = $('lanSwitch');
    sw.setAttribute('aria-checked', String(on));
    sw.dataset.busy = busy ? '1' : '0';
    sw.disabled = busy;
    const pill = $('lanPill');
    pill.dataset.state = on ? 'on' : '';
    pill.textContent = on ? 'on' : 'off';
    row.dataset.on = on ? '1' : '0';
    $('lanBody').hidden = !on;
    $('lanUac').hidden = on;
    said($('lanSaid'), note ? note.text : '', note && note.kind);
    if (!on) return;

    const s = snap || {};
    const addrs = s.addrs || [];
    const main = addrs[0];
    $('lanAddr').textContent = main || (snap ? 'no address found' : 'looking…');
    $('lanPort').textContent = String(s.port || state.port);
    $('lanCopyAddr').hidden = !main;

    // Every other way in, small. adapters carry the network's name when
    // Windows has one; addrs alone is enough when it has not.
    const names = new Map((s.adapters || []).map((a) => [a.ip, a]));
    const more = addrs.slice(1).map((ip) => {
      const a = names.get(ip);
      const tag = a ? [a.name, a.network].filter(Boolean).join(', ') : '';
      return tag ? `${ip} (${tag})` : ip;
    });
    $('lanOthers').textContent = more.length
      ? `Also reachable at ${more.join(' · ')}` : '';
    $('lanOthers').hidden = !more.length;

    const n = s.clients | 0;
    $('lanClients').textContent = snap
      ? (n ? `${n} device${n === 1 ? '' : 's'} connected now.` : 'No device is connected yet.')
      : '';

    const fwEl = $('lanFw');
    const ls = lines();
    fwEl.textContent = ls.length ? ls[0][0] : '';
    fwEl.classList.toggle('is-bad', !!ls.length && ls[0][1] === 'bad');
    $('lanFix').hidden = !(snap && s.firewall === 'missing');

    // Listening is the proxy bound to the LAN. On but not listening means
    // the setting is saved and the proxy has not been restarted with it.
    const wait = $('lanLater');
    const later = applied === 'next' || (snap && s.listening === false);
    wait.hidden = !later;
    wait.textContent = 'Applies when you reconnect.';
  }

  async function poll() {
    if (asking || !on || !supported()) return;
    asking = true;
    try {
      const r = await api().lanShareState();
      if (r) snap = r;
      if (r && typeof r.on === 'boolean' && r.on !== on && !busy) on = r.on;
    } catch (err) { /* keep the last answer */ }
    asking = false;
    paint();
  }

  function schedule() {
    if (timer) return;
    timer = setInterval(() => {
      if (!on || !visible()) { stop(); return; }
      poll();
    }, 10000);
  }

  function stop() {
    clearInterval(timer);
    timer = null;
  }

  async function set(want) {
    if (busy) return;
    busy = true;
    note = { text: want ? 'Waiting for Windows to ask permission…' : 'Turning off…', kind: 'work' };
    paint();
    let r;
    try {
      r = await api().setLanShare(want);
    } catch (err) {
      r = { ok: false, error: String(err) };
    }
    busy = false;
    if (r && r.ok) {
      on = !!r.lanShare;
      state.lanShare = on;
      applied = r.applied || null;
      note = r.warning ? { text: r.warning, kind: 'bad' } : null;
    } else {
      note = { text: (r && r.error) || 'Could not change it.', kind: 'bad' };
    }
    if (on) { snap = null; schedule(); }
    paint();
    if (on) poll();
  }

  async function fix() {
    $('lanFix').disabled = true;
    note = { text: 'Waiting for Windows to ask permission…', kind: 'work' };
    paint();
    let r;
    try { r = await api().fixLanShareFirewall(); } catch (err) { r = { ok: false, error: String(err) }; }
    $('lanFix').disabled = false;
    note = r && r.ok ? null : { text: (r && r.error) || 'Could not add the rule.', kind: 'bad' };
    if (r && r.firewall && snap) snap.firewall = r.firewall;
    paint();
    poll();
  }

  $('lanSwitch').addEventListener('click', () => set(!on));
  $('lanFix').addEventListener('click', fix);
  $('lanCopyAddr').addEventListener('click', (e) => {
    const a = (snap && snap.addrs && snap.addrs[0]) || '';
    copyText(e.currentTarget, a);
  });
  $('lanCopyPort').addEventListener('click', (e) => {
    copyText(e.currentTarget, String((snap && snap.port) || state.port));
  });

  return {
    take(info) {
      const has = 'lanShare' in info && supported();
      $('prefLan').hidden = !has;
      if (!has) return;
      if (!busy) on = !!info.lanShare;
      state.lanShare = on;
      paint();
      if (on && visible()) { poll(); schedule(); }
    },
    /* Called by showScreen with the slug now showing. */
    screen(slug) {
      if (slug === 'connection' && on) { poll(); schedule(); } else if (slug !== 'connection') stop();
    },
    /* Connected / disconnected: whether it is listening has probably changed. */
    refresh() {
      if (on && visible()) poll();
    },
  };
})();

/* --------------------------------------------------------- your own tunnel */

function paintTunnel(plan) {
  if (plan) state.tunnel = plan;
  const t = state.tunnel || {};
  const pill = $('tunnelPill');
  let label = 'not set';
  let mark = 'off';
  if (!t.hasClient) { label = 'no client'; mark = 'off'; }
  else if (!t.domain) { label = 'not set'; mark = 'off'; }
  else if (t.running) { label = 'running'; mark = 'on'; }
  else { label = 'ready'; mark = 'on'; }
  pill.textContent = label;
  pill.dataset.state = mark;

  if (document.activeElement !== $('tunnelDomain')) {
    $('tunnelDomain').value = t.domain || '';
  }
  // Set, but never shown. The page is given whether there is one, not what
  // it is, so the placeholder is the only thing that can say so.
  $('tunnelPass').placeholder = t.hasPassword ? 'saved' : 'made for you';
  $('tunnelApi').placeholder = t.hasApiPassword ? 'saved' : 'made for you';
  paintVia(t);
  paintWayIn(t.edges, t);
  // An address is not behind Cloudflare, and Shadowsocks goes straight to
  // the address either way: in both there is no other way in to find.
  $('tunnelScan').hidden = !!t.isAddress || t.transport === 'ss';
}

/* Shadowsocks or the WebSocket, for a server whose key offered both. */
function paintVia(t) {
  $('tunnelViaBox').hidden = !t.hasSs;
  const seg = $('tunnelVia');
  seg.dataset.via = t.transport === 'ss' ? 'ss' : 'ws';
  for (const b of seg.querySelectorAll('.seg__opt')) {
    b.setAttribute('aria-pressed', String(b.dataset.via === seg.dataset.via));
  }
}

async function useTunnelKey() {
  const btn = $('tunnelKeyUse');
  const text = $('tunnelKey').value.trim();
  if (!text) {
    said($('tunnelKeySaid'), 'Paste the relay:// line first.', 'bad');
    return;
  }
  busy(btn, true);
  const r = await window.pywebview.api.importKey(text);
  busy(btn, false);
  if (!r || !r.ok) {
    said($('tunnelKeySaid'), (r && r.error) || 'That key could not be used.', 'bad');
    return;
  }
  // Out of the page the moment it is on disk: it holds three passwords.
  $('tunnelKey').value = '';
  paintTunnel(r);
  paintWay();
  said($('tunnelKeySaid'),
       `Set up for ${r.domain}${r.transport === 'ss' ? ', over Shadowsocks' : ''}. `
       + 'Press Test below to check it carries.', 'good');
}

for (const b of $('tunnelVia').querySelectorAll('.seg__opt')) {
  b.addEventListener('click', async () => {
    const want = b.dataset.via;
    const seg = $('tunnelVia');
    if (seg.dataset.via === want || seg.dataset.busy === '1') return;
    const was = seg.dataset.via;
    seg.dataset.busy = '1';
    paintVia({ hasSs: true, transport: want });
    const r = await window.pywebview.api.setTunnelTransport(want);
    seg.dataset.busy = '0';
    if (!r || !r.ok) {
      paintVia({ hasSs: true, transport: was });
      said($('tunnelKeySaid'), (r && r.error) || 'That could not be changed.', 'bad');
      return;
    }
    paintTunnel(r);
    said($('tunnelKeySaid'), want === 'ss'
      ? 'Straight to the server over Shadowsocks.'
      : 'Through the WebSocket, as before.', 'good');
  });
}

/* Which address the client is dialling, which is normally nobody's business
   and is the whole story on the day it stops working. Hidden until there is
   one, so the pane does not grow a line saying nothing. */
function paintWayIn(edges, t) {
  const line = $('tunnelWayIn');
  if (t && t.transport === 'ss') {
    line.textContent = `Straight to ${t.domain}:${t.ssPort || ''} over Shadowsocks`
      + (t.ssPort ? `, and :${t.ssPort + 1} for server + exit.` : '.');
    line.hidden = false;
    return;
  }
  // A server by address: the certificate kept the first time it was met is
  // the one it has to show from then on, and this is how to check it.
  if (t && t.isAddress) {
    line.textContent = t.pin ? `Certificate sha256 ${t.pin.slice(0, 16)}…, kept from the first Test.`
      : 'Its certificate is kept the first time Test reaches it.';
    line.hidden = false;
    return;
  }
  if (!edges || !edges.length) { line.hidden = true; return; }
  const spare = edges.length - 1;
  line.textContent = `Dialling ${edges[0]}`
    + (spare ? `, with ${spare} more to fall back to.` : '.');
  line.hidden = false;
}

async function saveTunnel() {
  const btn = $('tunnelSave');
  busy(btn, true);
  said($('tunnelSaid'), 'Saving…', 'work');
  const plan = await window.pywebview.api.saveTunnel(
    $('tunnelDomain').value.trim(), $('tunnelPass').value, $('tunnelApi').value);
  busy(btn, false);
  // Out of the DOM as soon as they are on disk, for the same reason the
  // sign-in fields are.
  $('tunnelPass').value = '';
  $('tunnelApi').value = '';
  paintTunnel(plan);
  paintWay();
  said($('tunnelSaid'), plan.domain ? 'Saved.' : 'Cleared.', 'good');
}

async function testTunnel() {
  const btn = $('tunnelTest');
  busy(btn, true);
  // The pane's own word for where it stands, and for these few seconds it
  // does not know. Holding up "running" or "ready" while the thing that
  // decides which of those is true is still in the air is the one moment
  // that pill is allowed to be honestly blank instead.
  const pill = $('tunnelPill');
  pill.dataset.state = 'work';
  pill.textContent = 'asking';
  said($('tunnelSaid'), 'Starting the client and asking the server…', 'work');
  const r = await window.pywebview.api.testTunnel();
  busy(btn, false);
  if (!r.ok) {
    // Two failures worth telling apart: nothing to run, and nothing to
    // reach. The first is a missing file, the second is a wrong answer.
    const why = r.error === 'no-client'
      ? 'No tunnel client found. gost belongs beside the app, or in tunnel/.'
      : r.error === 'not-set-up' ? 'Fill the domain in first.' : r.error;
    said($('tunnelSaid'), why, 'bad');
    paintTunnel({ ...(state.tunnel || {}), running: false });
    return;
  }
  // What came out the far end is the answer; the exit the server is set to
  // is the detail. Said in that order, and the restart is mentioned rather
  // than hidden - it explains why the button took a few seconds longer.
  const woke = r.restarted ? 'Restarted a stale session. ' : '';
  // A repair is worth a sentence of its own. It is the one outcome where
  // the thing that was wrong is not the thing the user was told about.
  const moved = r.repaired && r.repaired.length
    ? `The way in had been filtered; moved to ${r.repaired[0]}. ` : '';
  said($('tunnelSaid'),
       `${woke}${moved}Carrying traffic — it comes out at ${r.seen || 'the far end'}`
       + `${r.exit ? `, through ${r.exit}` : ''}.`, 'good');
  paintTunnel({ ...(state.tunnel || {}), running: true, edges: r.edges });
  paintWay();
}

async function rescanEdges() {
  const btn = $('tunnelScan');
  busy(btn, true);
  said($('tunnelSaid'),
       'Asking every Cloudflare address that might carry it…', 'work');
  const r = await window.pywebview.api.rescanEdges();
  busy(btn, false);
  if (!r.ok) {
    const why = r.error === 'no-client'
      ? 'No tunnel client found. gost belongs beside the app, or in tunnel/.'
      : r.error === 'not-set-up' ? 'Fill the domain in first.' : r.error;
    said($('tunnelSaid'), why, 'bad');
    return;
  }
  // The count is the part worth showing. One address answering out of fifty
  // and forty answering out of fifty are both "it works", and they are not
  // the same weather.
  said($('tunnelSaid'),
       `${r.answered} of ${r.tried} addresses answered. Dialling ${r.edges[0]}`
       + `${r.running ? ', and the client is back up on it.' : '.'}`, 'good');
  paintTunnel({ ...(state.tunnel || {}), running: r.running, edges: r.edges });
}

function peek(fieldId, buttonId) {
  const field = $(fieldId);
  const showing = field.type === 'text';
  field.type = showing ? 'password' : 'text';
  $(buttonId).querySelector('use')
    .setAttribute('href', showing ? '#i-eye' : '#i-eye-off');
  $(buttonId).setAttribute('aria-pressed', String(!showing));
  $(buttonId).setAttribute('aria-label',
    showing ? 'Show the password' : 'Hide the password');
}

/* ---------------------------------------------------------------- prefs */

function paintPrefs(info) {
  $('sysProxy').setAttribute('aria-checked', String(state.systemProxy));
  if (info.routeWith) state.routeWith = info.routeWith;
  if ('tunReady' in info) state.tunReady = info.tunReady !== false;
  state.elevated = !!info.elevated;
  paintRouteWith();
  splitPane.take(info);
  sharePane.take(info);
  lanPane.take(info);
  if (info.orbColors) state.orbColors = info.orbColors;
  if (info.theme) applyTheme(info.theme);
  state.onClose = info.onClose || '';
  paintOnClose();
  if (info.window) paintWindowOptions(info.window);
  $('keepOnClose').setAttribute('aria-checked', String(!!info.keepOnClose));
  if (info.port) state.port = info.port;
  paintPort();
  $('folderPath').textContent = info.folder || '—';
  $('folderCount').textContent =
    `${info.serverCount || 0} servers · ${state.countries.length} countries`;
  state.serverCount = info.serverCount || 0;
  state.tunnelReady = !!info.tunnelReady;
  if (info.quickAt !== undefined) state.quickAt = info.quickAt || 0;
  if (info.autoQuick !== undefined) state.autoQuick = info.autoQuick !== false;
  paintQuick();
  $('aboutPaths').textContent = info.about || '';

  // Whether anything can connect at all. What is signed in as what is the
  // roster's business now, and it paints itself - but the rest of the window
  // still needs to know whether there is a credential behind the button.
  const had = state.hasCredentials;
  state.hasCredentials = info.hasCredentials !== false;
  // The front of the app is left saying NOT SET UP by boot(), which is the
  // only place that can be said - and nothing said otherwise once an account
  // arrived. So signing in through the very sheet boot() opened put a green
  // "Saved and in use" behind a window still reading NOT SET UP and refusing
  // to connect, and only a restart cleared it.
  if (!had && state.hasCredentials && state.mode === 'off') {
    setStatus('Disconnected', 'off', '', '');
    setHint(`${state.countries.length} countries ready.`);
  }

  if (info.mode) state.way = info.mode;
  paintWay();
  // Asked for separately: it looks for the client on disk and pokes the
  // port, which is more than info() should be doing on every repaint. The
  // strip is painted again when it lands - until then nothing here knows
  // whether there is a tunnel, and it would say there is not.
  window.pywebview.api.tunnelPlan().then((plan) => {
    paintTunnel(plan);
    paintWay();
  });
  // #authUser, #authPass and #authSaid were the credentials pane, and they
  // went the same way #authPill did when the accounts roster replaced it.
  // Filling them in stayed behind, and $('authUser').value threw on every
  // repaint of this sheet.
  //
  // It cost the whole Settings screen. paintPrefs is called first, so the
  // throw took everything after it with it: paintSort, paintUse, the
  // acctRefresh that fills the roster, and render() - which is what actually
  // draws the sheet. The cog opened onto a pane that never painted.
  //
  // acctRefresh does this job now, and it reads the roster rather than a
  // single username, which is the whole reason the pane was replaced.
}

/* paintAuthPill lived here, and painted an #authPill that the accounts roster
   replaced. The element went; the call did not - and $('authPill') is null, so
   every boot threw on the line after `state.hasCredentials` was set.

   Nothing above it was affected and everything below it was: paintWay() never
   ran, so the Provider / Your server / Server + exit strip kept whatever it
   was last left showing, and tunnelPlan() was never asked for, so the tunnel
   pane stayed empty. Both looked like features that had not been written.

   It was invisible on the desktop because pywebview has no console anybody
   watches. It surfaced the first time the same page ran in a WebView, where
   an unhandled rejection goes to logcat with a line number. */

/* Putting a sheet away, rather than dropping it.

   They rose in over 167ms and vanished on the frame they were closed. The
   arrival said where the page came from and the departure said nothing, so
   going back felt like the app losing the page rather than closing it - and
   it is the half of the motion that tells you the thing you were reading is
   behind the thing you are looking at now.

   The animation is hung on an attribute and never on the element. There is a
   hard-won note above .sheet in the stylesheet about this: a rule that keeps
   a closed dialog laid out is how this app once shipped completely unusable,
   reporting open === false while eating every click. [open][data-closing]
   only ever matches a dialog that is still open.

   The timer is not a belt-and-braces flourish. animationend does not fire if
   the animation never starts - reduced motion, a display change mid-flight,
   a sheet closed twice - and a sheet left half-shut is exactly the failure
   that note is about. */
const SHUT_MS = 130;

function shut(dlg) {
  if (!dlg || !dlg.open) return;
  if (dlg.dataset.closing === '1') return;
  dlg.dataset.closing = '1';
  let done = false;
  const finish = () => {
    if (done) return;
    done = true;
    delete dlg.dataset.closing;
    dlg.close();
  };
  dlg.addEventListener('animationend', finish, { once: true });
  setTimeout(finish, SHUT_MS + 80);
}

function said(el, text, kind) {
  el.textContent = text || '';
  el.classList.toggle('is-bad', kind === 'bad');
  el.classList.toggle('is-good', kind === 'good');
  // 'work' is not a colour, it is a tense. The sentence is the same one it
  // was going to be either way; while the answer is still out, the light
  // passes along it.
  el.classList.toggle('is-work', kind === 'work');
}

/* ------------------------------------------------------- while it is out */

/* A button in this sheet that goes off and asks something used to say so by
   disabling itself, which the page draws at 45% - the same thing it does to a
   control you are not allowed to press. Test on a sleeping tunnel is four
   seconds of a real round trip and it read as a button that had broken.

   busy() is the whole of the answer: still unpressable, and now saying which
   kind of unpressable it is. The rim turns, the label takes the amber this
   app already uses for work in progress, and a screen reader is told the same
   thing by aria-busy rather than left to infer it from a disabled attribute
   that has meant "no" everywhere else.

   It also counts. Anything in flight anywhere in the sheet lifts data-work on
   the dialog, which is what the floor reads. */
let inFlight = 0;

function busy(el, on) {
  if (el) {
    el.disabled = on;
    el.dataset.busy = on ? '1' : '0';
    if (on) el.setAttribute('aria-busy', 'true');
    else el.removeAttribute('aria-busy');
  }
  // Guarded rather than trusted. A handler that returns early on an error
  // path and never releases would otherwise leave the field running amber
  // for the rest of the session, and a floor that is always busy says
  // nothing at all.
  inFlight = Math.max(0, inFlight + (on ? 1 : -1));
  paintWork();
}

function paintWork() {
  $('prefs').dataset.work =
    (inFlight || state.sweep || state.pinning) ? '1' : '0';
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

/* A tally is 150px tall and shows about ten rows. The runs behind them
   produce four hundred, every one was kept, and every one arrived with its
   own rise animation into a scroll container that kept getting deeper - which
   is why the pane crawled by the end of a pin run and the whole screen felt
   heavy afterwards. A screenful and a half is more than anyone scrolls back
   through, and the files on disk are the actual record. */
const TALLY_KEEP = 60;

function tallyPrepend(list, li) {
  if (li.parentNode !== list) list.prepend(li);
  while (list.children.length > TALLY_KEEP) list.lastElementChild.remove();
}

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
  tallyPrepend($('pinTally'), li);
}

function drawPin(p) {
  const meter = $('pinMeter');

  if (p.phase === 'starting') {
    state.pinning = { total: p.total || 0, done: 0 };
    paintWork();
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
    paintWork();
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
    // And it is what a provider was waiting for. The engine has re-read its
    // folders by now, so this is the repaint that turns "none pinned" into a
    // count and lets the box be ticked - without it the pane went on
    // reporting the state from before the run that fixed it.
    refreshPrefs();
  }
}


/* One chip, and it is a verb: a pinned folder is worth nothing until
   something is reading from it. */
function drawPinUse(folder, count) {
  const list = $('pinUse');
  list.replaceChildren();
  list.hidden = !(folder && count);
  if (list.hidden) return;

  // Nothing to offer when the run wrote into one of the folders the app
  // reads anyway: those exits joined the list the moment it finished. The
  // button that was here said "Connect through these" and went through
  // useSiteFolder, which narrows - so pressing it after pinning Windscribe
  // took servers/ out of the list and lost every Surfshark exit with it.
  if (state.pinPlan && state.pinPlan.outIsStandard) {
    const li = document.createElement('li');
    li.className = 'pinuse__said';
    li.textContent = `${count} in the list already — nothing else to do.`;
    list.append(li);
    return;
  }

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
    paintWork();
    tally.replaceChildren();
    tally.hidden = false;
    meter.hidden = false;
    $('sweepBar').style.width = '0%';
    $('sweepGo').dataset.mode = 'running';
    $('sweepPick').disabled = true;
    $('sweepSites').disabled = true;
    $('sweepFirst').disabled = true;
    lockChoices(true);
    $('sweepWhere').hidden = true;
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
    tallyPrepend(tally, li);
    return;
  }

  if (p.phase === 'finished') {
    state.sweep = null;
    paintWork();
    $('sweepGo').dataset.mode = 'idle';
    $('sweepPick').disabled = false;
    $('sweepSites').disabled = false;
    $('sweepFirst').disabled = false;
    lockChoices(false);
    meter.hidden = true;
    drawSiteFolders(p.siteFolders, $('folderPath').textContent);
    drawWhere(p);
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

/* Where the ones that came up have gone. Said only when something did come
   up: after a run where nothing connected, naming a folder would be naming an
   empty one, and a cancelled run has usually written nothing either. The path
   is the whole point, so it is given in full rather than as a folder name you
   would then have to go looking for. */
function drawWhere(p) {
  const el = $('sweepWhere');
  el.replaceChildren();
  if (p.error || !p.worked || !p.into) { el.hidden = true; return; }

  const kept = document.createElement('span');
  kept.textContent = `Kept in ${p.into}`;
  el.append(kept);

  // Only when this run named sites. The per-site folders hold one copy each
  // of whichever servers served them, which is a different list from the one
  // above and worth pointing at separately.
  //
  // Named against the folder on the line above when it sits beside it, which
  // is the usual case: a second absolute path differing from the first in its
  // last word is a line you have to read twice, and at this width it wrapped
  // through the middle of the word that mattered.
  if (p.siteTestDir) {
    const parent = p.into.replace(/[\\/][^\\/]+[\\/]?$/, '');
    const beside = parent && p.siteTestDir.slice(0, parent.length) === parent
      ? p.siteTestDir.slice(parent.length).replace(/^[\\/]/, '')
      : '';
    const per = document.createElement('span');
    per.textContent = beside
      ? `Per site, in ${beside}\\ beside it`
      : `Per site, in ${p.siteTestDir}`;
    el.append(per);
  }
  el.hidden = false;
}

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
                                             + '#sweepRoute .scope__row, '
                                             + '#sweepLords .chip, #lookUpOwners')) {
    el.disabled = locked;
  }
}

/* The routes, and what each would cost in minutes. Minutes rather than a
   count, because the count is the same for two of them and the minutes are
   not - dialling five hundred exits is an afternoon and asking the server
   about them is a quarter of an hour. */
const ROUTE_WHY = {
  'provider': 'Connects to each one for real, writes how long its handshake '
    + 'took on the front of its name, and drops it again. That is the number '
    + 'the list is ordered by, and the only way to know it. Your connection '
    + 'goes down and up once per server, so reckon in minutes.',
  'server+exit': 'Points your server at each exit in turn and measures from '
    + 'the far side of the tunnel — which is the leg your traffic takes now, '
    + 'and it does not agree with the other one. Nothing disconnects and '
    + 'nothing is elevated, so this runs in the background.',
  'server': 'Asks what your own server serves, with no exit in front of it. '
    + 'One address and one row: the quickest way to tell a site refusing your '
    + 'server from a site refusing the exits.',
};

const ROUTE_SAYS = {
  'provider': 'Times the OpenVPN handshake from here. The honest number for '
    + 'a connection you make yourself, and the wrong one for traffic that '
    + 'leaves through your server.',
  'server+exit': 'Sets each exit on your server in turn and measures from '
    + 'the far side of the tunnel. One at a time, because the server holds a '
    + 'single exit chain.',
  'server': 'Asks what your own server serves, without any exit in front of '
    + 'it. The quickest answer to whether a site is refusing the server '
    + 'rather than the exits.',
};

function drawRoute(plan) {
  const now = plan.route || 'provider';
  for (const row of $('sweepRoute').querySelectorAll('.scope__row')) {
    const route = row.dataset.route;
    row.setAttribute('aria-pressed', String(route === now));
    const cell = row.querySelector('.scope__count');
    const mins = (plan.routeMinutes || {})[route];
    const text = mins == null ? '—' : `${mins} min`;
    if (cell.textContent !== text) {
      cell.textContent = text;
      cell.classList.remove('turned');
      void cell.offsetWidth;
      cell.classList.add('turned');
    }
    row.disabled = !!state.sweep;
  }
  $('routeSaid').textContent = ROUTE_SAYS[now] || '';
  if (ROUTE_WHY[now]) $('sweepWhy').textContent = ROUTE_WHY[now];
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
  drawRoute(plan);
  drawScope(plan);
  drawLandlords(plan);
  drawSiteFolders(plan.siteFolders, plan.into);
  // Last, because it reads what the four above just wrote.
  drawFolds(plan);

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

/* The main screen, which has been waiting behind data-boot (index.html).
   Called once the first run has been settled - either because there is
   nothing of it to do, or because the sheet that does it is already up in
   front. Harmless twice. */
function ready() {
  delete document.documentElement.dataset.boot;
}

async function bootSteps() {
  const info = await window.pywebview.api.boot();
  state.countries = info.countries || [];
  state.picked = info.picked || 'auto';
  state.way = info.mode || 'surfshark';
  state.systemProxy = info.systemProxy !== false;
  state.port = info.port || state.port;
  // Read now, not on the first visit to Settings: the list puts starred
  // places in a group of their own at the top, and drawn before this it
  // had no starred places to put there.
  state.favourites = info.favourites || state.favourites;
  state.sortBy = info.sortBy || state.sortBy;
  state.fuse = new Fuse(state.countries, {
    keys: ['name', 'code', 'alias'], threshold: 0.4, ignoreLocation: true,
  });
  paintPrefs(info);

  // Nothing to connect with, so the welcome goes up now - before the
  // intro rather than after it. Opened afterwards, it arrived a beat late:
  // the intro takes a moment to fade and the main screen it uncovered was
  // on screen for that moment, Connect button and all.
  const welcome = !info.hasCredentials && !info.tunnelReady;
  if (welcome) openWelcome();
  if (!info.introSeen) await runIntro();
  ready();

  if (!info.hasCredentials) {
    setStatus('Not set up', 'fail', 'No sign-in details yet', '');
    setHint('Add an account, or your own server, and this can connect.', true);
    render();
    // Nothing to connect with: the welcome above asks how it will
    // connect, rather than opening Settings on a page of options. A copy
    // with its own server set up already has an answer and carries on to
    // the main screen.
    paintUse(info);
    acctRefresh().catch(() => {});
    if (welcome) return;
  }
  if (info.recovered) {
    setHint('The app did not close properly last time. Windows proxy settings have been put back.');
  } else if (info.recoveredTun) {
    setHint('The app did not close properly last time. The network adapter it left up has been taken down.');
  }

  const s = info.status || {};
  if (s.state === 'on') {
    window.onConnected(s);
  } else {
    state.mode = 'off';
    setStatus('Disconnected', 'off', '', '');
    if (!info.recovered && !info.recoveredTun) setHint(`${state.countries.length} countries ready.`);
    render();
  }
  // Signed in, but an account with no servers yet: get them ready now.
  const lacking = providersWithoutServers(info);
  if (lacking.length && s.state !== 'on') prepareServers(lacking[0]);
  // Asked for once the window is already usable: it is a network round trip
  // and nothing on screen depends on it arriving.
  window.pywebview.api.whoami();
  // Off what boot already carried, rather than a second round trip for
  // something that was in the first one.
  if (info.sources) state.sources = info.sources;
  paintSourceHead();
  refreshDead();
  warmFlags(state.countries.map((c) => c.code));
  // Built now, while nothing is waiting for it, rather than in the frame the
  // sheet is animating in. The dialog is closed, so none of this paints - it
  // is the DOM work that is being moved, and the flags are being fetched
  // alongside it by the line above.
  drawList('');
}

/* --------------------------------------------------------------- wiring */

/* Whether anything can see the front of the app right now.

   The sheets are full-screen opaque dialogs, and `document.hidden` does not
   go true for one - so with Settings open the WebGL map went on drawing
   at full rate behind it, and the blurred card it sits on went on being
   recomposited with it. atlas.js reads this flag and stops.

   Watched rather than set beside each showModal(): there are six dialogs in
   here, and the seventh would have been the one nobody remembered. */
const covered = new MutationObserver(() => {
  document.body.dataset.covered =
    document.querySelector('dialog.sheet[open]') ? '1' : '0';
});
for (const sheet of document.querySelectorAll('dialog.sheet')) {
  covered.observe(sheet, { attributes: true, attributeFilter: ['open'] });
}

$('act').addEventListener('click', onAct);

/* The map as a button. A press does what the Connect button does; held for
   600 ms it reconnects - disconnect, then connect to the same pick - which
   is the one thing a stuck connection usually needs, without two presses
   and a wait between them. */
async function reconnect() {
  if (state.mode === 'busy') return;
  if (state.mode === 'on') await onAct();
  await onAct();
}

(function () {
  const orb = $('globeHit');
  let timer = 0;
  let held = false;
  const lift = () => { clearTimeout(timer); orb.classList.remove('is-pressed'); };
  const onSphere = (e) => !window.Atlas || window.Atlas.hit(e.clientX, e.clientY);
  orb.addEventListener('pointermove', (e) => {
    orb.classList.toggle('is-over', state.hasCredentials && onSphere(e));
  });
  orb.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || !state.hasCredentials || !onSphere(e)) return;
    held = false;
    orb.classList.add('is-pressed');
    timer = setTimeout(() => { held = true; lift(); reconnect(); }, 600);
  });
  orb.addEventListener('pointerup', lift);
  orb.addEventListener('pointerleave', () => { lift(); orb.classList.remove('is-over'); });
  orb.addEventListener('click', (e) => {
    if (held) { held = false; return; }
    if (state.hasCredentials && onSphere(e)) onAct();
  });
  orb.addEventListener('keydown', (e) => {
    if ((e.key === 'Enter' || e.key === ' ') && state.hasCredentials) {
      e.preventDefault();
      onAct();
    }
  });
})();

$('pick').addEventListener('click', openPicker);
$('pickerClose').addEventListener('click', () => shut($('picker')));
$('pickAny').addEventListener('click', () => choose('auto'));
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
  const test = e.target.closest('[data-test]');
  if (test) { e.stopPropagation(); startReach(test.dataset.test); return; }
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
    toggleCities(open.dataset.expand);
    return;
  }
  const drawer = e.target.closest('[data-continent]');
  if (drawer) { toggleContinent(drawer.dataset.continent); return; }
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
    if (r && r.dataset.continent) toggleContinent(r.dataset.continent);
    else if (r) choose(r.dataset.code);
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
  // Back to the top level, unless something is still going in one of the
  // screens. The sheet used to reopen wherever it was last left, which for
  // anyone who uses the tunnel pane means never seeing the settings menu
  // again after the first visit - a visit that ended is not a place to put
  // somebody back into three levels deep. A sweep or a pin is the exception
  // and the reason the old behaviour existed: those run for minutes, and
  // looking in on one is most of why the sheet gets reopened while it runs.
  if (!state.sweep && !state.pinning) showScreen(null);
  $('prefs').showModal();
  // Once it is on screen and has a size, and once the sheet has finished
  // arriving. A closed dialog is display:none and measures zero, so a field
  // built before showModal would be one column wide however big the window
  // was - and built in the same frame as the open, two hundred spans laid
  // out cost the one frame of the one animation anybody watches. It is
  // built once and never rebuilt, so this is a beat on the first visit and
  // nothing on any after it.
  setTimeout(() => {
    fillFloor();
    $('prefs').dataset.floor = state.screen ? '0' : '1';
  }, 220);
  // After the sheet is up rather than before it. One shells out to PowerShell
  // to ask whether another VPN holds the default route and the other opens a
  // socket to the proxy; waiting on either to show a settings sheet would be
  // a second of nothing.
  refreshSweep();
  refreshPin(true);
});
$('prefsClose').addEventListener('click', () => shut($('prefs')));


/* The way-out menu: a field that opens onto the three ways.

   Motion, and why each piece moves:
   - the field sinks to 0.98 under the pointer and springs back, so a press
     is felt before anything opens;
   - the list grows down out of the field (scaleY from its top edge, a few
     pixels of drop, a fade) rather than appearing, so it reads as coming
     from the thing that was pressed;
   - the options follow 30 ms apart, top first - the eye lands on the first
     choice, which is the default one;
   - the chevron turns over with a slight overshoot, the one playful note;
   - choosing slides the one glow under the new row, slides
     the field's word out upward and the new one in from below, and only
     then folds the list back - about 300 ms, enough to see what was taken.
   Closing is the opening run backwards, a little faster: leaving should
   cost less than arriving. Under reduced motion every one of these is a
   plain switch. */
const wayMenu = (() => {
  const root = $('way');
  const field = $('wayField');
  const list = $('wayList');
  const value = $('wayValue');
  const chev = field.querySelector('.dd__chev');
  const shown = () => [...list.querySelectorAll('.dd__opt')].filter((o) => !o.hidden);
  const nameOf = (way) => {
    const o = list.querySelector(`.dd__opt[data-way="${way}"] .dd__name`);
    return o ? o.textContent : '';
  };

  let calm = false;
  gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => {
    calm = true;
    return () => { calm = false; };
  });

  /* The one glow under the chosen row. It is placed, not faded, when the
     list opens, and slides when the choice changes. */
  const glow = list.querySelector('.dd__glow');
  function glide(opt, instant) {
    if (!glow || !opt) return;
    gsap.set(glow, { height: opt.offsetHeight, autoAlpha: 1 });
    if (instant || calm) gsap.set(glow, { y: opt.offsetTop });
    else gsap.to(glow, { y: opt.offsetTop, duration: 0.45, ease: 'power3.out', overwrite: 'auto' });
  }

  let open = false;
  let run = null;        // the opening timeline, reversed to close
  let swapping = false;  // the field's word is mid-change; sync must wait

  function opening() {
    const t = gsap.timeline({
      paused: true,
      defaults: { ease: 'power3.out' },
      onReverseComplete: () => { list.hidden = true; },
    });
    t.fromTo(list, { autoAlpha: 0, y: -6, scaleY: 0.92 },
                   { autoAlpha: 1, y: 0, scaleY: 1, duration: 0.26 }, 0)
     .fromTo(shown(), { autoAlpha: 0, y: -4 },
                      { autoAlpha: 1, y: 0, duration: 0.22, stagger: 0.03,
                        ease: 'power2.out' }, 0.05)
     .to(chev, { rotation: 180, duration: 0.38, ease: 'expo.out' }, 0);
    return t;
  }

  function show() {
    if (open) return;
    open = true;
    root.dataset.open = '1';
    field.setAttribute('aria-expanded', 'true');
    list.hidden = false;
    if (run) run.kill();
    run = null;
    if (calm) {
      gsap.set(chev, { rotation: 180 });
    } else {
      run = opening();
      run.play(0);
    }
    const here = list.querySelector('.dd__opt[aria-selected="true"]:not([hidden])')
      || shown()[0];
    glide(list.querySelector('.dd__opt[aria-selected="true"]:not([hidden])'), true);
    if (here) here.focus({ preventScroll: true });
    document.addEventListener('pointerdown', outside, true);
  }

  function hide(refocus) {
    if (!open) return;
    open = false;
    root.dataset.open = '0';
    field.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', outside, true);
    if (run && !calm) {
      run.timeScale(1.6).reverse();
    } else {
      list.hidden = true;
      gsap.set(chev, { rotation: 0 });
    }
    if (refocus) field.focus({ preventScroll: true });
  }

  function outside(e) {
    if (!root.contains(e.target)) hide(false);
  }

  /* The field's word, swapped in place: out upward, in from below. */
  function swap(text) {
    if (calm || value.textContent === text) {
      value.textContent = text;
      return;
    }
    swapping = true;
    gsap.timeline({ onComplete: () => { swapping = false; } })
      .to(value, { yPercent: -70, autoAlpha: 0, duration: 0.13, ease: 'power2.in' })
      .add(() => { value.textContent = text; })
      .fromTo(value, { yPercent: 70, autoAlpha: 0 },
                     { yPercent: 0, autoAlpha: 1, duration: 0.26, ease: 'power3.out',
                       clearProps: 'transform,opacity,visibility' });
  }

  /* Which row is the chosen one, said by the state rather than by a click -
     the tray and the first paint arrive here too. The check's own motion is
     pick()'s; here it is only made true. */
  function sync(way) {
    for (const o of list.querySelectorAll('.dd__opt')) {
      o.setAttribute('aria-selected', String(o.dataset.way === way));
    }
    if (!swapping) value.textContent = nameOf(way);
  }

  function pick(opt) {
    const way = opt.dataset.way;
    if (way !== state.way) {
      const was = list.querySelector('.dd__opt[aria-selected="true"]');
      if (!calm) {
        // Inline styles only while they move, then handed back to the CSS,
        // which already says a chosen row shows its check.
        gsap.fromTo(opt.querySelector('.dd__check'), { scale: 0.4, autoAlpha: 0 },
          { scale: 1, autoAlpha: 1, duration: 0.42, ease: 'expo.out',
            clearProps: 'all' });
        if (was && was !== opt) {
          gsap.fromTo(was.querySelector('.dd__check'), { scale: 1, autoAlpha: 1 },
            { scale: 0.4, autoAlpha: 0, duration: 0.16, ease: 'power2.in',
              clearProps: 'all' });
        }
      }
      glide(opt);
      swap(nameOf(way));
      chooseWay(way);
    }
    gsap.delayedCall(calm ? 0 : 0.3, () => hide(true));
  }

  function step(by) {
    const items = shown();
    if (!items.length) return;
    const at = items.indexOf(document.activeElement);
    const next = items[(at + by + items.length) % items.length];
    next.focus({ preventScroll: true });
  }

  field.addEventListener('click', () => (open ? hide(false) : show()));
  field.addEventListener('pointerdown', () => {
    if (!calm) gsap.to(field, { scale: 0.98, duration: 0.12, ease: 'power2.out', overwrite: 'auto' });
  });
  const lift = () => {
    if (!calm) gsap.to(field, { scale: 1, duration: 0.5, ease: 'expo.out', overwrite: 'auto' });
  };
  field.addEventListener('pointerup', lift);
  field.addEventListener('pointerleave', lift);
  field.addEventListener('keydown', (e) => {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
      e.preventDefault();
      show();
    }
  });

  list.addEventListener('click', (e) => {
    const opt = e.target.closest('.dd__opt');
    if (opt && !opt.hidden) pick(opt);
  });
  // The pointer moves the keyboard's place too, so the two never disagree
  // about which row Enter would take.
  list.addEventListener('pointermove', (e) => {
    const opt = e.target.closest('.dd__opt');
    if (opt && document.activeElement !== opt) opt.focus({ preventScroll: true });
  });
  list.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); step(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); step(-1); }
    else if (e.key === 'Home') { e.preventDefault(); shown()[0]?.focus(); }
    else if (e.key === 'End') { e.preventDefault(); shown().at(-1)?.focus(); }
    else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      const opt = document.activeElement.closest('.dd__opt');
      if (opt) pick(opt);
    } else if (e.key === 'Escape') {
      // Here and not in the sheet's handler: Escape closes the menu, not the
      // window behind it.
      e.preventDefault();
      e.stopPropagation();
      hide(true);
    } else if (e.key === 'Tab') {
      hide(false);
    }
  });

  return { sync, hide, isOpen: () => open };
})();

$('tunnelSave').addEventListener('click', saveTunnel);
$('tunnelTest').addEventListener('click', testTunnel);
$('tunnelScan').addEventListener('click', rescanEdges);
$('tunnelPeek').addEventListener('click', () => peek('tunnelPass', 'tunnelPeek'));
$('tunnelKeyPeek').addEventListener('click', () => peek('tunnelKey', 'tunnelKeyPeek'));
$('tunnelKeyUse').addEventListener('click', useTunnelKey);
$('tunnelKey').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') useTunnelKey();
});
$('tunnelApiPeek').addEventListener('click', () => peek('tunnelApi', 'tunnelApiPeek'));
$('tunnelApi').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') saveTunnel();
});
$('tunnelCopy').addEventListener('click', async (e) => {
  // What is shown is the readable version; what goes to the clipboard is the
  // whole installer in one line, because nothing hosts it and the script has
  // to reach the server somehow.
  const r = await window.pywebview.api.installCommand($('tunnelDomain').value.trim());
  if (!r || !r.ok) { said($('tunnelSaid'), (r && r.error) || 'nothing to copy', 'bad'); return; }
  await copyText(e.currentTarget, r.command);
  // The passwords may have just been made, and the address saved with them.
  paintTunnel(await window.pywebview.api.tunnelPlan());
  said($('tunnelSaid'), 'Copied — paste it into SSH as root, then press Test.', 'good');
});
for (const [id, key, what] of [['tunnelCopyStatus', 'status', 'status'],
                               ['tunnelCopyUninstall', 'uninstall', 'uninstall']]) {
  $(id).addEventListener('click', async (e) => {
    const r = await window.pywebview.api.installCommand($('tunnelDomain').value.trim());
    if (!r || !r.ok) { said($('tunnelSaid'), (r && r.error) || 'nothing to copy', 'bad'); return; }
    await copyText(e.currentTarget, r[key]);
    said($('tunnelSaid'), `Copied the ${what} command.`, 'good');
  });
}

/* The picker is a Windows dialog and it is its own feedback while it is up.
   What these are for is the part after it closes - reading a folder of two
   thousand configs is not instant - and stopping a second picker being asked
   for behind the first. */
$('pinPick').addEventListener('click', async () => {
  busy($('pinPick'), true);
  const r = await window.pywebview.api.choosePinFolder();
  busy($('pinPick'), false);
  if (r && r.ok) { paintPinPlan(r); checkPinProxy(); }
  else if (r && r.error) said($('pinSaid'), r.error, 'bad');
});

$('pinOutPick').addEventListener('click', async () => {
  busy($('pinOutPick'), true);
  const r = await window.pywebview.api.choosePinOut();
  busy($('pinOutPick'), false);
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
  busy($('sweepPick'), true);
  const r = await window.pywebview.api.chooseSweepFolder();
  busy($('sweepPick'), false);
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

$('sweepRoute').addEventListener('click', async (e) => {
  const row = e.target.closest('.scope__row');
  if (!row || state.sweep) return;
  // Marked before the round trip so the click lands at once; the answer
  // brings the minutes and the blockers, which are what actually changed.
  for (const other of $('sweepRoute').querySelectorAll('.scope__row')) {
    other.setAttribute('aria-pressed', String(other === row));
  }
  paintSweepPlan(await window.pywebview.api.setSweepRoute(row.dataset.route));
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
  // data-busy was set here and never cleared, so the tower in this button
  // flickered for the rest of the session after the first lookup. busy()
  // owns both ends of it now.
  busy(btn, true);
  said($('lordsSaid'), 'Asking who those addresses are rented from…', 'work');
  const r = await window.pywebview.api.lookUpOwners($('sweepFolder').textContent);
  busy(btn, false);
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

/* The switch moves first and Windows catches up, because a switch that waits
   for a registry write and a broadcast to every window on the machine before
   it moves is a switch that feels broken. What it did not do was ever look
   back: it flipped, fired, and believed itself whatever came back.

   So it moves on the press and holds the amber until the machine has
   answered - and if the answer is no, it slides back to where it was and
   says so. Half a second of a switch being optimistic is fine. A switch that
   is still lying a minute later is not. */
$('sysProxy').addEventListener('click', async () => {
  const sw = $('sysProxy');
  if (sw.dataset.busy === '1') return;
  const was = state.systemProxy;
  state.systemProxy = !was;
  sw.setAttribute('aria-checked', String(state.systemProxy));
  paintRouteWith();
  busy(sw, true);
  const r = await window.pywebview.api.setSystemProxy(state.systemProxy);
  busy(sw, false);
  if (r && r.ok === false) {
    state.systemProxy = was;
    sw.setAttribute('aria-checked', String(was));
    setHint(r.error || 'Windows would not take that.', true);
  }
  paintRouteWith();
});

/* How Windows is routed, under the switch that says whether it is. Out of
   reach while that switch is off: neither answer does anything then, and a
   choice that changes nothing should not look like one. Tunnel is out of
   reach too in a copy with no sing-box.exe, unless it is somehow already
   chosen - then it stays pressable, so it can be chosen away from. */
function paintRouteWith() {
  const seg = $('routeWith');
  const off = !state.systemProxy;
  const busyNow = seg.dataset.busy === '1';
  seg.dataset.with = state.routeWith === 'tun' ? 'tun' : 'proxy';
  $('prefRouteWith').dataset.off = off ? '1' : '0';
  for (const b of seg.querySelectorAll('.seg__opt')) {
    b.setAttribute('aria-pressed', String(b.dataset.with === seg.dataset.with));
    b.disabled = off || busyNow
      || (b.dataset.with === 'tun' && !state.tunReady && seg.dataset.with !== 'tun');
  }
  // Which programs a split tunnel can tell apart depends on this answer,
  // and the pane below says so.
  splitPane.note();
  if (busyNow) return;
  $('routeWithSaid').textContent = !state.tunReady
    ? 'Tunnel needs sing-box.exe beside Relay, and this copy does not have it.'
    : (seg.dataset.with === 'tun' && !state.elevated
        ? 'Windows will ask for administrator approval each time this connects.'
        : '');
}

/* Same promise as the switch above: it moves on the press, holds while the
   machine answers, and goes back if the answer is no. With a connection up
   the answer can take a while - it is a UAC prompt waiting for a person. */
for (const b of $('routeWith').querySelectorAll('.seg__opt')) {
  b.addEventListener('click', async () => {
    const seg = $('routeWith');
    const want = b.dataset.with;
    if (seg.dataset.busy === '1' || want === state.routeWith) return;
    const was = state.routeWith;
    state.routeWith = want;
    seg.dataset.busy = '1';
    paintRouteWith();
    if (want === 'tun' && state.mode === 'on') {
      $('routeWithSaid').textContent = 'Bringing up the network adapter…';
    }
    busy(null, true);
    let r = null;
    try {
      r = await window.pywebview.api.setRouteWith(want);
    } catch (e) {
      r = null;
    }
    busy(null, false);
    seg.dataset.busy = '0';
    if (!r || r.ok === false) {
      state.routeWith = (r && r.routeWith) || was;
      paintRouteWith();
      $('routeWithSaid').textContent =
        (r && r.error) || 'Windows would not take that.';
      return;
    }
    paintRouteWith();
  });
}

/* Split tunnel: which traffic goes round Relay rather than through it.

   The pane holds a draft. What the backend says is in force is state.split;
   what the pane shows is the draft, and the two only become one again on
   Apply - with a tunnel up, applying restarts the adapter, and that should
   happen once, when somebody says so, not once per chip. A repaint from the
   backend replaces the draft only while it is untouched, which is the rule
   the port field keeps too: never under somebody's hands.

   Motion, and why each piece moves:
   - a chip grows in out of nothing with a little overshoot, so the eye
     finds the one just added in a row of look-alikes;
   - a chip shrinks away when it is removed, and the ones after it slide
     into the gap from where they were instead of jumping - the list shows
     what happened to it;
   - a duplicate is not added a second time: the chip already there nods
     instead, which answers "is it in?" without a sentence;
   - the running-programs list grows down out of its row the way the way-out
     menu does, its first rows a beat apart;
   - Apply's answer lifts in, so it reads as the reply to the press.
   Under reduced motion every one of these is a plain change. */
const splitPane = (() => {
  const pane = $('prefSplit');
  const seg = $('splitMode');
  const apps = $('splitApps');
  const sites = $('splitSites');
  const input = $('splitSite');
  const siteSaid = $('splitSiteSaid');
  const apply = $('splitApply');
  const told = $('splitApplied');
  const runBtn = $('splitRunning');
  const pop = $('splitRun');
  const find = $('splitRunFind');
  const runList = $('splitRunList');
  const runNone = $('splitRunNone');

  let calm = false;
  gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => {
    calm = true;
    return () => { calm = false; };
  });

  const MODES = ['off', 'bypass', 'only'];
  const SAID = {
    off: 'Everything goes through Relay. The list waits here until one of the other two is chosen.',
    bypass: 'Everything goes through Relay except what is listed, which goes straight out.',
    only: 'Only what is listed goes through Relay; everything else goes straight out.',
  };
  // The pill is what is in force, not what is on screen - the two differ
  // between an edit and its Apply, and that difference is worth seeing.
  const PILL = { off: 'off', bypass: 'except list', only: 'only list' };

  let draft = null;     // what the pane shows; null on a backend without one
  let answer = null;    // the last Apply's {text, kind}, until the next edit
  let applying = false;
  let running = [];     // the last runningApps() answer
  let open = false;     // whether the running-programs list is up
  let run = null;       // its opening timeline, reversed to close
  let siteErr = '';     // 'local' | 'rejected' - who wrote the line under Sites

  /* Whatever arrived, in the one shape the rest of this reads. */
  const tidy = (s) => ({
    mode: MODES.includes(s && s.mode) ? s.mode : 'off',
    iran: !!(s && s.iran),
    apps: Array.isArray(s && s.apps) ? s.apps.map(String) : [],
    domains: Array.isArray(s && s.domains) ? s.domains.map(String) : [],
  });
  const copy = (s) => ({ ...s, apps: s.apps.slice(), domains: s.domains.slice() });
  // Order-blind: taking a chip off and putting it back is not a change.
  const key = (list) => list.map((v) => v.toLowerCase()).sort().join('\n');
  const same = (a, b) => !!a && !!b && a.mode === b.mode && a.iran === b.iran
    && key(a.apps) === key(b.apps) && key(a.domains) === key(b.domains);
  const listOf = (kind) => (kind === 'program' ? apps : sites);
  const valuesOf = (kind) => (kind === 'program' ? draft.apps : draft.domains);
  const rowFor = (list, value) =>
    [...list.children].find((li) => li.dataset.value === value);

  /* From the described state - boot() or info(), by way of paintPrefs. */
  function take(info) {
    if (!info || !info.split) {
      // A backend from before split tunnelling has nothing to stand this
      // pane on, so the pane is not there at all.
      if (!state.split) pane.hidden = true;
      return;
    }
    const was = state.split;
    state.split = tidy(info.split);
    if (info.splitLists) state.splitLists = info.splitLists;
    if (!draft || same(draft, was)) draft = copy(state.split);
    paint();
  }

  function paint() {
    if (!draft) { pane.hidden = true; return; }
    pane.hidden = false;
    const d = draft;
    seg.dataset.split = d.mode;
    pane.dataset.off = d.mode === 'off' ? '1' : '0';
    for (const b of seg.querySelectorAll('.seg__opt')) {
      b.setAttribute('aria-pressed', String(b.dataset.split === d.mode));
    }
    $('splitSaid').textContent = SAID[d.mode];
    $('splitIran').setAttribute('aria-checked', String(d.iran));
    paintLists();
    chips(apps, d.apps, 'program');
    chips(sites, d.domains, 'site');
    note();
    $('splitEmpty').hidden = !(d.mode === 'only' && !d.iran
                               && !d.apps.length && !d.domains.length);
    paintPill();
    if (applying) return;
    const dirty = !same(d, state.split);
    apply.disabled = !dirty;
    if (answer) said(told, answer.text, answer.kind);
    else said(told, dirty ? 'Not applied yet.' : '');
  }

  function paintPill() {
    const pill = $('splitPill');
    const mode = (state.split || {}).mode || 'off';
    pill.dataset.state = mode === 'off' ? '' : 'on';
    if (pill.textContent === PILL[mode]) return;
    pill.textContent = PILL[mode];
  }

  /* The counts are the backend's, read off the lists it ships. Zero of both
     is a copy without them, and saying so beats a switch that matches
     nothing while looking as though it matches all of Iran. */
  function paintLists() {
    const l = state.splitLists;
    const n = (x) => Number(x || 0).toLocaleString('en-US');
    $('splitIranSaid').textContent = !l
      ? 'Iranian domains and Iranian address ranges'
      : (l.domains || l.ranges)
        ? `${n(l.domains)} domains and ${n(l.ranges)} address ranges, updated with each release`
        : 'The lists are missing from this copy, so this has nothing to match';
  }

  /* Programs are only told apart by the tunnel. Through the proxy setting
     the only traffic Relay ever sees is the traffic a program chose to send
     it, so a program that ignores the setting is not "except" anything - it
     was never coming through in the first place. */
  function note() {
    $('splitAppsNote').textContent = state.routeWith === 'tun'
      ? 'Every program is affected.'
      : 'In proxy mode only programs that use the Windows proxy setting are '
        + 'affected — switch to Tunnel above to cover every program.';
  }

  /* -------------------------------------------------------------- chips */

  /* Built, not written as a string: these are whatever somebody typed. */
  function chip(value) {
    const li = document.createElement('li');
    li.dataset.value = value;
    const c = document.createElement('span');
    c.className = 'chip chip--x';
    const name = document.createElement('span');
    name.className = 'chip__name';
    name.textContent = value;
    const x = document.createElement('button');
    x.type = 'button';
    x.className = 'chip__x';
    x.title = 'Remove';
    x.setAttribute('aria-label', `Remove ${value}`);
    x.append(icon('#i-x', 'ico'));
    c.append(name, x);
    li.append(c);
    return li;
  }

  /* Matched up by value rather than rebuilt, so a repaint that changes
     nothing moves nothing, and focus on a remove button survives it. */
  function chips(list, values) {
    const have = new Map([...list.children].map((li) => [li.dataset.value, li]));
    const keep = new Set(values);
    for (const [v, li] of have) {
      if (!keep.has(v)) { gsap.killTweensOf(li.firstElementChild); li.remove(); }
    }
    let at = list.firstElementChild;
    for (const v of values) {
      const li = have.get(v) || chip(v);
      if (li === at) at = at.nextElementSibling;
      else list.insertBefore(li, at);
    }
  }

  function arrive(li) {
    if (!li || calm) return;
    gsap.fromTo(li.firstElementChild, { scale: 0.6, autoAlpha: 0 },
      { scale: 1, autoAlpha: 1, duration: 0.38, ease: 'expo.out',
        clearProps: 'transform,opacity,visibility' });
  }

  function nod(li) {
    if (!li) return;
    lit(li.firstElementChild);
    if (calm) return;
    gsap.fromTo(li.firstElementChild, { scale: 1 },
      { scale: 1.07, duration: 0.11, ease: 'power2.out', yoyo: true, repeat: 1,
        overwrite: 'auto', clearProps: 'transform' });
  }

  /* Whatever moved because the list changed under it slides from where it
     was. Transforms only - the measuring is two reads either side of one
     write, and nothing animates the layout itself. */
  function slide(list, change) {
    if (calm) { change(); return; }
    const before = new Map([...list.children].map((li) => [li, li.getBoundingClientRect()]));
    change();
    for (const li of list.children) {
      const was = before.get(li);
      if (!was) continue;
      const now = li.getBoundingClientRect();
      const dx = was.left - now.left;
      const dy = was.top - now.top;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) continue;
      gsap.fromTo(li, { x: dx, y: dy },
        { x: 0, y: 0, duration: 0.34, ease: 'power3.out', clearProps: 'transform' });
    }
  }

  /* Anything changed by hand: the last answer no longer describes what is
     on screen, so it goes, and the pane says what it does instead. */
  function edited() {
    answer = null;
    paint();
    if (open) marks();
  }

  function add(kind, values) {
    if (!draft) return 0;
    const into = valuesOf(kind);
    const list = listOf(kind);
    const fresh = [];
    for (const v of values) {
      const hit = into.find((x) => x.toLowerCase() === v.toLowerCase());
      if (hit) { nod(rowFor(list, hit)); continue; }
      into.push(v);
      fresh.push(v);
    }
    if (!fresh.length) return 0;
    edited();
    for (const v of fresh) arrive(rowFor(list, v));
    return fresh.length;
  }

  function leave(li, kind) {
    if (!li || li.dataset.leaving === '1') return;
    li.dataset.leaving = '1';
    const list = listOf(kind);
    const value = li.dataset.value;
    // Where the keyboard goes once its chip has gone: the next remove
    // button along, else the one before, else back to the way things are
    // added - never dropped on the body of the page.
    const near = li.nextElementSibling || li.previousElementSibling;
    const held = li.contains(document.activeElement);
    const done = () => {
      const into = valuesOf(kind);
      const i = into.indexOf(value);
      if (i >= 0) into.splice(i, 1);
      slide(list, edited);
      if (!held) return;
      const to = (near && near.isConnected && near.querySelector('.chip__x'))
        || (kind === 'program' ? $('splitPick') : input);
      to.focus({ preventScroll: true });
    };
    if (calm) { done(); return; }
    // Opacity rather than autoAlpha: a focused button that goes
    // visibility:hidden is blurred on the spot, and the keyboard would be
    // somewhere else before done() could hand it on.
    gsap.to(li.firstElementChild, { scale: 0.6, opacity: 0, duration: 0.16,
                                    ease: 'power2.in', onComplete: done });
  }

  for (const [list, kind] of [[apps, 'program'], [sites, 'site']]) {
    list.addEventListener('click', (e) => {
      const x = e.target.closest('.chip__x');
      if (x) leave(x.closest('li'), kind);
    });
    // The remove button already answers Enter and Space; Delete and
    // Backspace are what a hand on the keyboard reaches for first.
    list.addEventListener('keydown', (e) => {
      if (e.key !== 'Delete' && e.key !== 'Backspace') return;
      const x = e.target.closest('.chip__x');
      if (!x) return;
      e.preventDefault();
      leave(x.closest('li'), kind);
    });
  }

  /* ---------------------------------------------------- the three parts */

  for (const b of seg.querySelectorAll('.seg__opt')) {
    b.addEventListener('click', () => {
      if (!draft || draft.mode === b.dataset.split) return;
      draft.mode = b.dataset.split;
      edited();
    });
  }

  $('splitIran').addEventListener('click', () => {
    if (!draft) return;
    draft.iran = !draft.iran;
    edited();
  });

  /* A Windows dialog, and its own feedback while it is up. The button holds
     until it has closed, so a second one cannot be asked for behind it. */
  $('splitPick').addEventListener('click', async (e) => {
    const btn = e.currentTarget;
    hide(false);
    busy(btn, true);
    let r = null;
    try {
      r = await window.pywebview.api.pickApps();
    } catch (err) {
      r = null;
    }
    busy(btn, false);
    btn.focus({ preventScroll: true });
    if (!r) {
      answer = { text: 'The program picker would not open.', kind: 'bad' };
      paint();
      return;
    }
    add('program', (Array.isArray(r.apps) ? r.apps : []).map(String));
  });

  /* A light look before the backend's real one: something there, and no
     spaces in it. Commas and new lines separate entries, so a list pasted
     from somewhere else goes in as the several chips it is. */
  function addSites() {
    const parts = input.value.split(/[,;\n]+/).map((s) => s.trim()).filter(Boolean);
    if (!parts.length) {
      siteErr = 'local';
      said(siteSaid, 'Type a site, an address or a range first.', 'bad');
      input.focus({ preventScroll: true });
      return;
    }
    const spaced = parts.find((p) => /\s/.test(p));
    if (spaced) {
      siteErr = 'local';
      said(siteSaid, `“${spaced}” has a space in it.`, 'bad');
      return;
    }
    if (siteErr === 'local') { siteErr = ''; said(siteSaid, ''); }
    add('site', parts);
    input.value = '';
  }

  $('splitSiteAdd').addEventListener('click', addSites);
  // Enter adds; it does not apply. Applying can restart the adapter, and
  // that is not something to do by accident at the end of typing a name.
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); addSites(); }
  });
  input.addEventListener('input', () => {
    if (siteErr === 'local') { siteErr = ''; said(siteSaid, ''); }
  });

  /* ------------------------------------------------------ running now */

  function marks() {
    const mine = new Set(draft.apps.map((a) => a.toLowerCase()));
    for (const b of runList.querySelectorAll('.runpop__opt')) {
      b.setAttribute('aria-pressed', String(mine.has(b.dataset.app.toLowerCase())));
    }
  }

  function drawRunning() {
    const q = find.value.trim().toLowerCase();
    const shown = running.filter((a) => a.toLowerCase().includes(q));
    runList.replaceChildren(...shown.map((a) => {
      const li = document.createElement('li');
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'runpop__opt';
      b.dataset.app = a;
      const name = document.createElement('span');
      name.className = 'runpop__name';
      name.textContent = a;
      b.append(name, icon('#i-check', 'ico runpop__check'));
      li.append(b);
      return li;
    }));
    marks();
    runNone.hidden = shown.length > 0;
    runNone.textContent = running.length
      ? `Nothing running matches “${find.value.trim()}”.`
      : 'Windows named nothing running that could be listed.';
  }

  function show() {
    open = true;
    pop.hidden = false;
    runBtn.setAttribute('aria-expanded', 'true');
    if (run) run.kill();
    run = null;
    if (!calm) {
      // Opacity, not autoAlpha: autoAlpha's first frame is visibility:hidden,
      // and the filter cannot take focus inside something that is hidden.
      // [hidden] is what keeps it out of the way when it is closed.
      const rows = [...runList.children].slice(0, 8);
      run = gsap.timeline({
        defaults: { ease: 'power3.out' },
        onReverseComplete: () => { pop.hidden = true; },
      });
      run.fromTo(pop, { opacity: 0, y: -6, scaleY: 0.94 },
                      { opacity: 1, y: 0, scaleY: 1, duration: 0.24 }, 0);
      if (rows.length) {
        run.fromTo(rows, { opacity: 0, y: -4 },
                         { opacity: 1, y: 0, duration: 0.2, stagger: 0.025,
                           ease: 'power2.out' }, 0.05);
      }
    }
    find.focus({ preventScroll: true });
    // The row it hangs off can be the last thing on screen.
    pop.scrollIntoView({ block: 'nearest', behavior: calm ? 'auto' : 'smooth' });
    document.addEventListener('pointerdown', outside, true);
  }

  function hide(refocus) {
    if (!open) return;
    open = false;
    runBtn.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', outside, true);
    if (run && !calm) run.timeScale(1.6).reverse();
    else pop.hidden = true;
    if (refocus) runBtn.focus({ preventScroll: true });
  }

  function outside(e) {
    if (!pop.contains(e.target) && !runBtn.contains(e.target)) hide(false);
  }

  runBtn.addEventListener('click', async () => {
    if (open) { hide(true); return; }
    if (!draft || runBtn.dataset.busy === '1') return;
    busy(runBtn, true);
    let r = null;
    try {
      r = await window.pywebview.api.runningApps();
    } catch (err) {
      r = null;
    }
    busy(runBtn, false);
    running = r && Array.isArray(r.apps) ? r.apps.map(String) : [];
    find.value = '';
    drawRunning();
    if (!r) runNone.textContent = 'Windows would not say what is running.';
    show();
  });

  function toggleRunning(b) {
    const a = b.dataset.app;
    const hit = draft.apps.find((x) => x.toLowerCase() === a.toLowerCase());
    if (hit) {
      const li = rowFor(apps, hit);
      if (li) leave(li, 'program');
      else { draft.apps.splice(draft.apps.indexOf(hit), 1); edited(); }
      b.setAttribute('aria-pressed', 'false');
      return;
    }
    add('program', [a]);
    if (!calm) {
      gsap.fromTo(b.querySelector('.runpop__check'), { scale: 0.4, autoAlpha: 0 },
        { scale: 1, autoAlpha: 1, duration: 0.42, ease: 'expo.out',
          clearProps: 'transform,opacity,visibility' });
    }
  }

  find.addEventListener('input', drawRunning);
  runList.addEventListener('click', (e) => {
    const b = e.target.closest('.runpop__opt');
    if (b) toggleRunning(b);
  });
  pop.addEventListener('keydown', (e) => {
    const rows = [...runList.querySelectorAll('.runpop__opt')];
    const at = rows.indexOf(document.activeElement);
    if (e.key === 'Escape') {
      // Here and not in the sheet's handler: Escape puts the list away, not
      // the screen behind it.
      e.preventDefault();
      e.stopPropagation();
      hide(true);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      (rows[Math.min(at + 1, rows.length - 1)] || find).focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      (at > 0 ? rows[at - 1] : find).focus();
    } else if ((e.key === 'Home' || e.key === 'End') && at >= 0) {
      e.preventDefault();
      rows[e.key === 'Home' ? 0 : rows.length - 1].focus();
    } else if (e.key === 'Enter' && e.target === find) {
      // Type, Enter: the first match goes in. Only ever in - taking one out
      // is a press on the row itself, where you can see what it is.
      e.preventDefault();
      const first = rows[0];
      if (first && first.getAttribute('aria-pressed') !== 'true') toggleRunning(first);
    }
  });
  // Tabbing out of it is leaving it.
  pop.addEventListener('focusout', (e) => {
    const to = e.relatedTarget;
    if (open && to && !pop.contains(to) && to !== runBtn) hide(false);
  });

  /* ------------------------------------------------------------- apply */

  function reveal(el) {
    if (calm || !el.textContent) return;
    gsap.fromTo(el, { y: 4, autoAlpha: 0 },
      { y: 0, autoAlpha: 1, duration: 0.3, ease: 'power3.out',
        clearProps: 'transform,opacity,visibility' });
  }

  /* Same promise as the switches above it: the button holds while the
     machine answers, and what is in force afterwards is whatever the
     backend says, not what was sent. With a tunnel up the answer waits on
     the adapter coming back - possibly behind a UAC prompt, so possibly
     behind a person. */
  async function commit() {
    if (applying || !draft || same(draft, state.split)) return;
    hide(false);
    const want = copy(draft);
    const before = state.split;
    // Only a tunnel that is up has an adapter to restart, and a list saved
    // under Off that stays Off gives it no reason to.
    const tunUp = state.mode === 'on' && state.systemProxy && state.routeWith === 'tun'
      && !(want.mode === 'off' && (!before || before.mode === 'off'));
    applying = true;
    answer = null;
    busy(apply, true);
    said(told, tunUp
      ? `Restarting the network adapter with the new list…${
        state.elevated ? '' : ' Windows may ask for approval.'}`
      : 'Applying…', 'work');
    let r = null;
    try {
      r = await window.pywebview.api.setSplit(want);
    } catch (err) {
      r = null;
    }
    applying = false;
    busy(apply, false);
    // Edited while it was out: that edit is newer than the answer, so the
    // draft keeps it and only what is in force is brought up to date.
    const moved = !same(draft, want);
    let fresh = [];
    if (!r || r.ok === false) {
      if (r && r.split) state.split = tidy(r.split);
      answer = { text: (r && r.error) || 'That could not be applied.', kind: 'bad' };
    } else {
      state.split = tidy(r.split || want);
      if (!moved) {
        // What the backend made of what was typed replaces it - so the
        // chips that changed spelling are the ones that arrive again.
        const sent = new Set([...want.apps, ...want.domains]);
        fresh = [...state.split.apps, ...state.split.domains].filter((v) => !sent.has(v));
        draft = copy(state.split);
      }
      const rejected = Array.isArray(r.rejected) ? r.rejected.map(String) : [];
      siteErr = rejected.length ? 'rejected' : '';
      said(siteSaid, rejected.length
        ? `Left out — not a site, address or range: ${rejected.join(', ')}`
        : '', 'bad');
      // Three different things a press of Apply can have done, and each
      // says only what it did. A list saved under Off changes nothing that
      // is running, so "in force" would be a claim about nothing.
      const now = r.applied === 'now';
      let text;
      if (state.split.mode !== 'off') {
        text = !now ? 'Saved. It takes effect the next time Relay connects.'
          : tunUp ? 'In force — the adapter is back up with the new list.'
            : 'In force on the connection that is up now.';
      } else if (before && before.mode !== 'off') {
        text = now ? 'Off. Everything goes through Relay again.'
          : 'Saved. It is off from the next time Relay connects.';
      } else {
        text = 'Saved. The list is kept for when it is turned on.';
      }
      answer = { kind: 'good', text };
    }
    paint();
    for (const v of fresh) arrive(rowFor(apps, v) || rowFor(sites, v));
    reveal(told);
    if (siteErr === 'rejected') reveal(siteSaid);
  }

  apply.addEventListener('click', commit);

  return { take, paint, note, hide };
})();

/* Other devices: the server set up under Your own tunnel, handed to a phone.

   The server keeps a few numbered slots for this. Slot 0 comes out at the
   server itself and is always made; every place added here takes one of the
   others, and the backend times that place's exits from the server and pins
   the slot to the one that uploads best from where the server stands. What
   comes back is a link per slot in each kind the server speaks, and this
   draws each one as a code to scan.

   The places are the ones the exit list is drawn from - a country, a city,
   or one provider's share of either - and a pick goes to the backend in the
   same pieces connect() takes a code apart into, so "fr/par:windscribe"
   means the same place here as it does on the front of the app.

   Motion, and why each piece moves:
   - a place arrives as a chip and the others slide into its gap when it
     goes, the same as the split tunnel's list: one idiom for a list;
   - a place added past the server's last slot is refused by the row shaking
     its head, and the line under the chips says how many there is room for;
   - the list of places grows down out of its button, as the running-
     programs list does;
   - the lines of progress arrive a beat apart, and a line's mark pops when
     its place is settled, one way or the other;
   - the cards rise in one after another when they land, and a code that
     changes kind crossfades rather than blinking.
   Under reduced motion every one of these is a plain change. */
const sharePane = (() => {
  const menuRow = $('shareRow');
  const out = $('shareOut');
  const body = $('shareBody');
  const whyLine = $('shareWhy');
  const chipList = $('shareChips');
  const addBtn = $('shareAdd');
  const pop = $('sharePop');
  const find = $('shareFind');
  const list = $('shareList');
  const none = $('shareNone');
  const limit = $('shareLimit');
  const fullLine = $('shareFull');
  const make = $('shareMake');
  const stop = $('shareStop');
  const told = $('shareSaid');
  const runs = $('shareRuns');
  const cards = $('shareCards');
  const kindSeg = $('shareKind');

  let calm = false;
  gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => {
    calm = true;
    return () => { calm = false; };
  });

  // Byte mode reads a string a character at a time and keeps the low eight
  // bits of each, which is right for every link the server writes today and
  // silently wrong the day a name has a letter outside Latin-1 in it.
  if (window.qrcode && qrcode.stringToBytesFuncs && qrcode.stringToBytesFuncs['UTF-8']) {
    qrcode.stringToBytes = qrcode.stringToBytesFuncs['UTF-8'];
  }

  const NOT_READY = 'This needs your own server, set up under Connection '
    + '→ Your own tunnel.';
  const KINDS = { vless: 'VLESS', vmess: 'VMess', ss: 'SS' };
  const PHASE_SAID = {
    waiting: 'waiting its turn',
    testing: 'timing its exits from the server…',
    pinned: 'pinned',
    failed: 'no exit there answered',
    stopped: 'not reached',
  };
  const QR_SIDE = 220;   // css px, give or take a module - see drawQr
  const QR_MAX = 270;    // and never wider than this, so it fits a narrow card
  const QUIET = 4;       // modules of white round the code, as the spec asks

  const ex = { ready: false, slots: 0, why: '', host: '' };   // the backend's word
  let known = false;     // whether this backend describes it at all
  let picks = [];        // [{code, country, city, provider, only, label}]
  let offered = new Map();   // code -> pick, for the rows the list is showing
  let working = false;   // a run is out
  let stopping = false;
  let held = { make: false, stop: false };   // which buttons busy() holds
  let result = null;     // {at, links, subscription}
  let kind = 'vless';    // what the strip, Copy all and Copy subscription take
  let open = false;      // whether the list of places is up
  let run = null;        // its opening timeline, reversed to close
  const byCard = new WeakMap();   // a result card -> its link record
  const copyTimers = new WeakMap();

  /* ------------------------------------------------------------- state */

  /* From the described state - boot() or info(), by way of paintPrefs. */
  function take(info) {
    if (!info || !info.export) {
      // A backend from before this has nothing to put on the screen, so the
      // way to the screen is not there either.
      if (!known) menuRow.hidden = true;
      return;
    }
    known = true;
    menuRow.hidden = false;
    adopt(info.export);
  }

  function adopt(e) {
    ex.ready = !!e.ready;
    ex.slots = Math.max(0, Number(e.slots) || 0);
    ex.why = String(e.why || '');
    if (e.host) ex.host = String(e.host);
    // A server set up again with fewer slots than places chosen: the ones
    // past the end are the ones that no longer have anywhere to go.
    if (picks.length > ex.slots) picks = picks.slice(0, ex.slots);
    paint();
  }

  /* Asked each time the screen opens rather than carried in info(): the
     last set of links is the server's business and only this screen wants
     it. A set made in this session already is not replaced by an older one. */
  async function opened() {
    if (!known) return;
    let r = null;
    try {
      r = await window.pywebview.api.exportState();
    } catch (err) {
      r = null;
    }
    if (!r) return;
    adopt(r);
    const last = r.last;
    if (!working && last && Array.isArray(last.links)
        && (!result || (Number(last.at) || 0) > (result.at || 0))) {
      result = { at: Number(last.at) || 0, links: last.links,
                 subscription: last.subscription || null };
      drawResult(false);
      paint();
    }
    // "Made just now" was true when it was written, and the screen can be
    // come back to an hour later.
    paintAt();
  }

  function paintAt() {
    $('shareAt').textContent = result && result.at ? `made ${whenAgo(result.at)}` : '';
  }

  function paint() {
    whyLine.hidden = ex.ready;
    whyLine.textContent = ex.ready ? '' : (ex.why || NOT_READY);
    body.hidden = !ex.ready;
    out.hidden = !ex.ready || !result;
    paintPill();
    if (!ex.ready) { hide(false); return; }
    paintChips();
    $('shareCount').textContent = ex.slots ? `${picks.length} of ${ex.slots}` : '';
    addBtn.hidden = ex.slots === 0;
    addBtn.disabled = working;
    stop.hidden = !working;
    for (const x of chipList.querySelectorAll('.chip__x')) x.disabled = working;
    // Room, said where it is wanted. Under the chips for a server with no
    // slots at all, where there is no list to open; inside the list once
    // the slots there are have all been taken, because the list covers
    // the line under the chips and the next press is in the list.
    said(limit, ex.slots === 0
      ? 'This server keeps no slots for other places, so only the server '
        + 'itself can be shared. Running the installer again adds them.' : '');
    const full = ex.slots > 0 && picks.length >= ex.slots;
    fullLine.hidden = !full;
    fullLine.textContent = full
      ? `All ${ex.slots} slot${ex.slots === 1 ? ' is' : 's are'} taken. `
        + 'Take a place off to add another.' : '';
  }

  function paintPill() {
    const pill = $('sharePill');
    let text = 'ready';
    let mark = '';
    if (!ex.ready) { text = 'not ready'; mark = 'off'; }
    else if (working) { text = 'making'; mark = 'work'; }
    else if (result) {
      const n = result.links.filter((l) => !l.error).length;
      text = `${n} config${n === 1 ? '' : 's'}`;
      mark = 'on';
    }
    pill.dataset.state = mark;
    if (pill.textContent === text) return;
    pill.textContent = text;
  }

  /* -------------------------------------------------------------- places */

  /* One place as the backend wants it. The label is what the config is
     called on the phone, so it says the place the way a person would. */
  function pickOf(c, city, via) {
    const code = (city ? `${c.code}/${city.code}` : c.code) + (via ? `:${via}` : '');
    const where = city ? `${city.name}, ${c.name}` : c.name;
    return {
      code,
      country: c.code,
      city: city ? city.code : null,
      provider: via || null,
      only: null,
      label: via ? `${where} · ${PROVIDER_NAMES[via] || via}` : where,
    };
  }

  /* Built, not written as a string: the names are the backend's. */
  function chip(p) {
    const li = document.createElement('li');
    li.dataset.value = p.code;
    const c = document.createElement('span');
    c.className = 'chip chip--x';
    const name = document.createElement('span');
    name.className = 'chip__name';
    name.textContent = p.label;
    const x = document.createElement('button');
    x.type = 'button';
    x.className = 'chip__x';
    x.title = 'Remove';
    x.setAttribute('aria-label', `Remove ${p.label}`);
    x.append(icon('#i-x', 'ico'));
    c.append(name, x);
    li.append(c);
    return li;
  }

  /* The server itself, which is always made and cannot be taken off - so
     it is a fact in the row rather than an entry with a cross on it. */
  function serverChip() {
    const li = document.createElement('li');
    li.dataset.value = '';
    li.dataset.fixed = '1';
    const c = document.createElement('span');
    c.className = 'chip chip--fixed';
    c.title = 'Slot 0 comes out at the server itself, and is always made.';
    const name = document.createElement('span');
    name.className = 'chip__name';
    name.textContent = 'The server itself';
    const host = document.createElement('span');
    host.className = 'chip__host mono';
    c.append(icon('#i-socket', 'ico'), name, host);
    li.append(c);
    return li;
  }

  /* Matched up by value rather than rebuilt, so a repaint that changes
     nothing moves nothing, and focus on a remove button survives it. */
  function paintChips() {
    let first = chipList.firstElementChild;
    if (!first || first.dataset.fixed !== '1') {
      first = serverChip();
      chipList.prepend(first);
    }
    first.querySelector('.chip__host').textContent = ex.host || '';
    const have = new Map([...chipList.children].filter((li) => li !== first)
      .map((li) => [li.dataset.value, li]));
    const keep = new Set(picks.map((p) => p.code));
    for (const [v, li] of have) {
      if (!keep.has(v)) { gsap.killTweensOf(li.firstElementChild); li.remove(); }
    }
    let at = first.nextElementSibling;
    for (const p of picks) {
      const li = have.get(p.code) || chip(p);
      if (li === at) at = at.nextElementSibling;
      else chipList.insertBefore(li, at);
    }
  }

  const chipFor = (code) =>
    [...chipList.children].find((li) => li.dataset.value === code && !li.dataset.fixed);

  function arrive(li) {
    if (!li || calm) return;
    gsap.fromTo(li.firstElementChild, { scale: 0.6, autoAlpha: 0 },
      { scale: 1, autoAlpha: 1, duration: 0.38, ease: 'expo.out',
        clearProps: 'transform,opacity,visibility' });
  }

  /* Whatever moved because the row changed under it slides from where it
     was - transforms only, the same as the split tunnel's chips. */
  function slide(change) {
    if (calm) { change(); return; }
    const before = new Map([...chipList.children].map((li) => [li, li.getBoundingClientRect()]));
    change();
    for (const li of chipList.children) {
      const was = before.get(li);
      if (!was) continue;
      const now = li.getBoundingClientRect();
      const dx = was.left - now.left;
      const dy = was.top - now.top;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) continue;
      gsap.fromTo(li, { x: dx, y: dy },
        { x: 0, y: 0, duration: 0.34, ease: 'power3.out', clearProps: 'transform' });
    }
  }

  function edited() {
    paint();
    if (open) marks();
  }

  function leave(li) {
    if (!li || li.dataset.leaving === '1') return;
    li.dataset.leaving = '1';
    const code = li.dataset.value;
    const near = li.nextElementSibling
      || (li.previousElementSibling && !li.previousElementSibling.dataset.fixed
        ? li.previousElementSibling : null);
    const hadFocus = li.contains(document.activeElement);
    const done = () => {
      picks = picks.filter((p) => p.code !== code);
      slide(edited);
      if (!hadFocus) return;
      const to = (near && near.isConnected && near.querySelector('.chip__x')) || addBtn;
      to.focus({ preventScroll: true });
    };
    if (calm) { done(); return; }
    // Opacity rather than autoAlpha, for the split tunnel's reason: a
    // focused button that goes visibility:hidden is blurred on the spot.
    gsap.to(li.firstElementChild, { scale: 0.6, opacity: 0, duration: 0.16,
                                    ease: 'power2.in', onComplete: done });
  }

  /* No room. The row that was pressed shakes its head, and the line at the
     top of the list - already there, saying the slots are taken - nods, so
     the eye goes from the refusal to the reason. Under reduced motion the
     line is still there to read; it just does not move. */
  function refuse(el) {
    if (calm) return;
    if (el) {
      gsap.fromTo(el, { x: 0 },
        { keyframes: { x: [0, -5, 5, -3, 3, 0] }, duration: 0.38,
          ease: 'power1.inOut', overwrite: 'auto', clearProps: 'transform' });
    }
    gsap.fromTo(fullLine, { scale: 1 },
      { scale: 1.035, duration: 0.12, ease: 'power2.out', yoyo: true, repeat: 1,
        overwrite: 'auto', clearProps: 'transform' });
  }

  function toggle(code, el) {
    if (working) return;
    if (picks.some((p) => p.code === code)) {
      const li = chipFor(code);
      if (li) leave(li);
      else { picks = picks.filter((p) => p.code !== code); edited(); }
      if (el) el.setAttribute('aria-pressed', 'false');
      return;
    }
    const p = offered.get(code);
    if (!p) return;
    if (picks.length >= ex.slots) { refuse(el && el.closest('.sharepop__row')); return; }
    picks.push(p);
    edited();
    arrive(chipFor(code));
    if (!calm && el) {
      const check = el.querySelector('.runpop__check');
      if (check) {
        gsap.fromTo(check, { scale: 0.4, autoAlpha: 0 },
          { scale: 1, autoAlpha: 1, duration: 0.42, ease: 'expo.out',
            clearProps: 'transform,opacity,visibility' });
      }
    }
  }

  chipList.addEventListener('click', (e) => {
    const x = e.target.closest('.chip__x');
    if (x && !working) leave(x.closest('li'));
  });
  chipList.addEventListener('keydown', (e) => {
    if (e.key !== 'Delete' && e.key !== 'Backspace') return;
    const x = e.target.closest('.chip__x');
    if (!x || working) return;
    e.preventDefault();
    leave(x.closest('li'));
  });

  /* ------------------------------------------------- the list of places */

  /* A place's row, and when two providers reach it, one small button for
     each provider's share of it alone - which is the same choice the exit
     list makes with a provider's sign, made pressable here because there is
     no second screen to make it on. */
  function placeRow(c, city) {
    const base = pickOf(c, city, null);
    offered.set(base.code, base);
    const li = document.createElement('li');
    li.className = 'sharepop__row' + (city ? ' sharepop__row--city' : '');
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'runpop__opt sharepop__opt';
    b.dataset.code = base.code;
    const flag = document.createElement('span');
    flag.className = city ? 'flag flag--dot' : 'flag';
    flag.setAttribute('aria-hidden', 'true');
    if (!city) flag.style.setProperty('--flag', flagUrl(c.code));
    const name = document.createElement('span');
    name.className = 'runpop__name';
    name.textContent = city ? city.name : c.name;
    const meta = document.createElement('span');
    meta.className = 'sharepop__meta';
    const n = city ? city.count : c.count;
    const cities = (c.cityList || []).length;
    meta.textContent = (!city && cities > 1 ? `${cities} cities · ` : '')
      + `${n || 0} relay${n === 1 ? '' : 's'}`;
    b.append(flag, name, meta, icon('#i-check', 'ico runpop__check'));
    li.append(b);

    // Always there, empty or not, so the counts line up in one column
    // whether a row has the provider buttons after it or not.
    const ways = document.createElement('span');
    ways.className = 'sharepop__ways';
    const by = (city || c).by || {};
    const who = Object.keys(by).filter((k) => by[k] > 0).sort();
    if (who.length > 1) {
      for (const via of who) {
        const p = pickOf(c, city, via);
        offered.set(p.code, p);
        const w = document.createElement('button');
        w.type = 'button';
        w.className = 'sharepop__via';
        w.dataset.code = p.code;
        w.title = `${p.label} — only ${PROVIDER_NAMES[via] || via}'s exits`;
        w.setAttribute('aria-label', w.title);
        w.append(providerMark(via, 'ico'));
        ways.append(w);
      }
    }
    li.append(ways);
    return li;
  }

  /* Alphabetical rather than fastest first: this list is for finding a
     place by name, and how quickly it answers this window says nothing
     about how it answers the server. A country's cities follow it when it
     has more than one; a filter that names a city keeps the country above
     it, so the city is never shown without saying where it is. */
  function drawPlaces() {
    const q = find.value.trim().toLowerCase();
    const has = (s) => String(s || '').toLowerCase().includes(q);
    offered = new Map();
    const rows = [];
    const all = state.countries.slice().sort((a, b) => a.name.localeCompare(b.name));
    for (const c of all) {
      const cityList = (c.cityList || []).length > 1 ? c.cityList : [];
      const whole = !q || has(c.name) || has(c.code) || [].concat(c.alias || []).some(has);
      const cities = whole ? cityList : cityList.filter((x) => has(x.name) || has(x.nick));
      if (!whole && !cities.length) continue;
      rows.push(placeRow(c, null));
      for (const city of cities) rows.push(placeRow(c, city));
    }
    list.replaceChildren(...rows);
    // The column for them is only held open when some row has them; with
    // one provider signed in there are none, and it would be a gap.
    list.dataset.ways = list.querySelector('.sharepop__via') ? '1' : '';
    marks();
    none.hidden = rows.length > 0;
    none.textContent = state.countries.length
      ? `No place matches “${find.value.trim()}”.`
      : 'The exit list is empty. Add an account under Accounts first.';
  }

  function marks() {
    const mine = new Set(picks.map((p) => p.code));
    for (const b of list.querySelectorAll('[data-code]')) {
      b.setAttribute('aria-pressed', String(mine.has(b.dataset.code)));
    }
  }

  function show() {
    open = true;
    pop.hidden = false;
    addBtn.setAttribute('aria-expanded', 'true');
    if (run) run.kill();
    run = null;
    if (!calm) {
      // Opacity, not autoAlpha: the filter has to take focus on the first
      // frame, and nothing inside visibility:hidden can.
      const first = [...list.children].slice(0, 8);
      run = gsap.timeline({
        defaults: { ease: 'power3.out' },
        onReverseComplete: () => { pop.hidden = true; },
      });
      run.fromTo(pop, { opacity: 0, y: -6, scaleY: 0.94 },
                      { opacity: 1, y: 0, scaleY: 1, duration: 0.24 }, 0);
      if (first.length) {
        run.fromTo(first, { opacity: 0, y: -4 },
                          { opacity: 1, y: 0, duration: 0.2, stagger: 0.025,
                            ease: 'power2.out' }, 0.05);
      }
    }
    find.focus({ preventScroll: true });
    pop.scrollIntoView({ block: 'nearest', behavior: calm ? 'auto' : 'smooth' });
    document.addEventListener('pointerdown', outside, true);
  }

  function hide(refocus) {
    if (!open) return;
    open = false;
    addBtn.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', outside, true);
    if (run && !calm) run.timeScale(1.6).reverse();
    else pop.hidden = true;
    if (refocus) addBtn.focus({ preventScroll: true });
  }

  function outside(e) {
    if (!pop.contains(e.target) && !addBtn.contains(e.target)) hide(false);
  }

  addBtn.addEventListener('click', () => {
    if (open) { hide(true); return; }
    if (working || !ex.ready) return;
    find.value = '';
    drawPlaces();
    show();
  });

  find.addEventListener('input', drawPlaces);
  list.addEventListener('click', (e) => {
    const b = e.target.closest('[data-code]');
    if (b) toggle(b.dataset.code, b);
  });
  pop.addEventListener('keydown', (e) => {
    const rows = [...list.querySelectorAll('.sharepop__opt')];
    const at = rows.indexOf(document.activeElement);
    if (e.key === 'Escape') {
      // Here and not in the sheet's handler: Escape puts the list away, not
      // the screen behind it.
      e.preventDefault();
      e.stopPropagation();
      hide(true);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      (rows[Math.min(at + 1, rows.length - 1)] || find).focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      (at > 0 ? rows[at - 1] : find).focus();
    } else if ((e.key === 'Home' || e.key === 'End') && at >= 0) {
      e.preventDefault();
      rows[e.key === 'Home' ? 0 : rows.length - 1].focus();
    } else if (e.key === 'Enter' && e.target === find) {
      // Type, Enter: the first match goes in. Only ever in - taking one
      // out is a press on the row itself, where you can see what it is.
      e.preventDefault();
      const first = rows[0];
      if (first && first.getAttribute('aria-pressed') !== 'true') {
        toggle(first.dataset.code, first);
      }
    }
  });
  pop.addEventListener('focusout', (e) => {
    const to = e.relatedTarget;
    if (open && to && !pop.contains(to) && to !== addBtn) hide(false);
  });

  /* ------------------------------------------------------------ the run */

  function runRow(p) {
    const rows = [...runs.children];
    const byLabel = rows.find((li) => li.dataset.label === String(p.label || ''));
    if (byLabel) return byLabel;
    const i = Number(p.i);
    return Number.isInteger(i) && i >= 0 && i < rows.length ? rows[i] : null;
  }

  function setRun(li, phase, detail) {
    const was = li.dataset.phase;
    li.dataset.phase = phase;
    li.querySelector('.run__detail').textContent = detail || PHASE_SAID[phase] || '';
    if (calm || was === phase || (phase !== 'pinned' && phase !== 'failed')) return;
    gsap.fromTo(li.querySelector('.run__mark'), { scale: 0.5 },
      { scale: 1, duration: 0.42, ease: 'expo.out', clearProps: 'transform' });
  }

  function drawRuns(sent) {
    runs.replaceChildren(...sent.map((p) => {
      const li = document.createElement('li');
      li.className = 'run';
      li.dataset.label = p.label;
      const mark = document.createElement('span');
      mark.className = 'run__mark';
      mark.setAttribute('aria-hidden', 'true');
      mark.append(icon('#i-check', 'ico run__ok'), icon('#i-x', 'ico run__no'));
      const copy = document.createElement('span');
      copy.className = 'run__copy';
      const name = document.createElement('span');
      name.className = 'run__name';
      name.textContent = p.label;
      const detail = document.createElement('span');
      detail.className = 'run__detail';
      copy.append(name, detail);
      li.append(mark, copy);
      setRun(li, 'waiting');
      return li;
    }));
    runs.hidden = !sent.length;
    if (calm || !sent.length) return;
    gsap.fromTo([...runs.children], { y: 6, autoAlpha: 0 },
      { y: 0, autoAlpha: 1, duration: 0.28, stagger: 0.045, ease: 'power3.out',
        clearProps: 'transform,opacity,visibility' });
  }

  function release() {
    working = false;
    stopping = false;
    if (held.make) { held.make = false; busy(make, false); }
    if (held.stop) { held.stop = false; busy(stop, false); }
  }

  /* The server itself is always in the set, so an empty list of places is
     still something to make: the one config that comes out at the server. */
  async function commit() {
    if (working || !ex.ready) return;
    hide(false);
    const sent = picks.map(({ country, city, provider, only, label }) =>
      ({ country, city, provider, only, label }));
    working = true;
    stopping = false;
    held.make = true;
    busy(make, true);
    drawRuns(sent);
    said(told, sent.length ? 'Timing each place from the server…'
      : 'Asking the server for its own config…', 'work');
    paint();
    let r = null;
    try {
      r = await window.pywebview.api.buildExport(sent);
    } catch (err) {
      r = null;
    }
    // Only the refusal is handled here. Yes is "started", and everything
    // after that arrives as events - possibly before this line does.
    if (r && r.ok !== false) return;
    if (!working) return;
    release();
    runs.hidden = true;
    said(told, (r && r.error) || 'The server could not be asked.', 'bad');
    paint();
  }

  function progress(p) {
    if (!p || !working) return;
    if (p.phase === 'saving') {
      said(told, p.detail || 'Writing the slots on the server…', 'work');
      return;
    }
    const li = runRow(p);
    if (!li) return;
    setRun(li, p.phase in PHASE_SAID ? p.phase : 'testing', p.detail || '');
    if (stopping) return;
    const rows = [...runs.children];
    const at = rows.indexOf(li) + 1;
    said(told, p.phase === 'testing'
      ? `Timing ${at} of ${rows.length}: ${p.label || li.dataset.label}…`
      : `${at} of ${rows.length} done.`, 'work');
  }

  function done(d) {
    const cut = stopping;
    release();
    // Whatever was still waiting or being timed did not finish, and the
    // marks say so rather than turning for ever.
    for (const li of runs.children) {
      if (li.dataset.phase === 'testing' || li.dataset.phase === 'waiting') {
        setRun(li, 'stopped');
      }
    }
    if (!d || !d.ok) {
      said(told, (d && d.error) || (cut ? 'Stopped before anything was made.'
        : 'Nothing was made.'), 'bad');
      paint();
      return;
    }
    const links = Array.isArray(d.links) ? d.links : [];
    result = { at: Date.now() / 1000, links, subscription: d.subscription || null };
    const made = links.filter((l) => !l.error).length;
    const failed = links.length - made;
    said(told, `${made} config${made === 1 ? '' : 's'} made`
      + (failed ? `; ${failed} place${failed === 1 ? '' : 's'} found no exit that worked.`
        : '.') + (cut ? ' Stopped early.' : ''), failed ? '' : 'good');
    // The cards say everything the lines did, and say it with the code.
    runs.hidden = true;
    drawResult(true);
    paint();
    out.scrollIntoView({ block: 'start', behavior: calm ? 'auto' : 'smooth' });
  }

  make.addEventListener('click', commit);

  stop.addEventListener('click', async () => {
    if (!working || stopping) return;
    stopping = true;
    held.stop = true;
    busy(stop, true);
    said(told, 'Stopping once the place being timed is done…', 'work');
    let r = null;
    try {
      r = await window.pywebview.api.cancelExport();
    } catch (err) {
      r = null;
    }
    if (r && r.ok !== false) return;
    if (!working) return;
    stopping = false;
    held.stop = false;
    busy(stop, false);
    said(told, (r && r.error) || 'It would not stop.', 'bad');
  });

  /* ------------------------------------------------------------ results */

  const kindsOf = (l) => ['vless', 'vmess', 'ss'].filter((k) => l[k]);

  /* The standard subscription is the links one to a line, in base64. Built
     here only when the backend did not send one - a set read back from
     exportState() carries its links and nothing else. */
  function b64(text) {
    const bytes = new TextEncoder().encode(text);
    let s = '';
    for (let i = 0; i < bytes.length; i += 1) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }

  function lines(k) {
    return (result ? result.links : []).filter((l) => !l.error && l[k]).map((l) => l[k]);
  }

  /* A code is the one picture in here that has to be exact: a module that
     smears across two pixels is a module a phone camera reads as grey. So it
     is drawn in whole device pixels per module, at whatever size that comes
     to nearest 220, and shown at exactly that size - never scaled by CSS.
     Nearest rather than under: a VMess link is long enough that rounding
     down left it at 170px of two-pixel modules, which is a code a camera has
     to be held very still for. QR_MAX keeps it inside a card at 400 wide. */
  function drawQr(canvas, text) {
    if (typeof window.qrcode !== 'function') return false;
    let qr = null;
    try {
      qr = qrcode(0, 'M');
      qr.addData(text, 'Byte');
      qr.make();
    } catch (err) {
      return false;   // longer than a code can hold
    }
    const n = qr.getModuleCount();
    const cells = n + QUIET * 2;
    const dpr = Math.max(1, window.devicePixelRatio || 1);
    let unit = Math.max(1, Math.round((QR_SIDE * dpr) / cells));
    if (unit > 1 && (unit * cells) / dpr > QR_MAX) unit -= 1;
    const side = unit * cells;
    canvas.width = side;
    canvas.height = side;
    canvas.style.width = `${side / dpr}px`;
    const g = canvas.getContext('2d');
    g.fillStyle = '#fff';
    g.fillRect(0, 0, side, side);
    g.fillStyle = '#000';
    for (let r = 0; r < n; r += 1) {
      for (let c = 0; c < n; c += 1) {
        if (qr.isDark(r, c)) g.fillRect((c + QUIET) * unit, (r + QUIET) * unit, unit, unit);
      }
    }
    return true;
  }

  function card(l) {
    const li = document.createElement('li');
    li.className = 'qr';
    const k = Number(l.k) || 0;
    li.dataset.k = String(k);
    byCard.set(li, l);

    const head = document.createElement('div');
    head.className = 'qr__head';
    const name = document.createElement('span');
    name.className = 'qr__name';
    name.textContent = l.name || (k === 0 ? 'The server itself' : `Slot ${k}`);
    const slot = document.createElement('span');
    slot.className = 'qr__slot';
    slot.textContent = `slot ${k}`;
    head.append(name, slot);

    const meta = document.createElement('p');
    meta.className = 'qr__meta';
    if (k === 0) {
      meta.textContent = `Comes out at your server${ex.host ? `, ${ex.host}` : ''}.`;
    } else if (l.exit || l.upload !== undefined) {
      meta.append(l.exit ? `Comes out at ${l.exit}` : 'Pinned');
      const up = uploadSaid(l.upload);
      if (up) {
        const b = document.createElement('b');
        b.textContent = up;
        meta.append(' · ', b);
      }
    }
    li.append(head);
    if (meta.childNodes.length) li.append(meta);

    if (l.error || !kindsOf(l).length) {
      li.dataset.failed = '1';
      const err = document.createElement('p');
      err.className = 'qr__err';
      err.textContent = l.error || 'The server sent no link for this slot.';
      li.append(err);
      return li;
    }

    const kinds = kindsOf(l);
    const seg = document.createElement('div');
    seg.className = 'seg qr__kind' + (kinds.length === 3 ? ' seg--three' : '');
    seg.setAttribute('role', 'group');
    seg.setAttribute('aria-label', `Which kind of link for ${name.textContent}`);
    const slide = document.createElement('i');
    slide.className = 'seg__slide';
    slide.setAttribute('aria-hidden', 'true');
    seg.append(slide);
    if (kinds.length > 1) {
      for (const kk of kinds) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'seg__opt';
        b.dataset.proto = kk;
        b.textContent = KINDS[kk];
        seg.append(b);
      }
    }

    const box = document.createElement('div');
    box.className = 'qr__code';
    const canvas = document.createElement('canvas');
    canvas.setAttribute('role', 'img');
    box.append(canvas);
    const nocode = document.createElement('p');
    nocode.className = 'qr__none';
    nocode.hidden = true;

    const link = document.createElement('code');
    link.className = 'qr__link mono';

    const acts = document.createElement('div');
    acts.className = 'qr__acts';
    const copyBtn = document.createElement('button');
    copyBtn.type = 'button';
    copyBtn.className = 'btn btn--quiet btn--auto qr__copy';
    const copyWord = document.createElement('span');
    copyWord.textContent = 'Copy link';
    copyBtn.append(icon('#i-copy', 'ico'), copyWord);
    acts.append(copyBtn);

    if (kinds.length > 1) li.append(seg);
    li.append(box, nocode, link, acts);
    setKind(li, kind, false);
    return li;
  }

  /* Bytes a second, or the backend's own words for it. */
  function uploadSaid(up) {
    if (up === undefined || up === null || up === '') return '';
    if (typeof up === 'number') {
      const [v, u] = splitBytes(up);
      return `${v} ${u}/s up`;
    }
    const s = String(up);
    return /\bup\b/i.test(s) ? s : `${s} up`;
  }

  function setKind(li, want, animate) {
    const l = byCard.get(li);
    if (!l || li.dataset.failed === '1') return;
    const kinds = kindsOf(l);
    const k = kinds.includes(want) ? want : (kinds.includes('vless') ? 'vless' : kinds[0]);
    li.dataset.proto = k;
    const seg = li.querySelector('.qr__kind');
    if (seg) {
      seg.dataset.proto = k;
      for (const b of seg.querySelectorAll('.seg__opt')) {
        b.setAttribute('aria-pressed', String(b.dataset.proto === k));
      }
    }
    const text = String(l[k]);
    li.querySelector('.qr__link').textContent = text;
    const canvas = li.querySelector('canvas');
    const drew = drawQr(canvas, text);
    li.querySelector('.qr__code').hidden = !drew;
    canvas.setAttribute('aria-label', `QR code of the ${KINDS[k]} link`);
    const nocode = li.querySelector('.qr__none');
    nocode.hidden = drew;
    nocode.textContent = drew ? '' : 'This one is too long for a code. Copy the link instead.';
    if (drew && animate && !calm) {
      gsap.fromTo(canvas, { autoAlpha: 0, scale: 0.97 },
        { autoAlpha: 1, scale: 1, duration: 0.26, ease: 'power2.out',
          clearProps: 'transform,opacity,visibility' });
    }
  }

  function paintKind() {
    kindSeg.dataset.proto = kind;
    for (const b of kindSeg.querySelectorAll('.seg__opt')) {
      b.setAttribute('aria-pressed', String(b.dataset.proto === kind));
    }
    $('shareCopySub').disabled = !lines(kind).length;
    $('shareCopyAll').disabled = !lines(kind).length;
  }

  function drawResult(fresh) {
    out.hidden = !ex.ready || !result;
    if (!result) return;
    paintAt();
    const links = result.links.slice().sort((a, b) => (Number(a.k) || 0) - (Number(b.k) || 0));
    cards.replaceChildren(...links.map(card));
    paintKind();
    if (!fresh || calm) return;
    gsap.fromTo([...cards.children], { y: 10, autoAlpha: 0 },
      { y: 0, autoAlpha: 1, duration: 0.34, stagger: 0.06, ease: 'power3.out',
        clearProps: 'transform,opacity,visibility' });
  }

  /* The word on the button says it was done, for a moment. The clipboard
     itself is copyText's, which knows the way round a WebView that will
     not let a file:// page near it. */
  async function copyWith(btn, text) {
    if (!text) return;
    await copyText(btn, text);
    const word = btn.querySelector('span');
    if (word && !word.dataset.was) word.dataset.was = word.textContent;
    if (word) word.textContent = 'Copied';
    btn.dataset.copied = '1';
    clearTimeout(copyTimers.get(btn));
    copyTimers.set(btn, setTimeout(() => {
      delete btn.dataset.copied;
      if (!word) return;
      word.textContent = word.dataset.was;
      delete word.dataset.was;
    }, 1400));
    const mark = btn.querySelector('.ico');
    if (mark && !calm) {
      gsap.fromTo(mark, { scale: 0.6 },
        { scale: 1, duration: 0.4, ease: 'expo.out', clearProps: 'transform' });
    }
  }

  cards.addEventListener('click', (e) => {
    const li = e.target.closest('.qr');
    if (!li) return;
    const opt = e.target.closest('.seg__opt');
    if (opt) {
      if (opt.dataset.proto !== li.dataset.proto) setKind(li, opt.dataset.proto, true);
      return;
    }
    const btn = e.target.closest('.qr__copy');
    const l = byCard.get(li);
    if (btn && l) copyWith(btn, String(l[li.dataset.proto] || ''));
  });

  // The strip at the top turns every card at once; a card turned by hand
  // afterwards keeps its own until the strip is pressed again.
  for (const b of kindSeg.querySelectorAll('.seg__opt')) {
    b.addEventListener('click', () => {
      kind = b.dataset.proto;
      paintKind();
      for (const li of cards.children) {
        if (li.dataset.proto !== kind) setKind(li, kind, true);
      }
    });
  }

  $('shareCopyAll').addEventListener('click', (e) =>
    copyWith(e.currentTarget, lines(kind).join('\n')));

  $('shareCopySub').addEventListener('click', (e) => {
    const sent = result && result.subscription && result.subscription[kind];
    const all = lines(kind);
    copyWith(e.currentTarget, sent || (all.length ? b64(all.join('\n')) : ''));
  });

  return { take, opened, hide, progress, done };
})();

/* A run of Other devices, as the backend reports it: a line per place while
   the server times it, then the links. */
window.onExportProgress = (p) => sharePane.progress(p);
window.onExportDone = (d) => sharePane.done(d);

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
  busy($('chooseFolder'), true);
  const r = await window.pywebview.api.chooseFolder();
  // Held over refreshPrefs as well as over the picker. Reading a folder of
  // two thousand configs and repainting the sheet off it is most of what
  // this press costs, and it all happens after the dialog has gone.
  if (r && r.ok) await refreshPrefs();
  busy($('chooseFolder'), false);
  if (r && r.ok) setHint(`Now reading servers from ${r.folder}`);
  else if (r && r.error) setHint(r.error, true);
});

$('resetFolder').addEventListener('click', async () => {
  busy($('resetFolder'), true);
  await window.pywebview.api.resetFolder();
  await refreshPrefs();
  busy($('resetFolder'), false);
  setHint('Back to the servers that came with the app.');
});

initConnectionCore();
// Before boot, because it only rearranges markup that is already on the page
// and a settings sheet opened in the first second should already have it.
wireInfoButtons();
wireFolds();
async function boot() {
  try {
    await bootSteps();
  } finally {
    // Whatever happened - a step that threw, Python not answering - the
    // window is not left as a header over nothing.
    ready();
  }
}

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
  shut(dlg);
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
      + '.', 'good');
    prepareServers('windscribe');
  } else {
    said($('acctSaid'), 'Signed in as ' + r.username + ', but the proxy '
      + 'credential did not come back: ' + r.error, 'bad');
  }
  await acctRefresh();
  // The roster is not the only thing this changed. Surfshark's half of the
  // sheet has always done both - accountAdd calls acctRefresh and
  // refreshPrefs - and this one only ever did the first, so "Connect
  // through" went on saying "no Windscribe account" underneath a roster that
  // had just signed in to Windscribe.
  await refreshPrefs();
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


/* ----------------------------------------------------------- the folds */

/* One open at a time, and shutting the open one is a click on its own cap.

   Not <details>: the four of them have to answer to each other, and the
   answer on a shut one is written by whatever last drew the list inside it -
   both of which are this file's business rather than the element's. */
function wireFolds() {
  const all = document.querySelectorAll('.fold');
  for (const fold of all) {
    const cap = fold.querySelector('.fold__cap');
    if (!cap) continue;
    cap.addEventListener('click', () => {
      const wasOpen = fold.hasAttribute('data-open');
      for (const other of all) {
        other.removeAttribute('data-open');
        const c = other.querySelector('.fold__cap');
        if (c) c.setAttribute('aria-expanded', 'false');
      }
      if (!wasOpen) {
        fold.setAttribute('data-open', '');
        cap.setAttribute('aria-expanded', 'true');
        // The last of the four sits a finger above the bottom of the sheet,
        // and everything it opens opens below itself. Without this, opening
        // the sites fold puts the field you asked for off the screen.
        fold.scrollIntoView({block: 'nearest', behavior: 'smooth'});
      }
    });
  }
}

/* What a shut fold says. `now` is the answer and `side` is the number that
   answer costs - kept apart because they line up in different columns, and a
   sentence with the number inside it would not. */
function foldSays(id, now, side) {
  const a = $(id + 'Now');
  const b = $(id + 'Side');
  if (a) a.textContent = now || '\u2014';
  if (b) b.textContent = side == null ? '' : String(side);
}

/* Read off the list rather than off the plan: the row that is pressed is
   what the person is looking at, and the click handlers mark it before the
   round trip so it is never the one that is behind. */
function foldFromList(id, listId) {
  const row = $(listId).querySelector('.scope__row[aria-pressed="true"]');
  if (!row) return foldSays(id, null, null);
  const count = row.querySelector('.scope__count');
  foldSays(id, row.querySelector('.scope__name').textContent,
           count && count.textContent !== '\u2014' ? count.textContent : null);
}

function drawFolds(plan) {
  foldFromList('route', 'sweepRoute');
  foldFromList('scope', 'sweepScope');

  const companies = plan.companies || [];
  const chosen = plan.chosen || [];
  // None picked is not "none" - it is every one of them, which is a
  // different sentence and the opposite run.
  if (!companies.length) {
    foldSays('lords', 'nothing looked up yet', null);
  } else if (!chosen.length) {
    foldSays('lords', 'every company', companies.length);
  } else if (chosen.length === 1) {
    const one = companies.find((c) => c.name === chosen[0]);
    foldSays('lords', (one && one.owner) || chosen[0], one ? one.count : null);
  } else {
    foldSays('lords', `${chosen.length} companies`,
             companies.filter((c) => chosen.includes(c.name))
               .reduce((n, c) => n + (c.count || 0), 0));
  }

  const sites = plan.sites || [];
  foldSays('sites', sites.length ? sites.join(', ') : 'none, just the exits',
           sites.length || null);
}

/* ------------------------------------------------------------ the whys */

/* Every pane used to open with a paragraph explaining itself. Seven of them
   in a row turned a settings sheet into a page of documentation, where the
   control you came for was three sentences down and the six you did not come
   for were between you and it.

   The paragraphs are not deleted - they are the reason each setting is worth
   having, and losing them would be losing the argument. They move into the
   title, one hover away.

   Only descriptions move. The ones marked --tight are corrections about the
   control beside them ("being blocked is mostly a property of the address"),
   and those belong where they are: read at the moment they apply, not looked
   up. So is a .tip - it is boxed rather than hidden precisely because nobody
   hovers an (i) to find out something they do not know they need. */
function wireInfoButtons() {
  const whys = document.querySelectorAll(
    '.pane__why:not(.pane__why--tight)');
  for (const why of whys) {
    // A short description is the row's second line, in plain sight. Only
    // the paragraphs too long to sit under a title move into the (i).
    if (why.textContent.trim().length <= 170) continue;
    // A step inside a pane explains itself the same way a pane does, and the
    // line it belongs on is the step's caption rather than the pane's title
    // three inches above it. Nearest host wins.
    const step = why.closest('.step');
    const host = step || why.closest('.pane');
    const title = host && host.querySelector(step ? '.step__cap' : '.pane__title');
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
    // The paragraph itself, moved - not its markup, copied.
    //
    // Copying left the original to be deleted, and one of these is written
    // to after the page has booted: #sweepWhy says a different thing for
    // each of the three routes, and drawRoute sets it every time the route
    // changes. Against a deleted element that threw, and the throw was
    // inside drawRoute, so paintSweepPlan gave up halfway down the Servers
    // screen every time - the same shape of failure as the one written up
    // above shut(), and just as invisible.
    //
    // Moving it keeps the id, the markup and anything holding a reference,
    // and there is nothing left behind to go stale.
    bubble.appendChild(why);
    btn.append(icon, bubble);

    /* Inside the title, not after it.
       Two kinds of pane hold these. In one the title is a flex item and a
       button after it lands beside it; in the other the title is a block in
       a column, and a button after a block starts a line of its own - so
       the (i) sat under the heading, 2px left of everything, in every pane
       laid out that way. Inside the heading it is part of the line in both,
       which is what "moved into the title" was supposed to mean. */
    title.append(btn);

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
  for (const id of ['acctSave', 'acctCancel', 'acctUser',
                    'acctPass', 'acctTwo', 'acctLabel', 'wsRefresh',
                    'useSurfsharkDo', 'useWindscribeDo']) {
    const el = $(id);
    if (el) el.disabled = on;
  }
  for (const b of document.querySelectorAll('.prov__accts button')) {
    b.disabled = on;
  }
}

function acctPaint() {
  // Each provider's accounts live in that provider's card.
  const lists = {};
  for (const box of document.querySelectorAll('.prov__accts')) {
    box.textContent = '';
    lists[box.dataset.for] = box;
  }

  for (const row of acct.rows) {
    const list = lists[row.provider];
    if (!list) continue;
    const el = document.createElement('div');
    el.className = 'acct__row';
    el.dataset.provider = row.provider;
    el.dataset.active = row.active ? '1' : '0';
    el.dataset.ready = row.signedIn ? '1' : '0';

    // An initial in a circle rather than the provider's name in a pill:
    // the card the row sits in already says which provider it is.
    const tag = document.createElement('span');
    tag.className = 'acct__tag';
    tag.textContent = (row.label || row.username || '?').slice(0, 1).toUpperCase();

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
      use.addEventListener('click', () => acctUse(row.id, use));
      act.appendChild(use);
    }
    // Fetch and pin this account's servers from right here, as the Android
    // app's account cards do - not two screens away.
    const pinIt = document.createElement('button');
    pinIt.className = 'acct__icon';
    pinIt.type = 'button';
    pinIt.title = 'Get this provider\'s servers and pin them';
    pinIt.setAttribute('aria-label', pinIt.title);
    pinIt.append(icon('#i-pin', 'ico ico--sm'));
    pinIt.addEventListener('click', () => prepareServers(row.provider));
    act.appendChild(pinIt);
    // Remove asks once more, in place: the first press arms it, the second
    // removes, and leaving it alone for a few seconds disarms it.
    const drop = document.createElement('button');
    drop.className = 'btn btn--quiet btn--auto acct__drop';
    drop.type = 'button';
    drop.textContent = 'Remove';
    drop.addEventListener('click', () => {
      if (drop.dataset.sure !== '1') {
        drop.dataset.sure = '1';
        drop.textContent = 'Remove?';
        setTimeout(() => {
          drop.dataset.sure = '0';
          drop.textContent = 'Remove';
        }, 3000);
        return;
      }
      acctRemove(row.id, row.label, drop);
    });
    act.appendChild(drop);

    el.append(tag, who, act);
    list.appendChild(el);
  }

  for (const card of document.querySelectorAll('.prov')) {
    const key = card.dataset.provider;
    card.dataset.account = acctTaken(key) ? '1' : '0';
  }

  // The pill counts what is usable, not what is listed - a roster entry with
  // nothing behind it is not an account this app can connect with.
  const usable = acct.rows.filter((r) => r.signedIn).length;
  const pill = $('acctPill');
  pill.dataset.state = usable ? 'on' : 'off';
  pill.textContent = usable
    ? `${usable} account${usable === 1 ? '' : 's'}` : 'no accounts';
  paintAcctHero();

  // The refresh icon lives in the Windscribe card now, and only means
  // anything while there is a Windscribe account for it to be about.
  // Surfshark has no counterpart: its credential never expires, so there was
  // never anything to refresh.
  const ws = acct.rows.find((r) => r.provider === 'windscribe' && r.active);
  $('wsRefresh').hidden = !ws;
}

/* The number across the top of Accounts: how many providers have both an
   account and pinned servers, which is what it takes to connect through
   one. Painted from both halves - the roster and the providers' state -
   because either can change without the other. */
function paintAcctHero() {
  const per = state.provState || {};
  const keys = ['surfshark', 'windscribe'];
  const ready = keys.filter((k) => (per[k] || {}).usable).length;
  $('acctReadyN').textContent = String(ready);
  $('acctReadyN').closest('.accthero').dataset.ready = ready ? '1' : '0';
  const say = $('acctHeroSay');
  // The (i) button lives inside the title, so only the words are replaced.
  const words = say.firstChild && say.firstChild.nodeType === Node.TEXT_NODE
    ? say.firstChild : say.insertBefore(document.createTextNode(''), say.firstChild);
  words.textContent = ready ? 'providers ready to connect'
    : acct.rows.length ? 'ready yet - get the servers and pin them'
    : 'ready - add an account to connect';
  const bars = document.querySelectorAll('.accthero__bar i');
  bars.forEach((b, i) => { b.dataset.on = i < ready ? '1' : '0'; });
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
    shut(dlg);
    return;
  }
  $('acctUser').value = '';
  $('acctPass').value = '';
  $('acctTwo').value = '';
  $('acctLabel').value = '';
  said($('acctNewSaid'), '');
  // One account per provider: a provider that has its account already
  // gets the sentence saying so instead of the form.
  acctSetProvider(acct.provider);
  const taken = acctTaken(acct.provider);
  $('acctFull').hidden = !taken;
  $('acctForm').hidden = taken;
  if (!dlg.open) dlg.showModal();
  if (!taken) $('acctUser').focus();
}

/* One account per provider. */
function acctTaken(provider) {
  return acct.rows.some((r) => r.provider === provider);
}

const ACCT_HERO = {
  surfshark: {
    mark: '#i-ss',
    say: 'The service login from Surfshark’s manual-setup page, not your email.',
    user: 'Service username', hint: 'from the manual-setup page', go: 'Save account',
  },
  windscribe: {
    mark: '#i-ws',
    say: 'Your Windscribe login. A short puzzle comes next, asked once.',
    user: 'Username or email', hint: 'your Windscribe login', go: 'Continue to the puzzle',
  },
};

function acctSetProvider(which) {
  acct.provider = which;
  const spec = ACCT_HERO[which];
  const name = PROVIDER_NAMES[which];
  $('acctDlgTitle').textContent = `Add a ${name} account`;
  $('acctHero').dataset.provider = which;
  $('acctHeroMark').setAttribute('href', spec.mark);
  $('acctHeroName').textContent = `Connect ${name}`;
  $('acctNewSay').textContent = spec.say;
  $('acctSafeTo').textContent = name;
  $('acctSave').textContent = spec.go;
  // Surfshark's is a service credential off a web page; Windscribe's is the
  // account you log in with. Saying which is the difference between pasting
  // the right thing and being refused with no idea why.
  $('acctUserCap').textContent = spec.user;
  $('acctUser').placeholder = spec.hint;
  // Two-factor is a login thing, and only one of these is a login.
  $('acctTwoWrap').hidden = which !== 'windscribe';
  said($('acctNewSaid'), '');
}


$('acctCancel').addEventListener('click', () => acctShowForm(false));
$('acctDlgClose').addEventListener('click', () => acctShowForm(false));

$('acctSave').addEventListener('click', async () => {
  const user = $('acctUser').value.trim();
  const pass = $('acctPass').value;
  const label = $('acctLabel').value.trim();
  if (acctTaken(acct.provider)) {
    said($('acctNewSaid'), `${PROVIDER_NAMES[acct.provider]} already has its account. `
      + 'Remove it first to add a different one.', 'bad');
    return;
  }
  if (!user) { said($('acctNewSaid'), 'Enter the username.', 'bad'); return; }
  if (!pass) { said($('acctNewSaid'), 'Enter the password.', 'bad'); return; }

  busy($('acctSave'), true);
  acctBusy(true);
  if (acct.provider === 'surfshark') {
    said($('acctNewSaid'), 'Saving…', 'work');
    let r;
    try {
      r = await window.pywebview.api.accountAdd('surfshark', label, user, pass);
    } catch (e) {
      said($('acctNewSaid'), `Could not save the account: ${e?.message || e}`, 'bad');
      return;
    } finally {
      acctBusy(false);
      busy($('acctSave'), false);
    }
    if (!r.ok) { said($('acctNewSaid'), r.error, 'bad'); return; }
    $('acctPass').value = '';
    acctShowForm(false);
    said($('acctSaid'), `Saved and in use: ${r.label}.`, 'good');
    await acctRefresh();
    await refreshPrefs();
    // An account is not yet something to connect with: get its servers and
    // pin them now, rather than wait for somebody to find the buttons.
    prepareServers('surfshark');
    return;
  }

  // Windscribe: the puzzle stands between here and an account.
  said($('acctNewSaid'), 'Asking Windscribe…', 'work');
  const r = await window.pywebview.api.accountAdd(
    'windscribe', label, user, pass);
  if (!r.ok) {
    acctBusy(false);
    busy($('acctSave'), false);
    said($('acctNewSaid'), r.error, 'bad');
    return;
  }
  $('acctPass').value = '';
  ws.token = r.token;
  if (r.captcha) {
    // The puzzle is the wait now, and it is a picture on the screen in front
    // of the person - so Save stops turning and stops claiming to be the
    // thing being waited on.
    busy($('acctSave'), false);
    wsShowCaptcha(r.captcha);
    said($('acctNewSaid'), 'Solve the puzzle to finish.');
  } else {
    said($('acctNewSaid'), 'Signing in…', 'work');
    await wsSubmit();
    busy($('acctSave'), false);
  }
});

/* -- using and dropping ------------------------------------------------ */

async function acctUse(id, btn) {
  busy(btn, true);
  acctBusy(true);
  said($('acctSaid'), 'Switching…', 'work');
  const r = await window.pywebview.api.accountUse(id);
  acctBusy(false);
  busy(btn, false);
  if (!r.ok) { said($('acctSaid'), r.error, 'bad'); return; }
  said($('acctSaid'), `Now using ${r.label}.`, 'good');
  await acctRefresh();
  await refreshPrefs();
}

async function acctRemove(id, label, btn) {
  busy(btn, true);
  acctBusy(true);
  const r = await window.pywebview.api.accountRemove(id);
  acctBusy(false);
  busy(btn, false);
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
/* One line each, where there were two paragraphs. What to do next is not
   said in words any more - the card says it, on the button, as the only
   thing there is to press. */
async function fetchSurfshark(btn) {
  busy(btn, true);
  acctBusy(true);
  said($('provSaid'), 'Asking Surfshark…', 'work');
  const r = await window.pywebview.api.surfsharkServers();
  acctBusy(false);
  busy(btn, false);
  if (!r.ok) { said($('provSaid'), r.error, 'bad'); return; }
  said($('provSaid'), r.added
    ? `${r.added} new, ${r.total} in all.`
    : `Nothing new — all ${r.kept} were already here.`, 'good');
  await refreshPrefs();
}

async function fetchWindscribe(btn) {
  busy(btn, true);
  acctBusy(true);
  said($('provSaid'), 'Asking Windscribe…', 'work');
  const r = await window.pywebview.api.windscribeServers();
  acctBusy(false);
  busy(btn, false);
  if (!r.ok) { said($('provSaid'), r.error, 'bad'); return; }
  said($('provSaid'), `${r.written} fetched.`, 'good');
  // The card is what says what happens next, and it can only say it once it
  // has been told there are three hundred configs waiting to be pinned.
  await refreshPrefs();
  refreshPin(false);
}

/* -- what each card is for ---------------------------------------------- */

/* A provider is in exactly one of four states, and each has exactly one
   thing to do about it. That is the whole of this flow: the card says where
   it is, the button says the next step, and pressing it either does the step
   or puts you where the step is. Nothing else on the pane is required
   reading, which is the point - the paragraphs that used to carry this were
   read by nobody who needed them. */
const PROV_DO = {
  signin: { label: 'Add an account', lead: true },
  fetch: { label: 'Get servers', lead: true },
  pin: { label: 'Pin them', lead: true },
  update: { label: 'Update servers', lead: false },
};

function provStep(has) {
  if (!has.account) return 'signin';
  if (has.servers) return 'update';
  return has.waiting ? 'pin' : 'fetch';
}

function provSays(has) {
  if (!has.account) return 'not signed in';
  if (has.servers) return `${has.servers} exits`;
  if (has.waiting) return `${has.waiting} to pin`;
  return 'no servers yet';
}

async function provDo(key, step, btn) {
  if (step === 'signin') {
    acctSetProvider(key);
    acctShowForm(true);
    return;
  }
  if (step === 'fetch' || step === 'update') {
    await (key === 'windscribe' ? fetchWindscribe(btn) : fetchSurfshark(btn));
    return;
  }
  // Pinning is minutes of work and needs a route chosen, so this does not
  // start it - it opens the screen that does, already pointed at the right
  // pile. One press instead of a folder picker and a paragraph explaining
  // which folder to walk it to.
  const r = await window.pywebview.api.pinForProvider(key);
  if (r && !r.ok) { said($('provSaid'), r.error, 'bad'); return; }
  said($('provSaid'), '');
  showScreen('servers');
  refreshPin(false);
}

$('useOpts').addEventListener('click', (e) => {
  const btn = e.target.closest('.prov__do');
  if (!btn) return;
  provDo(btn.closest('.prov').dataset.provider, btn.dataset.step, btn);
});

$('wsRefresh').addEventListener('click', async (e) => {
  // Inside the card's <label>, same as the step button: without this, asking
  // for a credential would also tick the box behind it.
  e.preventDefault();
  e.stopPropagation();
  const btn = $('wsRefresh');
  busy(btn, true);
  acctBusy(true);
  said($('provSaid'), 'Asking for a fresh credential…', 'work');
  const r = await window.pywebview.api.windscribeRefresh();
  acctBusy(false);
  busy(btn, false);
  if (!r.ok) {
    said($('provSaid'), r.error + ' — sign in again to get a new one.', 'bad');
    return;
  }
  said($('provSaid'), 'Still good.', 'good');
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

/* ---------------------------------------------------------------- the floor */

/* The settings menu is four rows and then two thirds of a window with nothing
   in it. There is nothing to put down there - a fifth row invented to fill
   the space would be worse than the space - so it gets a texture instead:
   katakana at the weight of a watermark, faded out long before it reaches the
   row above it, with a character lifting and going back down here and there.

   Sequential rather than random. The alphabet is 71 long and the grid is 13
   or 14 wide, and neither divides the other, so laying it down in order gives
   the diagonals of a sheet of graph paper that has been sheared - which is a
   pattern, and quiet. Random is not quiet: the eye finds the clumps in it
   immediately and reads them as something meaning something.

   Kana specifically, and not this app's own alphabet, because it has to be
   unreadable. Anything in the writing system of somebody using this window
   would be read, and something to read behind a menu is a thing to squint at
   rather than a floor. */
const FLOOR_KANA =
  'アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン'
  + 'ガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ';

const FLOOR_CELL = 26;   // matches grid-auto-rows and the minmax in the CSS

/* Measured, not guessed, and only ever grown. The window is resizable and
   the sheet is the whole of it, so the number of cells that fit is a runtime
   fact - and rebuilding the field on every open would restart every one of
   its animations mid-cycle, which is the one thing that would make it look
   like an effect rather than like a floor. */
function fillFloor() {
  const floor = $('prefsFloor');
  const box = floor.getBoundingClientRect();
  if (!box.width || !box.height) return;
  const cols = Math.max(1, Math.floor((box.width - 12) / FLOOR_CELL));
  const rows = Math.max(1, Math.ceil((box.height - 12) / FLOOR_CELL));
  const want = cols * rows;
  const have = floor.childElementCount;
  if (have >= want) return;

  const more = document.createDocumentFragment();
  for (let i = have; i < want; i++) {
    const cell = document.createElement('span');
    cell.textContent = FLOOR_KANA[i % FLOOR_KANA.length];
    more.appendChild(cell);
  }
  floor.appendChild(more);
}

function showScreen(slug) {
  // A list left open over a screen that is going away would still be open,
  // and still listening for the click that closes it, on the way back in.
  splitPane.hide(false);
  sharePane.hide(false);
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
  const shown = found || $('prefsMenu');
  if (window.gsap && !CALM) {
    gsap.fromTo(shown, { opacity: 0 },
                { opacity: 1, duration: 0.333, ease: 'expo.out', clearProps: 'opacity' });
  }
  if (typeof lanPane !== 'undefined') lanPane.screen(slug);
  state.screen = found ? slug : null;
  // The last set of links is asked for on the way in, not at boot: it is
  // the server's to keep, and nothing else in the window wants it.
  if (state.screen === 'share') sharePane.opened();
  // The floor belongs to the top level and nowhere else. Behind a screen
  // full of panes it would be texture under furniture; behind four rows and
  // a lot of nothing it is the reason the nothing has a bottom to it.
  $('prefs').dataset.floor = found ? '0' : '1';
  // The one note in here that is about something you did rather than about
  // something that is so - "pick at least one" - has no state to go stale
  // against, so paintUse cannot take it away. Leaving a screen ends the
  // visit it belonged to; it was still sitting there in red on the way back
  // in, describing a click from ten minutes ago.
  const note = $('useSaid');
  note.dataset.needs = '';
  said(note, '');
}

function showSettingsMenu() {
  showScreen(null);
}

$('prefsBody').addEventListener('click', (e) => {
  const row = e.target.closest('.pane--line');
  if (!row || e.target.closest('button, input, a, label, select, .tip')) return;
  const sw = row.querySelector('.toggle:not(:disabled)');
  if (sw) sw.click();
});

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

/* Escape puts a sheet away the same way its close button does.

   A <dialog> closes itself on Escape, on the frame, with none of the motion
   the button gets - so the same sheet left two different ways two different
   ways, and the quicker one felt like a mistake.

   Registered after the handler above on purpose. That one takes Escape when
   there is a screen open, to step back to the settings menu instead of
   leaving; defaultPrevented is how this knows to keep its hands off. */
for (const dlg of document.querySelectorAll('dialog.sheet')) {
  dlg.addEventListener('cancel', (e) => {
    if (e.defaultPrevented) return;
    e.preventDefault();
    shut(dlg);
  });
}

/* ------------------------------------------- which providers to use */

/* Separate from the roster on purpose. "Which accounts exist" and "which of
   them am I connecting through today" are different questions, and folding
   them together would mean the only way to stop using a provider was to sign
   out of it - which throws away the session that took a captcha to get. */

const PROVIDER_NAMES = { surfshark: 'Surfshark', windscribe: 'Windscribe' };

/* The mark that stands for each provider wherever one has to be named in the
   space of a character. An initial was doing this job, and an initial is a
   letter you have to have been told the meaning of: S and W beside a city
   name read as a column of letters, not as two companies. The marks are the
   ones already in the sprite, monochrome, so they take the colour the tag is
   already given for how that provider is doing there. */
const PROVIDER_MARK = { surfshark: '#i-ss', windscribe: '#i-ws' };

function providerMark(key, className) {
  const href = PROVIDER_MARK[key];
  if (href) return icon(href, className || 'ico row__mark');
  // Anything this app has not been taught yet keeps the old initial, which
  // is better than a blank box in a column that is meant to line up.
  const el = document.createElement('span');
  el.textContent = (key || '?').slice(0, 1).toUpperCase();
  return el;
}

function paintUse(info) {
  const per = (info && info.providerState) || {};
  const usable = Object.keys(per).filter((k) => per[k].usable);
  const chosen = (info && info.providers) || usable;
  state.providers = chosen;
  state.provState = per;

  for (const key of ['surfshark', 'windscribe']) {
    const cap = key[0].toUpperCase() + key.slice(1);
    const box = $('use' + cap);
    const count = $('use' + cap + 'N');
    const has = per[key]
      || { servers: 0, account: false, waiting: 0, usable: false };
    box.checked = has.usable && chosen.includes(key);
    // A tick that can be put in a box that then refuses is worse than a box
    // that says why it is empty.
    box.disabled = !has.usable;
    const card = box.closest('.prov');
    card.dataset.empty = has.usable ? '0' : '1';
    // The card draws itself off this rather than off the hidden input, so
    // there is one attribute to look at when the two disagree.
    card.dataset.on = box.checked ? '1' : '0';
    // Where it is, in three words. "None pinned" was true of a provider with
    // nothing fetched and of one with three hundred configs waiting to be
    // pinned, which are not the same problem and do not have the same
    // answer - so it said neither.
    count.textContent = provSays(has);

    // And the one thing to do about it. Update is the only step that is not
    // required, so it is the only one drawn quietly; the rest are the reason
    // the provider is not working yet.
    const step = provStep(has);
    const spec = PROV_DO[step];
    const go = $('use' + cap + 'Do');
    go.dataset.step = step;
    go.dataset.lead = spec.lead ? '1' : '0';
    go.textContent = step === 'pin' ? `Pin ${has.waiting}` : spec.label;
    go.hidden = false;

    // Account, servers, pinned - ticked off in the card as each is done.
    const done = {
      account: has.account,
      servers: has.servers > 0 || has.waiting > 0,
      pinned: has.servers > 0,
    };
    for (const li of $('use' + cap + 'Steps').children) {
      li.dataset.done = done[li.dataset.step] ? '1' : '0';
    }
  }
  paintAcctHero();

  // A refusal that has since been answered. The note under this chooser
  // records which providers it was about, and once they have what they were
  // missing it is describing a state that does not exist - it sat there in
  // red saying "no Surfshark account" under a roster plainly showing the
  // Surfshark account, because adding one repaints the boxes and nothing
  // ever went back for the sentence under them.
  //
  // Only that kind. The refusal for a pick with nothing ticked in it is
  // about what was just done rather than about what is missing, and it is
  // still worth reading beside boxes that have snapped back - so commitUse
  // leaves this empty for that one and it stays until the next answer.
  const note = $('useSaid');
  const about = note.dataset.needs || '';
  if (about && about.split(' ').every((k) => (per[k] || {}).usable)) {
    note.dataset.needs = '';
    said(note, '');
  }
}

async function commitUse() {
  const want = ['surfshark', 'windscribe']
    .filter((k) => $('use' + k[0].toUpperCase() + k.slice(1)).checked);
  said($('useSaid'), 'Applying…');
  const r = await window.pywebview.api.setProviders(want);
  if (!r.ok) {
    // Which providers the complaint is about, so paintUse can take it away
    // when they stop being the problem. Empty for a pick with none of them
    // in it, which is not about a provider and does not go stale.
    $('useSaid').dataset.needs = want.join(' ');
    said($('useSaid'), r.error, 'bad');
    // Put the boxes back to what is really in force, so the screen never
    // shows a choice that was refused.
    await refreshPrefs();
    return;
  }
  $('useSaid').dataset.needs = '';
  // What was accepted, which is not always what was asked for - setProviders
  // drops anything that turned out to be unusable between the click and the
  // call, and a card left showing the ask would be showing a choice nobody
  // is connecting through.
  for (const key of ['surfshark', 'windscribe']) {
    const b = $('use' + key[0].toUpperCase() + key.slice(1));
    b.checked = r.providers.includes(key);
    b.closest('.prov').dataset.on = b.checked ? '1' : '0';
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

$('useOpts').addEventListener('change', (e) => {
  // Answered on the click rather than on the round trip. The card draws off
  // data-on, and waiting for setProviders to come back before setting it
  // left a card that had plainly been clicked showing nothing for as long as
  // the engine took to re-count its folders. What comes back then corrects
  // it, in commitUse, so a refusal still ends up showing the truth.
  const box = e.target.closest('.prov__in');
  if (box) box.closest('.prov').dataset.on = box.checked ? '1' : '0';
  commitUse();
});

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

/* A time's colour, the phone's thresholds: under 150 ms is good, under 300
   is fair, the rest is slow. */
function pingLevel(ms) {
  return ms >= 300 ? 'high' : ms >= 150 ? 'mid' : 'low';
}

/* Whether the pick is anywhere inside this country: the country itself, a
   city in it, one provider's share of it, or one of its servers. */
function pickIsIn(c) {
  const p = state.picked || '';
  if (!p || p === 'auto') return false;
  if (p.startsWith('file:')) {
    const stem = p.slice(5).replace(/^\d+\.\d+s-/, '').slice(0, 2).toLowerCase();
    return stem === c.code || (stem === 'gb' && c.code === 'uk');
  }
  return p.split(':')[0].split('/')[0] === c.code;
}

function reachState(c) {
  if (!c || !c.tested) return 'untested';
  if (!c.ok) return c.tested >= c.count ? 'blocked' : 'some';
  return c.ok < c.tested ? 'some' : 'ok';
}

/* -- running one ------------------------------------------------------- */

/* One row at a time. Pressing the bolt on a row while another is running
   stops that one rather than queueing behind it - two runs at once would
   measure each other, which is the same reason the engine refuses a second. */
async function startReach(code) {
  if (state.testing) {
    await window.pywebview.api.cancelReach();
    return;
  }
  const r = await window.pywebview.api.testReach(code);
  if (!r.ok) { said($('reachSaid'), r.error, 'bad'); return; }
  state.testing = code;
  paintTesting();
  said($('reachSaid'), `Asking ${r.total} in ${nameOf(code)}…`);
}

/* One exit per country, six at a time. By hand from Test all or Settings;
   on its own when the list opens and the last run is over six hours old -
   never with the tunnel up, where every time would be the tunnel's. */
async function startQuick(auto) {
  if (state.testing) {
    if (state.testing === 'quick' && !auto) await window.pywebview.api.cancelReach();
    return;
  }
  const r = await window.pywebview.api.quickTest();
  if (!r.ok) {
    if (!auto) {
      said($('reachSaid'), r.error, 'bad');
      said($('quickSaid'), r.error, 'bad');
    }
    return;
  }
  state.testing = 'quick';
  paintTesting();
  said($('reachSaid'), `Timing ${r.total} countries…`);
}

function maybeQuick() {
  const stale = Date.now() / 1000 - (state.quickAt || 0) > 6 * 3600;
  const up = state.mode === 'on' || state.mode === 'busy';
  if (state.autoQuick !== false && stale && !state.testing && !up) startQuick(true);
}

/* Settings' Quick timing: when it last ran, whether it runs on its own. */
function paintQuick() {
  const pill = $('quickPill');
  if (!pill) return;
  const running = state.testing === 'quick';
  const at = state.quickAt || 0;
  const mins = at ? Math.floor((Date.now() / 1000 - at) / 60) : null;
  pill.textContent = running ? 'timing'
    : mins === null ? 'never'
      : mins < 60 ? `${mins}m ago`
        : mins < 1440 ? `${Math.floor(mins / 60)}h ago` : `${Math.floor(mins / 1440)}d ago`;
  pill.dataset.state = running || (mins !== null && mins < 360) ? 'on' : 'off';
  $('autoQuick').setAttribute('aria-checked', String(state.autoQuick !== false));
  const run = $('quickRun');
  run.textContent = running ? 'Stop' : 'Time every country';
  run.disabled = !!state.testing && !running;
}

$('quickRun').addEventListener('click', () => startQuick(false));
$('autoQuick').addEventListener('click', async () => {
  state.autoQuick = state.autoQuick === false;
  paintQuick();
  await window.pywebview.api.setAutoQuick(state.autoQuick);
});

/* The bolt that is running, marked without rebuilding the list under the
   reader. Every other bolt goes quiet with it: a second press would only
   stop the first, and a row that looks pressable while it is not is worse
   than one that plainly waits its turn. */
function paintTesting() {
  for (const el of document.querySelectorAll('[data-test]')) {
    const busy = el.dataset.test === state.testing;
    el.dataset.busy = String(busy);
    el.dataset.idle = String(!!state.testing && !busy);
    el.setAttribute('aria-label', busy ? 'Stop testing this one'
      : 'Test the servers here');
    el.title = busy ? 'Stop' : 'Test these';
  }
  paintQuick();
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
  const row = document.querySelector(`.row[data-code="${country}"] .row__ping`);
  if (!row) return;
  if (rec.ms !== null && rec.ms !== undefined) {
    const was = row.dataset.best ? Number(row.dataset.best) : null;
    if (was === null || rec.ms < was) {
      row.dataset.best = String(rec.ms);
      delete row.dataset.blocked;
      row.textContent = msSaid(rec.ms);
      lit(row);
    }
  } else if (rec.ok === false && !row.dataset.best) {
    row.textContent = 'no answer';
    lit(row);
  }
}

/* A number that has just changed, marked for as long as it takes to notice.

   Twenty results land on one row during a run and only the ones that beat
   the best so far rewrite it - so the text changes three or four times, at
   moments nothing else on screen marks. Without this the row is simply
   different every time the eye comes back to it, and the reader is left to
   work out whether it moved. Restarted rather than queued, because the next
   improvement can land before the last mark has faded. */
function lit(el) {
  el.classList.remove('is-lit');
  void el.offsetWidth;
  el.classList.add('is-lit');
}

window.onReach = (p) => {
  // Eight at a time, so the count moves in steps rather than smoothly. It is
  // still the only honest thing to show: a bar would have to guess at how
  // long the ones still in flight are going to take, and the slow ones are
  // exactly the ones that are about to time out.
  const what = p.phase === 'pinging' ? 'timed' : 'checked';
  // The second look at the ones that did not answer. Said, because it is
  // seconds of a run that otherwise looks finished, and because an exit that
  // was drawn as blocked a moment ago is about to turn back - which reads as
  // the app changing its mind unless it says it is asking again.
  said($('reachSaid'), p.again
    ? `Asking ${p.again} that did not answer once more…`
    : `${p.done} of ${p.total} ${what}…`);
  $('reachBar').style.setProperty('--at', `${(p.done / (p.total || 1)) * 100}%`);
  if (p.file && p.result) patchRow(p.file, p.result);
};

window.onReachDone = (r) => {
  const quick = r.code === 'quick';
  // A quick run over an open list keeps the list where it is: the order
  // and which rows count as timed are taken now, before the new answers.
  if (quick && r.ok && $('picker').open && !$('search').value.trim()) {
    state.freeze = {
      order: rows().map((x) => x.dataset.code)
        .filter((code) => code && code !== 'auto' && !/[/:]/.test(code)),
      tested: new Set((state.countries || []).filter((c) => c.tested).map((c) => c.code)),
    };
  }
  if (quick && r.quickAt) state.quickAt = r.quickAt;
  state.testing = null;
  paintTesting();
  $('reachBar').style.setProperty('--at', '0%');
  if (!r.ok) { said($('reachSaid'), r.error || 'Could not test.', 'bad'); return; }
  state.countries = r.countries || state.countries;
  const wasOpen = state.openExits;
  state.exits = {};            // measured again, so the old detail is stale
  // Named, because a run is now about one place and the line under the list
  // is the only thing that says which. "30 of 37 answered" over a list of
  // ninety countries is a sentence about nothing in particular.
  const where = r.code && !quick ? ` in ${nameOf(r.code)}` : '';
  // A run where the exits turned the account away is not a run about the
  // exits, and "0 of 37 answered" reads as thirty-seven broken servers. The
  // same account is at every one of those addresses, so the count is one
  // fact repeated - which is worth saying out loud, because the thing to fix
  // is somewhere else entirely.
  //
  // Measured on this machine: 27 of 37 Surfshark exits refused, every one of
  // them a 407, because the file the proxy reads held an older credential
  // than the account the window was showing as signed in. The app said
  // nothing about credentials for any of it - see the note in main.py about
  // `answered`, which is why this could not fire.
  const answered = r.answered || 0;
  const refused = r.refused || 0;
  // The same test the connect race uses, so the two cannot disagree about
  // whether a run was about the account: enough of them to be evidence, and
  // most of what was asked. A handful of 407s is ordinary.
  const mostRefused = refused >= 3 && refused > r.tested / 2;
  const advice = ' — that is the account, not the servers. The service '
    + "username and password are on the provider's manual-setup page, and "
    + 'are not the email you log in with.';
  said($('reachSaid'),
    r.cancelled
      ? `Stopped after ${r.tested}${where}. ${answered} answered.`
      : mostRefused
        ? `${refused} of ${r.tested}${where} turned these credentials away`
          + advice
        // Some refused and some did not. Worth saying, because it is the
        // difference between an account that is wrong and a fleet where a
        // few exits happen not to run a proxy for it - and the second is
        // ordinary enough that hiding it would make the first look normal.
        : refused
          ? `${answered} of ${r.tested} answered${where}. `
            + `${refused} turned these credentials away.`
          : quick
            ? `${answered} of ${r.tested} countries answered.`
              + (state.freeze ? ' New times are in — press Fastest first to put them in order.' : '')
            : `${answered} of ${r.tested} answered${where}.`,
    answered ? 'good' : 'bad');
  if (quick) said($('quickSaid'), `${answered} of ${r.tested} countries answered.`);
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
  // A run has just re-judged every exit it asked, which is the one moment
  // the count in the header is certain to be out of date.
  refreshDead();
};

/* No listener here any more: the bolts are in the list, and the list already
   has one listener for every control inside a row - see the [data-test]
   branch there. A second handler bound per row would go stale on the next
   redraw, and the list redraws on every result. */

/* -- the exits behind one row ------------------------------------------ */

/* Countries are what the list is, because ninety-one endpoints is not
   something anybody reads. But once each exit has a measured time, the
   individual ones are worth being able to look at - "why is this country
   slow" and "is this one blocked" are questions about a server. */

/* A server's own number out of its host or filename: ar-008.x.com is #008. */
function serverNumber(host) {
  const m = /[a-z]{2}-(\d{2,4})(?:\.|_|$)/.exec(String(host || '').toLowerCase());
  return m ? `#${m[1]}` : '';
}

/* The engine's word for why an exit failed, said for a person. */
function failReason(why) {
  const w = String(why || '');
  if (!w) return 'It did not answer.';
  if (/timeout|timed out|deadline/i.test(w)) {
    return 'It did not answer in time. It may be down, or blocked on this line.';
  }
  if (/refused/i.test(w)) return 'It refused the connection.';
  if (/407|auth/i.test(w)) return 'It turned the account away.';
  return w.charAt(0).toUpperCase() + w.slice(1);
}

async function toggleExits(code, after) {
  const open = state.openExits === code;
  state.openExits = open ? null : code;
  for (const el of document.querySelectorAll('.exits')) {
    const group = el.parentElement;
    el.remove();
    // A card shut again when its servers go, unless its cities are open.
    if (group && group.classList.contains('group') && !group.querySelector('.group__in')) {
      group.classList.remove('is-open');
    }
  }
  for (const b of document.querySelectorAll('[data-exits]')) {
    // The attribute and nothing else. The sign is two paths now and CSS
    // folds one of them away; writing a character in here emptied the svg
    // and left a text glyph in a slot the rest of the list draws.
    b.dataset.on = String(b.dataset.exits === state.openExits);
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
    sign.append(providerMark(x.provider));
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
      : where && where !== short ? where : short;
    // The server's own number tells two exits in one city apart: ar-008.
    const num = serverNumber(x.host || x.file);
    if (num && where) {
      const n = document.createElement('span');
      n.className = 'exit__num';
      n.textContent = num;
      name.append(n);
    }
    name.title = x.host || '';

    const meta = document.createElement('span');
    meta.className = 'exit__meta';
    meta.textContent = x.ip;

    let said_ = document.createElement('span');
    said_.className = 'exit__ping';
    said_.textContent = x.ok === true ? msSaid(x.ms) : x.ok === false ? '' : 'not tested';
    if (x.ok === false) {
      // A red mark where the time would be; the reason is behind it.
      said_ = document.createElement('span');
      said_.className = 'exit__why';
      said_.tabIndex = 0;
      said_.setAttribute('aria-label', failReason(x.why));
      said_.append(icon('#i-alert', 'ico'));
      const bubble = document.createElement('span');
      bubble.className = 'info__bubble';
      bubble.setAttribute('role', 'tooltip');
      bubble.textContent = failReason(x.why);
      said_.append(bubble);
    }

    row.append(sign, name, meta, said_);
    box.append(row);
  }
  after.insertAdjacentElement('afterend', box);
  const group = after.parentElement && after.parentElement.classList.contains('group')
    ? after.parentElement : null;
  if (group) group.classList.add('is-open');
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
/* The bolt on a row: ask these exits, and only these.

   It was one button in the header that asked all four hundred. Two things
   were wrong with that. Nobody wants to know about ninety countries - they
   want to know about the one they are about to pick. And a run that long is
   where this line starts dropping connections, so the tail of it measured
   the moment rather than the exits.

   Same weight as the star, and always drawn rather than revealed on hover:
   a control that appears when the pointer arrives is a control somebody who
   does not already know about it never finds. */
function testMark(code) {
  const busy = state.testing === code;
  const el = document.createElement('span');
  el.className = 'rowtest';
  el.dataset.test = code;
  el.dataset.busy = String(busy);
  el.setAttribute('role', 'button');
  el.setAttribute('aria-label', busy ? 'Stop testing this one'
    : 'Test the servers here');
  el.title = busy ? 'Stop' : 'Test these';
  el.append(icon('#i-bolt', 'ico rowtest__ico'));
  return el;
}

function starFor(code) {
  const star = document.createElement('span');
  star.className = 'star';
  star.dataset.star = code;
  star.dataset.on = String(isFavourite(code));
  star.setAttribute('role', 'button');
  star.setAttribute('aria-label', 'Keep this one at the top');
  // One drawn star that fills, rather than ☆ swapped for ★. The two
  // glyphs are different widths in most faces, so the control jumped a
  // pixel sideways every time it was pressed - and beside three stroked
  // icons a text glyph was the one thing on the row drawn in a different
  // hand.
  star.append(icon('#i-star', 'ico star__ico'));
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
  t.append(providerMark(key));
  const name = PROVIDER_NAMES[key] || key;
  const said_ = !tested ? 'not tested yet'
    : ok ? `${ok} of ${tested} answered`
      + (ping !== undefined && ping !== null ? `, best ${msSaid(ping)}` : '')
      : `none of ${tested} answered`;
  t.title = `${name} · ${count} relay${count === 1 ? '' : 's'} · ${said_}`;
  return t;
}

function isFavourite(code) {
  return (state.favourites || []).includes(code);
}

/* Starred itself, or one of its cities is: either way it goes to the top. */
function starredIn(c) {
  return isFavourite(c.code)
    || (c.cityList || []).some((x) => isFavourite(`${c.code}/${x.code}`));
}

async function toggleFavourite(code) {
  const r = await window.pywebview.api.toggleFavourite(code);
  if (!r.ok) return;
  const added = r.codes.includes(code) && !state.favourites.includes(code);
  state.favourites = r.codes;
  $('list').dataset.key = '';
  drawList($('search').value.trim());
  // Starring moves the row to the top of the list, which is a redraw - so
  // the row you pressed is gone and a new one is somewhere else. The pop is
  // what carries the eye to it; without it the list simply changes shape and
  // it is not obvious that the thing you did is the reason.
  if (!added) return;
  const star = document.querySelector(`[data-star="${CSS.escape(code)}"]`);
  if (!star) return;
  star.classList.add('just-starred');
  star.addEventListener('animationend',
                        () => star.classList.remove('just-starred'),
                        { once: true });
}

/* The order. Answering-first stays underneath all three, because a blocked
   exit is not a good answer to "sort by name" either - and starred places
   come above everything, which is the whole point of starring one. */
function sortCountries(list) {
  const kind = state.sortBy || 'ping';
  const dead = (c) => (c.tested && !c.ok ? 1 : 0);
  const fav = (c) => (starredIn(c) ? 0 : 1);
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

/* The plus, drawn rather than typed, so it can fold into the minus instead
   of being replaced by it.

   As text it was `+` swapped for `−` - two glyphs of two widths, and the
   swap happened between frames, so what the eye got was one sign vanishing
   and another appearing in roughly the same place. Two lines with the
   upright one collapsing is the same control turning, which is what it is:
   one thing in one place doing one job in two directions. */
function expander(kind, code, on) {
  const NS = 'http://www.w3.org/2000/svg';
  const el = document.createElement('span');
  el.className = 'expand';
  el.dataset[kind === 'expand' ? 'expand' : 'exits'] = code;
  el.dataset.on = String(!!on);
  el.setAttribute('role', 'button');
  el.setAttribute('aria-label', kind === 'expand'
    ? 'Show the cities in this country' : 'Show the servers here');
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'ico expand__ico');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  // A chevron that turns over when the row is open, as on the phone.
  const path = document.createElementNS(NS, 'path');
  path.setAttribute('class', 'expand__chev');
  path.setAttribute('d', 'M6 9l6 6 6-6');
  svg.append(path);
  el.append(svg);
  return el;
}

/* The three things you can do to a row without leaving the list, in one
   recessed track rather than loose down the right-hand edge.

   Four unrelated glyphs at four sizes, evenly spaced, is what the right of
   every row was: no grouping, nothing saying which of them was the one that
   opens the row, and every one of them under 24px in a window driven with a
   mouse. The track groups them, the provider mark stays outside it because
   it says who rather than offering to do anything, and each control gets a
   real target with its own colour under it. */
function railFor(code, kind, on) {
  const rail = document.createElement('span');
  rail.className = 'rail';
  rail.append(testMark(code), starFor(code),
              kind ? expander(kind, code, on) : slot());
  return rail;
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
  let nick = null;
  if (city.nick) {
    nick = document.createElement('i');
    nick.className = 'row__nick';
    nick.textContent = city.nick;
  }

  const meta = document.createElement('span');
  meta.className = 'row__meta';
  const n = via ? (city.by || {})[via] || 0 : city.count;
  const bits = [`${n} relay${n === 1 ? '' : 's'}`];
  // The load down here, the time on the right: the time is what picks a
  // city, so it is the number that stands out.
  if (city.load !== null && city.load !== undefined) bits.push(`${loadSaid(city.load)} load`);
  meta.textContent = bits.join(' · ');
  copy.append(name, meta);

  const marks = document.createElement('span');
  marks.className = 'row__marks';
  // The city's best time where the load bar was, coloured by how good it
  // is - or that nothing there answered.
  const blocked = city.tested && !city.ok && city.tested >= city.count;
  if (blocked || (city.ping !== null && city.ping !== undefined)) {
    const ping = document.createElement('span');
    ping.className = 'row__ping';
    ping.textContent = blocked ? 'Blocked' : msSaid(city.ping);
    if (blocked) ping.dataset.blocked = '1';
    marks.append(ping);
  }
  // The nickname after the name, where it is the part of the line that can
  // go without anyone missing it.
  if (nick) name.append(' ', nick);

  // The phone's second line: whose relays, 10G and P2P, then the count and
  // the load - so the name line keeps its room for the name and the time.
  const lead = [];
  for (const key of Object.keys(city.by || {}).filter((k) => city.by[k] > 0).sort()) {
    // A city's own numbers are not split per provider, so the sign says
    // how many are there and leaves the verdict to the country's.
    lead.push(providerTag(key, city.by[key], 0, 0, null));
  }
  for (const [has, word, why, kind] of [
    [city.gbps === 10, '10G', '10 Gbps link', 'fast'],
    [city.p2p, 'P2P', 'P2P allowed here', 'p2p'],
  ]) {
    if (!has) continue;
    const m = document.createElement('span');
    m.className = 'mark';
    m.dataset.kind = kind;
    m.textContent = word;
    m.title = why;
    lead.push(m);
  }
  const count = document.createElement('span');
  count.className = 'row__count';
  count.textContent = meta.textContent;
  meta.replaceChildren(...lead, count);
  row.append(copy, marks,
             railFor(code, 'exits', state.openExits === code));
  return row;
}

/* -- the controls above the list --------------------------------------- */

function paintSort() {
  for (const b of $('sortBy').querySelectorAll('.pair__opt')) {
    b.setAttribute('aria-selected', String(b.dataset.sort === (state.sortBy || 'ping')));
  }
  slideSort();
}

/* The lit backing, under whichever icon is chosen. Without this the pill
   stayed 0 wide at the left edge and never showed at all, which left three
   icons distinguished only by a shade of grey - a control that answered
   every press by looking exactly as it had before.

   Measured rather than told, because a dialog that has never been opened has
   no layout: offsetWidth is 0 until showModal(), so this is called again on
   the way in rather than only when the choice changes. */
function slideSort() {
  const chosen = $('sortBy').querySelector('[aria-selected="true"]');
  const bar = $('sortBy').querySelector('.pair__slide');
  if (!chosen || !bar || !chosen.offsetWidth) return;
  bar.style.width = `${chosen.offsetWidth}px`;
  bar.style.transform = `translateX(${chosen.offsetLeft - 2}px)`;
}

/* What the chosen sort has to work with, when the answer is nothing.

   Two of the three are properties this list may simply not know. Nothing has
   a time until a test has been run, and how busy a server is is a figure only
   some providers publish - so both comparators fall through to the name, and
   pressing either icon leaves ninety rows in exactly the order they were
   already in. That is the right order to fall back to and a terrible thing to
   do in silence: the control looked broken because the only evidence it had
   done anything was a list that had not moved.

   It only speaks when there is nothing. A sort that worked does not need a
   sentence underneath saying it worked. */
function sortHasNothing(kind) {
  const list = state.countries || [];
  if (!list.length) return '';
  if (kind === 'ping' && !list.some((c) => c.ping !== null && c.ping !== undefined)) {
    return 'Nothing has been timed yet — test a country first.';
  }
  if (kind === 'load' && !list.some((c) => cityLoad(c) !== null)) {
    return 'These servers do not say how busy they are.';
  }
  return '';
}

$('sortBy').addEventListener('click', async (e) => {
  const opt = e.target.closest('.pair__opt');
  if (!opt) return;
  state.sortBy = opt.dataset.sort;
  // The list is laid out afresh, so the line asking for it is done with.
  if (state.freeze) said($('reachSaid'), '');
  state.freeze = null;
  paintSort();
  $('list').dataset.key = '';
  drawList($('search').value.trim());
  // Not over a run in progress: that line is counting exits at the time, and
  // it is saying something about this second rather than about the sort.
  const nothing = sortHasNothing(state.sortBy);
  if (nothing && !state.testing) said($('reachSaid'), nothing);
  await window.pywebview.api.setSort(state.sortBy);
});

/* ========================================================================
   THE ONES THAT STOPPED ANSWERING

   The sweep used to delete these itself, out of every folder that said they
   worked, on any run pointed at its own folder - which is every run this
   window starts. That is gone (see -Prune), and this is what replaced it.

   Three rules, and they are the whole design:

     nothing goes without being shown first. The sheet is opened to find out
     what would go; opening it removes nothing.

     which folder is a question. The same config sits in pinned\ and in
     success\ and they do not mean the same thing - one is a record that it
     connected once, the other is the only copy there is - so the sheet asks
     rather than assuming, and ticks nothing to begin with.

     nothing is destroyed. An exit that failed one thirty-second window will
     often answer perfectly a minute later; the test itself asks twice for
     that reason. A verdict that provisional does not earn a delete, so they
     go to a shelf and there is a button that empties it back.
   ======================================================================== */

const drop = {
  folders: [],
  exits: [],
  shelved: [],
  ticked: new Set(),
  aside: 0,
};

/* The mark in the header. Asked for after anything that could change the
   answer - a test finishing, a set-aside, a put-back - and never on a timer:
   the number only moves when the app itself moved it. */
async function refreshDead() {
  let r;
  try {
    r = await window.pywebview.api.deadExits();
  } catch (e) {
    return;
  }
  if (!r || !r.ok) return;
  drop.folders = r.folders || [];
  drop.exits = r.exits || [];
  drop.shelved = r.shelved || [];
  drop.aside = r.aside || 0;
  drop.dead = r.dead || 0;
  const n = drop.dead;
  const btn = $('dropOpen');
  // Present when there is either something to take out or something to put
  // back. An empty shelf and a clean list mean the button has nothing to say,
  // and a control with nothing to say is furniture.
  btn.hidden = !n && !drop.aside;
  btn.dataset.n = String(n);
  btn.setAttribute('aria-label', n
    ? `${n} exits stopped answering`
    : `${drop.aside} exits set aside`);
}

function openDrop() {
  drop.ticked.clear();
  said($('dropSaid'), '');
  paintDrop();
  $('dropDlg').showModal();
}

function paintDrop() {
  const total = drop.dead || 0;
  const when = drop.exits.reduce(
    (latest, x) => (x.at && x.at > latest ? x.at : latest), 0);
  // The date matters more than the count. A verdict from a week ago is a
  // week-old guess about a fleet that moves, and somebody about to act on it
  // should be told how old it is before they are told how many there are.
  const ago = when ? ` The last test was ${whenAgo(when)}.` : '';
  $('dropLede').innerHTML = total
    ? `<b>${total} exits</b> did not answer when they were last asked.${ago} `
      + 'They are still in the list, at the bottom, marked. An exit can miss '
      + 'one bad thirty seconds on this line and answer perfectly a minute '
      + 'later, so nothing here is deleted &mdash; what you take out is moved '
      + 'to a folder called <b>dropped</b> and can be put back.'
    : 'Everything in the list answered when it was last asked.';

  const chips = $('dropFolders');
  chips.textContent = '';
  for (const f of drop.folders) {
    const li = document.createElement('li');
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip';
    b.dataset.folder = f.path;
    b.setAttribute('aria-pressed', String(drop.ticked.has(f.path)));
    b.title = f.path;
    const name = document.createElement('span');
    name.textContent = f.label;
    const n = document.createElement('span');
    n.className = 'chip__count';
    n.textContent = String(f.count);
    b.append(name, n);
    // pinned holds what a pin run resolved and nothing else does. Taking an
    // exit out of it is not the same act as taking it out of a folder that
    // was only ever a record of what worked, and the chip says so where the
    // decision is being made.
    if (/(^|\/)(pinned|configs|windscribe)$/.test(f.label)) {
      const note = document.createElement('span');
      note.className = 'chip__note';
      note.textContent = 'originals';
      b.append(note);
    }
    li.append(b);
    chips.append(li);
  }

  paintDropList();
  $('dropGo').disabled = !drop.ticked.size;

  const back = $('dropBack');
  back.hidden = !drop.aside;
  if (drop.aside) {
    const where = (drop.shelved || []).map((s) => s.label).join(', ');
    $('dropBackWhy').textContent = `${drop.aside} set aside, ready to go back `
      + `to ${where || 'where they came from'}.`;
  }
}

function paintDropList() {
  const box = $('dropList');
  box.textContent = '';
  const rows = drop.exits.filter((x) => drop.ticked.has(x.folder));
  if (!rows.length) {
    const p = document.createElement('p');
    p.className = 'drop__empty';
    p.textContent = drop.folders.length
      ? 'Tick a folder to see what would come out of it.'
      : 'Nothing to take out.';
    box.append(p);
    return;
  }
  // Worst first is the wrong order here. These are being read to be checked,
  // not ranked, so they go in the order somebody would look for one: by
  // place, with the folder named on the row when more than one is ticked.
  const many = drop.ticked.size > 1;
  rows.sort((a, b) => (a.country || '').localeCompare(b.country || '')
    || (a.city || '').localeCompare(b.city || ''));
  for (const x of rows) {
    const row = document.createElement('div');
    row.className = 'drop__row';

    const where = document.createElement('span');
    where.className = 'drop__where';
    const place = [x.city, x.country ? x.country.toUpperCase() : '']
      .filter(Boolean).join(' · ');
    where.textContent = place || x.file;
    if (many) where.textContent += `  —  ${x.tag}`;

    const ip = document.createElement('span');
    ip.className = 'drop__ip';
    ip.textContent = x.ip || '';

    const why = document.createElement('span');
    why.className = 'drop__why';
    why.textContent = x.why || 'no answer';

    row.append(where, ip, why);
    box.append(row);
  }
}

/* How long ago, in the words a person would use. Not a timestamp: the point
   of the sentence is whether the verdict is fresh enough to act on, and
   "1788752940" answers that for nobody. */
function whenAgo(at) {
  const mins = Math.max(0, Math.round((Date.now() / 1000 - at) / 60));
  if (mins < 2) return 'just now';
  if (mins < 60) return `${mins} minutes ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return hours === 1 ? 'an hour ago' : `${hours} hours ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

async function dropGo() {
  const btn = $('dropGo');
  busy(btn, true);
  said($('dropSaid'), 'Moving them…', 'work');
  const r = await window.pywebview.api.dropExits([...drop.ticked]);
  busy(btn, false);
  if (!r || !r.ok) {
    said($('dropSaid'), (r && r.error) || 'Could not move them.', 'bad');
    return;
  }
  afterPoolChange(r);
  drop.ticked.clear();
  await refreshDead();
  paintDrop();
  said($('dropSaid'),
    `${r.moved} set aside from ${r.folders.join(', ')}.`
    + (r.failed ? ` ${r.failed} could not be moved.` : ''),
    r.failed ? 'bad' : 'good');
}

async function dropRestore() {
  const btn = $('dropRestore');
  busy(btn, true);
  said($('dropSaid'), 'Putting them back…', 'work');
  const r = await window.pywebview.api.restoreDropped();
  busy(btn, false);
  if (!r || !r.ok) {
    said($('dropSaid'), (r && r.error) || 'Could not put them back.', 'bad');
    return;
  }
  afterPoolChange(r);
  await refreshDead();
  paintDrop();
  said($('dropSaid'), `${r.back} put back.`
    + (r.stuck ? ` ${r.stuck} could not be.` : ''), r.stuck ? 'bad' : 'good');
}

/* The list is a different list now, and everything cached about it is stale.
   drawList memoises on a key that does not mention the server set, so the key
   has to be cleared by hand - otherwise the picker goes on showing exits that
   are not there any more. */
function afterPoolChange(r) {
  if (r.countries) {
    state.countries = r.countries;
    state.fuse = new Fuse(state.countries, {
      keys: ['name', 'code', 'alias'], threshold: 0.4, ignoreLocation: true,
    });
  }
  state.exits = {};
  $('list').dataset.key = '';
  drawList($('search').value.trim());
  paintSourceHead();
}

$('dropOpen').addEventListener('click', async () => {
  await refreshDead();
  openDrop();
});
$('dropClose').addEventListener('click', () => shut($('dropDlg')));
$('dropKeep').addEventListener('click', () => shut($('dropDlg')));
$('dropGo').addEventListener('click', dropGo);
$('dropRestore').addEventListener('click', dropRestore);
$('dropFolders').addEventListener('click', (e) => {
  const chip = e.target.closest('.chip[data-folder]');
  if (!chip) return;
  const path = chip.dataset.folder;
  if (drop.ticked.has(path)) drop.ticked.delete(path);
  else drop.ticked.add(path);
  chip.setAttribute('aria-pressed', String(drop.ticked.has(path)));
  paintDropList();
  $('dropGo').disabled = !drop.ticked.size;
  said($('dropSaid'), '');
});

/* ========================================================================
   WHERE THE EXITS COME FROM

   The app reads three folders at once and offers everything in them, which
   is right until you want it not to. There was no way to say "only these":
   the one control that narrowed anything refused to narrow onto a folder the
   app already read, so choosing pinned silently widened back to all of them
   and the count returned to what it was.

   It lives on the line that was already above the list saying how many there
   are. A row of chips under the search box would be permanent furniture for
   a setting most people set once; the count was already there, and a count
   that also says what it counted is one line doing two jobs rather than two
   lines doing one each.
   ======================================================================== */

function paintSourceHead() {
  const s = state.sources || {};
  const row = (s.rows || []).find((r) => r.key === s.source);
  $('sourceName').textContent = row ? row.name : 'All locations';
  // Lit when it is anything but everything. A filter that is on and does not
  // look on is how somebody comes to wonder where three hundred locations
  // went.
  $('sourcePick').dataset.narrow = (s.source && s.source !== 'all') ? '1' : '0';
}

async function openSource() {
  const r = await window.pywebview.api.sources();
  if (r && r.ok) state.sources = r;
  paintSourceRows();
  said($('srcSaid'), '');
  $('srcDlg').showModal();
}

function paintSourceRows() {
  const box = $('srcRows');
  box.textContent = '';
  const s = state.sources || {};
  for (const r of (s.rows || [])) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'menu__row';
    b.dataset.source = r.key;
    b.setAttribute('aria-current', String(r.key === s.source));

    const copy = document.createElement('span');
    copy.className = 'menu__copy';
    const name = document.createElement('span');
    name.className = 'menu__name';
    name.textContent = r.name;
    const note = document.createElement('span');
    note.className = 'menu__note';
    note.textContent = r.note;
    copy.append(name, note);

    const n = document.createElement('span');
    n.className = 'menu__n';
    n.textContent = String(r.count);
    // The tick is on every row and only visible on one. Appended to the
    // chosen row alone, it took 23px out of that row and left its count
    // sitting to the left of the other four - a column with one number out
    // of line, which is the one fault a screenshot will not show you.
    b.append(copy, n, icon('#i-check',
      r.key === s.source ? 'menu__tick' : 'menu__tick is-off'));
    box.append(b);
  }
}

async function useSource(key) {
  said($('srcSaid'), '');
  const r = await window.pywebview.api.setSource(key);
  if (!r || !r.ok) {
    said($('srcSaid'), (r && r.error) || 'Could not switch to that.', 'bad');
    return;
  }
  afterPoolChange(r);
  const got = await window.pywebview.api.sources();
  if (got && got.ok) state.sources = got;
  paintSourceHead();
  shut($('srcDlg'));
}

$('sourcePick').addEventListener('click', openSource);
$('srcClose').addEventListener('click', () => shut($('srcDlg')));
$('srcRows').addEventListener('click', (e) => {
  const row = e.target.closest('.menu__row[data-source]');
  if (row) useSource(row.dataset.source);
});
$('srcBrowse').addEventListener('click', async (e) => {
  const btn = e.currentTarget;
  busy(btn, true);
  const r = await window.pywebview.api.chooseFolder();
  busy(btn, false);
  if (!r || !r.ok) {
    if (r && r.error) said($('srcSaid'), r.error, 'bad');
    return;
  }
  // chooseFolder only sets the folder. Saying it is now the source is this
  // sheet's job, and going through setSource is what keeps the head of the
  // list and the settings file agreeing about which pool is on.
  await useSource(`folder:${r.folder}`);
});


/* ------------------------------------------------------------ first run */

/* The intro: four slides, the first time this copy opens. Resolves when it
   is finished with, by Skip or Get started, and says so to Python so it is
   never shown again. */
function runIntro() {
  return new Promise((done) => {
    const dlg = $('introDlg');
    const slides = [...dlg.querySelectorAll('.intro__slide')];
    const dots = [...$('introDots').children];
    let at = 0;
    const go = (to) => {
      at = to;
      slides.forEach((s, i) => {
        s.classList.toggle('is-here', i === at);
        s.classList.toggle('is-gone', i < at);
      });
      const here = slides[at];
      if (here && window.gsap && !CALM) {
        gsap.fromTo(here, { opacity: 0, x: 24 },
                    { opacity: 1, x: 0, duration: 0.333, ease: 'expo.out', clearProps: 'opacity,transform' });
      }
      dots.forEach((d, i) => d.classList.toggle('is-here', i === at));
      $('introNext').textContent = at === slides.length - 1 ? 'Get started' : 'Next';
    };
    const finish = () => {
      window.pywebview.api.markIntroSeen();
      shut(dlg);
      done();
    };
    $('introNext').onclick = () => (at < slides.length - 1 ? go(at + 1) : finish());
    $('introSkip').onclick = finish;
    dlg.addEventListener('cancel', (e) => { e.preventDefault(); finish(); }, { once: true });
    // However it goes away, the rest of boot goes on.
    dlg.addEventListener('close', () => done(), { once: true });
    go(0);
    dlg.showModal();
  });
}

/* The welcome: nothing to connect with, so ask how it will connect. Each
   answer goes straight to the place that sets it up. */
function openWelcome() {
  const dlg = $('welcomeDlg');
  if (!dlg.open) dlg.showModal();
}

// Not dismissed with Escape: behind it is a Connect button with nothing to
// connect through. It goes when there is something - an account, a server.
$('welcomeDlg').addEventListener('cancel', (e) => e.preventDefault());

for (const b of $('welcomeDlg').querySelectorAll('[data-provider]')) {
  b.addEventListener('click', () => {
    acctSetProvider(b.dataset.provider);
    acctShowForm(true);
  });
}

$('welcomeServer').addEventListener('click', () => {
  shut($('welcomeDlg'));
  $('prefs').showModal();
  showScreen('connection');
  refreshPrefs();
});

/* The setup: an account's servers fetched and then pinned, with each step
   shown as it happens. What the Android app does on its own after a sign-in
   - and what this app used to leave to two buttons in Settings. */
const setup = { provider: null, finish: null };

function setupAt(step, at) {
  const row = $('setupDlg').querySelector(`.steps__row[data-step="${step}"]`);
  row.dataset.at = at;
}

function setupFail(why) {
  $('setupTitle').textContent = 'Setup did not finish';
  $('setupError').textContent = why || 'Something went wrong.';
  $('setupError').hidden = false;
  $('setupAgain').hidden = false;
  $('setupDlg').querySelector('.logo-mark').classList.remove('logo-mark--spin');
  setup.finish = null;
}

async function prepareServers(provider) {
  const dlg = $('setupDlg');
  const name = provider === 'windscribe' ? 'Windscribe' : 'Surfshark';
  setup.provider = provider;
  $('setupTitle').textContent = 'Getting your servers ready';
  $('setupSigned').textContent = `Signed in to ${name}`;
  $('setupServers').textContent = `Getting ${name}'s server list`;
  $('setupError').hidden = true;
  $('setupAgain').hidden = true;
  $('setupDetail').textContent = '';
  $('setupBar').style.width = '2%';
  dlg.querySelector('.logo-mark').classList.add('logo-mark--spin');
  setupAt('servers', 'now');
  setupAt('pinning', 'todo');
  shut($('welcomeDlg'));
  if (!dlg.open) dlg.showModal();

  const got = await (provider === 'windscribe'
    ? window.pywebview.api.windscribeServers()
    : window.pywebview.api.surfsharkServers());
  if (!got || !got.ok) { setupFail(got && got.error); return false; }
  setupAt('servers', 'done');
  setupAt('pinning', 'now');

  const aimed = await window.pywebview.api.pinForProvider(provider);
  if (!aimed || !aimed.ok) { setupFail(aimed && aimed.error); return false; }
  const finished = new Promise((resolve) => { setup.finish = resolve; });
  const started = await window.pywebview.api.startPin();
  if (!started || !started.ok) { setupFail(started && started.error); return false; }
  const p = await finished;

  await refreshPrefs();
  if (p.error) { setupFail(p.error); return false; }
  if (!p.written) { setupFail('No server answered. Try again in a moment.'); return false; }
  setupAt('pinning', 'done');
  $('setupTitle').textContent = 'Ready';
  $('setupDetail').textContent = `${p.written} servers pinned`;
  dlg.querySelector('.logo-mark').classList.remove('logo-mark--spin');
  if (state.mode === 'off') {
    setStatus('Disconnected', 'off', '', '');
    setHint(`${state.countries.length} countries ready.`);
  }
  render();
  setTimeout(() => shut(dlg), 1200);
  return true;
}

/* Pin progress goes where it always went - the Servers screen - and, while
   a setup is waiting on it, to the setup sheet too. */
window.onPin = (p) => {
  drawPin(p);
  if (!setup.finish || !p) return;
  if (p.phase === 'result' && p.total) {
    $('setupBar').style.width = `${Math.max(2, Math.round((p.done / p.total) * 100))}%`;
    $('setupDetail').textContent = `${p.done} of ${p.total}`;
  } else if (p.phase === 'finished') {
    const finish = setup.finish;
    setup.finish = null;
    finish(p);
  }
};

$('setupRetry').addEventListener('click', () => prepareServers(setup.provider));
$('setupClose').addEventListener('click', () => shut($('setupDlg')));

/* Which signed-in providers have nothing to connect through yet. */
function providersWithoutServers(info) {
  const ps = (info && info.providerState) || {};
  return Object.keys(ps).filter((k) => ps[k].account && !ps[k].servers);
}


/* ---------------------------------------------------------------- themes */

/* Three themes, with the page and surface colour each swatch draws. A copy
   that saved one of the older names opens in Graphite, or White if it was a
   light one. */
const THEMES = [
  ['graphite', 'Graphite', '#0a0a0c', '#101013'],
  ['white', 'White', '#f6f6f7', '#ffffff'],
  ['black', 'Black', '#060607', '#101013'],
];

/* Moves the theme list's one glow to the chosen row; set once the list is
   built (below). */
// (var, not let: boot() may paint a theme before this line has run)
var themeGlider = null;
function glideTheme(instant) { if (themeGlider) themeGlider(instant); }

function applyTheme(key) {
  const k = String(key || '').toLowerCase();
  const theme = k === 'white' || k === 'light' ? 'white' : k === 'black' ? 'black' : 'graphite';
  state.theme = theme;
  if (theme === 'graphite') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
  // For the next start, so it opens in this theme rather than changing to it.
  try { localStorage.setItem('relay.theme', theme); } catch (e) { /* private window */ }
  for (const b of document.querySelectorAll('.theme')) {
    b.setAttribute('aria-checked', String(b.dataset.theme === theme));
  }
  glideTheme(false);
  // The map watches data-theme itself (ink lines and a pale planet on White).
}

(function () {
  const box = $('themes');
  for (const [key, name, page, card] of THEMES) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'theme';
    b.dataset.theme = key;
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', 'false');
    b.style.setProperty('--page', page);
    b.style.setProperty('--card', card);
    const pic = document.createElement('span');
    pic.className = 'theme__page';
    pic.append(document.createElement('i'), document.createElement('b'));
    const label = document.createElement('span');
    label.textContent = name;
    b.append(pic, label);
    b.addEventListener('click', () => {
      applyTheme(key);
      window.pywebview.api.setTheme(key);
    });
    box.appendChild(b);
  }
  // The chosen row sits on one glow that slides between the three. Placed
  // without motion whenever the list itself appears (the Look screen is
  // display:none until it is opened, and nothing can be measured until then).
  const glow = document.createElement('i');
  glow.className = 'themes__glow';
  glow.setAttribute('aria-hidden', 'true');
  box.prepend(glow);
  themeGlider = (instant) => {
    const b = box.querySelector('.theme[aria-checked="true"]');
    if (!b || !b.offsetHeight || !window.gsap) return;
    gsap.set(glow, { height: b.offsetHeight, autoAlpha: 1 });
    if (instant || CALM) gsap.set(glow, { y: b.offsetTop });
    else gsap.to(glow, { y: b.offsetTop, duration: 0.45, ease: 'power3.out', overwrite: 'auto' });
  };
  if (window.ResizeObserver) new ResizeObserver(() => glideTheme(true)).observe(box);
})();

/* Segmented controls. Each has one sliding piece, and it goes where the
   pressed option is - whoever pressed it, a click, the tray or a reply from
   Python - so it is moved here, from the attribute, and not beside each of
   the dozen places that set one. The CSS places it from --seg-i; this tweens
   that number. */
(function () {
  const place = (seg, instant) => {
    const opts = [...seg.querySelectorAll(':scope > .seg__opt')].filter((b) => !b.hidden);
    const at = opts.findIndex((b) => b.getAttribute('aria-pressed') === 'true');
    if (at < 0) return;
    const now = parseFloat(seg.style.getPropertyValue('--seg-i'));
    if (now === at) return;
    if (instant || CALM || !window.gsap || Number.isNaN(now)) {
      if (window.gsap) gsap.killTweensOf(seg);
      seg.style.setProperty('--seg-i', String(at));
    } else {
      gsap.to(seg, { '--seg-i': at, duration: 0.45, ease: 'power3.out', overwrite: 'auto' });
    }
  };
  for (const seg of document.querySelectorAll('.seg')) place(seg, true);
  new MutationObserver((records) => {
    const segs = new Set();
    for (const r of records) {
      const seg = r.target.closest && r.target.closest('.seg');
      if (seg) segs.add(seg);
    }
    for (const seg of segs) place(seg, false);
  }).observe(document.body, { subtree: true, attributes: true, attributeFilter: ['aria-pressed'] });
})();


/* ------------------------------------------------------- closing the window */

/* The X asks Python what it means now - put away, quit, or ask - so that it
   and Alt+F4 answer the same way. Nothing closes until that is settled. */
async function closeRequest() {
  const r = await window.pywebview.api.closeRequest();
  if (r && r.do === 'exit') return window.pywebview.api.quitApp();
  if (r && r.do === 'tray') return window.pywebview.api.hideWindow();
  return askClose();
}

function askClose() {
  const d = $('askClose');
  $('askRemember').checked = true;
  if (!d.open) d.showModal();
}

/* Alt+F4, or anything else that closed the window from underneath us. */
window.onAskClose = askClose;

async function answerClose(kind) {
  $('askClose').close();
  if ($('askRemember').checked) {
    state.onClose = kind;
    paintOnClose();
    await window.pywebview.api.setOnClose(kind);
  }
  if (kind === 'tray') window.pywebview.api.hideWindow();
  else window.pywebview.api.quitApp();
}

$('askTray').addEventListener('click', () => answerClose('tray'));
$('askQuit').addEventListener('click', () => answerClose('exit'));
// Escape leaves the window as it was, which is the one answer that is not a
// decision - so it is neither remembered nor acted on.
$('askClose').addEventListener('cancel', () => $('askClose').close());

function paintOnClose() {
  const seg = $('onClose');
  seg.dataset.close = state.onClose || '';
  for (const b of seg.querySelectorAll('.seg__opt')) {
    b.setAttribute('aria-pressed', String((b.dataset.close || '') === seg.dataset.close));
  }
}

for (const b of $('onClose').querySelectorAll('.seg__opt')) {
  b.addEventListener('click', async () => {
    state.onClose = b.dataset.close || '';
    paintOnClose();
    await window.pywebview.api.setOnClose(state.onClose);
  });
}
