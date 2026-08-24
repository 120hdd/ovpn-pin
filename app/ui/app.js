/* The page holds no truth of its own.

   Every fact on screen came from the engine, and the only thing this file
   decides is how to say it. That matters more than usual here: the whole
   product is a claim about where your traffic comes out, made to someone
   who cannot check it any other way. A number this file invented, or kept
   showing after it stopped being true, is the one unforgivable bug.

   So: no optimistic updates, no cached "probably still connected". Every
   state on screen is one the backend just confirmed. */

const $ = (id) => document.getElementById(id);

const FA_DIGITS = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];

/* Persian numerals for anything the app is saying, Latin for anything the
   network reported. Done here rather than with the font's ss20 feature so
   it can never accidentally reach an address. */
const fa = (n) => String(n).replace(/\d/g, (d) => FA_DIGITS[+d]);

const state = {
  countries: [],
  picked: 'auto',
  mode: 'off',        // off | busy | on | broken
  ready: false,
};

/* ------------------------------------------------------------- rendering */

let lastPlace = null;

function setPlace(text, kind) {
  const el = $('place');
  el.dataset.state = kind;
  if (text === lastPlace) return;
  lastPlace = text;
  el.textContent = text;
  el.classList.remove('is-changing');
  void el.offsetWidth;            // restart the animation
  el.classList.add('is-changing');
}

function setFacts({ ip, city, ms }) {
  $('factIp').textContent = ip || '—';
  $('factCity').textContent = city || '—';
  $('factMs').textContent = ms || '—';
}

function setNote(text, bad) {
  const el = $('note');
  el.textContent = text || '';
  el.classList.toggle('is-bad', !!bad);
}

function setMarquee(text) {
  const host = $('where');
  let m = document.querySelector('.marquee');
  if (!text) { if (m) m.remove(); return; }
  if (!m) {
    m = document.createElement('div');
    m.className = 'marquee';
    m.innerHTML = '<span></span>';
    host.appendChild(m);
  }
  // Doubled, so the loop has something to scroll into.
  const unit = `${text}   ·   `;
  m.querySelector('span').textContent = unit.repeat(8);
}

function render() {
  const act = $('act');
  const wire = $('wire');

  if (state.mode === 'busy') {
    act.dataset.mode = 'busy';
    $('actLabel').textContent = 'در حال اتصال';
    $('pick').disabled = true;
    wire.classList.remove('is-live', 'is-bad');
  } else if (state.mode === 'on') {
    act.dataset.mode = 'on';
    $('actLabel').textContent = 'قطع اتصال';
    $('pick').disabled = false;
    wire.classList.add('is-live');
    wire.classList.remove('is-bad');
  } else {
    act.dataset.mode = 'off';
    $('actLabel').textContent = 'اتصال';
    $('pick').disabled = false;
    wire.classList.remove('is-live', 'is-bad');
  }

  const c = state.countries.find((x) => x.code === state.picked);
  $('pickValue').textContent = state.picked === 'auto' ? 'خودکار' : (c ? c.name : state.picked);
}

/* ------------------------------------------------------------- the sheet */

function openSheet() {
  $('sheet').hidden = false;
  $('search').value = '';
  drawList('');
  setTimeout(() => $('search').focus(), 40);
}

function closeSheet() { $('sheet').hidden = true; }

function drawList(query) {
  const list = $('list');
  const q = (query || '').trim();
  list.innerHTML = '';

  if (!q) {
    const auto = document.createElement('button');
    auto.className = 'row row--auto' + (state.picked === 'auto' ? ' is-picked' : '');
    auto.innerHTML =
      '<span class="row__name">خودکار'
      + '<div class="row__sub">سریع‌ترین سروری که همان لحظه جواب بدهد</div></span>';
    auto.onclick = () => { state.picked = 'auto'; closeSheet(); render(); };
    list.appendChild(auto);
  }

  const matches = state.countries.filter(
    (c) => !q || c.name.includes(q) || c.code.includes(q.toLowerCase()));

  if (!matches.length) {
    const e = document.createElement('div');
    e.className = 'empty';
    e.textContent = 'کشوری با این نام پیدا نشد.';
    list.appendChild(e);
    return;
  }

  for (const c of matches) {
    const row = document.createElement('button');
    row.className = 'row' + (state.picked === c.code ? ' is-picked' : '');
    const time = c.best != null ? `${c.best.toFixed(1)}s` : '';
    row.innerHTML =
      `<span class="row__name">${c.name}</span>`
      + `<span class="row__code">${c.code.toUpperCase()}</span>`
      + `<span class="row__time">${time}</span>`;
    row.onclick = () => { state.picked = c.code; closeSheet(); render(); };
    list.appendChild(row);
  }
}

/* ------------------------------------------- what the backend tells us */

window.onProgress = (p) => {
  if (p.phase === 'probing') {
    setPlace('…', 'busy');
    const asked = fa(p.asked || 0), total = fa(p.total || 0);
    setNote(`از ${total} سرور، ${asked} تا پرسیده شد.\nهر بار فقط تعدادی از آن‌ها جواب می‌دهند، پس این کمی طول می‌کشد.`);
  } else if (p.phase === 'starting') {
    setNote('سروری پیدا شد. در حال برقراری اتصال…');
  } else if (p.phase === 'routing') {
    setNote('در حال تنظیم ویندوز روی این اتصال…');
  } else if (p.phase === 'verifying') {
    setNote('در حال بررسی اینکه واقعاً کار می‌کند…');
  }
};

window.onConnected = (status) => {
  state.mode = 'on';
  const e = status.exit || {};
  const seen = e.seen_as || {};
  const code = (seen.country || e.country || '').toLowerCase();
  const c = state.countries.find((x) => x.code === code);
  setPlace(c ? c.name : (code || '').toUpperCase() || 'ناشناخته', 'on');
  setFacts({
    ip: seen.ip || e.ip,
    city: (seen.colo || e.city || '').toUpperCase(),
    ms: e.answered != null ? `${e.answered.toFixed(2)}s` : '—',
  });
  $('whereLabel').textContent = 'اینترنت شما از اینجا بیرون می‌رود';
  // Three different things to say, and the difference between them matters
  // more here than anywhere else in the app.
  //
  // The middle case is the interesting one. A server labelled mk-skp really
  // came out in Croatia, and one labelled eg-cai came out in France — the
  // provider sells locations it does not physically have. The big word is
  // always the measured country, never the label, because the label is
  // marketing and the measurement is what a website will actually see. But
  // silently showing a country nobody picked looks like a bug, so it gets a
  // sentence.
  const claimed = (e.country || '').toLowerCase();
  const measured = (seen.country || '').toLowerCase();
  const claimedName = (state.countries.find((x) => x.code === claimed) || {}).name;

  if (e.unconfirmed) {
    setNote('اتصال برقرار است، ولی تأیید مستقلش این بار جواب نداد.\nاگر سایت‌ها باز می‌شوند، مشکلی نیست.');
  } else if (measured && claimed && measured !== claimed) {
    setNote(`این سرور با نام ${claimedName || claimed.toUpperCase()} فروخته می‌شود، ولی ترافیک واقعاً از اینجا بیرون می‌رود.\nآنچه سایت‌ها می‌بینند همین است.`);
  } else {
    setNote('همه‌ی برنامه‌ها روی این ویندوز از این مسیر می‌روند.\nبرای برگشتن به حالت عادی، «قطع اتصال» را بزنید.');
  }
  setMarquee(`${e.host || ''}  ·  ${seen.ip || e.ip || ''}`);
  render();
};

window.onFailed = (err) => {
  state.mode = 'off';
  setPlace('نشد', 'off');
  setFacts({});
  setMarquee(null);
  $('wire').classList.add('is-bad');

  const messages = {
    'all-refused': 'هیچ‌کدام از سرورها همین حالا جواب ندادند.\nاین عادی است — یکی دو دقیقه صبر کنید و دوباره بزنید.',
    'no-servers': 'برای این کشور سروری موجود نیست.\nکشور دیگری انتخاب کنید یا روی «خودکار» بگذارید.',
    'no-credentials': 'نام کاربری و رمز پیدا نشد.\nفایل .ovpn-auth کنار برنامه باید باشد.',
    'cancelled': 'لغو شد.',
    'did-not-start': 'اتصال برقرار شد ولی بالا نیامد.\nیک بار دیگر امتحان کنید.',
  };
  setNote(messages[err.kind] || 'اتصال برقرار نشد.\nیک بار دیگر امتحان کنید.', true);
  render();
};

/* ------------------------------------------------------------- actions */

async function onAct() {
  if (state.mode === 'busy') { await window.pywebview.api.cancel(); return; }

  if (state.mode === 'on') {
    setNote('در حال قطع کردن و برگرداندن تنظیمات ویندوز…');
    const r = await window.pywebview.api.disconnect();
    state.mode = 'off';
    setPlace('مستقیم', 'off');
    setFacts({});
    setMarquee(null);
    $('whereLabel').textContent = 'اینترنت شما از خط خودتان می‌رود';
    setNote('تنظیمات ویندوز به حالت قبل برگشت.');
    render();
    return;
  }

  state.mode = 'busy';
  render();
  setNote('در حال گشتن دنبال سرور…');
  const r = await window.pywebview.api.connect(state.picked);
  if (!r.ok) { state.mode = 'off'; render(); }
}

/* --------------------------------------------------------------- start */

async function boot() {
  const info = await window.pywebview.api.boot();
  state.countries = info.countries || [];

  if (!info.hasCredentials) {
    setPlace('—', 'idle');
    setNote('فایل نام کاربری و رمز پیدا نشد. بدون آن نمی‌شود وصل شد.', true);
    $('act').disabled = true;
    return;
  }

  if (info.recovered) {
    const t = $('toast');
    t.hidden = false;
    t.textContent = 'دفعه‌ی قبل برنامه درست بسته نشده بود. تنظیمات ویندوز به حالت عادی برگشت.';
    setTimeout(() => { t.hidden = true; }, 7000);
  }

  const s = info.status || {};
  if (s.state === 'on') {
    window.onConnected(s);
  } else {
    state.mode = 'off';
    setPlace('مستقیم', 'off');
    $('whereLabel').textContent = 'اینترنت شما از خط خودتان می‌رود';
    setNote(`${fa(state.countries.length)} کشور آماده است.\nیکی را انتخاب کنید یا همان «خودکار» را بزنید.`);
    render();
  }
  state.ready = true;
}

$('act').onclick = onAct;
$('pick').onclick = openSheet;
$('sheetClose').onclick = closeSheet;
$('search').oninput = (e) => drawList(e.target.value);
$('min').onclick = () => window.pywebview.api.minimise();
$('quit').onclick = () => window.pywebview.api.close();
document.onkeydown = (e) => {
  if (e.key === 'Escape' && !$('sheet').hidden) closeSheet();
};

window.addEventListener('pywebviewready', boot);
if (window.pywebview && window.pywebview.api) boot();

/* A hook the automated check uses to read state without guessing. */
window.__dbg = () => state.countries.length;
