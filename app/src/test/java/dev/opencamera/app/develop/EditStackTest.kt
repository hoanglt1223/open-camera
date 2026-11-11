/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import dev.opencamera.app.develop.model.AiShape
import dev.opencamera.app.develop.model.BrushShape
import dev.opencamera.app.develop.model.ColorRangeShape
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditHistory
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.EditStore
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.LinearShape
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.LuminanceRangeShape
import dev.opencamera.app.develop.model.MaskComponent
import dev.opencamera.app.develop.model.MaskOp
import dev.opencamera.app.develop.model.OriginalBackups
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.model.RadialShape
import dev.opencamera.app.develop.model.SourceRef
import dev.opencamera.app.develop.model.StackCodec
import dev.opencamera.app.develop.model.StackFormatException
import dev.opencamera.app.develop.model.Stroke
import dev.opencamera.app.develop.model.ToolStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EditStackTest {
    private fun sample(): EditStack {
        val mask = LocalMask(
            "m1", "Sky",
            listOf(
                MaskComponent(MaskOp.ADD, LinearShape(0.5f, 0f, 0.5f, 0.6f)),
                MaskComponent(MaskOp.SUBTRACT, BrushShape(listOf(Stroke(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 0.05f, 0.4f, 0.8f, erase = true)))),
                MaskComponent(MaskOp.INTERSECT, RadialShape(0.5f, 0.5f, 0.2f, 0.3f, 15f, 0.6f), invert = true),
                MaskComponent(MaskOp.ADD, LuminanceRangeShape(0.2f, 0.8f, 0.05f)),
                MaskComponent(MaskOp.ADD, ColorRangeShape(210f, 0.4f, 0.25f)),
                MaskComponent(MaskOp.ADD, AiShape("subject", "ai-subject.png", "u2netp")),
            ),
            mapOf(Params.EXPOSURE to -0.7f, Params.DEHAZE to 30f),
            opacity = 0.8f,
        )
        return EditStack(source = SourceRef(42, "00112233445566778899aabbccddeeff", "a.jpg"))
            .with(Params.EXPOSURE, 1.25f).with(Params.CONTRAST, 20f).with(Params.hsl("blue", "sat"), -40f)
            .with(Params.CROP_ANGLE, 3.5f)
            .withCurve("rgb", listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 1f)))
            .withCurve("b", listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.9f)))
            .copy(lookId = "vivid", masks = listOf(mask), tools = listOf(ToolStep("heal", "{\"x\":0.5}", listOf("patch1.png"))),
                disabled = setOf(Group.HSL))
    }

    @Test
    fun jsonRoundTripIsLossless() {
        val s = sample()
        val back = StackCodec.decode(StackCodec.encode(s))
        assertEquals(s.values, back.values)
        assertEquals(s.curves, back.curves)
        assertEquals(s.lookId, back.lookId)
        assertEquals(s.masks, back.masks)
        assertEquals(s.tools[0].toolId, back.tools[0].toolId)
        assertEquals(s.tools[0].files, back.tools[0].files)
        assertEquals(s.disabled, back.disabled)
        assertEquals(s.source, back.source)
        assertEquals(StackCodec.encode(s), StackCodec.encode(back))
    }

    @Test
    fun packedForXmpRoundTrips() {
        val s = sample()
        assertEquals(StackCodec.encode(s), StackCodec.encode(StackCodec.unpack(StackCodec.pack(s))))
    }

    @Test
    fun defaultsAreNotStoredAndValuesAreClamped() {
        val s = EditStack().with(Params.EXPOSURE, 9f).with(Params.CONTRAST, 0f)
        assertEquals(5f, s[Params.EXPOSURE])
        assertFalse(s.values.containsKey(Params.CONTRAST))
        assertTrue(EditStack().with(Params.EXPOSURE, 1f).with(Params.EXPOSURE, 0f).isIdentity)
    }

    @Test
    fun disabledGroupRendersDefaults() {
        val s = EditStack().with(Params.hsl("red", "hue"), 50f).copy(disabled = setOf(Group.HSL))
        assertEquals(50f, s[Params.hsl("red", "hue")])
        assertEquals(0f, s.effective(Params.hsl("red", "hue")))
    }

    @Test
    fun unknownKeysSurviveRoundTrip() {
        val json = """{"schemaVersion":2,"values":{"exposure":0.5,"future.slider":12}}"""
        val s = StackCodec.decode(json)
        assertEquals(12f, s.values["future.slider"])
        assertTrue(StackCodec.encode(s).contains("future.slider"))
    }

    @Test
    fun migratesVersion1Steps() {
        val v1 = """{"version":1,"steps":[
            {"tool":"wb","params":{"temperature":20,"tint":-5}},
            {"tool":"geometry","params":{"angle":2.5,"quarterTurns":1,"crop":[0.1,0.1,0.9,0.9]}},
            {"tool":"heal","params":{"x":0.4,"y":0.6,"r":0.02},"files":["p.png"]},
            {"tool":"object_removal","params":{}}
        ]}"""
        val s = StackCodec.decode(v1)
        assertEquals(20f, s[Params.TEMP])
        assertEquals(-5f, s[Params.TINT])
        assertEquals(2.5f, s[Params.CROP_ANGLE])
        assertEquals(1f, s[Params.CROP_ROT90])
        assertEquals(0.9f, s[Params.CROP_RIGHT])
        assertEquals(listOf("heal", "object_removal"), s.tools.map { it.toolId })
        assertEquals(listOf("p.png"), s.tools[0].files)
    }

    @Test(expected = StackFormatException::class)
    fun rejectsNewerSchema() {
        StackCodec.decode("""{"schemaVersion":99}""")
    }

    @Test(expected = StackFormatException::class)
    fun rejectsGarbage() {
        StackCodec.decode("not json")
    }

    @Test(expected = StackFormatException::class)
    fun rejectsCorruptPackedStack() {
        StackCodec.unpack("AAAA####")
    }

    @Test
    fun subsetAndPaste() {
        val s = sample()
        val light = s.subset(setOf(Group.LIGHT))
        assertEquals(setOf(Params.EXPOSURE, Params.CONTRAST), light.values.keys)
        assertNull(light.lookId)
        val target = EditStack().with(Params.SATURATION, 10f).with(Params.EXPOSURE, -1f)
        val pasted = target.pasteFrom(s, setOf(Group.LIGHT))
        assertEquals(1.25f, pasted[Params.EXPOSURE])
        assertEquals(10f, pasted[Params.SATURATION])
    }

    @Test
    fun blendInterpolatesTowardPreset() {
        val preset = EditStack().with(Params.EXPOSURE, 2f).withCurve("rgb", listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 1f)))
        val half = EditStack().blendToward(preset, setOf(Group.LIGHT, Group.CURVE), 0.5f)
        assertEquals(1f, half[Params.EXPOSURE], 1e-4f)
        assertEquals(0.1f, half.curve("rgb").first().y, 1e-4f)
    }

    @Test
    fun historyCoalescesSliderDragsAndUndoes() {
        val h = EditHistory(EditStack())
        for (v in 1..10) h.push(h.current.with(Params.EXPOSURE, v / 10f), "Exposure", "slider-exposure")
        h.seal()
        h.push(h.current.with(Params.CONTRAST, 10f), "Contrast", "slider-contrast")
        assertEquals(2, h.steps.size)
        h.undo()
        assertEquals(1f, h.current[Params.EXPOSURE])
        assertEquals(0f, h.current[Params.CONTRAST])
        h.undo()
        assertTrue(h.current.isIdentity)
        assertFalse(h.canUndo)
        h.redo(); h.redo()
        assertEquals(10f, h.current[Params.CONTRAST])
        h.snapshot("A")
        h.push(EditStack(), "Revert")
        h.restore(h.snapshots[0])
        assertEquals(10f, h.current[Params.CONTRAST])
    }

    @Test
    fun storeFindsStackAgainByHashAfterIdChange() {
        val dir = Files.createTempDirectory("edits").toFile()
        try {
            val store = EditStore(dir)
            val hash = EditStore.hash("photo bytes".byteInputStream())
            assertEquals(32, hash.length)
            val old = SourceRef(10, hash)
            store.save(old, sample(), listOf(EditHistory.Snapshot("s", EditStack().with(Params.EXPOSURE, 1f), 5)))
            val moved = SourceRef(77, hash)
            val loaded = store.load(moved)
            assertNotNull(loaded)
            assertEquals(1.25f, loaded!!.stack[Params.EXPOSURE])
            assertEquals(77L, loaded.stack.source!!.mediaId)
            assertEquals("s", loaded.snapshots.single().name)
            assertTrue(File(dir, "77-$hash.json").isFile)
            assertFalse(File(dir, "10-$hash.json").exists())
            // Same id, different hash (location redaction): still found.
            assertNotNull(store.load(SourceRef(77, "ffffffffffffffffffffffffffffffff")))
            assertNull(store.load(SourceRef(78, "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")))
            val rekeyed = SourceRef(77, "ffffffffffffffffffffffffffffffff")
            assertTrue(store.delete(rekeyed))
            assertNull(store.load(rekeyed))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptStoredStackLoadsAsNothing() {
        val dir = Files.createTempDirectory("edits").toFile()
        try {
            val ref = SourceRef(1, "aa")
            File(dir, "1-aa.json").writeText("{broken")
            assertNull(EditStore(dir).load(ref))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun originalBackupsExpire() {
        val dir = Files.createTempDirectory("orig").toFile()
        try {
            val b = OriginalBackups(dir)
            val f = b.keep("abc", "IMG 1.jpg", "x".byteInputStream())
            assertEquals(f, b.find("abc"))
            f.setLastModified(1000)
            assertEquals(1, b.purge(now = 1000 + OriginalBackups.RETENTION_MS + 1))
            assertNull(b.find("abc"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
