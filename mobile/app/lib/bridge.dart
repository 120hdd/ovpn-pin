/// The shim that lets `app/ui` run unchanged on a phone.
///
/// The page was written against pywebview, and calls forty-three methods on
/// `window.pywebview.api` - each returning a Promise - while listening for ten
/// events pushed at it as `window.onConnected(...)` and friends. None of that
/// is changed here. What is provided instead is the same two shapes, backed by
/// a Flutter channel rather than by a Python object:
///
///   page  ->  window.pywebview.api.connect('de')
///               -> a JSON message on the Relay channel
///                 -> MethodChannel -> Kotlin -> the Go core
///                   -> window.__relayReply(id, ok, value)
///                     -> the Promise settles
///
///   core  ->  window.onConnected({...})     pushed, never asked for
///
/// A Proxy stands in for the api object so that every method the page might
/// call exists, including ones the phone has no answer for. Those reject with
/// a sentence rather than throwing `undefined is not a function`, which is the
/// difference between a screen that explains itself and one that goes blank.
library;

const String bridgeScript = r'''
(function () {
  if (window.__relayBridge) return;
  window.__relayBridge = true;

  var pending = Object.create(null);
  var seq = 0;

  function call(name, args) {
    var id = ++seq;
    return new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject };
      try {
        Relay.postMessage(JSON.stringify({ id: id, name: name, args: args }));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  // Called from Dart. Kept off the api object so a page that enumerates the
  // api does not find it.
  window.__relayReply = function (id, ok, payload) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) p.resolve(payload);
    else p.reject(new Error(String(payload)));
  };

  // Every name resolves to a callable. The page asks for whatever it asks
  // for; what it cannot have comes back as a rejected promise carrying the
  // reason, which is what its own error paths already know how to show.
  var api = new Proxy({}, {
    get: function (_, name) {
      if (typeof name !== 'string') return undefined;
      return function () {
        return call(name, Array.prototype.slice.call(arguments));
      };
    },
    has: function () { return true; },
  });

  window.pywebview = { api: api };

  // pywebview fires this once its api is attached, and the page waits for it
  // before booting. Dispatched on the next tick so that a listener registered
  // later in the same script still sees it.
  setTimeout(function () {
    window.dispatchEvent(new Event('pywebviewready'));
  }, 0);
})();
''';

/// Pushed from Dart when the core has something to say. The page defines
/// these itself; this only calls them, and only if they exist - a build of
/// the page that has not defined one yet should not throw on every tick.
String emitScript(String event, String jsonPayload) => '''
(function () {
  var fn = window.on$event;
  if (typeof fn === 'function') fn($jsonPayload);
})();
''';
