/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.export

import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.StackCodec
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small XMP writer/reader for exported JPEGs: `xmp:CreatorTool`, a `photoshop:History`
 * summary and the app's own `opencamera:EditStack` (deflated, base64) so an export can be
 * reopened and edited further. No `crs:` here: the sliders do not mean the same as Adobe's;
 * see [CrsXmp] for the opt-in Lightroom sidecar.
 */
object XmpWriter {
    const val NS_OPENCAMERA = "http://ns.opencamera.dev/edit/1.0/"
    private const val NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private const val MAX_SEGMENT = 65533

    fun packet(stack: EditStack?, creatorTool: String, history: String): String {
        val packed = stack?.let { StackCodec.pack(it) }
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"").append(NS_RDF).append("\">")
            append("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"")
            append(" xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\" xmlns:opencamera=\"").append(NS_OPENCAMERA).append("\"")
            append(" xmp:CreatorTool=\"").append(escape(creatorTool)).append("\"")
            append(" photoshop:History=\"").append(escape(history)).append("\"")
            if (packed != null) append(" opencamera:EditStack=\"").append(packed).append("\"")
            append("/></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
        }
    }

    /**
     * Returns [jpeg] with one XMP APP1 segment after the leading APP0/APP1 segments, replacing any
     * existing XMP. If the packet does not fit one segment the edit stack is left out.
     */
    fun insert(jpeg: ByteArray, stack: EditStack?, creatorTool: String, history: String): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG" }
        var xmp = packet(stack, creatorTool, history).toByteArray(Charsets.UTF_8)
        if (xmp.size + HEADER.size + 2 > MAX_SEGMENT) xmp = packet(null, creatorTool, history).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(jpeg.size + xmp.size + 64)
        out.write(jpeg, 0, 2)
        var pos = 2
        var inserted = false
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            val end = pos + 2 + len
            if (end > jpeg.size) break
            val isApp01 = marker == 0xE0 || marker == 0xE1
            if (!isApp01 && !inserted) {
                writeSegment(out, xmp)
                inserted = true
            }
            if (!(marker == 0xE1 && isXmp(jpeg, pos + 4, len - 2))) out.write(jpeg, pos, end - pos)
            pos = end
        }
        if (!inserted) writeSegment(out, xmp)
        out.write(jpeg, pos, jpeg.size - pos)
        return out.toByteArray()
    }

    private fun writeSegment(out: ByteArrayOutputStream, xmp: ByteArray) {
        val len = HEADER.size + xmp.size + 2
        out.write(0xFF); out.write(0xE1); out.write(len shr 8); out.write(len and 0xFF)
        out.write(HEADER)
        out.write(xmp)
    }

    private fun isXmp(b: ByteArray, off: Int, len: Int): Boolean {
        if (len < HEADER.size || off + HEADER.size > b.size) return false
        for (i in HEADER.indices) if (b[off + i] != HEADER[i]) return false
        return true
    }

    /** The XMP packet of a JPEG, or null. */
    fun extract(jpeg: ByteArray): String? {
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) return null
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (marker == 0xE1 && isXmp(jpeg, pos + 4, len - 2)) {
                val start = pos + 4 + HEADER.size
                return String(jpeg, start, (pos + 2 + len - start).coerceAtMost(jpeg.size - start), Charsets.UTF_8)
            }
            pos += 2 + len
        }
        return null
    }

    /** The edit stack embedded by this app in an exported JPEG, or null. */
    fun readStack(jpeg: ByteArray): EditStack? {
        val xmp = extract(jpeg) ?: return null
        val packed = descriptions(xmp).firstNotNullOfOrNull { d ->
            d.getAttributeNS(NS_OPENCAMERA, "EditStack").ifBlank { null }
        } ?: return null
        return runCatching { StackCodec.unpack(packed) }.getOrNull()
    }

    internal fun descriptions(xml: String): List<Element> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // XMP never needs DTDs or entities; refuse them (XXE).
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val start = xml.indexOf("<x:xmpmeta").takeIf { it >= 0 } ?: xml.indexOf("<rdf:RDF").coerceAtLeast(0)
        val endTag = xml.lastIndexOf("</x:xmpmeta>")
        val body = if (endTag > 0) xml.substring(start, endTag + "</x:xmpmeta>".length) else xml.substring(start)
        val doc = f.newDocumentBuilder().parse(body.byteInputStream(Charsets.UTF_8))
        val nodes = doc.getElementsByTagNameNS(NS_RDF, "Description")
        return List(nodes.length) { nodes.item(it) as Element }
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
