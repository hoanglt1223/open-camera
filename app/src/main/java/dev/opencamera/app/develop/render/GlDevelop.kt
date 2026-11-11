/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.view.Surface
import dev.opencamera.app.develop.model.Params
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class GlException(message: String) : RuntimeException(message)

/**
 * OpenGL ES 3.0 develop renderer. Owns an EGL context; every method must run on the thread
 * that created it ([DevelopEngine] serialises calls). Intermediate results are textures:
 * the linear source with mipmaps (fine detail), the medium and large blurs (clarity, tone
 * base, dehaze) which only depend on the source and are computed once, and the mask texture.
 * A slider change therefore re-runs only the single develop pass.
 */
class GlDevelop private constructor(
    private val display: EGLDisplay,
    private val config: EGLConfig,
    private val context: EGLContext,
    private val pbuffer: EGLSurface,
) {
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private val linearize = Program(DevelopGlsl.VERTEX, DevelopGlsl.LINEARIZE)
    private val blur = Program(DevelopGlsl.VERTEX, DevelopGlsl.BLUR)
    private val develop = Program(DevelopGlsl.VERTEX, DevelopGlsl.DEVELOP)
    private val small = Program(DevelopGlsl.VERTEX, DevelopGlsl.SMALL)
    private var smallFor = 0
    private var smallTex = 0
    private var smallW = 0
    private var smallH = 0
    private val show = Program(DevelopGlsl.VERTEX, DevelopGlsl.DISPLAY)
    val halfFloat: Boolean = checkHalfFloat()
    private val linFormat = if (halfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
    private val linType = if (halfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE

    /** The working image (proxy) and its derived textures. */
    class Source(val width: Int, val height: Int, val lin: Int, val blurM: Int, val blurL: Int)

    private var curveTex = 0
    private var lutTex = 0
    private var toneTex = 0
    private var maskTex = 0
    private var emptyMask = 0
    private var uploadedLut: Any? = null

    val maxTextureSize: Int = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]

    init {
        curveTex = newTexture()
        // A 3D texture name must never be bound as 2D first (INVALID_OPERATION on rebind).
        lutTex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        toneTex = newTexture()
        maskTex = newTexture(linear = true)
        emptyMask = newTexture(linear = true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, emptyMask)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(4))
        // Empty LUT/tone so the sampler bindings are always complete.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, 2, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT,
            FloatBuffer.wrap(floatArrayOf(0f, 1f)))
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        texParams(GLES30.GL_TEXTURE_3D, false)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, 2, 2, 2, 0, GLES30.GL_RGB, GLES30.GL_FLOAT,
            FloatBuffer.wrap(FloatArray(24)))
    }

    // ---- source ------------------------------------------------------------------------

    /** Uploads [bitmap] as the working source and derives the cached blur textures. */
    fun createSource(bitmap: Bitmap): Source {
        checkError("before createSource")
        val lin = linearTexture(bitmap)
        checkError("linearTexture")
        val w = bitmap.width
        val h = bitmap.height
        val long = max(w, h).toFloat()
        val blurM = gaussian(lin, w, h, long / 100f)
        val blurL = gaussian(lin, w, h, long / 25f)
        checkError("createSource")
        return Source(w, h, lin, blurM, blurL)
    }

    fun release(source: Source) {
        GLES30.glDeleteTextures(3, intArrayOf(source.lin, source.blurM, source.blurL), 0)
    }

    /** sRGB bitmap -> linear texture with a full mip chain. */
    fun linearTexture(bitmap: Bitmap): Int {
        val src = newTexture(linear = true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        checkError("upload ${bitmap.config}")
        val lin = newTexture(linear = true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lin)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, linFormat, bitmap.width, bitmap.height, 0, GLES30.GL_RGBA, linType, null)
        drawTo(lin, bitmap.width, bitmap.height) {
            linearize.use()
            bindTexture(0, src, linearize.loc("uSrc"))
            GLES30.glUniform1i(linearize.loc("uLinear"), if (isLinear(bitmap)) 1 else 0)
        }
        checkError("linearize")
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lin)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        checkError("mipmap")
        GLES30.glDeleteTextures(1, intArrayOf(src), 0)
        return lin
    }

    private fun isLinear(b: Bitmap) = b.config == Bitmap.Config.RGBA_F16 && b.colorSpace?.let {
        it == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB) ||
            it == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_SRGB)
    } == true

    /** Separable Gaussian of [sigma] pixels, computed at the mip level where it is ~2.5 texels. */
    private fun gaussian(lin: Int, w: Int, h: Int, sigma: Float): Int {
        val lod = max(0, (ln(sigma / 2.5) / ln(2.0)).toInt())
        val lw = max(1, w shr lod)
        val lh = max(1, h shr lod)
        val scale = sigma / (1 shl lod) / 2.5f
        val tmp = newTexture(linear = true)
        val out = newTexture(linear = true)
        for (t in intArrayOf(tmp, out)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, linFormat, lw, lh, 0, GLES30.GL_RGBA, linType, null)
        }
        drawTo(tmp, lw, lh) {
            blur.use()
            bindTexture(0, lin, blur.loc("uSrc"))
            GLES30.glUniform2f(blur.loc("uStep"), scale / lw, 0f)
            GLES30.glUniform1f(blur.loc("uLod"), lod.toFloat())
        }
        drawTo(out, lw, lh) {
            blur.use()
            bindTexture(0, tmp, blur.loc("uSrc"))
            GLES30.glUniform2f(blur.loc("uStep"), 0f, scale / lh)
            GLES30.glUniform1f(blur.loc("uLod"), 0f)
        }
        GLES30.glDeleteTextures(1, intArrayOf(tmp), 0)
        return out
    }

    // ---- per-edit inputs ---------------------------------------------------------------

    /** Mask coverage for up to four masks, RGBA8 at proxy resolution; null clears. */
    fun setMasks(rgba: ByteBuffer?, width: Int, height: Int) {
        if (rgba == null) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba.position(0))
    }

    private fun uploadLook(u: DevelopUniforms) {
        val look = u.look ?: return
        if (look.lut !== uploadedLut) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, look.lut.size, look.lut.size, look.lut.size,
                0, GLES30.GL_RGB, GLES30.GL_FLOAT, FloatBuffer.wrap(look.lut.data))
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, look.tone.size, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT,
                FloatBuffer.wrap(look.tone))
            uploadedLut = look.lut
        }
    }

    // ---- develop -----------------------------------------------------------------------

    /**
     * Renders one region of the output into [target] (RGBA8, [tw] x [th]).
     * [region] is the source texture for this render: the proxy's linear texture, or a tile.
     * [srcRect] is that texture's rectangle in normalized source coordinates; [tile] the
     * rendered part of the output (u, v, width, height, normalized); [outW] x [outH] the size
     * of the whole output in pixels.
     */
    fun develop(
        u: DevelopUniforms,
        source: Source,
        region: Int,
        regionW: Int,
        srcRect: FloatArray,
        frame: Geometry.Frame,
        tile: FloatArray,
        outW: Int,
        outH: Int,
        target: Int,
        tw: Int,
        th: Int,
    ) {
        uploadLook(u)
        val regionH = max(1, (regionW * ((srcRect[3] - srcRect[1]) * source.height) / ((srcRect[2] - srcRect[0]) * source.width)).roundToInt())
        val blurS = smallBlur(source, region, regionW, regionH, srcRect)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 256, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, FloatBuffer.wrap(u.curve))
        // Tile output uv -> whole output uv -> source uv.
        val toSrc = frame.outputToSource * Mat3.translate(tile[0], tile[1]) * Mat3.scale(tile[2], tile[3])
        val p = develop
        drawTo(target, tw, th) {
            p.use()
            bindTexture(0, region, p.loc("uSrc"))
            bindTexture(7, blurS, p.loc("uBlurS"))
            bindTexture(1, source.blurM, p.loc("uBlurM"))
            bindTexture(2, source.blurL, p.loc("uBlurL"))
            bindTexture(3, if (u.maskCount > 0) maskTex else emptyMask, p.loc("uMask"))
            bindTexture(4, curveTex, p.loc("uCurve"))
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES30.glUniform1i(p.loc("uLut"), 5)
            bindTexture(6, toneTex, p.loc("uTone"))
            GLES30.glUniform1i(p.loc("uLutSize"), u.look?.lut?.size ?: 2)
            GLES30.glUniform1i(p.loc("uLook"), if (u.look != null) 1 else 0)
            GLES30.glUniform1f(p.loc("uLookIntensity"), u.look?.intensity ?: 0f)
            GLES30.glUniformMatrix3fv(p.loc("uOutToSrc"), 1, false, toSrc.columnMajor(), 0)
            GLES30.glUniform4f(p.loc("uSrcRect"), srcRect[0], srcRect[1], srcRect[2] - srcRect[0], srcRect[3] - srcRect[1])
            GLES30.glUniform4f(p.loc("uTile"), tile[0], tile[1], tile[2], tile[3])
            GLES30.glUniform2f(p.loc("uOutSize"), outW.toFloat(), outH.toFloat())
            GLES30.glUniformMatrix3fv(p.loc("uWb"), 1, false, u.wb.columnMajor(), 0)
            GLES30.glUniform1f(p.loc("uExposure"), u.exposure)
            GLES30.glUniform1f(p.loc("uContrast"), u.contrast)
            GLES30.glUniform1f(p.loc("uHighlights"), u.highlights)
            GLES30.glUniform1f(p.loc("uShadows"), u.shadows)
            GLES30.glUniform1f(p.loc("uWhites"), u.whites)
            GLES30.glUniform1f(p.loc("uBlacks"), u.blacks)
            GLES30.glUniform1f(p.loc("uVibrance"), u.vibrance)
            GLES30.glUniform1f(p.loc("uSaturation"), u.saturation)
            GLES30.glUniform1fv(p.loc("uHsl"), 24, u.hsl, 0)
            GLES30.glUniform1fv(p.loc("uHslCenters"), 8, Params.HSL_CENTERS, 0)
            GLES30.glUniform4fv(p.loc("uGrade"), 4, u.grading, 0)
            GLES30.glUniform2f(p.loc("uGradePivot"), u.gradePivot, u.gradeWidth)
            GLES30.glUniform1i(p.loc("uCurveOn"), if (u.curveIsIdentity) 0 else 1)
            GLES30.glUniform1f(p.loc("uClarity"), u.clarity)
            GLES30.glUniform1f(p.loc("uTexture"), u.texture)
            GLES30.glUniform1f(p.loc("uDehaze"), u.dehaze)
            GLES30.glUniform3fv(p.loc("uAtmosphere"), 1, u.atmosphere, 0)
            GLES30.glUniform4f(p.loc("uSharpen"), u.sharpen, u.sharpenRadius, u.sharpenDetail, u.sharpenMasking)
            GLES30.glUniform2f(p.loc("uNr"), u.nrLuma, u.nrColor)
            GLES30.glUniform4f(p.loc("uVignette"), u.vignette, u.vignetteMidpoint, u.vignetteRoundness, u.vignetteFeather)
            GLES30.glUniform1f(p.loc("uVignetteHighlights"), u.vignetteHighlights)
            GLES30.glUniform3f(p.loc("uGrain"), u.grain, DevelopKernel.grainCell(u, outW.toFloat(), outH.toFloat()), u.grainRoughness)
            GLES30.glUniform1i(p.loc("uShoulder"), if (u.shoulder) 1 else 0)
            GLES30.glUniform1i(p.loc("uMaskCount"), u.maskCount)
            val local = FloatArray(11 * 4)
            for (i in 0 until 11) for (s in 0 until DevelopUniforms.MASK_SLOTS) local[i * 4 + s] = u.local[s][i]
            GLES30.glUniform4fv(p.loc("uLocal"), 11, local, 0)
        }
        checkError("develop")
    }

    /**
     * Fine-detail blur of [region] (sigma relative to the full source so proxy and export
     * agree). Cached for the proxy, whose region texture never changes.
     */
    private fun smallBlur(source: Source, region: Int, regionW: Int, regionH: Int, srcRect: FloatArray): Int {
        if (region == smallFor && smallW == regionW && smallH == regionH && smallTex != 0) return smallTex
        val regionPxPerSrc = regionW / (srcRect[2] - srcRect[0]) / source.width.toFloat()
        val sigma = max(1f, max(source.width, source.height) * regionPxPerSrc / SMALL_BLUR_DIVISOR)
        val lod = max(0f, (ln(sigma / 1.5) / ln(2.0)).toFloat())
        if (smallTex == 0 || smallW != regionW || smallH != regionH) {
            deleteTexture(smallTex)
            smallTex = newTexture(linear = true)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smallTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, linFormat, regionW, regionH, 0, GLES30.GL_RGBA, linType, null)
            smallW = regionW; smallH = regionH
        }
        drawTo(smallTex, regionW, regionH) {
            small.use()
            bindTexture(0, region, small.loc("uSrc"))
            GLES30.glUniform1f(small.loc("uSmallLod"), lod)
            GLES30.glUniform2f(small.loc("uSmall"), sigma / regionW, sigma / regionH)
        }
        smallFor = region
        return smallTex
    }

    fun newTarget(w: Int, h: Int): Int {
        val t = newTexture(linear = true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return t
    }

    fun deleteTexture(t: Int) {
        if (t == 0) return
        // GL reuses names; never serve a cached blur for a recycled region texture.
        if (t == smallFor) smallFor = 0
        GLES30.glDeleteTextures(1, intArrayOf(t), 0)
    }

    /** Reads an RGBA8 texture into a new ARGB_8888 bitmap (rows top-down). */
    fun read(texture: Int, w: Int, h: Int, into: Bitmap? = null): Bitmap {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        checkError("read")
        val bmp = into ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    /** Down-sampled read for the histogram and thumbnails. */
    fun readScaled(texture: Int, w: Int, h: Int, maxSide: Int): Bitmap {
        val s = minOf(1f, maxSide.toFloat() / max(w, h))
        val sw = max(1, (w * s).roundToInt())
        val sh = max(1, (h * s).roundToInt())
        val t = newTarget(sw, sh)
        drawTo(t, sw, sh) {
            show.use()
            bindTexture(0, texture, show.loc("uAfter"))
            bindTexture(1, texture, show.loc("uBefore"))
            // The display program flips y for the screen; flip back for an image-order read.
            GLES30.glUniformMatrix3fv(show.loc("uViewToImage"), 1, false,
                Mat3(floatArrayOf(1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 1f)).columnMajor(), 0)
            GLES30.glUniform1f(show.loc("uSplit"), -1f)
            GLES30.glUniform3f(show.loc("uBackground"), 0f, 0f, 0f)
            GLES30.glUniform1i(show.loc("uWarn"), 0)
        }
        return read(t, sw, sh).also { deleteTexture(t) }
    }

    // ---- screen ------------------------------------------------------------------------

    fun attachWindow(surface: Surface?) {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        if (surface != null && surface.isValid) {
            windowSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (windowSurface == EGL14.EGL_NO_SURFACE) android.util.Log.w("GlDevelop", "eglCreateWindowSurface 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    /**
     * Shows [after] (and [before] left of [split], 0..1 in view x) in the attached window.
     * [viewToImage] maps view uv (0..1, top-down) to image uv.
     */
    fun present(after: Int, before: Int, viewW: Int, viewH: Int, viewToImage: Mat3, split: Float, warn: Boolean, bg: Int) {
        if (windowSurface == EGL14.EGL_NO_SURFACE) { android.util.Log.w("GlDevelop", "present: no window surface"); return }
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            android.util.Log.w("GlDevelop", "present: eglMakeCurrent 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        show.use()
        bindTexture(0, after, show.loc("uAfter"))
        bindTexture(1, if (before != 0) before else after, show.loc("uBefore"))
        GLES30.glUniformMatrix3fv(show.loc("uViewToImage"), 1, false, viewToImage.columnMajor(), 0)
        GLES30.glUniform1f(show.loc("uSplit"), split)
        GLES30.glUniform3f(show.loc("uBackground"), ((bg shr 16) and 255) / 255f, ((bg shr 8) and 255) / 255f, (bg and 255) / 255f)
        GLES30.glUniform1i(show.loc("uWarn"), if (warn) 1 else 0)
        drawQuad(show)
        val err = GLES30.glGetError()
        if (!EGL14.eglSwapBuffers(display, windowSurface) || err != 0) {
            android.util.Log.w("GlDevelop", "present: gl 0x${Integer.toHexString(err)} egl 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    fun finish() = GLES30.glFinish()

    fun release() {
        attachWindow(null)
        linearize.release(); blur.release(); develop.release(); show.release(); small.release()
        deleteTexture(smallTex)
        GLES30.glDeleteTextures(5, intArrayOf(curveTex, lutTex, toneTex, maskTex, emptyMask), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    /** True while the context is usable (false after a context loss). */
    fun isAlive(): Boolean = EGL14.eglGetCurrentContext() == context && GLES30.glGetError() != GL_CONTEXT_LOST

    // ---- helpers -----------------------------------------------------------------------

    private inline fun drawTo(texture: Int, w: Int, h: Int, setup: () -> Unit) {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            throw GlException("Framebuffer incomplete: 0x${Integer.toHexString(status)}")
        }
        GLES30.glViewport(0, 0, w, h)
        setup()
        drawQuad(currentProgram!!)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
    }

    private var currentProgram: Program? = null

    private fun drawQuad(p: Program) {
        val a = p.attrib
        GLES30.glEnableVertexAttribArray(a)
        GLES30.glVertexAttribPointer(a, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(a)
    }

    private fun bindTexture(unit: Int, texture: Int, location: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(location, unit)
    }

    private fun newTexture(linear: Boolean = false): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        texParams(GLES30.GL_TEXTURE_2D, linear)
        return t[0]
    }

    private fun texParams(target: Int, linear: Boolean) {
        val f = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, f)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, f)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun checkHalfFloat(): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
        return "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
    }

    private fun checkError(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) throw GlException("GL error 0x${Integer.toHexString(e)} in $where")
    }

    private inner class Program(vs: String, fs: String) {
        val id: Int = link(vs, fs)
        val attrib = GLES30.glGetAttribLocation(id, "aPos")
        private val locations = HashMap<String, Int>()

        fun use() {
            GLES30.glUseProgram(id)
            currentProgram = this
        }

        fun loc(name: String) = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

        fun release() = GLES30.glDeleteProgram(id)
    }

    companion object {
        /** The fine-detail blur is long side / this, in source pixels. */
        const val SMALL_BLUR_DIVISOR = 1400f
        private const val GL_CONTEXT_LOST = 0x0507

        /** Creates a context with a 1x1 pbuffer; throws [GlException] when ES 3 is not available. */
        fun create(): GlDevelop {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw GlException("No EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw GlException("eglInitialize failed")
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw GlException("No ES3 config")
            }
            val config = configs[0]!!
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext failed")
            val pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, ctx)) throw GlException("eglMakeCurrent failed")
            return GlDevelop(display, config, ctx, pbuffer)
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw GlException("Shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw GlException("Link failed: ${GLES30.glGetProgramInfoLog(p)}")
            return p
        }
    }
}
