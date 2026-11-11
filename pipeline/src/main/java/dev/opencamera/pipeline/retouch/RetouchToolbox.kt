/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.ModelDownloader
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.SaliencyDetector
import dev.opencamera.ml.tasks.Segmenter
import kotlin.math.abs

/**
 * All retouch tools behind one object: lookup by id, replay of a [RetouchStack] on any bitmap
 * (preview or full resolution), metadata tags and the optional big-lama download.
 */
class RetouchToolbox(context: Context) {
    private val app = context.applicationContext
    val runtime = MlRuntime.get(app)
    val regions = FaceRegions(FaceMesh(runtime))
    val inpainter = Inpainter(runtime)

    val tools: List<RetouchTool> = listOf(
        EraseTool(inpainter, SelectionMasker(Segmenter(runtime), SaliencyDetector(runtime))),
        BlemishTool(),
        SkinSmoothTool(regions),
        RedEyeTool(regions),
        TeethWhitenTool(regions),
        EyeBrightenTool(regions),
        WhiteBalanceTool(),
        GeometryTool(),
        ReshapeTool(regions),
    )

    fun tool(id: String) = tools.firstOrNull { it.id == id }

    fun tagsOf(op: RetouchOp): Set<String> = tool(op.tool)?.tagsFor(op.params).orEmpty()

    class Replay(val bitmap: Bitmap, val report: List<String>, val tags: Set<String>)

    /**
     * Applies [ops] in order. A failing op is skipped and reported; the rest still run, so an
     * export never loses every edit because one model became unavailable.
     */
    fun replay(image: Bitmap, ops: List<RetouchOp>): Replay {
        var current = image
        val report = ArrayList<String>()
        val tags = HashSet<String>()
        for (op in ops) {
            val tool = tool(op.tool)
            if (tool == null) {
                report += "${op.tool}=unknown"
                continue
            }
            try {
                val r = tool.apply(current, op.params)
                if (r.bitmap !== current) current.recycle()
                current = r.bitmap
                tags += tool.tagsFor(op.params)
                report += "${op.tool}=${r.method}"
            } catch (e: RetouchUnavailable) {
                report += "${op.tool}=skipped(${e.message})"
            } catch (e: OutOfMemoryError) {
                report += "${op.tool}=skipped(OOM)"
            } catch (e: Exception) {
                Log.w(TAG, "Replay of ${op.tool} failed", e)
                report += "${op.tool}=skipped(${e.javaClass.simpleName})"
            }
        }
        return Replay(current, report, tags)
    }

    /** big-lama: size and source shown to the user before asking to download. */
    val lamaSpec get() = runtime.registry.spec(Inpainter.LAMA)

    /** Blocking download with SHA-256 check; throws IOException. Call only after the user agreed. */
    fun downloadLama(progress: ModelDownloader.Progress?) {
        val spec = lamaSpec ?: throw java.io.IOException("big-lama is not declared")
        ModelDownloader(runtime.registry).download(spec, progress)
    }

    private companion object {
        const val TAG = "RetouchToolbox"
    }
}

/**
 * Lens distortion for photos taken by this phone: Camera2 LENS_DISTORTION and intrinsics of the
 * camera whose focal length matches the EXIF one, normalized to the image size. Null for other
 * phones, older Android, missing data, a cropped aspect ratio, or when the HAL already corrects
 * distortion in its JPEGs (then a second correction would distort the photo).
 */
object LensProfiles {
    @SuppressLint("InlinedApi")
    fun forPhoto(context: Context, exif: ExifInterface?, width: Int, height: Int): Map<String, Float>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || exif == null) return null
        if (exif.getAttribute(ExifInterface.TAG_MODEL) != Build.MODEL) return null
        val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat()
        if (focal <= 0f) return null
        return runCatching {
            val cm = context.getSystemService(CameraManager::class.java)
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
                if (focals.none { abs(it - focal) < 0.05f }) continue
                val modes = ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                if (modes != null && modes.any { it != CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF }) return null
                val d = ch.get(CameraCharacteristics.LENS_DISTORTION) ?: continue
                val k = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: continue
                val array = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: continue
                val aw = array.width().toFloat()
                val ah = array.height().toFloat()
                val (iw, ih) = if ((width > height) == (aw > ah)) width to height else height to width
                if (abs(iw.toFloat() / ih - aw / ah) > 0.02f || d.all { it == 0f }) return null
                return mapOf(
                    "fx" to k[0] / aw, "fy" to k[1] / ah, "cx" to k[2] / aw, "cy" to k[3] / ah,
                    "k1" to d[0], "k2" to d[1], "k3" to d[2], "p1" to d[3], "p2" to d[4],
                )
            }
            null
        }.getOrNull()
    }
}

/** EXIF/XMP disclosure of retouch edits; the photo editor shell (phase 12) may write XMP history. */
object RetouchExif {
    fun write(exif: ExifInterface, tags: Set<String>, software: String) {
        if (tags.isEmpty()) return
        val label = tags.sorted().joinToString(",")
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "$software (retouch: $label)")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Edited: $label")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "OpenCamera-Retouch: $label")
    }
}
