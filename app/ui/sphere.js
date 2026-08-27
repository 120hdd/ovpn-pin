/* The connection core: a twisted, colour-graded sphere.

   The technique is Mario Carrillo's "Twisted Colorful Spheres with Three.js"
   (Codrops, 26 Jan 2021 - tympanus.net/codrops/2021/01/26/twisted-colorful-
   spheres-with-three-js/). Three steps, in this order:

     1. push every vertex of a sphere out along its own normal by periodic
        Perlin noise, so the ball goes lumpy;
     2. twist the result about Y by a sine wave that travels up the body -
        the article's `angle = sin(uv.y*uFrequency + t) * uAmplitude`;
     3. colour it with that same distortion value through a cosine palette,
        which is what keeps it from reading as a grey lump with lighting on.

   What is different here is that there is no three.js. The article's demo
   pulls in three, glsl-noise and glsl-rotate - the better part of a megabyte
   to draw one ball. This file is raw WebGL2: the icosphere is built in JS at
   load, the two shader helpers are vendored inline (webgl-noise's pnoise,
   MIT; rotateY is six lines), and it mounts through the same
   window.OrbCore.init(canvas) contract app.js already falls back from when
   the GPU is blocked. One file, no dependency, no build step.

   State speaks through the two channels the app already uses:
   - the palette is fitted to the hero's --blob-a/b/c, so the sphere is
     lavender/teal at rest, amber/coral while negotiating, mint/cyan when the
     route is up, rose/violet on failure. The fit is exact - see fitPalette.
   - the pace, the twist and the noise all follow data-state: calm and barely
     kneading at idle, fast and wrung tight while busy, smooth and slow when
     connected, near-frozen and slack on failure.

   Both ease instead of snapping. The background stays transparent, so the
   CSS glow, halo and orbit rings behind the canvas still do their work.
   A still frame under prefers-reduced-motion. */

(function () {
  'use strict';

  /* ------------------------------------------------------------ geometry */

  /* A subdivided icosahedron rather than a lat/long sphere: even triangles
     everywhere, no pinched pole for the twist to shred. Order 4 is 2562
     vertices / 5120 triangles - about 8px an edge at this size, which the
     displacement never gets coarse enough to show. Position doubles as the
     normal, so there is only one attribute to upload. */
  function icosphere(order) {
    const t = (1 + Math.sqrt(5)) / 2;
    const pos = [
      -1, t, 0,   1, t, 0,  -1, -t, 0,   1, -t, 0,
       0, -1, t,  0, 1, t,   0, -1, -t,  0, 1, -t,
       t, 0, -1,  t, 0, 1,  -t, 0, -1,  -t, 0, 1,
    ];
    for (let i = 0; i < pos.length; i += 3) {
      const l = Math.hypot(pos[i], pos[i + 1], pos[i + 2]);
      pos[i] /= l; pos[i + 1] /= l; pos[i + 2] /= l;
    }

    let idx = [
      0, 11, 5,  0, 5, 1,   0, 1, 7,   0, 7, 10,  0, 10, 11,
      1, 5, 9,   5, 11, 4,  11, 10, 2, 10, 7, 6,  7, 1, 8,
      3, 9, 4,   3, 4, 2,   3, 2, 6,   3, 6, 8,   3, 8, 9,
      4, 9, 5,   2, 4, 11,  6, 2, 10,  8, 6, 7,   9, 8, 1,
    ];

    /* One cache shared across every level: an edge is only ever split once,
       and without it the vertex count triples and the seams show. */
    const seen = new Map();
    const mid = (a, b) => {
      const key = a < b ? a * 65536 + b : b * 65536 + a;
      const hit = seen.get(key);
      if (hit !== undefined) return hit;
      const x = (pos[a * 3] + pos[b * 3]) / 2;
      const y = (pos[a * 3 + 1] + pos[b * 3 + 1]) / 2;
      const z = (pos[a * 3 + 2] + pos[b * 3 + 2]) / 2;
      const l = Math.hypot(x, y, z);
      const n = pos.length / 3;
      pos.push(x / l, y / l, z / l);
      seen.set(key, n);
      return n;
    };

    for (let s = 0; s < order; s++) {
      const next = [];
      for (let i = 0; i < idx.length; i += 3) {
        const a = idx[i], b = idx[i + 1], c = idx[i + 2];
        const ab = mid(a, b), bc = mid(b, c), ca = mid(c, a);
        next.push(a, ab, ca,  b, bc, ab,  c, ca, bc,  ab, bc, ca);
      }
      idx = next;
    }
    return { pos: new Float32Array(pos), idx: new Uint16Array(idx) };
  }

  /* ------------------------------------------------------------- shaders */

  /* Periodic 3D Perlin noise, verbatim from Stefan Gustavson's webgl-noise
     (ashima/webgl-noise, periodic/3d, MIT) - the same pnoise the article
     imports through glsl-noise. Vendored because it is seventy lines and the
     alternative is a package. */
  const PNOISE = `
vec3 mod289(vec3 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 mod289(vec4 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 permute(vec4 x){ return mod289(((x*34.0)+1.0)*x); }
vec4 taylorInvSqrt(vec4 r){ return 1.79284291400159 - 0.85373472095314*r; }
vec3 fade(vec3 t){ return t*t*t*(t*(t*6.0-15.0)+10.0); }

float pnoise(vec3 P, vec3 rep) {
  vec3 Pi0 = mod(floor(P), rep);
  vec3 Pi1 = mod(Pi0 + vec3(1.0), rep);
  Pi0 = mod289(Pi0);
  Pi1 = mod289(Pi1);
  vec3 Pf0 = fract(P);
  vec3 Pf1 = Pf0 - vec3(1.0);
  vec4 ix = vec4(Pi0.x, Pi1.x, Pi0.x, Pi1.x);
  vec4 iy = vec4(Pi0.yy, Pi1.yy);
  vec4 iz0 = Pi0.zzzz;
  vec4 iz1 = Pi1.zzzz;

  vec4 ixy = permute(permute(ix) + iy);
  vec4 ixy0 = permute(ixy + iz0);
  vec4 ixy1 = permute(ixy + iz1);

  vec4 gx0 = ixy0 * (1.0/7.0);
  vec4 gy0 = fract(floor(gx0) * (1.0/7.0)) - 0.5;
  gx0 = fract(gx0);
  vec4 gz0 = vec4(0.5) - abs(gx0) - abs(gy0);
  vec4 sz0 = step(gz0, vec4(0.0));
  gx0 -= sz0 * (step(0.0, gx0) - 0.5);
  gy0 -= sz0 * (step(0.0, gy0) - 0.5);

  vec4 gx1 = ixy1 * (1.0/7.0);
  vec4 gy1 = fract(floor(gx1) * (1.0/7.0)) - 0.5;
  gx1 = fract(gx1);
  vec4 gz1 = vec4(0.5) - abs(gx1) - abs(gy1);
  vec4 sz1 = step(gz1, vec4(0.0));
  gx1 -= sz1 * (step(0.0, gx1) - 0.5);
  gy1 -= sz1 * (step(0.0, gy1) - 0.5);

  vec3 g000 = vec3(gx0.x, gy0.x, gz0.x);
  vec3 g100 = vec3(gx0.y, gy0.y, gz0.y);
  vec3 g010 = vec3(gx0.z, gy0.z, gz0.z);
  vec3 g110 = vec3(gx0.w, gy0.w, gz0.w);
  vec3 g001 = vec3(gx1.x, gy1.x, gz1.x);
  vec3 g101 = vec3(gx1.y, gy1.y, gz1.y);
  vec3 g011 = vec3(gx1.z, gy1.z, gz1.z);
  vec3 g111 = vec3(gx1.w, gy1.w, gz1.w);

  vec4 norm0 = taylorInvSqrt(vec4(dot(g000,g000), dot(g010,g010), dot(g100,g100), dot(g110,g110)));
  g000 *= norm0.x; g010 *= norm0.y; g100 *= norm0.z; g110 *= norm0.w;
  vec4 norm1 = taylorInvSqrt(vec4(dot(g001,g001), dot(g011,g011), dot(g101,g101), dot(g111,g111)));
  g001 *= norm1.x; g011 *= norm1.y; g101 *= norm1.z; g111 *= norm1.w;

  float n000 = dot(g000, Pf0);
  float n100 = dot(g100, vec3(Pf1.x, Pf0.yz));
  float n010 = dot(g010, vec3(Pf0.x, Pf1.y, Pf0.z));
  float n110 = dot(g110, vec3(Pf1.xy, Pf0.z));
  float n001 = dot(g001, vec3(Pf0.xy, Pf1.z));
  float n101 = dot(g101, vec3(Pf1.x, Pf0.y, Pf1.z));
  float n011 = dot(g011, vec3(Pf0.x, Pf1.yz));
  float n111 = dot(g111, Pf1);

  vec3 f = fade(Pf0);
  vec4 nz = mix(vec4(n000, n100, n010, n110), vec4(n001, n101, n011, n111), f.z);
  vec2 nyz = mix(nz.xy, nz.zw, f.y);
  return 2.2 * mix(nyz.x, nyz.y, f.x);
}`;

  const VERT = `#version 300 es
precision highp float;

/* unit sphere: the position IS the normal, so one attribute is enough */
layout(location = 0) in vec3 a_pos;

uniform mat4  u_proj;
uniform mat3  u_rot;      /* spin about Y, then the fixed tilt */
uniform float u_dist;     /* camera pull-back along -Z */
uniform float u_time;     /* noise and twist phase, not wall clock */
uniform float u_density;
uniform float u_strength;
uniform float u_twist;
uniform float u_freq;

out float v_distort;
out float v_lat;
out vec3  v_normal;
out vec3  v_view;
${PNOISE}

/* glsl-rotate's rotateY, written out. */
vec3 rotateY(vec3 p, float a) {
  float c = cos(a), s = sin(a);
  return vec3(c*p.x + s*p.z, p.y, -s*p.x + c*p.z);
}

/* One point of the deformed surface, from a direction on the undeformed
   sphere. Steps 1 and 2 of the article together, because the normal has to
   be rebuilt from both of them at once. */
vec3 surface(vec3 n, out float distort) {
  distort = pnoise(n*u_density + u_time, vec3(10.0)) * u_strength;
  vec3 p = n * (1.0 + distort);
  /* The article twists by uv.y. An icosphere has no UVs, and on a unit
     sphere position.y is the same latitude under another name. */
  return rotateY(p, sin(p.y*u_freq + u_time) * u_twist);
}

void main() {
  vec3 n = normalize(a_pos);

  /* The article never rebuilds the normals - three.js lights the blob with
     the ones the sphere arrived with, which is why its lumps read as painted
     on rather than lit. Sampling the surface twice more, a hair away across
     the tangent plane, gives the normal of the surface actually drawn. Three
     noise evaluations a vertex over 2562 vertices: free. */
  vec3 up = abs(n.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
  vec3 t1 = normalize(cross(up, n));
  vec3 t2 = cross(n, t1);
  const float e = 0.012;

  float d, d1, d2;
  vec3 p  = surface(n, d);
  vec3 p1 = surface(normalize(n + t1*e), d1);
  vec3 p2 = surface(normalize(n + t2*e), d2);
  vec3 nrm = normalize(cross(p1 - p, p2 - p));
  if (dot(nrm, p) < 0.0) nrm = -nrm;          /* keep it pointing outward */

  vec3 vp = u_rot*p - vec3(0.0, 0.0, u_dist);

  v_distort = d / max(u_strength, 1e-4);      /* normalised to [-1,1] */
  v_lat     = p.y;
  v_normal  = u_rot*nrm;
  v_view    = -vp;                            /* the camera is the origin here */
  gl_Position = u_proj * vec4(vp, 1.0);
}`;

  const FRAG = `#version 300 es
precision highp float;

in float v_distort;
in float v_lat;
in vec3  v_normal;
in vec3  v_view;

uniform vec3  u_pa, u_pb, u_pc, u_pd;   /* cosine palette, fitted in JS */
uniform vec3  u_rim;

out vec4 fragColor;

/* Inigo Quilez's cosine gradient - the article's colouring step, unchanged. */
vec3 cosPalette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
  return a + b * cos(6.28318 * (c*t + d));
}

void main() {
  /* The article feeds the raw distortion straight in. Two changes here.

     One: it is normalised first, so a state turning the noise up changes how
     lumpy the body is without also changing what colour it is.

     Two: latitude carries most of the ramp and the distortion modulates it,
     rather than the other way round. Perlin noise almost never reaches its
     own extremes - it sits around the middle - so distortion alone parks the
     whole sphere on --blob-b and the other two stops are never seen. Reading
     top-down puts --blob-a at the crown, --blob-b through the waist and
     --blob-c underneath, which is also the order the light wants them in,
     and the lumps then push the bands around within that. */
  float t = clamp(0.5 - 0.34*v_lat + 0.55*v_distort, 0.0, 1.0);
  vec3 base = clamp(cosPalette(t, u_pa, u_pb, u_pc, u_pd), 0.0, 1.0);

  vec3 V = normalize(v_view);
  vec3 N = normalize(v_normal);
  /* Two-sided, and by facing rather than by winding: a hard twist folds the
     body through itself, and the inside of the fold has to light too. */
  if (dot(N, V) < 0.0) N = -N;
  vec3 L = normalize(vec3(-0.42, 0.78, 0.62));

  float lam  = dot(N, L)*0.5 + 0.5;           /* wrapped: nothing goes black */
  float rim  = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 2.8);
  float spec = pow(max(dot(reflect(-L, N), V), 0.0), 30.0);

  /* Kept deliberately short of blowing out. The gain used to run to 1.16
     before the rim and the highlight were even added, which washed the crown
     of the body to white and took the palette with it - invisible while the
     fragments were still being composited at partial alpha, obvious the
     moment they were not. */
  vec3 col = base * (0.44 + 0.56*lam*lam);
  col += u_rim * rim * 0.42;
  col += vec3(1.0) * spec * 0.15;

  /* 8-bit banding is plainly visible across a gradient this smooth */
  col += (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;

  /* Fully opaque, always. This is a solid body that overlaps itself, and any
     alpha below 1 here blends its own back faces through its front - which
     arrives as flat translucent sheets with straight edges across the body,
     because that is what the far side's triangles are. The fade-in is CSS
     opacity on the canvas instead, which composites the finished image after
     the depth buffer has already settled what is in front. */
  fragColor = vec4(col, 1.0);
}`;

  /* --------------------------------------------------------------- state */

  /* Idle kneads slowly. Busy wrings itself out - faster spin, tighter twist,
     denser noise. Connected settles into a smooth, slow turn. Failure goes
     slack and very nearly stops.

     Twist is the dial that decides whether this reads as a body or as a rag.
     Much past about 1.4 the noise ridges stretch into thin flapping lobes
     and the silhouette goes ragged - dramatic, but it stops being a sphere,
     and three of these four states have to sit in the same app as each other.

     Density is only a look: how many lumps, and how big. There is no floor
     on it. An earlier pass here claimed one, on the theory that sparse
     Perlin cells were showing their lattice as hard creases; that was wrong.
     The creases were the fragment shader compositing the body's own back
     faces through its front, raising the density merely broke the ghosts
     into smaller pieces, and the note at the end of the fragment shader is
     what actually fixed it.

     Wave is lower than it looks like it should be: the phase is added to the
     noise coordinate before density scales it, so denser cells stream past
     faster at the same nominal speed. */
  const STATES = {
    off:  { spin: 0.17, wave: 0.32, twist: 0.92, noise: 0.24, density: 2.50 },
    busy: { spin: 0.52, wave: 1.00, twist: 1.05, noise: 0.28, density: 2.60 },
    on:   { spin: 0.24, wave: 0.42, twist: 0.62, noise: 0.20, density: 2.45 },
    fail: { spin: 0.05, wave: 0.10, twist: 0.38, noise: 0.19, density: 2.40 },
  };

  const FALLBACK = { a: [0.61, 0.55, 1.0], b: [0.30, 0.89, 0.82], c: [0.28, 0.23, 0.76] };

  function hexToRgb(str) {
    const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec((str || '').trim());
    if (!m) return null;
    let h = m[1];
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    return [
      parseInt(h.slice(0, 2), 16) / 255,
      parseInt(h.slice(2, 4), 16) / 255,
      parseInt(h.slice(4, 6), 16) / 255,
    ];
  }

  /* The bridge between the article and this app's palette.

     A cosine gradient is a + b*cos(2pi*(c*t + d)) per channel, and the demo
     picks its four vectors by eye. We cannot: the sphere has to come out
     lavender/teal at rest and mint/cyan when connected, in the app's exact
     hexes, because the route label and the orbit beads are already those
     colours. So solve for the coefficients instead.

     Fix c = 1/2, so t across [0,1] sweeps half a period and the ramp runs
     end to end instead of looping. Write phi = 2pi*d. Then

       f(0)   = a + b*cos(phi)   -> must equal A   (--blob-a)
       f(1/2) = a - b*sin(phi)   -> must equal B   (--blob-b)
       f(1)   = a - b*cos(phi)   -> must equal C   (--blob-c)

     Adding the first and last gives a = (A+C)/2; subtracting them gives
     b*cos(phi) = (A-C)/2; the middle one gives b*sin(phi) = a - B. Two legs
     of a right triangle, so b is the hypotenuse and phi the angle. Exact
     through all three stops, still a real cosine palette, nothing eyeballed. */
  function fitPalette(A, B, C, out) {
    for (let i = 0; i < 3; i++) {
      const mid = (A[i] + C[i]) / 2;
      const x = (A[i] - C[i]) / 2;
      const y = mid - B[i];
      out.a[i] = mid;
      out.b[i] = Math.hypot(x, y);
      out.c[i] = 0.5;
      out.d[i] = Math.atan2(y, x) / (2 * Math.PI);
    }
    return out;
  }

  /* Column-major perspective. Aspect is 1 for the app's square stage, but
     the lab page can be any shape. */
  function perspective(out, fovy, aspect, near, far) {
    const f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
    out[0] = f / aspect; out[1] = 0; out[2] = 0; out[3] = 0;
    out[4] = 0; out[5] = f; out[6] = 0; out[7] = 0;
    out[8] = 0; out[9] = 0; out[10] = (far + near) * nf; out[11] = -1;
    out[12] = 0; out[13] = 0; out[14] = 2 * far * near * nf; out[15] = 0;
    return out;
  }

  /* --------------------------------------------------------------- mount */

  window.OrbCore = {
    init(canvas) {
      let gl;
      try {
        gl = canvas.getContext('webgl2', {
          alpha: true, premultipliedAlpha: true, antialias: true, depth: true,
        });
      } catch (_) { gl = null; }
      if (!gl) return false;

      function shader(type, src) {
        const s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
          console.error('sphere shader compile:', gl.getShaderInfoLog(s));
          return null;
        }
        return s;
      }
      const vs = shader(gl.VERTEX_SHADER, VERT);
      const fs = shader(gl.FRAGMENT_SHADER, FRAG);
      if (!vs || !fs) return false;
      const prog = gl.createProgram();
      gl.attachShader(prog, vs);
      gl.attachShader(prog, fs);
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
        console.error('sphere shader link:', gl.getProgramInfoLog(prog));
        return false;
      }
      gl.useProgram(prog);

      const mesh = icosphere(4);
      gl.bindVertexArray(gl.createVertexArray());
      gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ARRAY_BUFFER, mesh.pos, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, mesh.idx, gl.STATIC_DRAW);

      gl.enable(gl.DEPTH_TEST);
      /* No back-face culling, on purpose: past about a radian the twist
         folds the body through itself and the interior faces are part of the
         shape. The depth buffer sorts them; the fragment shader lights
         whichever side ends up facing us. */
      gl.disable(gl.CULL_FACE);
      /* And no blending either, which matters more than it sounds. Every
         fragment is opaque, so there is nothing to blend - but leaving it on
         means anything that drops alpha below 1 silently composites the back
         of the body through the front. The soft silhouette comes from MSAA
         instead: the resolve writes the partial coverage into the drawing
         buffer's alpha and the browser composites the canvas with it. */
      gl.disable(gl.BLEND);
      gl.clearColor(0, 0, 0, 0);

      const U = {};
      for (const n of ['u_proj', 'u_rot', 'u_dist', 'u_time', 'u_density',
                       'u_strength', 'u_twist', 'u_freq', 'u_pa', 'u_pb',
                       'u_pc', 'u_pd', 'u_rim'])
        U[n] = gl.getUniformLocation(prog, n);

      /* Framed to radius 1.68 rather than to the body: the extra headroom is
         what keeps the sphere off the 163px inner orbit ring, and leaves the
         twist somewhere to throw a lobe. */
      const FOV = 0.50, DIST = 1.68 / Math.sin(FOV / 2), TILT = -0.24;
      const proj = new Float32Array(16);
      const rot = new Float32Array(9);
      gl.uniform1f(U.u_dist, DIST);
      gl.uniform1f(U.u_freq, 2.30);

      const hero = canvas.closest('.hero') || document.getElementById('hero')
                || document.documentElement;
      function targets() {
        const state = hero.dataset.state || 'off';
        const cs = getComputedStyle(hero);
        const pick = (name, fb) => hexToRgb(cs.getPropertyValue(name)) || fb;
        const s = STATES[state] || STATES.off;
        return {
          a: pick('--blob-a', FALLBACK.a),
          b: pick('--blob-b', FALLBACK.b),
          c: pick('--blob-c', FALLBACK.c),
          spin: s.spin, wave: s.wave, twist: s.twist,
          noise: s.noise, density: s.density,
        };
      }
      let want = targets();
      const cur = {
        a: want.a.slice(), b: want.b.slice(), c: want.c.slice(),
        spin: want.spin, wave: want.wave, twist: want.twist,
        noise: want.noise, density: want.density,
      };
      new MutationObserver(() => { want = targets(); })
        .observe(hero, { attributes: true, attributeFilter: ['data-state'] });

      const pal = { a: [0, 0, 0], b: [0, 0, 0], c: [0, 0, 0], d: [0, 0, 0] };

      function resize() {
        const dpr = Math.min(devicePixelRatio || 1, 2);
        const box = canvas.getBoundingClientRect();
        if (!box.width || !box.height) return false;
        const w = Math.round(box.width * dpr), h = Math.round(box.height * dpr);
        if (canvas.width !== w || canvas.height !== h) {
          canvas.width = w; canvas.height = h;
          gl.viewport(0, 0, w, h);
          gl.uniformMatrix4fv(U.u_proj, false, perspective(proj, FOV, w / h, 0.1, 20));
        }
        return true;
      }

      function draw(phase, spin) {
        /* tilt about X, then spin about Y, folded into one mat3 so the
           vertex shader can rotate the normal with the same matrix */
        const cs = Math.cos(spin), ss = Math.sin(spin);
        const cx = Math.cos(TILT), sx = Math.sin(TILT);
        rot[0] = cs;  rot[1] = sx * ss;  rot[2] = -cx * ss;
        rot[3] = 0;   rot[4] = cx;       rot[5] = sx;
        rot[6] = ss;  rot[7] = -sx * cs; rot[8] = cx * cs;
        gl.uniformMatrix3fv(U.u_rot, false, rot);

        fitPalette(cur.a, cur.b, cur.c, pal);
        gl.uniform3fv(U.u_pa, pal.a);
        gl.uniform3fv(U.u_pb, pal.b);
        gl.uniform3fv(U.u_pc, pal.c);
        gl.uniform3fv(U.u_pd, pal.d);
        /* the rim takes --blob-a, so the silhouette still carries the state
           colour where the body has turned away from the light */
        gl.uniform3f(U.u_rim, cur.a[0], cur.a[1], cur.a[2]);

        gl.uniform1f(U.u_time, phase);
        gl.uniform1f(U.u_twist, cur.twist);
        gl.uniform1f(U.u_strength, cur.noise);
        gl.uniform1f(U.u_density, cur.density);

        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.drawElements(gl.TRIANGLES, mesh.idx.length, gl.UNSIGNED_SHORT, 0);
      }

      const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
      const SCALARS = ['spin', 'wave', 'twist', 'noise', 'density'];
      let last = 0, phase = 0, spin = 0, fade = 0, settled = false;

      function frame(now) {
        requestAnimationFrame(frame);
        if (document.hidden) { last = 0; return; }
        if (!last) { last = now; return; }
        const dt = Math.min(now - last, 100) / 1000;
        last = now;
        if (!resize()) return;

        /* ease the palette and the pace toward the current state */
        const k = 1 - Math.exp(-dt * 5);
        let moving = fade < 1;
        for (const key of SCALARS) {
          const diff = want[key] - cur[key];
          if (Math.abs(diff) > 1e-4) { cur[key] += diff * k; moving = true; }
        }
        for (const key of ['a', 'b', 'c']) {
          for (let i = 0; i < 3; i++) {
            const diff = want[key][i] - cur[key][i];
            if (Math.abs(diff) > 1e-4) { cur[key][i] += diff * k; moving = true; }
          }
        }
        /* the fade rides on the element, not on the fragments - see the
           opacity note at the end of the fragment shader */
        if (fade < 1) {
          fade = Math.min(1, fade + dt * 1.8);
          canvas.style.opacity = fade < 1 ? fade.toFixed(3) : '';
        }

        if (reduceMotion.matches) {
          if (settled && !moving) return;
          settled = !moving;
          draw(3.1, 0.45);             /* one frame, picked for its profile */
          return;
        }
        settled = false;
        phase += dt * cur.wave;
        spin = (spin + dt * cur.spin) % (Math.PI * 2);
        draw(phase, spin);
      }
      requestAnimationFrame(frame);
      return true;
    },
  };
})();
