/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GLES 3 program that draws an external OES camera texture through a look.
 * The fragment shader is a line-by-line port of [LookMath]. Must be used on the
 * thread that owns the GL context.
 */
internal class GlLookProgram(externalInput: Boolean = true) {
    private val inputTarget = if (externalInput) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D
    private val program = link(
        VERTEX,
        if (externalInput) FRAGMENT else FRAGMENT
            .replace("#extension GL_OES_EGL_image_external_essl3 : require\n", "")
            .replace("samplerExternalOES", "sampler2D"),
    )
    private val aPos = GLES30.glGetAttribLocation(program, "aPos")
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uInput = GLES30.glGetUniformLocation(program, "uInput")
    private val uLut = GLES30.glGetUniformLocation(program, "uLut")
    private val uTone = GLES30.glGetUniformLocation(program, "uTone")
    private val uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
    private val uEnabled = GLES30.glGetUniformLocation(program, "uEnabled")
    private val uIntensity = GLES30.glGetUniformLocation(program, "uIntensity")
    private val uGrain = GLES30.glGetUniformLocation(program, "uGrain")
    private val uVignette = GLES30.glGetUniformLocation(program, "uVignette")
    private val uSize = GLES30.glGetUniformLocation(program, "uSize")

    private val textures = IntArray(2).also { GLES30.glGenTextures(2, it, 0) }
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var params: LookParams? = null
    private var uploadedLut: Lut3d? = null

    fun setLook(look: LookParams?) {
        params = look
        if (look == null) return
        if (look.lut !== uploadedLut) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[0])
            setNearest(GLES30.GL_TEXTURE_3D)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, look.lut.size, look.lut.size, look.lut.size,
                0, GLES30.GL_RGB, GLES30.GL_FLOAT, FloatBuffer.wrap(look.lut.data),
            )
            uploadedLut = look.lut
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
        setNearest(GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, ToneTable.SIZE, 1, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, FloatBuffer.wrap(look.tone),
        )
    }

    fun draw(inputTexture: Int, texMatrix: FloatArray, width: Int, height: Int) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(inputTarget, inputTexture)
        GLES30.glUniform1i(uInput, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform2f(uSize, width.toFloat(), height.toFloat())
        val p = params
        GLES30.glUniform1i(uEnabled, if (p == null) 0 else 1)
        if (p != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[0])
            GLES30.glUniform1i(uLut, 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
            GLES30.glUniform1i(uTone, 2)
            GLES30.glUniform1i(uLutSize, p.lut.size)
            GLES30.glUniform1f(uIntensity, p.intensity)
            GLES30.glUniform2f(uGrain, p.grainAmount, LookMath.grainCell(p, width, height))
            GLES30.glUniform2f(uVignette, p.vignetteAmount, p.vignetteSoftness)
        }
        GLES30.glViewport(0, 0, width, height)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    fun release() {
        GLES30.glDeleteTextures(2, textures, 0)
        GLES30.glDeleteProgram(program)
    }

    private fun setNearest(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private companion object {
        const val VERTEX = """#version 300 es
in vec4 aPos;
uniform mat4 uTexMatrix;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexMatrix * vec4((aPos.xy + 1.0) * 0.5, 0.0, 1.0)).xy;
}
"""

        const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp int;
precision highp sampler3D;
uniform samplerExternalOES uInput;
uniform sampler3D uLut;
uniform sampler2D uTone;
uniform int uLutSize;
uniform int uEnabled;
uniform float uIntensity;
uniform vec2 uGrain;     // amount, cell size in pixels
uniform vec2 uVignette;  // amount, softness
uniform vec2 uSize;
in vec2 vTex;
out vec4 fragColor;

""" + LookGlsl.FUNCTIONS + """
void main() {
    vec3 src = texture(uInput, vTex).rgb;
    if (uEnabled == 0) { fragColor = vec4(src, 1.0); return; }
    float k = uIntensity;
    vec3 c = mix(src, sampleLut(src), k);
    c += (vec3(tone(c.r), tone(c.g), tone(c.b)) - c) * k;
    int x = int(gl_FragCoord.x);
    int y = int(uSize.y) - 1 - int(gl_FragCoord.y);
    if (uVignette.x > 0.0) {
        float nx = (float(x) + 0.5) / uSize.x * 2.0 - 1.0;
        float ny = (float(y) + 0.5) / uSize.y * 2.0 - 1.0;
        float w2 = uSize.x * uSize.x, h2 = uSize.y * uSize.y;
        float dist = sqrt((nx * nx * w2 + ny * ny * h2) / (w2 + h2));
        c *= 1.0 - uVignette.x * k * smoothstep(1.0 - uVignette.y, 1.0, dist);
    }
    if (uGrain.x > 0.0) {
        int cx = int(floor(float(x) / uGrain.y));
        int cy = int(floor(float(y) / uGrain.y));
        float n = float(hash(cx, cy) >> 8u) / 16777216.0 - 0.5;
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c += n * uGrain.x * k * 0.4 * (1.0 - abs(2.0 * l - 1.0));
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

        fun compile(type: Int, src: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
            return shader
        }

        fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Program link failed: ${GLES30.glGetProgramInfoLog(p)}" }
            return p
        }
    }
}
