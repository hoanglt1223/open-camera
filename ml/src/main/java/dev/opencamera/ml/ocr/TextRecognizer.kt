/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable

/** One recognised word and its box in page pixels. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class OcrPage(val text: String, val words: List<OcrWord>)

/** Offline text recognition for document pages. Not thread-safe; use from one thread. */
interface TextRecognizer : Closeable {
    fun recognize(page: Bitmap): OcrPage
}

object Ocr {
    private const val TAG = "Ocr"

    /** False in builds without an OCR engine (foss); the document mode then hides OCR. */
    val included: Boolean get() = OCR_INCLUDED

    /** A recognizer for [languages] (Tesseract codes joined by '+'), or null when unavailable. */
    fun open(context: Context, languages: String = "eng+vie"): TextRecognizer? =
        runCatching { openRecognizer(context.applicationContext, languages) }
            .onFailure { Log.w(TAG, "OCR unavailable", it) }
            .getOrNull()
}