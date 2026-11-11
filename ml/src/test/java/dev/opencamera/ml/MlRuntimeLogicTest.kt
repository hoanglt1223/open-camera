/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class MlRuntimeLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun specJson(license: String = "Apache-2.0", sha: String = "a".repeat(64), url: String? = null, size: Long = 4) = """
        {"id":"m","file":"m.onnx","format":"onnx","size":$size,"sha256":"$sha","license":"$license",
         "source":"test","url":${url?.let { "\"$it\"" } ?: "null"},"accelerators":["GPU","CPU"],"inputShape":[1,3,8,8]}
    """.trimIndent()

    @Test
    fun parsesSpec() {
        val s = ModelSpec.parse(specJson())
        assertEquals(ModelFormat.ONNX, s.format)
        assertTrue(s.bundled)
        assertEquals(listOf(Accelerator.GPU, Accelerator.CPU), s.accelerators)
        assertEquals(10_000L, s.timeoutMs)
    }

    @Test
    fun rejectsNonCommercialLicense() {
        try {
            ModelSpec.parse(specJson(license = "CC-BY-NC-4.0"))
            fail("Non-commercial license accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not allowed"))
        }
    }

    @Test
    fun rejectsMalformedHash() {
        try {
            ModelSpec.parse(specJson(sha = "1234"))
            fail("Bad hash accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun cpuIsAlwaysTheLastCandidate() {
        val s = ModelSpec.parse(specJson())
        val noGpu = BackendSelector.candidates(s, BackendSelector.Preference.AUTO) { it == Accelerator.CPU }
        assertEquals(listOf(Accelerator.CPU), noGpu)
        val forced = BackendSelector.candidates(s, BackendSelector.Preference.NNAPI) { true }
        assertEquals(listOf(Accelerator.NNAPI, Accelerator.GPU, Accelerator.CPU), forced)
    }

    @Test
    fun chooseRejectsBackendThatDisagreesWithCpu() {
        val pick = BackendSelector.choose(
            listOf(
                BackendSelector.Trial(Accelerator.CPU, true, 40.0, 1.0),
                BackendSelector.Trial(Accelerator.GPU, true, 5.0, 0.5),
                BackendSelector.Trial(Accelerator.NNAPI, false, 1.0, 1.0),
            )
        )
        assertEquals(Accelerator.CPU, pick)
        assertEquals(
            Accelerator.GPU,
            BackendSelector.choose(
                listOf(BackendSelector.Trial(Accelerator.CPU, true, 40.0, 1.0), BackendSelector.Trial(Accelerator.GPU, true, 5.0, 0.999))
            ),
        )
        assertEquals(Accelerator.CPU, BackendSelector.choose(emptyList()))
    }

    @Test
    fun tilesCoverImageExactlyOnce() {
        for ((w, h) in listOf(1000 to 750, 256 to 256, 100 to 300, 4000 to 3000, 257 to 511)) {
            val counts = IntArray(w * h)
            for (t in Tiling.plan(w, h, 256, 16)) {
                assertTrue(t.coreX >= t.x && t.coreY >= t.y)
                assertTrue(t.coreX + t.coreW <= t.x + 256 && t.coreY + t.coreH <= t.y + 256)
                for (y in t.coreY until t.coreY + t.coreH) for (x in t.coreX until t.coreX + t.coreW) counts[y * w + x]++
            }
            assertTrue("$w x $h", counts.all { it == 1 })
        }
    }

    @Test
    fun tileCoresKeepAwayFromInnerWindowEdges() {
        val tiles = Tiling.plan(1000, 256, 256, 16)
        for (t in tiles) {
            if (t.coreX > 0) assertTrue(t.coreX - t.x >= 16)
            if (t.coreX + t.coreW < 1000) assertTrue(t.x + 256 - (t.coreX + t.coreW) >= 16)
        }
    }

    @Test
    fun downloadKeepsOnlyVerifiedFile() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val target = tmp.root.resolve("models/m/m.onnx")
        val ok = ModelDownloader({ target }) { ByteArrayInputStream(payload) }
        ok.download(ModelSpec.parse(specJson(sha = sha, url = "https://example.org/m.onnx")))
        assertTrue(target.isFile)

        target.delete()
        val tampered = ModelDownloader({ target }) { ByteArrayInputStream(byteArrayOf(9, 9, 9, 9)) }
        try {
            tampered.download(ModelSpec.parse(specJson(sha = sha, url = "https://example.org/m.onnx")))
            fail("Tampered download accepted")
        } catch (e: IOException) {
            assertFalse(target.exists())
            assertFalse(target.resolveSibling("m.onnx.part").exists())
        }
    }

    @Test
    fun downloadRejectsOversizedStream() {
        val target = tmp.root.resolve("m.onnx")
        val d = ModelDownloader({ target }) { ByteArrayInputStream(ByteArray(10)) }
        try {
            d.download(ModelSpec.parse(specJson(url = "https://example.org/m.onnx", size = 4)))
            fail("Oversized download accepted")
        } catch (e: IOException) {
            assertFalse(target.exists())
        }
    }

    @Test
    fun cosine() {
        assertEquals(1.0, Tensor.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-9)
        assertEquals(0.0, Tensor.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertNull(null)
    }
}
