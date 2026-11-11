/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

/**
 * GLSL shared by every GPU path that applies a look (camera preview and the photo editor).
 * Expects `uniform sampler3D uLut`, `uniform int uLutSize` and `uniform sampler2D uTone`
 * to be declared by the including shader. Mirrors [LookMath].
 */
object LookGlsl {
    const val FUNCTIONS = """
vec3 L(ivec3 p) { return texelFetch(uLut, p, 0).rgb; }

vec3 sampleLut(vec3 c) {
    float s = float(uLutSize - 1);
    vec3 f = clamp(c, 0.0, 1.0) * s;
    ivec3 i0 = min(ivec3(f), ivec3(uLutSize - 2));
    vec3 d = f - vec3(i0);
    float x = d.r, y = d.g, z = d.b;
    ivec3 o1, o2;
    float w0, w1, w2, w3;
    if (x >= y) {
        if (y >= z) { o1 = ivec3(1,0,0); o2 = ivec3(1,1,0); w0 = 1.0-x; w1 = x-y; w2 = y-z; w3 = z; }
        else if (x >= z) { o1 = ivec3(1,0,0); o2 = ivec3(1,0,1); w0 = 1.0-x; w1 = x-z; w2 = z-y; w3 = y; }
        else { o1 = ivec3(0,0,1); o2 = ivec3(1,0,1); w0 = 1.0-z; w1 = z-x; w2 = x-y; w3 = y; }
    } else {
        if (x >= z) { o1 = ivec3(0,1,0); o2 = ivec3(1,1,0); w0 = 1.0-y; w1 = y-x; w2 = x-z; w3 = z; }
        else if (y >= z) { o1 = ivec3(0,1,0); o2 = ivec3(0,1,1); w0 = 1.0-y; w1 = y-z; w2 = z-x; w3 = x; }
        else { o1 = ivec3(0,0,1); o2 = ivec3(0,1,1); w0 = 1.0-z; w1 = z-y; w2 = y-x; w3 = x; }
    }
    return w0 * L(i0) + w1 * L(i0 + o1) + w2 * L(i0 + o2) + w3 * L(i0 + ivec3(1));
}

float tone(float v) {
    float pos = clamp(v, 0.0, 1.0) * 255.0;
    int i = min(int(pos), 254);
    float f = pos - float(i);
    float a = texelFetch(uTone, ivec2(i, 0), 0).r;
    float b = texelFetch(uTone, ivec2(i + 1, 0), 0).r;
    return a + (b - a) * f;
}

uint hash(int cx, int cy) {
    uint h = uint(cx) * 73856093u ^ uint(cy) * 19349663u;
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

"""
}