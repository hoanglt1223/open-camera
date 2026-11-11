/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.raw

import android.util.Log

/**
 * Camera-maker RAW (CR2/CR3, NEF, ARW, RAF, ORF, RW2, ...) through LibRaw. LibRaw is built as
 * its own shared library, `libopencamera_libraw.so` (LGPL-2.1, see NOTICE), loaded
 * dynamically: replacing that file with a compatible LibRaw build changes the decoder. The
 * JNI glue (`libopencamera_rawjni.so`) only uses LibRaw's C API.
 */
object LibRawDecoder {
    class Decoded(val width: Int, val height: Int, val flip: Int, val rgb16: ShortArray, val make: String, val model: String)

    class RawDecodeException(val code: Int, message: String) : Exception(message)

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("opencamera_libraw")
            System.loadLibrary("opencamera_rawjni")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "LibRaw not available", e)
            false
        }
    }

    val available: Boolean get() = loaded

    val version: String? get() = if (loaded) runCatching { nativeVersion() }.getOrNull() else null

    /**
     * Decodes to 16-bit linear sRGB, camera white balance. [half] halves each side (fast
     * preview). [path] may be `/proc/self/fd/N` for a content URI.
     */
    fun decode(path: String, half: Boolean): Decoded {
        if (!loaded) throw RawDecodeException(-1, "LibRaw is not installed")
        val info = IntArray(4)
        val camera = arrayOfNulls<String>(2)
        val data = nativeDecode(path, half, info, camera)
            ?: throw RawDecodeException(info[0], runCatching { nativeErrorText(info[0]) }.getOrDefault("error ${info[0]}"))
        return Decoded(info[1], info[2], info[3], data, camera[0].orEmpty(), camera[1].orEmpty())
    }

    private external fun nativeVersion(): String
    private external fun nativeDecode(path: String, half: Boolean, info: IntArray, camera: Array<String?>): ShortArray?
    private external fun nativeErrorText(code: Int): String

    private const val TAG = "LibRawDecoder"
}
