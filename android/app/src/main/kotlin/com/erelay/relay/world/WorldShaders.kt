package com.erelay.relay.world

/*
 * The world's shaders (GLSL ES 3.00). The planet's is the desktop's GLOBE_FRAG
 * line for line; what three.js gave the desktop for free (lines, sprites, rings)
 * is written out here as four small programs.
 */

/**
 * The planet: a dark sphere under a fixed light, a faint 15 degree graticule,
 * one country filled with its flag (the one the journey has left, fading, and
 * the one it arrives at, spreading), and a thin pastel limb.
 *
 * Each fill is a mask (white where the country is, over its own box) and the
 * flag stretched over the same box; both are read at the fragment's own
 * longitude and latitude, so the flag never wanders off the land. The fill
 * spreads outward from where it lands: a fragment is lit while its angle from
 * that point is under fill * span. The bitmaps are uploaded north-row-first, so
 * v is flipped on the way in.
 */
internal const val PLANET_VERT = """#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 uV;
uniform mat4 uP;
out vec3 vN;
out vec3 vV;
out vec3 vO;
void main() {
  vO = normalize(a_pos);
  vec4 mv = uV * vec4(a_pos, 1.0);
  vV = normalize(-mv.xyz);
  vN = normalize(mat3(uV) * a_pos);
  gl_Position = uP * mv;
}
"""

internal const val PLANET_FRAG = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uMaskO, uMaskD, uFlagO, uFlagD;
uniform vec4 uBoxO, uBoxD;
uniform float uFillO, uFillD, uSpanO, uSpanD, uRed, uLight, uHasO, uHasD;
uniform vec3 uLandO, uLandD;
in vec3 vN;
in vec3 vV;
in vec3 vO;
out vec4 o;

float lit(sampler2D m, vec4 box, vec2 ll, out vec2 uv) {
  uv = (ll - box.xy) / (box.zw - box.xy);
  if (uv.x < 0. || uv.x > 1. || uv.y < 0. || uv.y > 1.) return 0.;
  return texture(m, vec2(uv.x, 1. - uv.y)).r;
}

// The flag as the file has it (no colour-space round trip, so it is not darkened),
// mipmapped so it stays crisp from the globe's height down to the country's.
vec3 flag(sampler2D t, vec2 uv) {
  return texture(t, vec2(clamp(uv.x, 0., 1.), 1. - clamp(uv.y, 0., 1.))).rgb;
}

void main() {
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
  // Both flags are read on every fragment: a mipmapped read needs its neighbours' uv, so not inside a branch.
  vec3 flagO = flag(uFlagO, uvO), flagD = flag(uFlagD, uvD);
  c = mix(c, flagO * (.9 + .14 * shade), fO);
  c = mix(c, flagD * (.9 + .14 * shade), fD);
  c += vec3(.12, .85, .38) * mD * (1. - smoothstep(0., .006, abs(dD - uFillD * uSpanD))) * step(.001, uFillD) * step(uFillD, .999);
  float l = dot(c, vec3(.3, .59, .11)); c = mix(c, vec3(l * 1.2, l * .3, l * .35), uRed);
  float rim = pow(1. - max(dot(vN, vV), 0.), 4.);
  c += mix(vec3(.54, .65, 1.) * .55, vec3(-.25, -.2, -.05), uLight) * rim;
  o = vec4(c, 1.);
}
"""

/**
 * Lines of a constant width on screen, from segments: one instance is one
 * segment (two points of the unit sphere, or of a circle), the quad's four
 * corners are the shared vertices. Each is stretched sideways in pixels after
 * projection, which is what GL's own lines cannot do (one pixel, and phones
 * have three to the dp). A segment with an end behind the camera is dropped.
 */
internal const val LINE_VERT = """#version 300 es
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec3 a_a;
layout(location = 2) in vec3 a_b;
uniform mat4 uVP;
uniform mat4 uModel;
uniform float uR;
uniform vec2 uView;
uniform float uWidth;
out float vSide;
void main() {
  vec4 ca = uVP * (uModel * vec4(a_a * uR, 1.0));
  vec4 cb = uVP * (uModel * vec4(a_b * uR, 1.0));
  if (ca.w < 0.01 || cb.w < 0.01) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vSide = 0.0; return; }
  vec2 half_ = uView * 0.5;
  vec2 d = (cb.xy / cb.w - ca.xy / ca.w) * half_;
  float len = length(d);
  vec2 dir = len > 1e-4 ? d / len : vec2(1.0, 0.0);
  vec2 n = vec2(-dir.y, dir.x);
  vec4 c = mix(ca, cb, a_corner.x);
  c.xy += n * (a_corner.y * uWidth * 0.5) / half_ * c.w;
  gl_Position = c;
  vSide = a_corner.y;
}
"""

internal const val LINE_FRAG = """#version 300 es
precision mediump float;
in float vSide;
uniform vec4 uColor;
out vec4 o;
void main() {
  float fw = fwidth(vSide);
  float e = 1.0 - smoothstep(1.0 - fw * 1.5, 1.0, abs(vSide));
  o = vec4(uColor.rgb, uColor.a * e);
}
"""

/** A soft round dot that always faces the camera and keeps its size in the world: the riders, the comet, the traffic. */
internal const val SPRITE_VERT = """#version 300 es
layout(location = 0) in vec2 a_corner;
uniform mat4 uV;
uniform mat4 uP;
uniform vec3 uC;
uniform float uSize;
out vec2 vC;
void main() {
  vec4 v = uV * vec4(uC, 1.0);
  v.xy += a_corner * uSize * 0.5;
  gl_Position = uP * v;
  vC = a_corner;
}
"""

/** The desktop's glow texture, as a formula: 1 at the middle, .55 a quarter out, 0 at the edge. */
internal const val SPRITE_FRAG = """#version 300 es
precision mediump float;
in vec2 vC;
uniform vec4 uColor;
out vec4 o;
void main() {
  float r = length(vC);
  if (r >= 1.0) discard;
  float a = r < 0.25 ? mix(1.0, 0.55, r / 0.25) : mix(0.55, 0.0, (r - 0.25) / 0.75);
  o = vec4(uColor.rgb, uColor.a * a);
}
"""

/** The flat ring that ripples out of home. */
internal const val FLAT_VERT = """#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 uMVP;
void main() {
  gl_Position = uMVP * vec4(a_pos, 1.0);
}
"""

internal const val FLAT_FRAG = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 o;
void main() {
  o = uColor;
}
"""

/** The page behind the planet, dark theme: one soft cone of light from the top (the desktop's body::before). */
internal const val CONE_VERT = """#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

internal const val CONE_FRAG = """#version 300 es
precision mediump float;
uniform vec2 uRes;
out vec4 o;
void main() {
  vec2 s = vec2(gl_FragCoord.x / uRes.x, 1.0 - gl_FragCoord.y / uRes.y);
  float d = length((s - vec2(0.5, -0.08)) / vec2(0.6, 0.45));
  o = vec4(vec3(1.0), 0.07 * clamp(1.0 - d / 0.7, 0.0, 1.0));
}
"""
