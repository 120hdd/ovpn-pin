package com.erelay.relay.orb

/*
 * The hologram globe's shaders (GLSL ES 3.00). The look is the Kootaj
 * landing's hologram-scene.ts: a cyan latitude/longitude wire sphere with the
 * continents faint inside it and a brighter limb, drawn twice over (the far
 * side of the graticule shows through, dimmer) with nothing but a soft
 * fall-off for edges. There the globe is a glTF model with baked textures;
 * here the lines are computed from the sphere's own longitude/latitude
 * (fwidth keeps them a constant number of pixels wide at any size) and the
 * land is a 1024x512 mask, assets/globe.png (red = land, green = coast).
 *
 * One draw call, no depth buffer, no MSAA, no derivatives beyond fwidth.
 * Output is premultiplied (the view is a TextureView and composites that way).
 */

internal const val VERT = """#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
uniform mat3 u_rot;
uniform vec2 u_center;
uniform vec2 u_scale;
out vec3 v_view;
out vec3 v_obj;
out vec2 v_uv;
void main() {
  vec3 p = u_rot * a_pos;
  v_view = p;
  v_obj = a_pos;
  v_uv = a_uv;
  gl_Position = vec4(u_center + p.xy * u_scale, 0.0, 1.0);
}
"""

internal const val FRAG = """#version 300 es
precision mediump float;
precision highp sampler2D;
in highp vec3 v_view;
in highp vec3 v_obj;
in highp vec2 v_uv;
uniform sampler2D u_mask;
uniform vec3 u_col;
uniform vec3 u_mark;
uniform vec2 u_res;
uniform float u_markAmt;
uniform float u_pulse;
uniform float u_light;
uniform float u_aa;
out vec4 o;

// Distance in pixels from the nearest whole number of x, as a 0..1 line with a
// faint skirt. Lines that crowd (near the poles and the limb) fade out rather
// than turn into a smear.
float wire(highp float x) {
  highp float w = max(fwidth(x), 1e-5);
  highp float d = abs(fract(x - 0.5) - 0.5) / w;
  float core = clamp(1.15 - d, 0.0, 1.0);
  float skirt = clamp(1.0 - d / 3.4, 0.0, 1.0);
  float crowd = 1.0 - smoothstep(0.28, 0.5, w);
  return (core + 0.22 * skirt * skirt) * crowd;
}

void main() {
  float z = v_view.z;
  float front = step(0.0, z);
  float az = abs(z);
  // The sphere's own soft edge, about 1.5px wide.
  float edge = smoothstep(0.0, u_aa, az);

  float lines = max(wire(v_uv.x * 24.0), wire(v_uv.y * 12.0));
  vec2 m = texture(u_mask, v_uv).rg;

  // Far side: only the graticule, dimmer. Near side: graticule, land, coast.
  float a = lines * mix(0.30, 0.78, front);
  a += front * (m.r * 0.20 + m.g * 0.30);
  // The limb collects light where the meridians crowd.
  a += pow(1.0 - az, 4.0) * mix(0.10, 0.26, front);

  // The one marker: a dot and a ring, only on the near side.
  vec3 hot = vec3(0.0);
  if (u_markAmt > 0.0 && front > 0.5) {
    float c = clamp(dot(v_obj, u_mark), -1.0, 1.0);
    float ang = sqrt(max(2.0 * (1.0 - c), 0.0));
    float dotA = 1.0 - smoothstep(0.022, 0.034, ang);
    float rr = 0.05 + 0.05 * u_pulse;
    float ring = (1.0 - smoothstep(0.0, 0.010, abs(ang - rr))) * (1.0 - u_pulse) * 0.9;
    float mk = clamp(dotA + ring, 0.0, 1.0) * u_markAmt;
    a = max(a, mk * 1.15);
    hot = vec3(dotA * u_markAmt);
  }

  // Behind the text and the controls: quieter toward the left and the bottom.
  vec2 s = gl_FragCoord.xy / u_res;
  a *= mix(0.30, 1.0, smoothstep(0.12, 0.78, s.x));
  a *= mix(0.55, 1.0, smoothstep(0.08, 0.30, s.y));
  a *= edge;
  a = clamp(a, 0.0, 1.0);

  // Light page: ink, no white core. Dark page: the line runs white-hot at its centre.
  vec3 col = mix(mix(u_col, vec3(1.0), 0.42 * lines * lines * front), u_col * 0.85, u_light);
  col = mix(col, vec3(1.0), hot.x * (1.0 - u_light));
  o = vec4(col * a, a);
}
"""
