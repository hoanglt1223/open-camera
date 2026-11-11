/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.vendorbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorBridgeTest {
    @Test
    fun userChoiceOpenAlwaysWins() {
        assertEquals(
            ProcessingSource.OPEN,
            SourcePolicy.resolve(ProcessingSource.OPEN, true, true, false),
        )
    }

    @Test
    fun phoneProcessingNeedsBridgeAvailabilityAndNoFailure() {
        val phone = ProcessingSource.PHONE
        assertEquals(phone, SourcePolicy.resolve(phone, true, true, false))
        assertEquals(ProcessingSource.OPEN, SourcePolicy.resolve(phone, false, true, false))
        assertEquals(ProcessingSource.OPEN, SourcePolicy.resolve(phone, true, false, false))
        assertEquals(ProcessingSource.OPEN, SourcePolicy.resolve(phone, true, true, true))
    }

    @Test
    fun labels() {
        assertEquals("OEM Night", SourcePolicy.label(ProcessingSource.PHONE, "Night"))
        assertEquals("Open Camera HDR", SourcePolicy.label(ProcessingSource.OPEN, "HDR"))
    }

    @Test
    fun parsesTypedValues() {
        assertEquals(1, VendorTagRegistry.parse("a", "i32:1")!!.value)
        assertEquals(5L, VendorTagRegistry.parse("a", "i64: 5")!!.value)
        assertEquals(255.toByte(), VendorTagRegistry.parse("a", "u8:255")!!.value)
        assertEquals(true, VendorTagRegistry.parse("a", "bool:true")!!.value)
        assertEquals(0.5f, VendorTagRegistry.parse("a", "f32:0.5")!!.value)
        assertArrayEquals(
            intArrayOf(1, 2), VendorTagRegistry.parse("a", "i32[]:1, 2")!!.value as IntArray
        )
    }

    @Test
    fun rejectsMalformedValues() {
        assertNull(VendorTagRegistry.parse("a", "1"))
        assertNull(VendorTagRegistry.parse("a", "i32:x"))
        assertNull(VendorTagRegistry.parse("a", "u8:256"))
        assertNull(VendorTagRegistry.parse("a", "bool:yes"))
        assertNull(VendorTagRegistry.parse("a", "str:hi"))
        assertNull(VendorTagRegistry.parse("", "i32:1"))
    }

    @Test
    fun selectsOnlyVerifiedOfferedAndValidTags() {
        val registry = VendorTagRegistry()
        val tags = mapOf(
            "org.codeaurora.qcamera3.mfnr.enable" to "u8:1",
            "com.qti.missing" to "i32:1",
            "xiaomi.bad" to "i32:oops",
        )
        val offered = setOf("org.codeaurora.qcamera3.mfnr.enable", "xiaomi.bad")
        assertEquals(emptyList<VendorTagRegistry.Tag>(), registry.select(tags, false, offered))
        val selected = registry.select(tags, true, offered)
        assertEquals(listOf("org.codeaurora.qcamera3.mfnr.enable"), selected.map { it.name })
        assert(registry.isDisabled("xiaomi.bad"))

        registry.disable("org.codeaurora.qcamera3.mfnr.enable", "test")
        assertEquals(emptyList<VendorTagRegistry.Tag>(), registry.select(tags, true, offered))
    }
}
