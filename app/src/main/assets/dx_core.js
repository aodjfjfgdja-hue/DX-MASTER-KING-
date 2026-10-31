/* DX MASTER core: runs in the Messenger page AND inside the ZIP extension popup */
(function () {
  var NATIVE = window.DXNative;
  var inPopup = location.hostname === 'dxext.local';
  function parse(s) { try { return JSON.parse(s); } catch (e) { return null; } }

  /* ---------- 1) Mic booster hook (page only) ---------- */
  if (!inPopup) {
    window.__dxNodes = window.__dxNodes || [];
    if (window.__dxGain == null) window.__dxGain = 1;
    window.__dxSet = function (v) {
      window.__dxGain = v;
      window.__dxNodes.forEach(function (n) { try { n.gain.value = v; } catch (e) {} });
    };
    if (!window.__dxHooked && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      window.__dxHooked = true;
      var orig = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      navigator.mediaDevices.getUserMedia = async function (c) {
        var s = await orig(c);
        try {
          if (!c || !c.audio || !s.getAudioTracks().length) return s;
          var Ctx = window.AudioContext || window.webkitAudioContext;
          var ctx = new Ctx();
          try { await ctx.resume(); } catch (e) {}
          var src = ctx.createMediaStreamSource(new MediaStream(s.getAudioTracks()));
          var g = ctx.createGain();
          g.gain.value = window.__dxGain;
          var lim = ctx.createDynamicsCompressor();
          lim.threshold.value = -6; lim.knee.value = 3; lim.ratio.value = 20;
          lim.attack.value = 0.002; lim.release.value = 0.08;
          var dest = ctx.createMediaStreamDestination();
          src.connect(g); g.connect(lim); lim.connect(dest);
          window.__dxNodes.push(g);
          var ot = dest.stream.getAudioTracks()[0];
          var os = ot.stop.bind(ot);
          ot.stop = function () {
            os();
            s.getAudioTracks().forEach(function (t) { t.stop(); });
            try { ctx.close(); } catch (e) {}
            var i = window.__dxNodes.indexOf(g);
            if (i >= 0) window.__dxNodes.splice(i, 1);
          };
          var out = new MediaStream();
          out.addTrack(ot);
          s.getVideoTracks().forEach(function (t) { out.addTrack(t); });
          return out;
        } catch (e) { return s; }
      };
    }

    /* CSS helper for extension content scripts */
    window.__dxCss = function (name, text) {
      window.__dxCssDone = window.__dxCssDone || {};
      if (window.__dxCssDone[name]) return;
      window.__dxCssDone[name] = 1;
      try {
        var sh = new CSSStyleSheet();
        sh.replaceSync(text);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sh]);
      } catch (e) {
        var el = document.createElement('style');
        el.textContent = text;
        (document.head || document.documentElement).appendChild(el);
      }
    };
  }

  /* ---------- 2) Small chrome.* shim for the loaded ZIP extension ---------- */
  var listeners = [], bgListeners = [], cbs = {}, seq = 0, storeL = [];

  function noop() {
    var f = function () {
      var cb = arguments[arguments.length - 1];
      if (typeof cb === 'function') { try { cb(); } catch (e) {} }
    };
    return new Proxy(f, {
      get: function (_, k) {
        if (k === 'then') return undefined;
        if (k === 'addListener' || k === 'removeListener') return function () {};
        if (k === 'hasListener') return function () { return false; };
        return noop();
      }
    });
  }

  function send(msg, cb) {
    var p;
    if (typeof cb !== 'function') p = new Promise(function (res) { cb = res; });
    var user = cb, called = false, id = ++seq;
    cb = function (r) {
      if (called) return;
      called = true;
      delete cbs[id];
      try { user(r); } catch (e) {}
    };
    cbs[id] = cb;
    NATIVE.send(JSON.stringify({ id: id, msg: msg }));
    /* the popup can also talk to the extension background script living in the same context */
    if (inPopup && bgListeners.length) {
      var sender = { id: 'dxmaster-ext', url: location.href };
      bgListeners.slice().forEach(function (f) { try { f(msg, sender, cb); } catch (e) {} });
    }
    return p;
  }

  function later(cb, val) {
    if (typeof cb === 'function') { setTimeout(function () { cb(val); }, 0); return undefined; }
    return Promise.resolve(val);
  }

  function area() {
    return {
      get: function (keys, cb) {
        var all = parse(NATIVE.storageGet()) || {}, out = {};
        if (keys == null) out = all;
        else if (typeof keys === 'string') { if (keys in all) out[keys] = all[keys]; }
        else if (Array.isArray(keys)) keys.forEach(function (k) { if (k in all) out[k] = all[k]; });
        else Object.keys(keys).forEach(function (k) { out[k] = (k in all) ? all[k] : keys[k]; });
        return later(cb, out);
      },
      set: function (items, cb) { NATIVE.storageSet(JSON.stringify(items || {})); return later(cb); },
      remove: function (keys, cb) { NATIVE.storageRemove(JSON.stringify([].concat(keys))); return later(cb); },
      clear: function (cb) {
        NATIVE.storageRemove(JSON.stringify(Object.keys(parse(NATIVE.storageGet()) || {})));
        return later(cb);
      }
    };
  }

  var hasExt = false;
  try { hasExt = !!(NATIVE && NATIVE.hasExt()); } catch (e) {}
  if (!hasExt || (window.chrome && window.chrome.__dx)) return;

  /* Extension scripts that add <script src="https://dxext.local/..."> to the page:
     the page CSP would block them, so we run the file through the native side instead. */
  if (!inPopup && !window.__dxPatched) {
    window.__dxPatched = true;
    var PFX = 'https://dxext.local/';
    var isExtScript = function (n) {
      return !!(n && n.tagName === 'SCRIPT' && typeof n.src === 'string' && n.src.indexOf(PFX) === 0);
    };
    var runExt = function (n) {
      var ok = false;
      try { ok = NATIVE.runFile(decodeURIComponent(n.src.substring(PFX.length).split('?')[0])); } catch (e) {}
      setTimeout(function () { try { n.dispatchEvent(new Event(ok ? 'load' : 'error')); } catch (e) {} }, 0);
    };
    ['appendChild', 'insertBefore'].forEach(function (m) {
      var o = Node.prototype[m];
      Node.prototype[m] = function (a, b) {
        if (isExtScript(a)) { runExt(a); return a; }
        return o.call(this, a, b);
      };
    });
    var oa = Element.prototype.append;
    Element.prototype.append = function () {
      var rest = [];
      for (var i = 0; i < arguments.length; i++) {
        if (isExtScript(arguments[i])) runExt(arguments[i]); else rest.push(arguments[i]);
      }
      return oa.apply(this, rest);
    };
  }

  window.__dxRecv = function (json) {
    var d = parse(json);
    if (!d) return;
    var done = false;
    var sendResponse = function (resp) {
      if (done) return;
      done = true;
      NATIVE.reply(JSON.stringify({ id: d.id, resp: resp === undefined ? null : resp }));
    };
    var sender = { id: 'dxmaster-ext', url: location.href, tab: { id: 1 } };
    listeners.slice().forEach(function (f) { try { f(d.msg, sender, sendResponse); } catch (e) {} });
  };
  window.__dxReply = function (json) {
    var d = parse(json);
    if (!d) return;
    var cb = cbs[d.id];
    delete cbs[d.id];
    if (cb) { try { cb(d.resp); } catch (e) {} }
  };
  window.__dxStorage = function (json) {
    var ch = parse(json);
    if (!ch) return;
    storeL.slice().forEach(function (f) { try { f(ch, 'local'); } catch (e) {} });
  };

  var ch = {
    __dx: true,
    runtime: {
      id: 'dxmaster-ext',
      lastError: undefined,
      getURL: function (p) { return 'https://dxext.local/' + String(p).replace(/^\.?\//, ''); },
      getManifest: function () { return parse(NATIVE.manifest()) || {}; },
      sendMessage: function (a, b, c) {
        if (typeof c === 'function') return send(b, c);
        return send(a, typeof b === 'function' ? b : undefined);
      },
      onMessage: {
        addListener: function (f) { listeners.push(f); if (window.__dxInBG) bgListeners.push(f); },
        removeListener: function (f) {
          var i = listeners.indexOf(f); if (i >= 0) listeners.splice(i, 1);
          i = bgListeners.indexOf(f); if (i >= 0) bgListeners.splice(i, 1);
        },
        hasListener: function (f) { return listeners.indexOf(f) >= 0; }
      },
      onInstalled: { addListener: function () {} },
      connect: function () {
        return {
          postMessage: function () {}, disconnect: function () {},
          onMessage: { addListener: function () {} }, onDisconnect: { addListener: function () {} }
        };
      }
    },
    tabs: {
      query: function (q, cb) {
        return later(cb, [{ id: 1, active: true, windowId: 1, url: 'https://www.facebook.com/messages/' }]);
      },
      sendMessage: function (id, msg, opt, cb) {
        if (typeof opt === 'function') cb = opt;
        return send(msg, cb);
      },
      onUpdated: { addListener: function () {} }
    },
    storage: {
      local: area(),
      sync: area(),
      onChanged: {
        addListener: function (f) { storeL.push(f); },
        removeListener: function (f) { var i = storeL.indexOf(f); if (i >= 0) storeL.splice(i, 1); }
      }
    },
    i18n: {
      getMessage: function (k) { return k; },
      getUILanguage: function () { return 'en'; }
    }
  };

  if (inPopup) {
    window.chrome = new Proxy(ch, {
      get: function (t, k) { return (k in t) ? t[k] : (typeof k === 'string' ? noop() : undefined); }
    });
    window.browser = window.chrome;
  } else {
    var c = window.chrome = window.chrome || {};
    Object.keys(ch).forEach(function (k) { try { c[k] = ch[k]; } catch (e) {} });
  }
})();
