/* The hologram globe: a lat/long wire-grid sphere with faint continents,
   centred on the right edge of the window so about half of it shows.

   Ported from the Kootaj landing page (the owner's own scene,
   src/components/webgl/hologram-scene.ts). That one loads a 2048px GLB into
   three.js and draws a double-sided, depth-write-off, ADDITIVE emissive mesh:
   a graticule texture for the lines, a land mask for the continents, a
   near-orthographic camera (22 deg) so an off-centre sphere stays round.

   Here there is no three.js and no model. The same picture is computed per
   pixel on a screen-aligned quad (one draw call):

     - the sphere is analytic: each pixel inside the disc has two hits, the
       near and the far surface, and BOTH are shaded and summed - the same
       thing the double-sided additive mesh does, which is what makes the
       limb crowd with light and the globe read as round rather than flat;
     - the lines are the graticule: every 15 deg, anti-aliased from screen
       derivatives, every other one brighter, with a little extra light where
       a meridian meets a parallel. They fade out where they would be denser
       than the pixels can hold (the limb, the poles);
     - the land is the landing page's own earth-holo mask (globe-land.js, 1 bit
       per texel), drawn as a faint fill and a fainter coast - never competing
       with the lines;
     - a thin ring on the limb.

   Dark themes add light (premultiplied alpha 0). White paints ink instead,
   because adding light to a pale page only makes it paler. One shader, one
   uniform decides.

   Meaning, not decoration. When a route is up, the globe turns until the exit
   country sits in the middle of the visible half and a small dot marks it.
   The state colour tints the grid: cyan off, amber while negotiating, green up,
   red on failure, eased over 600 ms. Reduced motion draws one still frame.

   No WebGL2 (blocked GPU, remote session): the same graticule is drawn once on
   the canvas with the 2D API - the still. */

(function () {
  'use strict';

  const D2R = Math.PI / 180;

  /* Approximate capital / centroid per country code: [lat, lon]. */
  const LL = {
    ad: [42.5, 1.5], ae: [24.5, 54.4], af: [34.5, 69.2], al: [41.3, 19.8], am: [40.2, 44.5],
    aq: [-78, 0], ar: [-34.6, -58.4], at: [48.2, 16.4], au: [-25.3, 134], az: [40.4, 49.9],
    ba: [43.9, 18.4], bd: [23.8, 90.4], be: [50.8, 4.4], bg: [42.7, 23.3], bh: [26.2, 50.6],
    bn: [4.9, 114.9], bo: [-16.5, -68.1], br: [-15.8, -47.9], bs: [25.0, -77.4], bt: [27.5, 89.6],
    by: [53.9, 27.6], bz: [17.3, -88.8], ca: [45.4, -75.7], ch: [46.9, 7.4], cl: [-33.4, -70.7],
    cn: [35, 103], co: [4.7, -74.1], cr: [9.9, -84.1], cy: [35.2, 33.4], cz: [50.1, 14.4],
    de: [52.5, 13.4], dk: [55.7, 12.6], do: [18.5, -69.9], dz: [36.8, 3.1], ec: [-0.2, -78.5],
    ee: [59.4, 24.7], eg: [30.0, 31.2], es: [40.4, -3.7], fi: [60.2, 24.9], fr: [48.9, 2.35],
    gb: [51.5, -0.1], ge: [41.7, 44.8], gl: [64.2, -51.7], gr: [37.98, 23.7], gt: [14.6, -90.5],
    hk: [22.3, 114.2], hn: [14.1, -87.2], hr: [45.8, 16.0], hu: [47.5, 19.0], id: [-6.2, 106.8],
    ie: [53.3, -6.3], il: [31.8, 35.2], in: [28.6, 77.2], iq: [33.3, 44.4], ir: [35.7, 51.4],
    is: [64.1, -21.9], it: [41.9, 12.5], jm: [18.0, -76.8], jo: [31.95, 35.9], jp: [35.7, 139.7],
    ke: [-1.3, 36.8], kg: [42.9, 74.6], kh: [11.6, 104.9], kr: [37.6, 127.0], kz: [51.2, 71.4],
    la: [17.97, 102.6], lk: [6.9, 79.9], lt: [54.7, 25.3], lu: [49.6, 6.1], lv: [56.95, 24.1],
    ma: [34.0, -6.8], md: [47.0, 28.9], me: [42.4, 19.3], mk: [42.0, 21.4], mm: [19.8, 96.1],
    mn: [47.9, 106.9], mo: [22.2, 113.5], mt: [35.9, 14.5], mx: [19.4, -99.1], my: [3.1, 101.7],
    ng: [9.1, 7.5], nl: [52.4, 4.9], no: [59.9, 10.75], np: [27.7, 85.3], nz: [-41.3, 174.8],
    pa: [9.0, -79.5], pe: [-12.0, -77.0], ph: [14.6, 121.0], pk: [33.7, 73.1], pl: [52.2, 21.0],
    pr: [18.5, -66.1], pt: [38.7, -9.1], py: [-25.3, -57.6], qa: [25.3, 51.5], ro: [44.4, 26.1],
    rs: [44.8, 20.5], ru: [55.75, 37.6], sa: [24.7, 46.7], se: [59.3, 18.1], sg: [1.35, 103.8],
    si: [46.05, 14.5], sk: [48.1, 17.1], sv: [13.7, -89.2], th: [13.75, 100.5], tn: [36.8, 10.2],
    tr: [39.9, 32.9], tw: [25.0, 121.5], ua: [50.45, 30.5], uk: [51.5, -0.1], us: [38.9, -77.0],
    uy: [-34.9, -56.2], uz: [41.3, 69.2], ve: [10.5, -66.9], vn: [21.0, 105.8], za: [-25.7, 28.2],
  };

  /* How the globe is framed. Radius and centre are fractions of the window. */
  const FRAME = {
    radiusH: 0.56,        /* radius as a fraction of the window height ... */
    radiusW: 0.58,        /* ... and of its width: whichever is smaller */
    overhang: 0.06,       /* the centre sits this many radii past the right edge */
    centerY: 0.47,        /* centre y, fraction of window height */
  };
  const LEAN = 0.10;       /* roll, radians (the source's tiltZ) */
  const REST_TILT = 0.2;   /* radians: the north a little toward the viewer */
  const SPIN_IDLE = -13;   /* deg/s; negative = the way the Earth turns (surface drifts right) */
  const SPIN_BUSY = -30;
  const FACE_OFFSET = 30;  /* deg: the exit sits this far left of the globe's meridian, in the visible half */
  const MAX_DPR = 1.5;
  const FRAME_MS = 1000 / 30 - 4;

  const VERT = `#version 300 es
precision highp float;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
  gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);
}`;

  const FRAG = `#version 300 es
precision highp float;
uniform vec2  uCenter;   /* globe centre, device px, origin bottom-left */
uniform float uR;        /* globe radius, device px */
uniform float uDpr;
uniform mat3  uRot;      /* view -> object */
uniform vec3  uInk;
uniform float uLight;    /* 0 dark theme (adds light), 1 white theme (paints ink) */
uniform float uLevel;    /* overall brightness */
uniform vec3  uMark;     /* marker, view space; z <= 0 hides it */
uniform float uMarkOn;
uniform vec2  uFade;     /* window-y where the bottom fade starts / ends, device px from the canvas top */
uniform float uCanvasH;
uniform vec4  uQuiet;    /* the sparkline's box, device px from the canvas top-left */
uniform float uQuietOn;
uniform sampler2D uLand;
out vec4 o;

const float PI = 3.14159265;
const float STEP = 15.0 * PI / 180.0;

/* One family of anti-aliased graticule lines. u is the angle in steps. */
float lines(float u, float fw, float width) {
  float k = floor(u + 0.5);
  float f = abs(u - k);
  float d = f / max(fw, 1e-5);                       /* pixels from the line */
  float l = exp(-(d * d) / (width * width));
  float spacing = 1.0 / max(fw, 1e-5);               /* pixels between lines */
  l *= smoothstep(2.5 * uDpr, 8.0 * uDpr, spacing);
  return l * (mod(k, 2.0) < 0.5 ? 1.0 : 0.6);
}

/* x = light, y = lit core, for one hit. back: the far surface, dimmer. */
vec2 surface(vec2 q, float zs, float back) {
  vec3 n = vec3(q, zs * sqrt(max(0.0, 1.0 - dot(q, q))));
  vec3 p = uRot * n;
  float lat = asin(clamp(p.y, -1.0, 1.0));
  float lon = atan(p.x, p.z);
  float ul = lat / STEP;
  float uo = lon / STEP;
  float fwl = fwidth(ul);
  float fwo = min(fwidth(uo), fwidth(fract(uo + 0.5)));
  float w = 0.95 * uDpr;
  float a = lines(ul, fwl, w);
  float b = lines(uo, fwo, w);
  float grid = max(a, b) + a * b * 0.9;
  float m = texture(uLand, vec2(lon / (2.0 * PI) + 0.5, 0.5 - lat / PI)).r;
  float land = smoothstep(0.3, 0.7, m);
  float coast = m * (1.0 - m) * 4.0;
  float g = grid * 0.85 + land * 0.15 + coast * 0.2;
  return vec2(g * mix(1.0, 0.5, back), grid * a * b);
}

void main() {
  vec2 q = (gl_FragCoord.xy - uCenter) / uR;
  float r2 = dot(q, q);
  vec2 s = surface(q, 1.0, 0.0) + surface(q, -1.0, 1.0);
  float r = sqrt(r2);
  float dEdge = (1.0 - r) * uR;
  float ring = exp(-(dEdge * dEdge) / (1.6 * uDpr * 1.6 * uDpr)) * 0.75;
  float rim = smoothstep(0.82, 1.0, r) * 0.22;
  float I = (s.x + ring + rim) * uLevel;

  /* quiet toward the bottom of the window, under the addresses and the button */
  float wy = uCanvasH - gl_FragCoord.y;
  I *= mix(1.0, 0.3, smoothstep(uFade.x, uFade.y, wy));

  /* quiet where the live throughput figures sit, so they stay readable */
  vec2 qp = vec2(gl_FragCoord.x, wy);
  vec2 qa = smoothstep(uQuiet.xy - 14.0 * uDpr, uQuiet.xy + 6.0 * uDpr, qp);
  vec2 qb = 1.0 - smoothstep(uQuiet.zw - 6.0 * uDpr, uQuiet.zw + 14.0 * uDpr, qp);
  I *= 1.0 - 0.72 * uQuietOn * qa.x * qa.y * qb.x * qb.y;

  /* a lit centre: white-hot where lines cross, colour in the falloff */
  vec3 hot = mix(uInk, vec3(1.0), clamp(s.y * 0.8 + I * 0.12, 0.0, 0.7));
  vec4 dark = vec4(hot * I, 0.0);
  float a = 1.0 - exp(-I * 1.35);
  vec4 light = vec4(uInk * a, a);
  vec4 col = mix(dark, light, uLight);

  if (uMarkOn > 0.001 && uMark.z > 0.0) {
    float d = length(q - uMark.xy) * uR;
    float dot_ = (1.0 - smoothstep(3.4 * uDpr, 4.6 * uDpr, d)) * uMarkOn;
    float halo = exp(-(d * d) / (13.0 * uDpr * 13.0 * uDpr)) * 0.5 * uMarkOn;
    vec3 mc = mix(vec3(1.0), uInk * 0.55, uLight);
    vec3 hc = uInk;
    /* dark: add; white: paint over */
    vec4 add = vec4(hc * halo + mc * dot_, 0.0);
    float pa = clamp(dot_ + halo * 0.5, 0.0, 1.0);
    vec4 paint = vec4(col.rgb * (1.0 - pa) + mix(hc, mc, step(0.5, dot_)) * pa, col.a * (1.0 - pa) + pa);
    col = mix(col + add, paint, uLight);
  }
  if (r2 > 1.0) { discard; }
  o = col;
}`;

  window.Globe = (function () {
    let canvas = null, host = null;
    let gl = null, prog = null, U = {}, tex = null;
    let ctx2d = null;
    let ready = false, started = false;
    let isCalm = () => false;
    let faceCode = null;
    let raf = 0, last = 0, drawnAt = 0, boost = 0, dirty = true;

    /* what is on screen */
    const S = {
      c: 0,            /* the longitude on the centre meridian, degrees */
      tilt: REST_TILT, /* radians */
      spin: SPIN_IDLE, /* deg/s */
      lock: false,     /* a country is faced: no spinning */
      markOn: 0,
      quiet: 0,
      ink: { r: 0.2, g: 0.84, b: 0.9 },
      level: 1,
    };
    const mark = { lat: 0, lon: 0 };     /* object space, radians */
    let geo = { w: 0, h: 0, cx: 0, cy: 0, R: 0, dpr: 1, bw: 0, bh: 0, fadeY0: 1e9, fadeY1: 1e9, left: 0, top: 0 };
    let light = 0;
    const rotBuf = new Float32Array(9);
    const markV = new Float32Array(3);

    /* ------------------------------------------------------------ colour */

    function parseRgb(str) {
      const m = /rgba?\(([^)]+)\)/.exec(str || '');
      if (!m) return null;
      const v = m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);
      return v.length >= 3 ? { r: v[0] / 255, g: v[1] / 255, b: v[2] / 255 } : null;
    }
    function targetInk() {
      return parseRgb(getComputedStyle(canvas).color) || { r: 0.2, g: 0.84, b: 0.9 };
    }
    function readTheme() {
      light = document.documentElement.dataset.theme === 'white' ? 1 : 0;
      dirty = true;
    }
    function tweenInk() {
      const to = targetInk();
      if (isCalm() || !window.gsap) {
        Object.assign(S.ink, to);
        dirty = true;
        kick();
        return;
      }
      boost++;
      gsap.to(S.ink, {
        r: to.r, g: to.g, b: to.b, duration: 0.6, ease: 'power2.out', overwrite: 'auto',
        onUpdate: () => { dirty = true; },
        onComplete: () => { boost = Math.max(0, boost - 1); },
      });
      kick();
    }

    /* ----------------------------------------------------------- geometry */

    function layout() {
      const w = host.clientWidth || window.innerWidth;
      const h = host.clientHeight || window.innerHeight;
      if (!w || !h) return false;
      const dpr = Math.min(window.devicePixelRatio || 1, MAX_DPR);
      const R = Math.min(h * FRAME.radiusH, w * FRAME.radiusW);
      const cx = w + R * FRAME.overhang;
      const cy = h * FRAME.centerY;
      /* the canvas is only the part of the window the disc touches */
      const pad = 2;
      const left = Math.max(0, Math.floor(cx - R - pad));
      const right = Math.min(w, Math.ceil(cx + R + pad));
      const top = Math.max(0, Math.floor(cy - R - pad));
      const bottom = Math.min(h, Math.ceil(cy + R + pad));
      const bw = right - left, bh = bottom - top;
      if (bw <= 0 || bh <= 0) return false;
      const swap = document.getElementById('swap');
      let y0 = h * 0.62;
      if (swap) {
        const t = swap.getBoundingClientRect().top;
        if (t > 0) y0 = t;
      }
      /* where the sparkline and its figures sit when a route is up */
      let qx0 = 0, qy0 = 0, qx1 = 0, qy1 = 0;
      const mid = document.querySelector('.hero .mid');
      if (mid) {
        const mr = mid.getBoundingClientRect();
        qx0 = mr.left - 4; qx1 = mr.left + Math.min(mr.width, 304) + 4;
        qy0 = mr.top; qy1 = mr.top + (h < 641 ? 40 : 48) + 14;
      }
      geo = { w, h, cx, cy, R, dpr, bw, bh, left, top, fadeY0: y0 - 36, fadeY1: y0 + 24, qx0, qy0, qx1, qy1 };
      canvas.style.left = left + 'px';
      canvas.style.top = top + 'px';
      canvas.style.width = bw + 'px';
      canvas.style.height = bh + 'px';
      const pw = Math.max(1, Math.round(bw * dpr)), ph = Math.max(1, Math.round(bh * dpr));
      if (canvas.width !== pw || canvas.height !== ph) { canvas.width = pw; canvas.height = ph; }
      if (gl) gl.viewport(0, 0, pw, ph);
      dirty = true;
      return true;
    }

    function faceTarget() {
      const ll = faceCode && LL[faceCode];
      return ll || null;
    }

    /* view-space rotation: roll * tilt * yaw, as a column-major mat3 (object -> view) */
    function viewMatrix(out) {
      const yaw = -S.c * D2R;
      const cy = Math.cos(yaw), sy = Math.sin(yaw);
      const ct = Math.cos(S.tilt), st = Math.sin(S.tilt);
      const cz = Math.cos(LEAN), sz = Math.sin(LEAN);
      /* Ry */
      const ry = [cy, 0, -sy, 0, 1, 0, sy, 0, cy];          /* column-major */
      /* Rx(tilt) */
      const rx = [1, 0, 0, 0, ct, st, 0, -st, ct];
      /* Rz(lean) */
      const rz = [cz, sz, 0, -sz, cz, 0, 0, 0, 1];
      const mul = (a, b) => {
        const r = new Array(9);
        for (let c = 0; c < 3; c++) for (let rr = 0; rr < 3; rr++) {
          r[c * 3 + rr] = a[0 * 3 + rr] * b[c * 3 + 0] + a[1 * 3 + rr] * b[c * 3 + 1] + a[2 * 3 + rr] * b[c * 3 + 2];
        }
        return r;
      };
      const m = mul(rz, mul(rx, ry));
      for (let i = 0; i < 9; i++) out[i] = m[i];
      return out;
    }

    /* ------------------------------------------------------------- the GL */

    function compile(type, src) {
      const sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        const msg = gl.getShaderInfoLog(sh);
        gl.deleteShader(sh);
        throw new Error(msg);
      }
      return sh;
    }

    function landBytes() {
      const L = window.GLOBE_LAND;
      if (!L) return null;
      const bin = atob(L.bits);
      const px = new Uint8Array(L.w * L.h);
      for (let i = 0; i < px.length; i++) {
        px[i] = (bin.charCodeAt(i >> 3) >> (7 - (i & 7))) & 1 ? 255 : 0;
      }
      return px;
    }

    function build() {
      try {
        prog = gl.createProgram();
        gl.attachShader(prog, compile(gl.VERTEX_SHADER, VERT));
        gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
        gl.linkProgram(prog);
        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
        gl.useProgram(prog);
        for (const n of ['uCenter', 'uR', 'uDpr', 'uRot', 'uInk', 'uLight', 'uLevel', 'uMark',
                         'uMarkOn', 'uFade', 'uCanvasH', 'uQuiet', 'uQuietOn', 'uLand']) {
          U[n] = gl.getUniformLocation(prog, n);
        }
        tex = gl.createTexture();
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, tex);
        const L = window.GLOBE_LAND;
        const bytes = landBytes();
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
        if (bytes) {
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, L.w, L.h, 0, gl.RED, gl.UNSIGNED_BYTE, bytes);
        } else {
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, 1, 1, 0, gl.RED, gl.UNSIGNED_BYTE, new Uint8Array([0]));
        }
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.uniform1i(U.uLand, 0);
        gl.disable(gl.DEPTH_TEST);
        gl.disable(gl.BLEND);
        gl.clearColor(0, 0, 0, 0);
        return true;
      } catch (e) {
        if (window.console) console.warn('globe: shader failed', e);
        return false;
      }
    }

    function draw() {
      if (!geo.bw) return;
      if (!gl) { draw2d(); return; }
      if (gl.isContextLost()) return;
      const R = geo.R * geo.dpr;
      /* the canvas origin is bottom-left in GL */
      gl.uniform2f(U.uCenter, (geo.cx - geo.left) * geo.dpr, (geo.bh - (geo.cy - geo.top)) * geo.dpr);
      gl.uniform1f(U.uR, R);
      gl.uniform1f(U.uDpr, geo.dpr);
      const m = viewMatrix(rotBuf);
      /* view -> object is the transpose */
      const inv = new Float32Array([m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]]);
      gl.uniformMatrix3fv(U.uRot, false, inv);
      gl.uniform3f(U.uInk, S.ink.r, S.ink.g, S.ink.b);
      gl.uniform1f(U.uLight, light);
      gl.uniform1f(U.uLevel, S.level * (light ? 0.9 : 0.8));
      /* marker in view space */
      const cl = Math.cos(mark.lat), sl = Math.sin(mark.lat);
      const ox = cl * Math.sin(mark.lon), oy = sl, oz = cl * Math.cos(mark.lon);
      markV[0] = m[0] * ox + m[3] * oy + m[6] * oz;
      markV[1] = m[1] * ox + m[4] * oy + m[7] * oz;
      markV[2] = m[2] * ox + m[5] * oy + m[8] * oz;
      gl.uniform3fv(U.uMark, markV);
      gl.uniform1f(U.uMarkOn, S.markOn);
      gl.uniform2f(U.uFade, (geo.fadeY0 - geo.top) * geo.dpr, (geo.fadeY1 - geo.top) * geo.dpr);
      gl.uniform1f(U.uCanvasH, geo.bh * geo.dpr);
      gl.uniform4f(U.uQuiet, (geo.qx0 - geo.left) * geo.dpr, (geo.qy0 - geo.top) * geo.dpr,
                   (geo.qx1 - geo.left) * geo.dpr, (geo.qy1 - geo.top) * geo.dpr);
      gl.uniform1f(U.uQuietOn, S.quiet);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }

    /* The still, for a machine without WebGL2: the graticule in 2D, once. */
    function draw2d() {
      const g = ctx2d;
      if (!g) return;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, canvas.width, canvas.height);
      const d = geo.dpr, R = geo.R * d;
      const cx = (geo.cx - geo.left) * d, cy = (geo.cy - geo.top) * d;
      const m = viewMatrix(rotBuf);
      const ink = `rgb(${Math.round(S.ink.r * 255)},${Math.round(S.ink.g * 255)},${Math.round(S.ink.b * 255)})`;
      g.lineWidth = Math.max(1, d);
      g.strokeStyle = ink;
      const project = (lat, lon) => {
        const cl = Math.cos(lat);
        const x = cl * Math.sin(lon), y = Math.sin(lat), z = cl * Math.cos(lon);
        return [m[0] * x + m[3] * y + m[6] * z, m[1] * x + m[4] * y + m[7] * z, m[2] * x + m[5] * y + m[8] * z];
      };
      const run = (pts, a0) => {
        g.beginPath();
        let pen = false;
        for (const p of pts) {
          if (p[2] < -0.02) { pen = false; continue; }
          const X = cx + p[0] * R, Y = cy - p[1] * R;
          if (pen) g.lineTo(X, Y); else { g.moveTo(X, Y); pen = true; }
        }
        g.globalAlpha = a0;
        g.stroke();
      };
      for (let la = -75; la <= 75; la += 15) {
        const pts = [];
        for (let lo = -180; lo <= 180; lo += 4) pts.push(project(la * D2R, lo * D2R));
        run(pts, la % 30 === 0 ? 0.55 : 0.3);
      }
      for (let lo = -180; lo < 180; lo += 15) {
        const pts = [];
        for (let la = -90; la <= 90; la += 4) pts.push(project(la * D2R, lo * D2R));
        run(pts, lo % 30 === 0 ? 0.55 : 0.3);
      }
      g.globalAlpha = 0.7;
      g.beginPath();
      g.arc(cx, cy, R, 0, Math.PI * 2);
      g.stroke();
      const ll = faceTarget();
      if (ll && S.markOn > 0.5) {
        const p = project(ll[0] * D2R, ll[1] * D2R);
        if (p[2] > 0) {
          g.globalAlpha = 1;
          g.fillStyle = ink;
          g.beginPath();
          g.arc(cx + p[0] * R, cy - p[1] * R, 4 * d, 0, Math.PI * 2);
          g.fill();
        }
      }
      g.globalAlpha = 1;
    }

    /* --------------------------------------------------------------- loop */

    function visible() {
      return !document.hidden && document.body.dataset.covered !== '1' && ready;
    }

    /* The render loop. It runs while something moves, and draws one frame when
       something merely changed; it does not run at all when hidden or covered. */
    function frame(now) {
      raf = 0;
      if (!visible()) { last = 0; return; }
      const calm = isCalm();
      const moving = !calm && ((!S.lock && S.spin !== 0) || boost > 0);
      if (moving || dirty) {
        const cap = boost > 0 ? 0 : FRAME_MS;
        if (now - drawnAt >= cap) {
          const dt = last ? Math.min((now - last) / 1000, 0.1) : 0;
          last = now;
          drawnAt = now;
          if (!calm && !S.lock) { S.c = (S.c + S.spin * dt) % 360; }
          if (!layoutOk) layoutOk = layout();
          if (layoutOk) draw();
          dirty = false;
        }
      }
      if (moving || dirty) raf = requestAnimationFrame(frame);
      else last = 0;
    }
    let layoutOk = false;

    function kick() {
      if (!raf && started) raf = requestAnimationFrame(frame);
    }

    /* ------------------------------------------------------------ the API */

    function stateName() {
      return document.documentElement.dataset.conn || 'off';
    }

    /* Called by app.js whenever the connection state or the exit country can
       have changed. Cheap when nothing did. */
    function sync(code) {
      if (!started) return;
      const st = stateName();
      if (st !== lastState) { lastState = st; tweenInk(); }
      applyFace(st === 'on' ? (code || null) : null, st);
      const calm = isCalm();
      const q = st === 'on' ? 1 : 0;
      if (S.quiet !== q) {
        if (calm || !window.gsap) S.quiet = q;
        else gsap.to(S, { quiet: q, duration: 0.5, ease: 'power2.out', overwrite: 'auto',
                          onUpdate: () => { dirty = true; kick(); } });
      }
      /* pace: quicker while negotiating */
      if (!S.lock) {
        const want = st === 'busy' ? SPIN_BUSY : SPIN_IDLE;
        if (calm || !window.gsap) S.spin = want;
        else gsap.to(S, { spin: want, duration: 0.9, ease: 'power2.out', overwrite: 'auto' });
      }
      if (st !== laidFor) {
        laidFor = st;
        requestAnimationFrame(() => { layoutOk = layout(); dirty = true; kick(); });
      }
      dirty = true;
      kick();
    }

    let facing = null, lastState = null, laidFor = null;
    function applyFace(code, st) {
      const ll = code && LL[code];
      const calm = isCalm();
      if (ll) {
        if (facing === code) return;
        facing = code;
        mark.lat = ll[0] * D2R;
        mark.lon = ll[1] * D2R;
        S.lock = true;
        const targetC = ll[1] + FACE_OFFSET;
        const targetTilt = Math.max(-0.7, Math.min(0.9, ll[0] * D2R * 0.55));
        if (calm || !window.gsap) {
          S.c = targetC; S.tilt = targetTilt; S.markOn = 1; dirty = true; kick();
          return;
        }
        /* keep turning the way it was turning, so it settles rather than reverses */
        let delta = ((targetC - S.c) % 360 + 360) % 360;       /* 0..360 going east (increasing c) */
        delta = delta - 360;                                    /* going the idle way: negative */
        const dur = Math.max(1.3, Math.min(3.4, 0.9 + Math.abs(delta) / 110));
        gsap.killTweensOf(S, 'c,tilt,markOn,spin');
        boost++;
        gsap.to(S, {
          c: S.c + delta, tilt: targetTilt, duration: dur, ease: 'sine.inOut',
          onUpdate: () => { dirty = true; },
          onComplete: () => { boost = Math.max(0, boost - 1); S.c = targetC; dirty = true; },
        });
        gsap.to(S, { markOn: 1, duration: 0.5, delay: Math.max(0, dur - 0.7), ease: 'power2.out',
                     onUpdate: () => { dirty = true; } });
      } else {
        const was = facing !== null || S.lock;
        facing = null;
        if (!was) return;
        gsap.killTweensOf(S, 'c,tilt,markOn');
        S.lock = false;
        if (calm || !window.gsap) {
          S.markOn = 0; S.tilt = REST_TILT; dirty = true; kick();
          return;
        }
        boost++;
        gsap.to(S, { markOn: 0, duration: 0.3, ease: 'power2.out', onUpdate: () => { dirty = true; } });
        gsap.to(S, {
          tilt: REST_TILT, duration: 1.4, ease: 'sine.inOut',
          onUpdate: () => { dirty = true; },
          onComplete: () => { boost = Math.max(0, boost - 1); },
        });
      }
    }

    /* Hit test for the click area: is this window point on the globe? */
    function hit(x, y) {
      if (!geo.R) return false;
      const dx = x - geo.cx, dy = y - geo.cy;
      return dx * dx + dy * dy <= geo.R * geo.R;
    }

    function init(cv, opts) {
      canvas = cv;
      host = document.documentElement;
      isCalm = (opts && opts.calm) || (() => false);
      try {
        gl = canvas.getContext('webgl2', {
          alpha: true, premultipliedAlpha: true, antialias: false, depth: false,
          stencil: false, powerPreference: 'low-power',
        });
      } catch (e) { gl = null; }
      if (gl) {
        ready = build();
        if (!ready) gl = null;
      }
      if (!gl) {
        ctx2d = canvas.getContext('2d');
        ready = !!ctx2d;
      }
      if (!ready) return false;

      canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); ready = false; });
      canvas.addEventListener('webglcontextrestored', () => {
        ready = build();
        layoutOk = layout();
        dirty = true;
        kick();
      });

      readTheme();
      Object.assign(S.ink, targetInk());
      S.c = 8;
      started = true;
      layoutOk = layout();

      new MutationObserver(() => { readTheme(); tweenInk(); kick(); })
        .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
      let rz = 0;
      const relayout = () => {
        cancelAnimationFrame(rz);
        rz = requestAnimationFrame(() => { layoutOk = layout(); dirty = true; kick(); });
      };
      window.addEventListener('resize', relayout);
      if (window.ResizeObserver) new ResizeObserver(relayout).observe(document.getElementById('hero') || document.body);
      document.addEventListener('visibilitychange', () => { last = 0; kick(); });
      new MutationObserver(() => { last = 0; kick(); })
        .observe(document.body, { attributes: true, attributeFilter: ['data-covered'] });

      /* reduced motion can flip while the app is open */
      if (window.gsap) {
        gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => {
          dirty = true; kick();
          return () => { dirty = true; kick(); };
        });
      }
      kick();
      return true;
    }

    return { init, sync, hit, has: (c) => !!LL[c], geometry: () => geo };
  })();
})();
