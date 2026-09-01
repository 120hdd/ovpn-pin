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

function nameOf(code) {
  const c = state.countries.find((x) => x.code === (code || '').toLowerCase());
  return c ? c.name : (code || '').toUpperCase();
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

  const c = state.countries.find((x) => x.code === state.picked);
  $('pickLabel').textContent = state.picked === 'auto'
    ? 'Fastest available' : (c ? c.name : state.picked);
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
  const key = `${q}|${state.picked}|${state.countries.length}`;
  if (list.dataset.key === key) return;
  list.dataset.key = key;

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

  const found = q ? state.fuse.search(q).map((r) => r.item) : state.countries;
  if (!found.length) {
    const e = document.createElement('p');
    e.className = 'empty';
    e.textContent = `No country matches “${q}”.`;
    list.append(e);
    return;
  }

  const build = (c) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'row' + (state.picked === c.code ? ' is-picked' : '');
    row.dataset.code = c.code;
    row.style.setProperty('--flag', flagUrl(c.code));
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
    copy.append(name, meta);
    // No tick. The chosen row is outlined instead - see .row.is-picked.
    row.append(copy);
    return row;
  };

  // A screenful now, the rest once the frame is over. Seventy-five of these
  // built in one go was measured at about 165ms of the first open's stutter -
  // and sixty-seven of them are below the fold, where nobody is waiting for
  // them. content-visibility already stops those being painted; this stops
  // them being built in the frame that matters.
  const AT_ONCE = 14;
  for (const c of found.slice(0, AT_ONCE)) list.append(build(c));
  markCursor();

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
    for (const c of rest.slice(at, at + AT_ONCE)) batch.append(build(c));
    list.append(batch);
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

function choose(code) {
  state.picked = code;
  $('picker').close();
  render();
  window.pywebview.api.remember(code);
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

function paintWay() {
  const way = state.way || 'surfshark';
  const ready = way === 'surfshark' || (state.tunnel && state.tunnel.domain);

  $('way').dataset.way = way;
  $('wayWrap').dataset.way = way;
  for (const b of $('way').querySelectorAll('.seg__opt')) {
    b.setAttribute('aria-pressed', String(b.dataset.way === way));
  }
  // The picker belongs to the two ways that have a country in them.
  document.querySelector('.stack').dataset.way = way;

  const note = $('wayNote');
  said(note, ready ? WAY_SAID[way] : WAY_NEEDS_TUNNEL, ready ? '' : 'bad');
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
  $('tunnelPass').placeholder = t.hasPassword ? 'saved' : 'not set';
  $('tunnelApi').placeholder = t.hasApiPassword ? 'saved' : 'not set';
  $('tunnelCmd').textContent =
    `./install-server.sh ${t.domain || 'yourdomain.com'} `
    + '<surfshark-user> <surfshark-pass>';
}

async function saveTunnel() {
  const btn = $('tunnelSave');
  btn.disabled = true;
  said($('tunnelSaid'), 'Saving…');
  const plan = await window.pywebview.api.saveTunnel(
    $('tunnelDomain').value.trim(), $('tunnelPass').value, $('tunnelApi').value);
  btn.disabled = false;
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
  btn.disabled = true;
  said($('tunnelSaid'), 'Starting the client and asking the server…');
  const r = await window.pywebview.api.testTunnel();
  btn.disabled = false;
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
  said($('tunnelSaid'),
       `Up. The server is leaving by ${r.exit || 'the exit it was left on'}.`,
       'good');
  paintTunnel({ ...(state.tunnel || {}), running: true });
  paintWay();
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
  if (info.port) state.port = info.port;
  paintPort();
  $('folderPath').textContent = info.folder || '—';
  $('folderCount').textContent =
    `${info.serverCount || 0} servers · ${state.countries.length} countries`;
  $('aboutPaths').textContent = info.about || '';

  state.hasCredentials = info.hasCredentials !== false;
  paintAuthPill();

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
  // The name is shown back; the password never is. A field that arrives
  // pre-filled with a password is a password on screen, and all that buys is
  // the ability to read it over somebody's shoulder.
  const user = $('authUser');
  if (document.activeElement !== user) user.value = info.username || '';
  // The placeholder carries the state, so the field is not simultaneously
  // empty and correct with nothing saying which.
  $('authPass').placeholder = state.hasCredentials
    ? 'on file — type to replace' : 'not set';
  if (!state.hasCredentials) said($('authSaid'), 'Not set, so nothing can connect yet.', 'bad');
}

/* The one fact this pane is about, said in two words at the top of it - and
   it reacts when it changes, because somebody has just typed a password and
   wants to see that it landed. */
function paintAuthPill() {
  const pill = $('authPill');
  const want = state.hasCredentials ? 'on' : 'off';
  const words = state.hasCredentials ? 'on file' : 'not set';
  if (pill.dataset.state === want && pill.textContent === words) return;
  pill.dataset.state = want;
  pill.textContent = words;
  pill.classList.remove('turned');
  void pill.offsetWidth;
  pill.classList.add('turned');
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

async function saveCredentials() {
  const user = $('authUser').value.trim();
  const pass = $('authPass').value;
  const btn = $('authSave');
  btn.disabled = true;
  said($('authSaid'), 'Saving…');
  const r = await window.pywebview.api.saveCredentials(user, pass);
  btn.disabled = false;
  if (!r.ok) {
    said($('authSaid'), r.error, 'bad');
    return;
  }
  // Out of the DOM the moment it has been written. It is on disk now, and a
  // settings sheet left open on a filled password field is the one place this
  // app would be leaking one.
  $('authPass').value = '';
  said($('authSaid'), r.env
    ? 'Saved, and .env was updated to match — the sweep scripts read that one first.'
    : 'Saved.', 'good');
  state.hasCredentials = true;
  paintAuthPill();
  $('act').disabled = false;
  if (state.mode === 'off') {
    setStatus('DISCONNECTED', 'off', '', '');
    setHint(`${state.countries.length} countries ready.`);
  }
  render();
}

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
  state.way = info.mode || 'surfshark';
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
    setTimeout(() => $('authUser').focus(), 60);
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

$('authSave').addEventListener('click', saveCredentials);
$('authPass').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') saveCredentials();
});
$('authUser').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') $('authPass').focus();
});

$('authPeek').addEventListener('click', () => {
  const field = $('authPass');
  const showing = field.type === 'text';
  field.type = showing ? 'password' : 'text';
  // The eye is open while the password is, and crossed out while it is not.
  $('authPeek').querySelector('use')
    .setAttribute('href', showing ? '#i-eye' : '#i-eye-off');
  $('authPeek').setAttribute('aria-pressed', String(!showing));
  $('authPeek').setAttribute('aria-label',
    showing ? 'Show the password' : 'Hide the password');
});

$('way').addEventListener('click', (e) => {
  const opt = e.target.closest('.seg__opt');
  if (opt && !opt.disabled) chooseWay(opt.dataset.way);
});

$('tunnelSave').addEventListener('click', saveTunnel);
$('tunnelTest').addEventListener('click', testTunnel);
$('tunnelPeek').addEventListener('click', () => peek('tunnelPass', 'tunnelPeek'));
$('tunnelApiPeek').addEventListener('click', () => peek('tunnelApi', 'tunnelApiPeek'));
$('tunnelApi').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') saveTunnel();
});
$('tunnelCopy').addEventListener('click', (e) =>
  copyText(e.currentTarget, $('tunnelCmd').textContent));

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
  // password must never be sitting in the DOM after it has been saved.
  passwordInDom: $('authPass').value.length > 0,
});
