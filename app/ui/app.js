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

  state.hasCredentials = info.hasCredentials !== false;
  paintAuthPill();
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
  // Asserted on by the automated check: whatever else this page does, a
  // password must never be sitting in the DOM after it has been saved.
  passwordInDom: $('authPass').value.length > 0,
});
