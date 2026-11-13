/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

internal const val OCR_INCLUDED = true

/**
 * Tesseract (Apache-2.0) with the tessdata_fast models bundled under `assets/tessdata`. Tesseract
 * reads models from a directory, so they are copied to `files/tessdata` once.
 */
internal fun openRecognizer(context: Context, languages: String): TextRecognizer? {
    val root = File(context.filesDir, "ocr")
    val dir = File(root, "tessdata")
    for (lang in languages.split('+')) {
        val name = "$lang.traineddata"
        val target = File(dir, name)
        // Copies land through a rename, so an existing file is always complete.
        if (target.length() > 0) continue
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(target)) { "cannot install $name" }
    }
    val api = TessBaseAPI()
    if (!api.init(root.path, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
        api.recycle()
        return null
    }
    return TesseractRecognizer(api)
}

private class TesseractRecognizer(private val api: TessBaseAPI) : TextRecognizer {
    override fun recognize(page: Bitmap): OcrPage {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setImage(page)
        val text = api.getUTF8Text().orEmpty()
        val words = mutableListOf<OcrWord>()
        val it = api.resultIterator
        if (it != null) {
            try {
                it.begin()
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                do {
                    val w = it.getUTF8Text(level)?.trim().orEmpty()
                    val r = it.getBoundingRect(level)
                    if (w.isNotEmpty() && r != null && r.width() > 0 && r.height() > 0) {
                        words += OcrWord(w, r.left, r.top, r.right, r.bottom)
                    }
                } while (it.next(level))
            } finally {
                it.delete()
            }
        }
        api.clear()
        return OcrPage(text.trim(), words)
    }

    override fun close() = api.recycle()
}