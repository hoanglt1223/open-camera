/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import dev.opencamera.pipeline.looks.LookGlsl

/**
 * Shaders of the develop pipeline. Texture coordinates use image orientation (t = 0 is the
 * top row of the bitmap), so `glReadPixels` rows come out top-down.
 */
object DevelopGlsl {
    const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = (aPos + 1.0) * 0.5;
}
"""

    /** sRGB (or already linear half-float) bitmap -> linear RGB; alpha carries min(r, g, b) for the dark channel. */
    const val LINEARIZE = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform int uLinear;
in vec2 vTex;
out vec4 o;
vec3 eotf(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)); }
void main() {
    vec3 s = texture(uSrc, vTex).rgb;
    vec3 c = uLinear == 1 ? max(s, vec3(0.0)) : eotf(s);
    o = vec4(c, min(c.r, min(c.g, c.b)));
}
"""

    /** One direction of a separable Gaussian (13 taps, bilinear pairs), sampling a mip level. */
    const val BLUR = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uStep;
uniform float uLod;
in vec2 vTex;
out vec4 o;
void main() {
    vec4 s = textureLod(uSrc, vTex, uLod) * 0.1596;
    s += (textureLod(uSrc, vTex + uStep * 1.4, uLod) + textureLod(uSrc, vTex - uStep * 1.4, uLod)) * 0.2786;
    s += (textureLod(uSrc, vTex + uStep * 3.3, uLod) + textureLod(uSrc, vTex - uStep * 3.3, uLod)) * 0.1186;
    s += (textureLod(uSrc, vTex + uStep * 5.2, uLod) + textureLod(uSrc, vTex - uStep * 5.2, uLod)) * 0.0230;
    o = s;
}
"""

    /** Fine-detail blur of a region texture: 3x3 taps one sigma apart on the matching mip level. */
    const val SMALL = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uSmall;
uniform float uSmallLod;
in vec2 vTex;
out vec4 o;
void main() {
    vec2 st = vTex;
    vec3 bs = textureLod(uSrc, st, uSmallLod).rgb * 0.25;
    bs += (textureLod(uSrc, st + vec2(uSmall.x, 0.0), uSmallLod).rgb + textureLod(uSrc, st - vec2(uSmall.x, 0.0), uSmallLod).rgb
         + textureLod(uSrc, st + vec2(0.0, uSmall.y), uSmallLod).rgb + textureLod(uSrc, st - vec2(0.0, uSmall.y), uSmallLod).rgb) * 0.125;
    bs += (textureLod(uSrc, st + uSmall, uSmallLod).rgb + textureLod(uSrc, st - uSmall, uSmallLod).rgb
         + textureLod(uSrc, st + vec2(uSmall.x, -uSmall.y), uSmallLod).rgb + textureLod(uSrc, st - vec2(uSmall.x, -uSmall.y), uSmallLod).rgb) * 0.0625;
    o = vec4(bs, 1.0);
}
"""

    /** Draws a texture into the view; left of [uSplit] shows [uBefore] (before/after). */
    const val DISPLAY = """#version 300 es
precision mediump float;
uniform sampler2D uAfter;
uniform sampler2D uBefore;
uniform mat3 uViewToImage;
uniform float uSplit;
uniform vec3 uBackground;
uniform int uWarn;
in vec2 vTex;
out vec4 o;
void main() {
    vec3 p = uViewToImage * vec3(vTex.x, 1.0 - vTex.y, 1.0);
    vec2 t = p.xy / p.z;
    if (t.x < 0.0 || t.y < 0.0 || t.x > 1.0 || t.y > 1.0) { o = vec4(uBackground, 1.0); return; }
    vec3 c = (t.x < uSplit) ? texture(uBefore, t).rgb : texture(uAfter, t).rgb;
    if (uWarn == 1) {
        // Clipping warning with a hatch pattern, so it does not rely on colour alone.
        float hatch = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 8.0));
        if (max(c.r, max(c.g, c.b)) >= 0.996) c = mix(vec3(1.0, 0.0, 0.0), vec3(1.0), hatch);
        else if (max(c.r, max(c.g, c.b)) <= 0.004) c = mix(vec3(0.0, 0.3, 1.0), vec3(0.0), hatch);
    }
    o = vec4(c, 1.0);
}
"""

    const val DEVELOP = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler3D;
uniform sampler2D uSrc;
uniform sampler2D uBlurS;
uniform sampler2D uBlurM;
uniform sampler2D uBlurL;
uniform sampler2D uMask;
uniform sampler2D uCurve;
uniform sampler3D uLut;
uniform sampler2D uTone;
uniform int uLutSize;
uniform int uLook;
uniform float uLookIntensity;
uniform mat3 uOutToSrc;
uniform vec4 uSrcRect;
uniform vec4 uTile;
uniform vec2 uOutSize;
uniform mat3 uWb;
uniform float uExposure, uContrast, uHighlights, uShadows, uWhites, uBlacks, uVibrance, uSaturation;
uniform float uHsl[24];
uniform vec4 uGrade[4];
uniform vec2 uGradePivot;
uniform int uCurveOn;
uniform float uClarity, uTexture, uDehaze;
uniform vec3 uAtmosphere;
uniform vec4 uSharpen;
uniform vec2 uNr;
uniform vec4 uVignette;
uniform float uVignetteHighlights;
uniform vec3 uGrain;
uniform int uShoulder;
uniform int uMaskCount;
uniform vec4 uLocal[11];
uniform float uHslCenters[8];
in vec2 vTex;
out vec4 fragColor;
""" + LookGlsl.FUNCTIONS + """
const float LOG2_MID = -2.473931;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float oetf(float v) { v = clamp(v, 0.0, 1.0); return v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1.0 / 2.4) - 0.055; }
vec3 oetf3(vec3 c) { return vec3(oetf(c.r), oetf(c.g), oetf(c.b)); }
float hueOf(vec3 c) {
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b)), d = mx - mn;
    if (d <= 1e-6) return 0.0;
    float h;
    if (mx == c.r) { h = (c.g - c.b) / d; if (h < 0.0) h += 6.0; }
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return h * 60.0;
}
float curveAt(float v, int ch) {
    float pos = clamp(v, 0.0, 1.0) * 255.0;
    int i = min(int(pos), 254);
    float a = texelFetch(uCurve, ivec2(i, 0), 0)[ch];
    float b = texelFetch(uCurve, ivec2(i + 1, 0), 0)[ch];
    return a + (b - a) * (pos - float(i));
}
uint ghash(int cx, int cy) {
    uint h = uint(cx) * 73856093u ^ uint(cy) * 19349663u;
    h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u;
    return h;
}
float gh(int i, int j) { return float(ghash(i, j) >> 8u) / 16777216.0 - 0.5; }
float grainNoise(vec2 p, float cell, float rough) {
    vec2 f = p / cell - 0.5;
    ivec2 c = ivec2(floor(f));
    vec2 t = f - vec2(c);
    float s = mix(mix(gh(c.x, c.y), gh(c.x + 1, c.y), t.x), mix(gh(c.x, c.y + 1), gh(c.x + 1, c.y + 1), t.x), t.y);
    ivec2 n = ivec2(floor(p / cell));
    float nearest = gh(n.x, n.y);
    return s * 1.6 + (nearest - s * 1.6) * rough;
}
float loc(vec4 m, int i) { return dot(m, uLocal[i]); }

void main() {
    vec3 sp = uOutToSrc * vec3(vTex, 1.0);
    vec2 g = sp.xy / sp.z;                       // global source uv
    vec2 st = (g - uSrcRect.xy) / uSrcRect.zw;   // region texture uv
    vec2 ouv = uTile.xy + vTex * uTile.zw;        // whole-output uv
    vec4 m = uMaskCount > 0 ? texture(uMask, g) : vec4(0.0);

    vec3 c = texture(uSrc, st).rgb;
    vec3 bs = texture(uBlurS, st).rgb;
    vec3 bm = texture(uBlurM, g).rgb;
    vec4 bl = texture(uBlurL, g);
    if (g.x < 0.0 || g.y < 0.0 || g.x > 1.0 || g.y > 1.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    float y0 = max(luma(c), 1e-5);
    float ys0 = max(luma(bs), 1e-5);
    float ym0 = max(luma(bm), 1e-5);

    if (uNr.y > 0.0) {
        float s = y0 / ym0;
        c = y0 + mix(c - y0, (bm - ym0) * s, uNr.y);
    }
    float nr = clamp(uNr.x + loc(m, 9), 0.0, 1.0);
    if (nr > 0.0) {
        float d = y0 - ys0;
        float thr = 0.002 + 0.04 * nr * sqrt(y0);
        float w = nr * (1.0 - smoothstep(thr, 3.0 * thr, abs(d)));
        c *= (y0 - w * d) / y0;
    }

    float lt = loc(m, 4), lti = loc(m, 5);
    vec3 gains = vec3(1.0 + 0.2 * lt + 0.1 * lti, 1.0 - 0.15 * lti, 1.0 - 0.2 * lt + 0.1 * lti);
    float ev = exp2(uExposure + loc(m, 0));
    c = (uWb * c) * gains * ev;
    vec3 lb = (uWb * bl.rgb) * gains * ev;

    float dh = clamp(uDehaze + loc(m, 10), -1.0, 1.0);
    if (dh != 0.0) {
        vec3 A = uAtmosphere * ev;
        if (dh > 0.0) {
            float amax = max(max(A.r, max(A.g, A.b)), 1e-4);
            float t = clamp(1.0 - 0.95 * dh * (bl.a * ev / amax), 0.1, 1.0);
            c = max(vec3(0.0), (c - A) / t + A);
            lb = max(vec3(0.0), (lb - A) / t + A);
        } else {
            float t = 1.0 + 0.6 * dh;
            c = c * t + A * (1.0 - t);
            lb = lb * t + A * (1.0 - t);
        }
    }

    float y = max(luma(c), 1e-5);
    float yb = max(luma(lb), 1e-5);
    float ly = log2(y), lyb = log2(yb);
    float diff = (ly - lyb) / 0.7;
    float w = exp(-diff * diff);
    float base = ly + (lyb - ly) * w;
    float detail = ly - base;
    float vv = (base - LOG2_MID) / 3.0;
    base += (uHighlights + loc(m, 2)) * 1.2 * smoothstep(0.0, 1.0, vv);
    base += (uShadows + loc(m, 3)) * 1.2 * smoothstep(0.0, 1.0, -vv);
    base = LOG2_MID + (base - LOG2_MID) * (1.0 + 0.5 * (uContrast + loc(m, 1)));
    float y2 = exp2(base + detail);

    float wmid = smoothstep(0.0, 0.15, y2) * (1.0 - smoothstep(0.6, 1.5, y2));
    float cla = uClarity + loc(m, 7);
    float lc = cla * 0.8 * clamp(log2(y0 / ym0), -2.0, 2.0) * wmid + uTexture * 0.8 * clamp(log2(y0 / ys0), -1.0, 1.0);
    c *= y2 / y * exp2(lc);

    float mx = max(c.r, max(c.g, c.b));
    if (uShoulder == 1 && mx > 0.85) c *= (0.85 + 0.15 * (1.0 - exp(-(mx - 0.85) / 0.15))) / mx;
    c = oetf3(c);

    float bp = -0.06 * uBlacks;
    float wp = 1.0 - 0.12 * uWhites;
    c = (c - bp) / (wp - bp);

    float ye = luma(c);
    float hue = hueOf(c);
    float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    int k = 7;
    for (int i = 0; i < 8; i++) if (hue >= uHslCenters[i]) k = i;
    int nx = (k + 1) % 8;
    float hs = uHslCenters[k];
    float he = nx == 0 ? 360.0 : uHslCenters[nx];
    float ht = clamp(((hue < hs ? hue + 360.0 : hue) - hs) / (he - hs), 0.0, 1.0);
    float s1 = ht * ht * (3.0 - 2.0 * ht);
    float w0 = 1.0 - s1;
    float hueShift = w0 * uHsl[k * 3] + s1 * uHsl[nx * 3];
    float satAdj = w0 * uHsl[k * 3 + 1] + s1 * uHsl[nx * 3 + 1];
    float lumAdj = w0 * uHsl[k * 3 + 2] + s1 * uHsl[nx * 3 + 2];
    float skin = (k == 1 ? w0 : 0.0) + (nx == 1 ? s1 : 0.0);
    hueShift *= smoothstep(0.0, 0.05, chroma);
    vec3 cc = c - ye;
    if (hueShift != 0.0) {
        float th = radians(hueShift);
        float cs = cos(th), sn = sin(th);
        vec3 n = vec3(0.57735026);
        cc = cc * cs + cross(n, cc) * sn + n * dot(n, cc) * (1.0 - cs);
    }
    float f = (1.0 + uSaturation + loc(m, 6)) * (1.0 + satAdj);
    f *= uVibrance > 0.0 ? 1.0 + uVibrance * (1.0 - smoothstep(0.0, 0.5, chroma)) * (1.0 - 0.5 * skin) : 1.0 + uVibrance * 0.8;
    f = max(f, 0.0);
    c = ye + cc * f;
    c *= 1.0 + lumAdj * 0.5 * clamp(chroma * 3.0, 0.0, 1.0);

    ye = luma(c);
    float gws = 1.0 - smoothstep(uGradePivot.x - uGradePivot.y, uGradePivot.x, ye);
    float gwh = smoothstep(uGradePivot.x, uGradePivot.x + uGradePivot.y, ye);
    float gwm = max(0.0, 1.0 - gws - gwh);
    vec4 gw = vec4(gws, gwm, gwh, 1.0);
    for (int i = 0; i < 4; i++) {
        c += gw[i] * uGrade[i].rgb;
        c *= 1.0 + gw[i] * uGrade[i].a;
    }

    if (uCurveOn == 1) c = vec3(curveAt(c.r, 0), curveAt(c.g, 1), curveAt(c.b, 2));

    if (uLook == 1) {
        c = clamp(c, 0.0, 1.0);
        c = mix(c, sampleLut(c), uLookIntensity);
        c += (vec3(tone(c.r), tone(c.g), tone(c.b)) - c) * uLookIntensity;
    }

    float sh = clamp(uSharpen.x + loc(m, 8), 0.0, 1.5);
    if (sh > 0.0) {
        float rmix = ((uSharpen.y - 0.5) / 2.5) * 0.25;
        float lim = 0.02 + 0.25 * uSharpen.z;
        float d = clamp(y0 - (ys0 + (ym0 - ys0) * rmix), -lim, lim);
        float em = uSharpen.w > 0.0 ? smoothstep(uSharpen.w * 0.03, uSharpen.w * 0.03 + 0.01, abs(y0 - ym0)) : 1.0;
        c += sh * 1.5 * (oetf(y0) - oetf(max(0.0, y0 - d))) * em;
    }

    if (uVignette.x != 0.0) {
        float aspect = uOutSize.x / uOutSize.y;
        vec2 dd = (ouv - 0.5) * 2.0;
        float rr = max(0.0, uVignette.z);
        float kx = 1.0 + (aspect / max(aspect, 1.0) - 1.0) * rr;
        float ky = 1.0 + (1.0 / max(aspect, 1.0) - 1.0) * rr;
        float dist = sqrt(dd.x * dd.x * kx * kx + dd.y * dd.y * ky * ky);
        float inner = 0.4 + uVignette.y * 0.8;
        float outer = inner + 0.05 + uVignette.w;
        float wv = smoothstep(inner, outer, dist);
        if (uVignette.x < 0.0) {
            float protect = 1.0 - uVignetteHighlights * smoothstep(0.7, 1.0, luma(c));
            c *= 1.0 + uVignette.x * wv * protect;
        } else {
            c += (1.0 - c) * uVignette.x * wv;
        }
    }

    if (uGrain.x > 0.0) {
        float n = grainNoise(ouv * uOutSize, uGrain.y, uGrain.z);
        float yy = luma(c);
        c += n * uGrain.x * 0.25 * (1.0 - abs(2.0 * yy - 1.0) * 0.5);
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
