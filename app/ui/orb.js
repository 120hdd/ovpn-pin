/* The connection core: gooey metaballs in the state palette.

   The fragment shader is vendored from Paper Shaders (paper-design/shaders,
   Apache-2.0 - see PAPER-SHADERS-LICENSE.txt): their "metaballs" effect -
   colored soft balls wandering around the center, merging into organic
   shapes. Two departures from their source, both noted inline: the noise
   texture is replaced with their own procedural hash21 (no asset to ship),
   and the sizing vertex shader is reduced to the square-canvas case. The
   mount code and the state wiring are ours.

   State speaks through two channels:
   - the ball colors follow the hero's --blob-a/b/c palette: lavender/teal
     at rest, amber/coral while negotiating, mint/cyan when the route is up,
     rose/violet on failure. Merging balls blend their colors.
   - speed follows data-state: calm idle, urgent busy, settled on, near-still
     fail. Both ease instead of snapping.

   The background is fully transparent - the balls float straight on the app
   background and the CSS glow/orbits behind them. 30fps cap; a still frame
   under prefers-reduced-motion. */

(function () {
  'use strict';

  const VERT = `#version 300 es
precision mediump float;
out vec2 v_objectUV;
void main(){
  vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  vec2 pos = v*2.0 - 1.0;
  gl_Position = vec4(pos, 0.0, 1.0);
  /* square canvas: no aspect fitting needed. 1.15 spreads object space so
     the balls' roaming range sits inside the 210px stage's orbit rings. */
  v_objectUV = vec2(pos.x, -pos.y)*0.5*1.15;
}`;

  /* Fragment shader from paper-design/shaders (metaballs.ts), Apache-2.0.
     Helpers (PI, banding fix) inlined from their shader-utils.ts; their
     texture-based randomR is swapped for the procedural hash21 from the
     same file, so no noise texture needs shipping. */
  const FRAG = `#version 300 es
precision mediump float;

uniform float u_time;

uniform vec4 u_colorBack;
uniform vec4 u_colors[8];
uniform float u_colorsCount;
uniform float u_size;
uniform float u_count;

in vec2 v_objectUV;

out vec4 fragColor;

#define TWO_PI 6.28318530718
#define PI 3.14159265358979323846

float hash21(vec2 p) {
  p = fract(p * vec2(0.3183099, 0.3678794)) + 0.1;
  p += dot(p, p + 19.19);
  return fract(p.x * p.y);
}
float randomR(vec2 p) {
  return hash21(floor(p));
}
float noise(float x) {
  float i = floor(x);
  float f = fract(x);
  float u = f * f * (3.0 - 2.0 * f);
  vec2 p0 = vec2(i, 0.0);
  vec2 p1 = vec2(i + 1.0, 0.0);
  return mix(randomR(p0), randomR(p1), u);
}

float getBallShape(vec2 uv, vec2 c, float p) {
  float s = .5 * length(uv - c);
  s = 1. - clamp(s, 0., 1.);
  s = pow(s, p);
  return s;
}

void main() {
  vec2 shape_uv = v_objectUV;

  shape_uv += .5;

  const float firstFrameOffset = 2503.4;
  float t = .2 * (u_time + firstFrameOffset);

  vec3 totalColor = vec3(0.);
  float totalShape = 0.;
  float totalOpacity = 0.;

  for (int i = 0; i < 20; i++) {
    if (i >= int(ceil(u_count))) break;

    float idxFract = float(i) / 20.;
    float angle = TWO_PI * idxFract;

    float speed = 1. - .2 * idxFract;
    float noiseX = noise(angle * 10. + float(i) + t * speed);
    float noiseY = noise(angle * 20. + float(i) - t * speed);

    vec2 pos = vec2(.5) + 1e-4 + .9 * (vec2(noiseX, noiseY) - .5);

    int safeIndex = i % int(u_colorsCount + 0.5);
    vec4 ballColor = u_colors[safeIndex];
    ballColor.rgb *= ballColor.a;

    float sizeFrac = 1.;
    if (float(i) > floor(u_count - 1.)) {
      sizeFrac *= fract(u_count);
    }

    float shape = getBallShape(shape_uv, pos, 45. - 30. * u_size * sizeFrac);
    shape *= pow(u_size, .2);
    shape = smoothstep(0., 1., shape);

    totalColor += ballColor.rgb * shape;
    totalShape += shape;
    totalOpacity += ballColor.a * shape;
  }

  totalColor /= max(totalShape, 1e-4);
  totalOpacity /= max(totalShape, 1e-4);

  float edge_width = fwidth(totalShape);
  float finalShape = smoothstep(.4, .4 + edge_width, totalShape);

  vec3 color = totalColor * finalShape;
  float opacity = totalOpacity * finalShape;

  vec3 bgColor = u_colorBack.rgb * u_colorBack.a;
  color = color + bgColor * (1. - opacity);
  opacity = opacity + u_colorBack.a * (1. - opacity);

  color += 1. / 256. * (fract(sin(dot(.014 * gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453123) - .5);

  fragColor = vec4(color, opacity);
}`;

  /* ---------------------------------------------------------------- mount */

  function hexToRgb(str) {
    const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec((str || '').trim());
    if (!m) return null;
    let h = m[1];
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    return [
      parseInt(h.slice(0, 2), 16)/255,
      parseInt(h.slice(2, 4), 16)/255,
      parseInt(h.slice(4, 6), 16)/255,
    ];
  }

  /* The pace of the goo follows the pace of the connection. */
  const STATE_SPEED = { off: 0.5, busy: 1.6, on: 0.7, fail: 0.25 };

  const FALLBACK = {
    a: [0.61, 0.55, 1.0],
    b: [0.30, 0.89, 0.82],
    c: [0.28, 0.23, 0.76],
  };

  window.OrbCore = {
    init(canvas) {
      let gl;
      try {
        gl = canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: false });
      } catch (_) { gl = null; }
      if (!gl) return false;

      function shader(type, src) {
        const s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
          console.error('orb shader compile:', gl.getShaderInfoLog(s));
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
        console.error('orb shader link:', gl.getProgramInfoLog(prog));
        return false;
      }
      gl.useProgram(prog);

      const U = {};
      for (const n of ['u_time', 'u_colorBack', 'u_colorsCount', 'u_size', 'u_count'])
        U[n] = gl.getUniformLocation(prog, n);
      U.u_colors = gl.getUniformLocation(prog, 'u_colors[0]');

      /* fixed settings, tuned for a 210px stage */
      gl.uniform4f(U.u_colorBack, 0, 0, 0, 0);      /* transparent: nothing behind */
      gl.uniform1f(U.u_colorsCount, 3);
      gl.uniform1f(U.u_count, 7);
      gl.uniform1f(U.u_size, 0.8);

      const hero = canvas.closest('.hero') || document.getElementById('hero');
      function targets() {
        const state = (hero && hero.dataset.state) || 'off';
        const cs = hero && getComputedStyle(hero);
        const pick = (name, fb) => (cs && hexToRgb(cs.getPropertyValue(name))) || fb;
        return {
          a: pick('--blob-a', FALLBACK.a),
          b: pick('--blob-b', FALLBACK.b),
          c: pick('--blob-c', FALLBACK.c),
          speed: STATE_SPEED[state] != null ? STATE_SPEED[state] : 0.5,
        };
      }
      let want = targets();
      const cur = { a: want.a.slice(), b: want.b.slice(), c: want.c.slice(), speed: want.speed };
      if (hero) {
        new MutationObserver(() => { want = targets(); })
          .observe(hero, { attributes: true, attributeFilter: ['data-state'] });
      }

      /* u_colors is vec4[8]; we use the first three slots */
      const colorData = new Float32Array(32);
      function pushColors() {
        const slots = [cur.a, cur.b, cur.c];
        for (let s = 0; s < 3; s++) {
          colorData[s*4 + 0] = slots[s][0];
          colorData[s*4 + 1] = slots[s][1];
          colorData[s*4 + 2] = slots[s][2];
          colorData[s*4 + 3] = 1;
        }
        gl.uniform4fv(U.u_colors, colorData);
      }

      function resize() {
        /* 1.5x supersample keeps the gooey edges clean at 210px */
        const dpr = Math.min(devicePixelRatio || 1, 2)*1.5;
        const box = canvas.getBoundingClientRect();
        if (!box.width || !box.height) return false;
        const w = Math.round(box.width*dpr), h = Math.round(box.height*dpr);
        if (canvas.width !== w || canvas.height !== h) {
          canvas.width = w; canvas.height = h;
          gl.viewport(0, 0, w, h);
        }
        return true;
      }

      const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
      let last = 0, phase = 0, settled = false;

      function draw() {
        gl.uniform1f(U.u_time, phase);
        pushColors();
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      }

      function frame(now) {
        requestAnimationFrame(frame);
        if (document.hidden) return;
        if (now - last < 32) return;          /* 30fps is plenty for goo */
        const dt = Math.min(now - last, 200)/1000;
        last = now;
        if (!resize()) return;

        /* ease palette and pace toward the current state */
        const k = 1 - Math.exp(-dt*6);
        let moving = Math.abs(want.speed - cur.speed) > 1e-3;
        cur.speed += (want.speed - cur.speed)*k;
        for (const key of ['a', 'b', 'c']) {
          for (let i = 0; i < 3; i++) {
            const diff = want[key][i] - cur[key][i];
            if (Math.abs(diff) > 1e-4) { cur[key][i] += diff*k; moving = true; }
          }
        }

        if (reduceMotion.matches) {
          if (settled && !moving) return;
          settled = !moving;
          phase = 5.0;
          draw();
          return;
        }
        settled = false;
        phase += dt*cur.speed;
        draw();
      }
      requestAnimationFrame(frame);
      return true;
    },
  };
})();
