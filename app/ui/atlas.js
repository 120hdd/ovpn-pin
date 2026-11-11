/* The world behind the home screen (three.js, plain script: the window loads
   over file://, where modules are refused - see three.min.js).

   It tells the connection as a journey:
     off    straight down onto the country your own address is in, filled with
            its flag; every other country is only its border, in faint neon
     busy   rings of signal ripple out of it while the servers are asked, and
            the camera eases back
     on     the camera pulls out until the planet is in view, the signal runs
            an arc over it to the country the traffic really leaves from, and
            the camera comes down onto that one, which fills with its flag
            from where the signal landed; points run the arc at the speed of
            the traffic
     fail   the signal stops halfway along its arc, flickers red, and the
            camera goes home

   Only one country is ever filled; the neighbours stay outlines. Three
   tilted neon orbits ride round the planet and show only once the camera is
   far enough out to see them.

   What it is told: the state (it watches data-conn), the home country (from
   the address Cloudflare saw before anything of ours was in the way), the
   exit country and the pick. The outlines come from world-data.js
   (Natural Earth, public domain; app/make_world.py). */
(function () {
  'use strict';

  const D2R = Math.PI / 180;
  const FOV = 36;
  const MAX_DPR = 1.75;
  const IDLE_MS = 1000 / 30 - 4;                  /* nothing travelling: 30 fps */
  const NEON = { peri: 0x8AA6FF, aqua: 0x62E6F2, orchid: 0xCF9BFF, pulse: 0x1ED760 };
  const INK = { peri: 0x4C63B8, aqua: 0x1E8C98, orchid: 0x8A54C4, pulse: 0x139A48 };   /* the same, for a light page */

  const dirOf = (lon, lat) => {
    const T = window.THREE;
    return new T.Vector3(Math.cos(lat * D2R) * Math.sin(lon * D2R), Math.sin(lat * D2R),
                         Math.cos(lat * D2R) * Math.cos(lon * D2R));
  };

  window.Atlas = (function () {
    let T = null, W = null;
    let canvas = null, renderer = null, scene = null, camera = null;
    let globeMat = null, borders = null, orbits = [], ripples = [];
    let outlineO = null, outlineD = null, arc = null;
    let started = false, ticking = false, isCalm = () => false, drawnAt = 0, moving = 0;
    let light = false;
    let state = 'off', homeCode = null, liveCode = null, pickedCode = null;
    let originCode = null, destCode = null, busyTl = null, journey = null;
    const view = { dir: null, dist: 5, shift: 0 };
    const lean = { x: 0, y: 0 };
    const S = { t: 0, orbitSpeed: 1, blink: 0.5, traffic: 0, trafficSpeed: 0.3 };

    /* ------------------------------------------------------------ geography */

    const has = (code) => !!(code && W && W.by[code]);
    const middle = (code) => dirOf(...(W.mid[code] || [0, 0]));
    /* how far out to stand to see the whole country from straight above,
       with its neighbours round it */
    function heightFor(code) {
      const b = W.box[code], lat = (b[1] + b[3]) / 2;
      const halfW = (b[2] - b[0]) / 2 * Math.cos(lat * D2R) * D2R, halfH = (b[3] - b[1]) / 2 * D2R;
      const t = Math.tan(FOV / 2 * D2R), aspect = camera.aspect;
      return 1 + Math.min(Math.max(halfH / t, halfW / (t * aspect)) * 1.6 + 0.12, 3.2);
    }

    /* A country's fill: its outline painted into a texture laid over its own
       box, with the flag stretched over the same box. Both read with the
       same coordinates, so the flag never wanders off the land. */
    const cache = new Map();
    function countryTextures(code) {
      if (cache.has(code)) return cache.get(code);
      const b = W.box[code];
      const cw = 1024, ch = Math.max(64, Math.min(1024, Math.round(1024 * (b[3] - b[1]) / Math.max(0.01, b[2] - b[0]))));
      const cv = document.createElement('canvas'); cv.width = cw; cv.height = ch;
      const g = cv.getContext('2d');
      g.fillStyle = '#fff';
      for (const i of W.by[code]) {
        const r = W.rings[i];
        g.beginPath();
        for (let k = 0; k < r.length; k += 2) {
          const x = (r[k] - b[0]) / (b[2] - b[0]) * cw, y = (b[3] - r[k + 1]) / (b[3] - b[1]) * ch;
          if (k) g.lineTo(x, y); else g.moveTo(x, y);
        }
        g.closePath(); g.fill();
      }
      const mask = new T.CanvasTexture(cv);
      const flag = new T.Texture();
      const entry = { mask, flag, ready: false };
      const img = new Image();
      img.onload = () => {
        /* 2048 across: the camera comes down close enough over a small country
           that 1024 showed its pixels. The colours go in and out untouched -
           this shader writes what it reads, so marking the flag sRGB only had
           it decoded to linear and never encoded back, a flag a shade darker
           than its own file. */
        const f = document.createElement('canvas'); f.width = 2048; f.height = 1536;
        f.getContext('2d').drawImage(img, 0, 0, 2048, 1536);
        flag.image = f; flag.colorSpace = T.NoColorSpace;
        flag.anisotropy = renderer.capabilities.getMaxAnisotropy(); flag.needsUpdate = true;
        entry.ready = true; kick();
      };
      /* the map says gb, the flag files (and the rest of the app) say uk */
      img.src = `flags/${code === 'gb' ? 'uk' : code}.svg`;
      cache.set(code, entry);
      return entry;
    }

    /* ------------------------------------------------------------- building */

    const additive = (color, opacity) => new T.MeshBasicMaterial({
      color, transparent: true, opacity, depthWrite: false,
      blending: light ? T.NormalBlending : T.AdditiveBlending });

    function glowTexture() {
      const c = document.createElement('canvas'); c.width = c.height = 64;
      const g = c.getContext('2d'), r = g.createRadialGradient(32, 32, 0, 32, 32, 32);
      r.addColorStop(0, 'rgba(255,255,255,1)'); r.addColorStop(0.25, 'rgba(255,255,255,.55)');
      r.addColorStop(1, 'rgba(255,255,255,0)');
      g.fillStyle = r; g.fillRect(0, 0, 64, 64);
      return new T.CanvasTexture(c);
    }
    let GLOW = null;
    const sprite = (color, size) => {
      const s = new T.Sprite(new T.SpriteMaterial({ map: GLOW, color, transparent: true, depthWrite: false,
        blending: light ? T.NormalBlending : T.AdditiveBlending }));
      s.scale.setScalar(size);
      return s;
    };

    const GLOBE_VERT = `varying vec3 vN; varying vec3 vV; varying vec3 vO;
      void main(){ vO = normalize(position); vec4 mv = modelViewMatrix * vec4(position, 1.);
        vV = normalize(-mv.xyz); vN = normalize(normalMatrix * normal); gl_Position = projectionMatrix * mv; }`;
    const GLOBE_FRAG = `uniform sampler2D uMaskO, uMaskD, uFlagO, uFlagD; uniform vec4 uBoxO, uBoxD;
      uniform float uFillO, uFillD, uSpanO, uSpanD, uRed, uLight, uHasO, uHasD; uniform vec3 uLandO, uLandD;
      varying vec3 vN; varying vec3 vV; varying vec3 vO;
      float lit(sampler2D m, vec4 box, vec2 ll, out vec2 uv) {
        uv = (ll - box.xy) / (box.zw - box.xy);
        if (uv.x < 0. || uv.x > 1. || uv.y < 0. || uv.y > 1.) return 0.;
        return texture2D(m, uv).r;            /* canvas textures arrive flipped: north is v = 1 */
      }
      void main(){
        float lat = degrees(asin(clamp(vO.y, -1., 1.))), lon = degrees(atan(vO.x, vO.z));
        vec2 ll = vec2(lon, lat), uvO, uvD;
        float shade = .55 + .45 * max(dot(vN, normalize(vec3(-.4, .6, .7))), 0.);
        vec3 base = mix(vec3(.045, .048, .058), vec3(.90, .91, .93), uLight) * mix(shade, 1., uLight * .6);
        vec2 g = abs(fract(ll / 15. + .5) - .5) / fwidth(ll / 15.);
        base += mix(vec3(.55, .62, .85) * .05, vec3(-.06), uLight) * (1. - min(min(g.x, g.y), 1.));
        float mO = lit(uMaskO, uBoxO, ll, uvO) * uHasO, mD = lit(uMaskD, uBoxD, ll, uvD) * uHasD;
        float dO = acos(clamp(dot(vO, uLandO), -1., 1.)), dD = acos(clamp(dot(vO, uLandD), -1., 1.));
        float fO = mO * (1. - smoothstep(uFillO * uSpanO - .004, uFillO * uSpanO, dO));
        float fD = mD * (1. - smoothstep(uFillD * uSpanD - .004, uFillD * uSpanD, dD));
        vec3 c = base;
        /* the flag in its own colours, only a breath of the light across it */
        c = mix(c, texture2D(uFlagO, uvO).rgb * (.9 + .14 * shade), fO);
        c = mix(c, texture2D(uFlagD, uvD).rgb * (.9 + .14 * shade), fD);
        c += vec3(.12, .85, .38) * mD * (1. - smoothstep(0., .006, abs(dD - uFillD * uSpanD))) * step(.001, uFillD) * step(uFillD, .999);
        float l = dot(c, vec3(.3, .59, .11)); c = mix(c, vec3(l * 1.2, l * .3, l * .35), uRed);
        float rim = pow(1. - max(dot(vN, vV), 0.), 4.);
        c += mix(vec3(.54, .65, 1.) * .55, vec3(-.25, -.2, -.05), uLight) * rim;
        gl_FragColor = vec4(c, 1.);
      }`;

    function ringsGeometry(indices, r) {
      const pos = [];
      for (const i of indices) {
        const ring = W.rings[i];
        for (let k = 0; k < ring.length; k += 2) {
          const k2 = (k + 2) % ring.length;
          const a = dirOf(ring[k], ring[k + 1]).multiplyScalar(r), b = dirOf(ring[k2], ring[k2 + 1]).multiplyScalar(r);
          pos.push(a.x, a.y, a.z, b.x, b.y, b.z);
        }
      }
      const g = new T.BufferGeometry();
      g.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
      return g;
    }
    const lineMat = (color, opacity) => new T.LineBasicMaterial({ color, transparent: true, opacity, depthWrite: false,
      blending: light ? T.NormalBlending : T.AdditiveBlending });
    function outline(code, color) {
      const l = new T.LineSegments(ringsGeometry(W.by[code], 1.0022), lineMat(color, 1));
      scene.add(l);
      return l;
    }
    function drop(obj) {
      if (!obj) return;
      scene.remove(obj);
      obj.traverse((o) => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
    }

    function build() {
      const tone = light ? INK : NEON;
      scene = new T.Scene();
      camera = new T.PerspectiveCamera(FOV, 1, 0.01, 100);
      GLOW = glowTexture();
      const blank = new T.DataTexture(new Uint8Array([0, 0, 0, 255]), 1, 1); blank.needsUpdate = true;
      globeMat = new T.ShaderMaterial({
        vertexShader: GLOBE_VERT, fragmentShader: GLOBE_FRAG,
        uniforms: {
          uMaskO: { value: blank }, uMaskD: { value: blank }, uFlagO: { value: blank }, uFlagD: { value: blank },
          uBoxO: { value: new T.Vector4(0, 0, 1, 1) }, uBoxD: { value: new T.Vector4(0, 0, 1, 1) },
          uFillO: { value: 0 }, uFillD: { value: 0 }, uSpanO: { value: 0.2 }, uSpanD: { value: 0.2 },
          uLandO: { value: new T.Vector3(0, 0, 1) }, uLandD: { value: new T.Vector3(0, 0, 1) },
          uRed: { value: 0 }, uLight: { value: light ? 1 : 0 }, uHasO: { value: 0 }, uHasD: { value: 0 },
        },
      });
      scene.add(new T.Mesh(new T.SphereGeometry(1, 160, 120), globeMat));
      borders = new T.LineSegments(ringsGeometry(W.rings.map((_, i) => i), 1.0012),
                                   lineMat(light ? 0x6B7486 : NEON.peri, light ? 0.55 : 0.42));
      scene.add(borders);

      orbits = [];
      for (const d of [{ r: 1.32, tilt: [1.1, 0, 0.3], color: tone.peri, speed: 0.25, n: 3 },
                       { r: 1.5, tilt: [-0.6, 0.8, -0.2], color: tone.aqua, speed: -0.18, n: 2 },
                       { r: 1.68, tilt: [0.25, -0.5, 0.9], color: tone.orchid, speed: 0.14, n: 2 }]) {
        const ring = new T.Group();
        ring.rotation.set(...d.tilt);
        const path = new T.CatmullRomCurve3(new T.EllipseCurve(0, 0, d.r, d.r).getPoints(180)
          .map((p) => new T.Vector3(p.x, 0, p.y)), true);
        const line = new T.Mesh(new T.TubeGeometry(path, 240, 0.0035, 3, true), additive(d.color, 0.6));
        const glow = new T.Mesh(new T.TubeGeometry(path, 240, 0.016, 3, true), additive(d.color, 0.08));
        ring.add(line, glow);
        const riders = Array.from({ length: d.n }, (_, i) => {
          const s = sprite(d.color, 0.09); ring.add(s);
          return { s, u: i / d.n, ph: Math.random() * 6 };
        });
        scene.add(ring);
        orbits.push({ ring, path, line, glow, riders, d });
      }
      ripples = Array.from({ length: 3 }, () => {
        const m = new T.Mesh(new T.RingGeometry(0.96, 1, 64), additive(tone.pulse, 0));
        m.scale.setScalar(0.001); scene.add(m);
        return m;
      });
      view.dir = dirOf(40, 25);
    }

    /* ---------------------------------------------------------------- camera */

    /* The country sits in the middle band, between the status block and the
       panel below; this is how far off the window's centre that is. */
    function bandShift() {
      const mid = document.querySelector('.hero .mid');
      const h = window.innerHeight;
      if (!mid || !h) return 0;
      const r = mid.getBoundingClientRect();
      return ((r.top + r.bottom) / 2 - h / 2) / h;
    }

    const UP = () => new T.Vector3(0, 1, 0);
    function placeCamera() {
      const side = new T.Vector3().crossVectors(UP(), view.dir).normalize();
      const north = new T.Vector3().crossVectors(view.dir, side).normalize();
      const pos = view.dir.clone().addScaledVector(side, lean.x * 0.04)
        .addScaledVector(north, -0.035 - lean.y * 0.03).normalize().multiplyScalar(view.dist);
      camera.position.copy(pos);
      camera.up.copy(north);
      const reach = (view.dist - 1) * 2 * Math.tan(FOV / 2 * D2R);
      camera.lookAt(view.dir.clone().addScaledVector(north, view.shift * reach));
    }

    function layout() {
      const w = window.innerWidth, h = window.innerHeight;
      if (!w || !h) return false;
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAX_DPR));
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      view.shift = bandShift();
      return true;
    }

    /* ------------------------------------------------------------------ loop */

    function visible() {
      return !document.hidden && document.body.dataset.covered !== '1';
    }

    function tick(time, deltaMs) {
      if (!visible()) { stop(); return; }
      const now = performance.now();
      if (!moving && now - drawnAt < IDLE_MS) return;
      const calm = isCalm();
      const d = calm ? 0 : Math.min(deltaMs, 50) / 1000;
      S.t += d;
      const far = T.MathUtils.smoothstep(view.dist, 2.1, 3.6);
      for (const o of orbits) {
        o.ring.rotation.y += d * o.d.speed * 0.2 * S.orbitSpeed;
        o.line.material.opacity = 0.6 * far;
        o.glow.material.opacity = 0.08 * far;
        for (const r of o.riders) {
          r.u = (r.u + d * o.d.speed * 0.2 * S.orbitSpeed + 1) % 1;
          r.s.position.copy(o.path.getPointAt(r.u));
          const b = 0.5 + 0.5 * Math.sin(S.t * (2 + S.blink * 9) + r.ph);
          r.s.material.opacity = far * (0.2 + 0.8 * b * b);
        }
      }
      if (arc && S.traffic > 0) {
        arc.traffic.forEach((s, i) => {
          const u = (S.t * S.trafficSpeed + i / arc.traffic.length) % 1;
          s.position.copy(arc.curve.getPointAt(u));
          s.material.opacity = S.traffic * Math.sin(u * Math.PI);
        });
      }
      placeCamera();
      renderer.render(scene, camera);
      drawnAt = now;
      if (calm && !moving) stop();
    }

    function kick() {
      if (!started || ticking || !visible() || !window.gsap) return;
      ticking = true;
      gsap.ticker.add(tick);
    }
    function stop() {
      if (!ticking) return;
      ticking = false;
      gsap.ticker.remove(tick);
    }
    /* a timeline that moves the camera or the arc runs at full rate */
    function run(tl) {
      moving += 1;
      tl.eventCallback('onComplete', () => { moving = Math.max(0, moving - 1); });
      tl.eventCallback('onInterrupt', () => { moving = Math.max(0, moving - 1); });
      kick();
      return tl;
    }
    const dur = (s) => (isCalm() ? 0 : s);

    /* ------------------------------------------------------------- the fills */

    /* How far the flag must spread from where it lands, in radians: to the
       farthest point of the country's land inside its box (the only part the
       flag paints), and a hair more so the edge closes cleanly. */
    function span(code, at) {
      const b = W.box[code];
      let far = 0;
      for (const i of W.by[code]) {
        const r = W.rings[i];
        for (let k = 0; k < r.length; k += 2) {
          const lon = r[k], lat = r[k + 1];
          if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) continue;
          far = Math.max(far, at.angleTo(dirOf(lon, lat)));
        }
      }
      return far + 0.01;
    }
    function fillAs(which, code, land) {
      const u = globeMat.uniforms, k = which === 'O' ? 'O' : 'D';
      if (!has(code)) { u['uHas' + k].value = 0; return; }
      const tex = countryTextures(code);
      u['uMask' + k].value = tex.mask;
      u['uFlag' + k].value = tex.flag;
      u['uBox' + k].value.set(...W.box[code]);
      const at = land || middle(code);
      u['uSpan' + k].value = span(code, at);
      u['uLand' + k].value.copy(at);
      u['uHas' + k].value = 1;
    }

    /* --------------------------------------------------------------- journeys */

    function makeArc(a, b) {
      const ang = a.angleTo(b), h = 0.08 + 0.32 * ang / Math.PI;
      const axis = new T.Vector3().crossVectors(a, b).normalize();
      const pts = [];
      for (let i = 0; i <= 160; i += 1) {
        const t = i / 160;
        pts.push(a.clone().applyAxisAngle(axis, ang * t).multiplyScalar(1.003 + h * Math.sin(Math.PI * t)));
      }
      const curve = new T.CatmullRomCurve3(pts);
      const geo = new T.TubeGeometry(curve, 200, 0.0024, 4, false);
      const mesh = new T.Mesh(geo, additive(light ? INK.pulse : NEON.pulse, 0.95));
      geo.setDrawRange(0, 0);
      const comet = sprite(light ? INK.pulse : 0xffffff, 0.12); comet.visible = false;
      const traffic = Array.from({ length: 7 }, () => { const s = sprite(light ? INK.pulse : NEON.pulse, 0.05); s.material.opacity = 0; return s; });
      scene.add(mesh, comet, ...traffic);
      return { curve, geo, mesh, full: geo.index.count, comet, traffic };
    }
    function dropArc() {
      if (!arc) return;
      [arc.mesh, arc.comet, ...arc.traffic].forEach(drop);
      arc = null;
    }

    /* the slow turn of the planet from one point above it to another */
    function turnTo(tl, to, at, length) {
      const from = view.dir.clone(), p = { t: 0 };
      tl.to(p, { t: 1, duration: dur(length), ease: 'power2.inOut',
                 onUpdate() { view.dir.copy(from).lerp(to, p.t).normalize(); } }, at);
    }

    /* Settle above home - or, before the address is known, above the pick,
       or over the Middle East from far enough out to be a planet. */
    function goHome(first) {
      if (journey) journey.kill();
      if (busyTl) { busyTl.kill(); busyTl = null; }
      const u = globeMat.uniforms;
      const code = has(homeCode) ? homeCode : (has(pickedCode) ? pickedCode : null);
      originCode = code;
      destCode = null;
      if (code) fillAs('O', code);
      if (outlineO) drop(outlineO);
      outlineO = code ? outline(code, light ? INK.peri : NEON.peri) : null;
      if (outlineD) { drop(outlineD); outlineD = null; }
      const tl = gsap.timeline({ defaults: { ease: 'power2.inOut' } });
      const target = code ? middle(code) : dirOf(45, 28);
      const height = code ? heightFor(code) : 4.6;
      if (first) {
        view.dir.copy(target);
        view.dist = 6;
        tl.to(view, { dist: height, duration: dur(2.2), ease: 'expo.inOut' }, 0.2);
        u.uFillO.value = 1;
      } else {
        tl.to(view, { dist: Math.max(view.dist, 3.4), duration: dur(0.8) }, 0);
        turnTo(tl, target, 0.4, 1.4);
        tl.to(view, { dist: height, duration: dur(1.1), ease: 'power3.inOut' }, 1.4)
          .to(u.uFillD, { value: 0, duration: dur(0.5) }, 0)
          .to(u.uFillO, { value: 1, duration: dur(1) }, 1.4);
      }
      tl.to(u.uRed, { value: 0, duration: dur(0.5) }, 0)
        .to(S, { orbitSpeed: 1, blink: 0.5, traffic: 0, duration: dur(1) }, 0)
        .add(dropArc, 0.5);
      ripples.forEach((m) => { m.material.opacity = 0; });
      journey = run(tl);
    }

    function searching() {
      if (busyTl) busyTl.kill();
      const at = has(originCode) ? middle(originCode) : view.dir.clone();
      busyTl = gsap.timeline();
      ripples.forEach((m, i) => {
        m.position.copy(at).multiplyScalar(1.003);
        m.lookAt(0, 0, 0);
        busyTl.fromTo(m.scale, { x: 0.001, y: 0.001 }, { x: 0.16, y: 0.16, duration: 1.6, ease: 'power2.out', repeat: -1 }, i * 0.53)
          .fromTo(m.material, { opacity: 0.9 }, { opacity: 0, duration: 1.6, ease: 'power1.in', repeat: -1 }, i * 0.53);
      });
      busyTl.to(view, { dist: view.dist + 0.35, duration: dur(2.2), ease: 'sine.inOut' }, 0)
        .to(S, { orbitSpeed: 4, blink: 1, duration: dur(0.8) }, 0);
      moving += 1;
      busyTl.eventCallback('onInterrupt', () => { moving = Math.max(0, moving - 1); });
      kick();
    }

    /* Out, over, and down onto where the traffic leaves - or, failing, a
       signal that stops halfway and comes home. */
    function arrive(code, fail) {
      if (journey) journey.kill();
      if (busyTl) { busyTl.kill(); busyTl = null; }
      ripples.forEach((m) => gsap.to(m.material, { opacity: 0, duration: dur(0.3) }));
      if (!has(code)) { if (!fail) return; goHome(false); return; }
      const u = globeMat.uniforms;
      const from = has(originCode) ? middle(originCode) : view.dir.clone();
      const to = middle(code);
      destCode = code;
      fillAs('D', code, to);
      u.uFillD.value = 0;
      if (outlineD) drop(outlineD);
      outlineD = outline(code, light ? INK.pulse : NEON.pulse);
      outlineD.material.opacity = 0;
      dropArc();
      /* from a country to itself there is no journey to show: fly, then fill */
      const same = from.angleTo(to) < 0.03;
      arc = same ? null : makeArc(from, to);
      if (same && fail) { goHome(false); return; }
      const draw = { u: 0 };
      const tl = gsap.timeline({ defaults: { ease: 'power2.inOut' } });
      tl.to(view, { dist: 5, duration: dur(1.3), ease: 'power3.inOut' }, 0);
      if (arc) {
        const a = arc;
        tl.set(a.comet, { visible: true }, 0.7)
          .to(draw, { u: fail ? 0.5 : 1, duration: dur(fail ? 1.2 : 1.9), ease: fail ? 'power1.in' : 'power1.inOut',
                      onUpdate() {
                        a.geo.setDrawRange(0, Math.floor(a.full * draw.u / 6) * 6);
                        a.comet.position.copy(a.curve.getPointAt(Math.min(draw.u, 1)));
                      } }, 0.7);
      }
      const start = view.dir.clone(), p = { t: 0 };
      tl.to(p, { t: fail ? 0.45 : 1, duration: dur(fail ? 1.4 : 2.1),
                 onUpdate() { view.dir.copy(start).lerp(to, p.t).normalize(); } }, 0.55);
      if (fail) {
        tl.to(arc.mesh.material.color, { r: 1, g: 0.42, b: 0.48, duration: dur(0.2) })
          .to(arc.mesh.material, { opacity: 0.15, duration: 0.07, yoyo: true, repeat: 5 }, '<')
          .to(arc.comet.material, { opacity: 0, duration: dur(0.6) }, '<')
          .to(u.uRed, { value: 0.7, duration: dur(0.3), yoyo: true, repeat: 1 }, '<')
          .add(() => goHome(false), '+=1.2');
        journey = run(tl);
        return;
      }
      tl.to(view, { dist: heightFor(code), duration: dur(1.5), ease: 'power3.inOut' }, 2.3)
        .add(() => { if (arc) arc.comet.visible = false; }, 2.6)
        .to(outlineD.material, { opacity: 1, duration: dur(0.4) }, 2.4)
        .to(u.uFillD, { value: 1, duration: dur(1.3), ease: 'power2.out' }, 2.6)
        .to(u.uFillO, { value: 0, duration: dur(0.9) }, 2.6)
        .to(S, { orbitSpeed: 1, blink: 0.3, traffic: 1, duration: dur(1) }, 2.6);
      if (outlineO) tl.to(outlineO.material, { opacity: 0.35, duration: dur(0.9) }, 2.6);
      journey = run(tl);
    }

    /* the exit moved while connected (the check came back with a different
       country): a short hop, no planet */
    function hop(code) {
      if (!has(code) || code === destCode) return;
      const u = globeMat.uniforms;
      destCode = code;
      fillAs('D', code);
      if (outlineD) drop(outlineD);
      outlineD = outline(code, light ? INK.pulse : NEON.pulse);
      const tl = gsap.timeline({ defaults: { ease: 'power2.inOut' } });
      u.uFillD.value = 0;
      turnTo(tl, middle(code), 0, 1.2);
      tl.to(view, { dist: heightFor(code), duration: dur(1.2) }, 0)
        .to(u.uFillD, { value: 1, duration: dur(1), ease: 'power2.out' }, 0.8);
      journey = run(tl);
    }

    /* ------------------------------------------------------------------ state */

    function follow() {
      const st = document.documentElement.dataset.conn || 'off';
      if (st === state) {
        if (st === 'on' && liveCode && liveCode !== destCode) hop(liveCode);
        return;
      }
      const was = state;
      state = st;
      if (st === 'busy') searching();
      else if (st === 'on') arrive(liveCode || pickedCode, false);
      else if (st === 'fail') { if (was === 'busy') arrive(pickedCode, true); else goHome(false); }
      else goHome(false);
      kick();
    }

    /* ------------------------------------------------------------------- API */

    /* Called by app.js whenever the state or the country can have changed:
       `live` is where the traffic leaves now, `picked` the choice. */
    function sync(live, picked) {
      liveCode = live || null;
      pickedCode = picked || null;
      if (!started) return;
      if (state === 'off' && !has(homeCode) && has(pickedCode) && originCode !== pickedCode) goHome(false);
      follow();
    }

    /* The country the user's own address is in, as Cloudflare saw it before
       anything of ours was in the way. */
    function home(code) {
      code = (code || '').toLowerCase();
      if (!has(code) || code === homeCode) return;
      const first = homeCode === null && originCode === null;
      homeCode = code;
      if (!started) return;
      if (state === 'off') { goHome(first); return; }
      if (state === 'on' && destCode && code !== destCode) {
        /* the address came back after the route was up: the journey started
           from a guess, so draw it again from where you really are */
        originCode = code;
        fillAs('O', code);
        globeMat.uniforms.uFillO.value = 0;
        if (outlineO) drop(outlineO);
        outlineO = outline(code, light ? INK.peri : NEON.peri);
        outlineO.material.opacity = 0.35;
        dropArc();
        arc = makeArc(middle(code), middle(destCode));
        arc.geo.setDrawRange(0, arc.full);
        kick();
      }
    }

    function hit() { return true; }

    function themeNow() { return document.documentElement.dataset.theme === 'white'; }

    function init(cv, opts) {
      T = window.THREE; W = window.WORLD;
      if (!T || !W) return false;
      canvas = cv;
      isCalm = (opts && opts.calm) || (() => false);
      try {
        renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'low-power' });
      } catch (e) { renderer = null; }
      if (!renderer) { canvas.hidden = true; return false; }
      renderer.setClearColor(0x000000, 0);
      light = themeNow();
      build();
      started = true;
      layout();
      goHome(true);
      canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); stop(); });
      let rz = 0;
      const relayout = () => { cancelAnimationFrame(rz); rz = requestAnimationFrame(() => { layout(); kick(); }); };
      window.addEventListener('resize', relayout);
      if (window.ResizeObserver) new ResizeObserver(relayout).observe(document.getElementById('hero') || document.body);
      document.addEventListener('visibilitychange', kick);
      new MutationObserver(kick).observe(document.body, { attributes: true, attributeFilter: ['data-covered'] });
      new MutationObserver(follow).observe(document.documentElement, { attributes: true, attributeFilter: ['data-conn'] });
      /* the theme decides blending, so a change rebuilds the scene where it stands */
      new MutationObserver(() => {
        if (themeNow() === light) return;
        light = themeNow();
        const keep = { dir: view.dir.clone(), dist: view.dist };
        scene.traverse((o) => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
        outlineO = outlineD = arc = null;
        build();
        view.dir.copy(keep.dir); view.dist = keep.dist;
        const was = state; state = '__'; originCode = null;
        if (was === 'on' && liveCode) {
          goHome(false); state = 'on';
          arrive(liveCode, false);
        } else { state = was; goHome(false); }
      }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
      const leanX = gsap.quickTo(lean, 'x', { duration: 1.2, ease: 'power3.out' });
      const leanY = gsap.quickTo(lean, 'y', { duration: 1.2, ease: 'power3.out' });
      window.addEventListener('pointermove', (e) => {
        if (isCalm()) return;
        leanX((e.clientX / window.innerWidth - 0.5) * 2);
        leanY((e.clientY / window.innerHeight - 0.5) * 2);
        kick();
      });
      gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => { kick(); return () => kick(); });
      kick();
      return true;
    }

    return { init, sync, home, hit };
  })();
})();
