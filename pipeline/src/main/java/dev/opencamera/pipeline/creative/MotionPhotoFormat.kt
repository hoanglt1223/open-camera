/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import java.io.ByteArrayOutputStream

/**
 * Google Motion Photo container: the still JPEG with an XMP packet describing the container
 * (`GCamera:MotionPhoto` + `Container:Directory`, plus the older `MicroVideo` keys read by
 * earlier Google Photos builds), followed directly by the MP4.
 */
object MotionPhotoFormat {
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    /** Builds a motion photo from [jpeg] and [mp4]; [presentationUs] is the still's time in the video. */
    fun build(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long): ByteArray {
        require(isJpeg(jpeg)) { "Not a JPEG" }
        require(mp4.size > 8) { "Empty video" }
        val segments = splitHeader(jpeg)
        val xmp = xmpPacket(mp4.size, presentationUs).toByteArray(Charsets.UTF_8)
        val ns = XMP_NS.toByteArray(Charsets.ISO_8859_1)
        val payload = ns.size + xmp.size
        require(payload + 2 <= 0xffff) { "XMP too large" }
        val out = ByteArrayOutputStream(jpeg.size + mp4.size + payload + 16)
        out.write(0xff); out.write(0xd8)
        // Keep APP0/Exif first, drop any older XMP, then ours, then the rest of the file.
        var rest = 2
        for (s in segments) {
            if (s.isXmp) {
                rest = s.end
                continue
            }
            if (s.marker == 0xe0 || s.marker == 0xe1) {
                out.write(jpeg, s.start, s.end - s.start)
                rest = s.end
            } else {
                break
            }
        }
        out.write(0xff); out.write(0xe1)
        out.write((payload + 2) ushr 8); out.write((payload + 2) and 0xff)
        out.write(ns)
        out.write(xmp)
        out.write(jpeg, rest, jpeg.size - rest)
        out.write(mp4)
        return out.toByteArray()
    }

    /** Length of the embedded video declared in the XMP, or null if [file] is not a motion photo. */
    fun videoLength(file: ByteArray): Int? {
        if (!isJpeg(file)) return null
        val seg = splitHeader(file).firstOrNull { it.isXmp } ?: return null
        val text = String(file, seg.start, seg.end - seg.start, Charsets.ISO_8859_1)
        if (!text.contains("GCamera:MotionPhoto=\"1\"")) return null
        val len = Regex("""Item:Semantic="MotionPhoto"[^>]*Item:Length="(\d+)"""").find(text)
            ?: Regex("""GCamera:MicroVideoOffset="(\d+)"""").find(text) ?: return null
        return len.groupValues[1].toIntOrNull()?.takeIf { it in 1 until file.size }
    }

    /** Extracts the trailing MP4 of a motion photo, or null. */
    fun extractVideo(file: ByteArray): ByteArray? {
        val len = videoLength(file) ?: return null
        return file.copyOfRange(file.size - len, file.size)
    }

    /**
     * Re-attaches the clip of [original] (if it is a motion photo) to [rendered], a re-encoded
     * still of it. Returns [rendered] unchanged otherwise.
     */
    fun carryOver(original: ByteArray, rendered: ByteArray): ByteArray {
        val video = extractVideo(original) ?: return rendered
        val seg = splitHeader(original).first { it.isXmp }
        val text = String(original, seg.start, seg.end - seg.start, Charsets.ISO_8859_1)
        val ts = Regex("""MotionPhotoPresentationTimestampUs="(-?\d+)"""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return build(rendered, video, ts)
    }

    fun isJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte()

    private class Segment(val marker: Int, val start: Int, val end: Int, val isXmp: Boolean)

    /** Marker segments between SOI and SOS. */
    private fun splitHeader(jpeg: ByteArray): List<Segment> {
        val list = ArrayList<Segment>()
        var p = 2
        val ns = XMP_NS.toByteArray(Charsets.ISO_8859_1)
        while (p + 4 <= jpeg.size && (jpeg[p].toInt() and 0xff) == 0xff) {
            val marker = jpeg[p + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) break
            val len = ((jpeg[p + 2].toInt() and 0xff) shl 8) or (jpeg[p + 3].toInt() and 0xff)
            val end = p + 2 + len
            if (len < 2 || end > jpeg.size) break
            val isXmp = marker == 0xe1 && len - 2 >= ns.size &&
                (0 until ns.size).all { jpeg[p + 4 + it] == ns[it] }
            list += Segment(marker, p, end, isXmp)
            p = end
        }
        return list
    }

    private fun xmpPacket(videoLength: Int, presentationUs: Long) = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Open Camera">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            GCamera:MotionPhoto="1"
            GCamera:MotionPhotoVersion="1"
            GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
            GCamera:MicroVideo="1"
            GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="$videoLength"
            GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
           <Container:Directory>
            <rdf:Seq>
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
             </rdf:li>
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
             </rdf:li>
            </rdf:Seq>
           </Container:Directory>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()
}
