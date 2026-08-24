/* The page holds no truth of its own.

   Every fact on screen came from the backend, and this file only decides how
   to say it. That matters more than usual: the whole product is a claim about
   where your traffic comes out, made to someone who cannot check it any other
   way. A number invented here, or left on screen after it stopped being true,
   is the one unforgivable bug.

   The last version had a different unforgivable bug — a panel that was
   supposed to be hidden covered the window and ate every click, because a CSS
   display rule quietly beat the hidden attribute. Everything that can be a
   real platform element now is one: the picker is a <dialog>, so its
   visibility is the browser's business rather than a stylesheet's.

   Fuse is loaded as a plain script rather than a module: pywebview serves
   local files over file://, where ES module imports are blocked outright,
   and the failure mode is a page that loads and does nothing. */

const $ = (id) => document.getElementById(id);

const state = {
  countries: [],
  picked: 'auto',
  mode: 'off',          // off | busy | on | fail
  lastIp: null,
  fuse: null,
  cursor: 0,
};

/* ------------------------------------------------------------- rendering */

function setExit(ip, place, live) {
  const el = $('exitIp');
  el.textContent = ip || '—';
  el.classList.toggle('was', !live && !!ip);
  $('exitPlace').textContent = place;
  $('exit').dataset.state = live ? 'on' : 'off';
  $('exitCap').textContent = live
    ? 'Your traffic exits from'
    : (ip ? 'Last exited from' : 'Your traffic exits from');
}

function setStatus(word, kind, where, host) {
  const s = $('status');
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

function render() {
  const act = $('act');
  act.dataset.mode = state.mode === 'fail' ? 'off' : state.mode;
  $('card').dataset.busy = String(state.mode === 'busy');
  $('pick').disabled = state.mode === 'busy';
  $('more').hidden = !(state.mode === 'on');
  if (state.mode !== 'on') {
    $('details').hidden = true;
    $('more').setAttribute('aria-expanded', 'false');
  }
  const c = state.countries.find((x) => x.code === state.picked);
  $('pickLabel').textContent = state.picked === 'auto'
    ? 'Fastest available' : (c ? c.name : state.picked);
}

/* --------------------------------------------------------------- picker */

function openPicker() {
  const dlg = $('picker');
  $('search').value = '';
  state.cursor = 0;
  drawList('');
  dlg.showModal();
  setTimeout(() => $('search').focus(), 30);
}

function closePicker() { $('picker').close(); }

function matches(query) {
  const q = (query || '').trim();
  if (!q) return state.countries;
  return state.fuse.search(q).map((r) => r.item);
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
    sub.textContent = 'Whichever server answers first, right now';
    name.append(sub);
    auto.append(name);
    list.append(auto);
  }

  const found = matches(q);
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
    const name = document.createElement('span');
    name.className = 'row__name';
    name.textContent = c.name;
    const meta = document.createElement('span');
    meta.className = 'row__meta';
    meta.textContent = c.best != null ? `${c.best.toFixed(1)}s` : '';
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
  closePicker();
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
  const stage = STAGES[p.phase] || 'Working';
  let detail = '';
  if (p.phase === 'probing' && p.total) {
    detail = `${p.asked || 0} of ${p.total} asked`;
  }
  setStatus('CONNECTING', 'busy', stage, detail);
};

window.onConnected = (status) => {
  state.mode = 'on';
  const e = status.exit || {};
  const seen = e.seen_as || {};
  const ip = seen.ip || e.ip;
  state.lastIp = ip;

  const measured = (seen.country || '').toLowerCase();
  const claimed = (e.country || '').toLowerCase();
  const mName = (state.countries.find((x) => x.code === measured) || {}).name;
  const cName = (state.countries.find((x) => x.code === claimed) || {}).name;
  const place = mName || cName || '—';

  setExit(ip, place, true);
  setStatus('CONNECTED', 'on', place + (e.cityName ? `, ${e.cityName}` : ''),
            e.host || '');
  $('detIn').textContent = `${e.ip || '—'}:443`;
  $('detOut').textContent = ip || '—';

  // The label and the measurement disagree often enough to be worth saying.
  setHint(measured && claimed && measured !== claimed
    ? `This server is sold as ${cName || claimed.toUpperCase()}, but its traffic really comes out in ${place}. What websites see is the second one.`
    : (e.unconfirmed
        ? 'Connected, but the independent check did not answer. If pages load, it is fine.'
        : 'Everything on this PC now goes through this connection.'));
  render();
};

window.onFailed = (err) => {
  state.mode = 'fail';
  setExit(state.lastIp, 'Not connected', false);
  setStatus('NOT CONNECTED', 'fail', 'Could not connect', '');
  const say = {
    'all-refused': 'No server accepted the connection just now. That is normal — wait a minute and try again.',
    'no-servers': 'No servers for that country. Pick another, or use Fastest available.',
    'no-credentials': 'The username and password file is missing, so there is nothing to sign in with.',
    'cancelled': 'Cancelled.',
    'did-not-start': 'The connection opened but did not come up. Try once more.',
  };
  setHint(say[err.kind] || 'Could not connect. Try once more.', err.kind !== 'cancelled');
  render();
};

window.onDisconnected = (info) => {
  state.mode = 'off';
  setExit(state.lastIp, 'Not connected', false);
  setStatus('DISCONNECTED', 'off', '', '');
  setHint(info && info.minutes
    ? `Windows is back to normal. Routed for ${info.minutes} minute${info.minutes === 1 ? '' : 's'}.`
    : 'Windows is back to normal.');
  render();
};

/* -------------------------------------------------------------- actions */

async function onAct() {
  if (state.mode === 'busy') {
    await window.pywebview.api.cancel();
    return;
  }
  if (state.mode === 'on') {
    setStatus('DISCONNECTING', 'busy', 'Putting Windows back', '');
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

/* ---------------------------------------------------------------- start */

async function boot() {
  const info = await window.pywebview.api.boot();
  state.countries = info.countries || [];
  state.picked = info.picked || 'auto';
  state.fuse = new Fuse(state.countries, {
    keys: ['name', 'code', 'alias'], threshold: 0.4, ignoreLocation: true,
  });

  if (!info.hasCredentials) {
    setStatus('NOT SET UP', 'fail', 'Missing sign-in details', '');
    setHint('The file with the username and password is not next to the app, so it cannot connect.', true);
    $('act').disabled = true;
    $('pick').disabled = true;
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
    setExit(null, 'Not connected', false);
    setStatus('DISCONNECTED', 'off', '', '');
    if (!info.recovered) {
      setHint(`${state.countries.length} countries ready.`);
    }
    render();
  }
}

/* --------------------------------------------------------------- wiring */

$('act').addEventListener('click', onAct);
$('pick').addEventListener('click', openPicker);
$('pickerClose').addEventListener('click', closePicker);
$('search').addEventListener('input', (e) => { state.cursor = 0; drawList(e.target.value); });

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

window.addEventListener('pywebviewready', boot);
if (window.pywebview && window.pywebview.api) boot();

/* Read by the automated check, which asserts on real hit-testing rather than
   on handlers existing — the previous suite called .click() directly, which
   bypasses hit-testing entirely and so happily passed on a window where
   every control was covered. */
window.__probe = () => ({
  countries: state.countries.length,
  mode: state.mode,
  picked: state.picked,
  pickerOpen: $('picker').open,
});
