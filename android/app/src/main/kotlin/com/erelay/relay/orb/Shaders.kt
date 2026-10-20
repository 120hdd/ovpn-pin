package com.erelay.relay.orb

/*
 * The desktop's sphere shaders, from app/ui/sphere.js, unchanged.
 *
 * WebGL2 is GLSL ES 3.00 and so is OpenGL ES 3.0, so these compile here as
 * they are - which is the whole reason the orb is GLES rather than AGSL: AGSL
 * is fragment-only, and this is a displaced mesh with a depth buffer.
 *
 * The technique is Mario Carrillo's "Twisted Colorful Spheres with Three.js"
 * (Codrops, 2021); pnoise is Stefan Gustavson's webgl-noise (MIT). See the
 * long notes in sphere.js for why each line is the way it is.
 */

internal const val PNOISE = """
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
}
"""

internal val VERT = """#version 300 es
precision highp float;

layout(location = 0) in vec3 a_pos;

uniform mat4  u_proj;
uniform mat3  u_rot;
uniform float u_dist;
uniform float u_time;
uniform float u_density;
uniform float u_strength;
uniform float u_twist;
uniform float u_freq;

out float v_distort;
out float v_lat;
out vec3  v_normal;
out vec3  v_view;
$PNOISE

vec3 rotateY(vec3 p, float a) {
  float c = cos(a), s = sin(a);
  return vec3(c*p.x + s*p.z, p.y, -s*p.x + c*p.z);
}

vec3 surface(vec3 n, out float distort) {
  distort = pnoise(n*u_density + u_time, vec3(10.0)) * u_strength;
  vec3 p = n * (1.0 + distort);
  return rotateY(p, sin(p.y*u_freq + u_time) * u_twist);
}

void main() {
  vec3 n = normalize(a_pos);
  vec3 up = abs(n.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
  vec3 t1 = normalize(cross(up, n));
  vec3 t2 = cross(n, t1);
  const float e = 0.012;

  float d, d1, d2;
  vec3 p  = surface(n, d);
  vec3 p1 = surface(normalize(n + t1*e), d1);
  vec3 p2 = surface(normalize(n + t2*e), d2);
  vec3 nrm = normalize(cross(p1 - p, p2 - p));
  if (dot(nrm, p) < 0.0) nrm = -nrm;

  vec3 vp = u_rot*p - vec3(0.0, 0.0, u_dist);

  v_distort = d / max(u_strength, 1e-4);
  v_lat     = p.y;
  v_normal  = u_rot*nrm;
  v_view    = -vp;
  gl_Position = u_proj * vec4(vp, 1.0);
}
"""

internal const val FRAG = """#version 300 es
precision highp float;

in float v_distort;
in float v_lat;
in vec3  v_normal;
in vec3  v_view;

uniform vec3  u_pa, u_pb, u_pc, u_pd;
uniform vec3  u_rim;

out vec4 fragColor;

vec3 cosPalette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
  return a + b * cos(6.28318 * (c*t + d));
}

void main() {
  float t = clamp(0.5 - 0.34*v_lat + 0.55*v_distort, 0.0, 1.0);
  vec3 base = clamp(cosPalette(t, u_pa, u_pb, u_pc, u_pd), 0.0, 1.0);

  vec3 V = normalize(v_view);
  vec3 N = normalize(v_normal);
  if (dot(N, V) < 0.0) N = -N;
  vec3 L = normalize(vec3(-0.42, 0.78, 0.62));

  float lam  = dot(N, L)*0.5 + 0.5;
  float rim  = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 2.8);
  float spec = pow(max(dot(reflect(-L, N), V), 0.0), 30.0);

  vec3 col = base * (0.44 + 0.56*lam*lam);
  col += u_rim * rim * 0.42;
  col += vec3(1.0) * spec * 0.15;

  col += (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;

  fragColor = vec4(col, 1.0);
}
"""
