/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.preset.PresetCodec
import dev.opencamera.app.develop.preset.PresetRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PhotoStylesTest {
    private fun at(lut: dev.opencamera.pipeline.looks.Lut3d, r: Int, g: Int, b: Int, c: Int) =
        lut.data[((b * lut.size + g) * lut.size + r) * 3 + c]

    @Test
    fun standardStyleIsIdentity() {
        val lut = PhotoStyles.lut(PhotoStyle(), size = 9)
        for (b in 0 until 9) for (g in 0 until 9) for (r in 0 until 9) {
            assertEquals(r / 8f, at(lut, r, g, b, 0), 2e-3f)
            assertEquals(g / 8f, at(lut, r, g, b, 1), 2e-3f)
            assertEquals(b / 8f, at(lut, r, g, b, 2), 2e-3f)
        }
        assertTrue(PhotoStyles.toEditStack(PhotoStyle()).values.isEmpty())
    }

    @Test
    fun warmthAndToneMoveColoursTheRightWay() {
        val warm = PhotoStyles.lut(PhotoStyle(warmth = 60), size = 9)
        assertTrue("red up", at(warm, 4, 4, 4, 0) > at(warm, 4, 4, 4, 2))
        val rich = PhotoStyles.lut(PhotoStyle(tone = 80), size = 9)
        val plain = PhotoStyles.lut(PhotoStyle(), size = 9)
        // More contrast: a dark grey gets darker, a light grey lighter.
        assertTrue(at(rich, 2, 2, 2, 1) < at(plain, 2, 2, 2, 1))
        assertTrue(at(rich, 7, 7, 7, 1) > at(plain, 7, 7, 7, 1))
        for (v in rich.data) assertTrue(v in 0f..1f)
    }

    @Test
    fun frozenIdsCarryValuesAndRejectGarbage() {
        val s = PhotoStyle(tone = 45, warmth = -20, color = 10)
        val id = PhotoStyles.frozenId(s)
        assertTrue(PhotoStyles.isStyle(id))
        assertTrue(PhotoStyles.isStyle(PhotoStyles.LOOK_ID))
        assertFalse(PhotoStyles.isStyle("film-warm"))
        assertFalse(PhotoStyles.isStyle(null))
        assertEquals(s, PhotoStyles.parseFrozen(id))
        assertNull(PhotoStyles.parseFrozen("style:1:2"))
        assertNull(PhotoStyles.parseFrozen("style:a:b:c"))
        assertEquals(PhotoStyle(100, -100, 0), PhotoStyles.parseFrozen("style:500:-500:0"))
    }

    @Test
    fun styleSavesAsEditorPreset() {
        val dir = Files.createTempDirectory("styles").toFile()
        try {
            val repo = PresetRepository(dir) { emptyList() }
            val style = PhotoStyle(tone = 50, warmth = 30, color = -20)
            val saved = repo.save("Style", PhotoStyles.PRESET_GROUPS, PhotoStyles.toEditStack(style))
            val loaded = repo.user().single()
            assertEquals(saved.id, loaded.id)
            assertEquals(30f, loaded.stack[Params.CONTRAST], 1e-4f)
            assertEquals(9f, loaded.stack[Params.TEMP], 1e-4f)
            assertEquals(-12f, loaded.stack[Params.VIBRANCE], 1e-4f)
            val text = PresetCodec.encode(loaded)
            assertEquals(loaded.stack.values, PresetCodec.decode(text, "x").first.stack.values)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun presetsAreDistinctAndMatchTheirIndex() {
        assertEquals(PhotoStyles.PRESETS.size, PhotoStyles.PRESETS.map { it.second }.toSet().size)
        PhotoStyles.PRESETS.forEachIndexed { i, (_, s) -> assertEquals(i, PhotoStyles.presetIndex(s)) }
        assertEquals(-1, PhotoStyles.presetIndex(PhotoStyle(tone = 1)))
    }
}
