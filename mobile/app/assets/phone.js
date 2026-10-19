/* The handful of sentences the desktop means and a phone does not.
 *
 * Loaded after app.js, and it changes behaviour by wrapping rather than by
 * editing: each handler here calls the original and then corrects the one
 * thing that was about the wrong machine. app/ui stays the original, and a
 * rewrite of app.js upstream keeps working here as long as the handler still
 * exists - and when it does not, this notices rather than silently doing
 * nothing.
 */

(function () {
  'use strict';

  /**
   * Replace one of the page's window.onX handlers, keeping the old one.
   *
   * Refuses rather than guesses when the handler is not there: a wrapper
   * installed over nothing is a correction that stops arriving, and finding
   * that out from a stale sentence on screen is worse than finding it out
   * from the console.
   */
  function wrap(name, after) {
    var original = window[name];
    if (typeof original !== 'function') {
      console.warn('phone.js: no ' + name + ' to wrap - app.js has moved on');
      return;
    }
    window[name] = function () {
      var out = original.apply(this, arguments);
      try {
        after.apply(this, arguments);
      } catch (e) {
        console.error('phone.js: ' + name, e);
      }
      return out;
    };
  }

  function hint(text) {
    var el = document.getElementById('hint');
    if (el) {
      el.textContent = text;
      el.classList.remove('is-bad');
    }
  }

  /**
   * Change what a line says without changing what is inside it.
   *
   * `.pane__why` carries `<span id="routeAddr">`, and app.js writes the
   * listening address into it on every paint of the settings sheet. Setting
   * textContent takes that span out of the document, and the next paint
   * throws on a null - so the words are replaced around the elements rather
   * than over them.
   *
   * What is kept is hidden. These nodes are about a machine this is not; they
   * only have to exist, so that the paint that writes into them finishes.
   */
  function resay(el, text) {
    var kept = Array.prototype.slice.call(el.children);
    el.textContent = text;
    kept.forEach(function (node) {
      node.style.display = 'none';
      el.appendChild(node);
    });
  }

  // "Windows is back to normal." There is no system proxy on a phone: the tun
  // carries everything and nothing about the machine is left altered, so the
  // sentence does not just miss - it describes an undoing that never had a
  // doing. What is true here is that the tunnel is down.
  wrap('onDisconnected', function (info) {
    hint(info && info.minutes
      ? 'Tunnel closed. Carried for ' + info.minutes +
        (info.minutes === 1 ? ' minute.' : ' minutes.')
      : 'Tunnel closed.');
  });

  // The one thing about this tunnel a person should not have to discover:
  // CONNECT carries a stream, so UDP does not cross it. QUIC falls back to
  // TCP when it gets nothing, which is what makes the web work here - but a
  // game or a call that wants UDP will not.
  wrap('onConnected', function () {
    hint('DNS is re-asked over HTTPS. UDP does not cross this tunnel.');
  });

  /* ------------------------------------------------------- pinning here */

  /* "Through a proxy" and a box for 127.0.0.1:10808.

     On a phone there is no local port and nothing listening on one. The
     choice is still real - a lookup can go through whatever is carrying
     traffic, or straight out - so the row is reworded rather than hidden, and
     the port box goes, because a field that cannot be filled in usefully is
     worse than no field. */
  function relabelRoute() {
    var seg = document.getElementById('pinRoute');
    if (!seg) return false;

    var opts = seg.querySelectorAll('.seg__opt');
    for (var i = 0; i < opts.length; i++) {
      if (opts[i].dataset.route === 'proxy') opts[i].textContent = 'Through the tunnel';
      if (opts[i].dataset.route === 'direct') opts[i].textContent = 'Straight out';
    }

    var port = document.getElementById('pinPortWrap');
    if (port) port.hidden = true;

    var cap = seg.closest('.flow__step');
    var note = cap && cap.querySelector('#pinRouteSaid');
    if (note && !note.textContent) {
      note.textContent =
        'Through the tunnel asks the resolver from wherever this phone is '
        + 'already coming out. Straight out asks from here.';
    }
    return true;
  }

  /* The desktop names a port in this sentence. There is not one here. */
  wrap('onPin', function (p) {
    if (!p || p.phase !== 'starting') return;
    var el = document.getElementById('pinSaid');
    if (!el) return;
    el.textContent = p.route === 'proxy'
      ? 'Asking through the tunnel…'
      : 'Asking directly…';
  });

  /* --------------------------------------------------------- the sweep */

  /* Nothing should be asking about a pane that is not on the screen.
   *
   * Sweeping runs OpenVPN against every exit and times the leg it takes, which
   * needs a machine that can run OpenVPN - so the bridge refuses it with a
   * sentence saying exactly that, and phone.css takes the pane off the screen.
   * What is left is app.js still asking: it reaches for the plan on the way
   * into the settings sheet, after every pin run, and from five other places,
   * and each refusal arrives as an unhandled rejection in the console.
   *
   * Replaced rather than caught, because there is genuinely nothing to paint.
   * A Promise, because several of those callers await it.
   */
  if (typeof window.refreshSweep === 'function') {
    window.refreshSweep = function () { return Promise.resolve(); };
  } else {
    console.warn('phone.js: no refreshSweep to silence - app.js has moved on');
  }

  /* ------------------------------------------------- the local network */

  /* "Route all of Windows" is the one row in Settings that cannot mean
     anything here: there is no system proxy on a phone, the tun carries
     everything, and the switch would be a switch for nothing.

     The phone has a question in exactly that shape though, and it is one
     people hit within a minute of connecting: the tunnel takes 0.0.0.0/0,
     which is the printer and the NAS and the router's own page as well as
     the internet. So the row is reused rather than hidden - same place, same
     shape, different question. */
  function replaceProxyRow() {
    var toggle = document.getElementById('sysProxy');
    if (!toggle) return false;

    var row = toggle.closest('.pane');
    if (!row) return false;

    var title = row.querySelector('.pane__title');
    var why = row.querySelector('.pane__why');
    if (!title || !why) return false;

    resay(title, 'Reach the local network');
    resay(why,
      'Leaves the printer, the router and anything else on this wifi outside '
      + 'the tunnel. They are not reachable from the internet either way. '
      + 'Off, everything goes through - including this phone’s own '
      + 'debugger.');

    // A fresh node, so every listener app.js attached to the old switch goes
    // with it. Adding a second listener would leave the first one still
    // calling setSystemProxy, which on a phone answers with a refusal.
    // And the old one is put out of sight rather than out of the document.
    // paintPrefs opens with
    // `$('sysProxy').setAttribute('aria-checked', ...)`, so a switch that has
    // been removed is a TypeError on the first line of the paint - and the
    // port, the folder, the counts, the paths and the credential all come
    // after it. The sheet opens blank and nothing on screen says why. It cost
    // an afternoon once; it is one line to never pay again.
    var fresh = toggle.cloneNode(true);
    fresh.id = 'allowLan';
    toggle.parentNode.insertBefore(fresh, toggle);
    toggle.style.display = 'none';

    function paint(on) {
      fresh.setAttribute('aria-checked', String(!!on));
    }

    window.pywebview.api.info().then(function (info) {
      paint(info && info.allowLan !== false);
    }).catch(function () { paint(true); });

    fresh.addEventListener('click', function () {
      var next = fresh.getAttribute('aria-checked') !== 'true';
      paint(next);
      window.pywebview.api.setAllowLan(next).then(function (r) {
        if (r && r.needsReconnect) {
          hint('Reconnect for that to take effect - Android fixes the routes '
             + 'when the tunnel opens.');
        }
      }).catch(function (e) {
        paint(!next);
        hint('That could not be saved: ' + e.message, true);
      });
    });
    return true;
  }

  /* The settings screen is built once, but not necessarily before this file
     runs. Try now, and if the row is not there yet, watch for it - once. */
  var wanted = [replaceProxyRow, relabelRoute];
  var todo = wanted.filter(function (f) { return !f(); });
  if (todo.length) {
    var seen = new MutationObserver(function () {
      todo = todo.filter(function (f) { return !f(); });
      if (!todo.length) seen.disconnect();
    });
    seen.observe(document.body, { childList: true, subtree: true });
    // A page that never grows these should not leave an observer running for
    // the life of the app.
    setTimeout(function () { seen.disconnect(); }, 30000);
  }
})();
