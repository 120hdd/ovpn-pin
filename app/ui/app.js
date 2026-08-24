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
};

const flagUrl = (code) => `url("flags/${code}.svg")`;

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
  act.dataset.mode = state.mode === 'fail' ? 'off' : state.mode;
  $('stage').dataset.state = state.mode === 'fail' ? 'off' : state.mode;
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
  $('search').value = '';
  state.cursor = 0;
  drawList('');
  $('picker').showModal();
  setTimeout(() => $('search').focus(), 30);
}

function drawList(query) {
  const list = $('list');
  const q = (query || '').trim();
  list.replaceChildren();

  if (!q) {
    const auto = document.createElement('button');
    auto.type = 'button';
    auto.className = 'row row--auto' + (state.picked === 'auto' ? ' is-picked' : '');
    auto.dataset.code = 'auto';
    const name = document.createElement('span');
    name.className = 'row__name';
    name.append('Fastest available');
    const sub = document.createElement('span');
    sub.className = 'row__sub';
    sub.textContent = 'Races every location and takes the first to answer';
    name.append(sub);
    auto.append(name);
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

  for (const c of found) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'row' + (state.picked === c.code ? ' is-picked' : '');
    row.dataset.code = c.code;
    row.style.setProperty('--flag', flagUrl(c.code));
    const name = document.createElement('span');
    name.className = 'row__name';
    name.textContent = c.name;
    const meta = document.createElement('span');
    meta.className = 'row__meta';
    // How many addresses a country has is what decides whether picking it
    // will work, so that is the number shown rather than a speed nobody can
    // act on.
    meta.textContent = String(c.count);
    row.append(name, meta);
    list.append(row);
  }
  markCursor();
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
            : 'Serving on 127.0.0.1:8899. Windows was left alone, so point what you want at it.')));
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
  $('folderPath').textContent = info.folder || '—';
  $('folderCount').textContent =
    `${info.serverCount || 0} servers · ${state.countries.length} countries`;
  $('aboutPaths').textContent = info.about || '';
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

/* ---------------------------------------------------------------- start */

async function boot() {
  const info = await window.pywebview.api.boot();
  state.countries = info.countries || [];
  state.picked = info.picked || 'auto';
  state.systemProxy = info.systemProxy !== false;
  state.fuse = new Fuse(state.countries, {
    keys: ['name', 'code', 'alias'], threshold: 0.4, ignoreLocation: true,
  });
  paintPrefs(info);

  if (!info.hasCredentials) {
    setStatus('NOT SET UP', 'fail', 'Missing sign-in details', '');
    setHint('The username and password file is not next to the app, so it cannot connect.', true);
    $('act').disabled = true;
    render();
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
}

/* --------------------------------------------------------------- wiring */

$('act').addEventListener('click', onAct);
$('pick').addEventListener('click', openPicker);
$('pickerClose').addEventListener('click', () => $('picker').close());
$('search').addEventListener('input', (e) => { state.cursor = 0; drawList(e.target.value); });
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

$('settings').addEventListener('click', async () => {
  await refreshPrefs();
  $('prefs').showModal();
});
$('prefsClose').addEventListener('click', () => $('prefs').close());

$('sysProxy').addEventListener('click', async () => {
  state.systemProxy = !state.systemProxy;
  $('sysProxy').setAttribute('aria-checked', String(state.systemProxy));
  await window.pywebview.api.setSystemProxy(state.systemProxy);
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
});
