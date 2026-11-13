/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencamera.ml.MlRuntime
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Palm detector on the device. Photos are not part of the repository: push JPEGs whose name
 * starts with "palm" (an open hand) or "neg" (no hand) into the test app's `files/oc-palm`
 * (for example through `run-as`); without them the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class PalmDeviceTest {
    @Test
    fun palmsScoreHighAndOtherPhotosLow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "oc-palm")
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
        assumeTrue("no test photos in $dir", files.isNotEmpty())
        val detector = PalmDetector(MlRuntime.get(context))
        assertTrue("model", detector.available)
        for (f in files.sortedBy { it.name }) {
            val bmp = BitmapFactory.decodeFile(f.path) ?: continue
            val palm = detector.detect(bmp)
            Log.i("PalmDeviceTest", "${f.name}: $palm")
            if (f.name.startsWith("palm")) assertTrue("${f.name}: $palm", (palm?.score ?: 0f) >= 0.6f)
            if (f.name.startsWith("neg")) assertTrue("${f.name}: $palm", (palm?.score ?: 0f) < 0.4f)
        }
    }
}
