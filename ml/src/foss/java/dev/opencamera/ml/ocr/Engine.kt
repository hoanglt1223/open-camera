/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.ocr

import android.content.Context

/** The foss build ships no OCR engine (Tesseract comes as a prebuilt native library). */
internal const val OCR_INCLUDED = false

@Suppress("UNUSED_PARAMETER")
internal fun openRecognizer(context: Context, languages: String): TextRecognizer? = null