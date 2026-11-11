/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dev.opencamera.capture.BurstKind
import dev.opencamera.pipeline.Burst
import dev.opencamera.pipeline.MultiFrameMode
import dev.opencamera.pipeline.MultiFrameProcessor
import dev.opencamera.pipeline.queue.BurstArchive
import dev.opencamera.vendorbridge.ProcessingSource
import dev.opencamera.vendorbridge.SourcePolicy
import java.io.File
import java.util.UUID

/** Merge and save parameters of a burst; serialisable into a [BurstArchive]. */
data class MergeJob(
    val kind: BurstKind,
    val toneEvStep: Float,
    val crop: IntArray?,
    val rotationDegrees: Int,
    val timestamp: Long,
    val exposure: ExposureExif?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val source: String = "YUV",
) {
    val mode: MultiFrameMode
        get() = when (kind) {
            BurstKind.NIGHT -> MultiFrameMode.NIGHT
            BurstKind.ASTRO -> MultiFrameMode.ASTRO
            BurstKind.SUPER_RES -> MultiFrameMode.SUPER_RES
            else -> MultiFrameMode.HDR
        }

    fun location(): Location? = if (latitude != null && longitude != null) {
        Location("burst").also { l ->
            l.latitude = latitude
            l.longitude = longitude
            altitude?.let { l.altitude = it }
        }
    } else {
        null
    }

    fun toMap(): Map<String, String> = buildMap {
        put("kind", kind.name)
        put("tone", toneEvStep.toString())
        crop?.let { put("crop", it.joinToString(",")) }
        put("rotation", rotationDegrees.toString())
        put("timestamp", timestamp.toString())
        exposure?.exposureNs?.let { put("exposureNs", it.toString()) }
        exposure?.iso?.let { put("iso", it.toString()) }
        exposure?.aperture?.let { put("aperture", it.toString()) }
        exposure?.focalLength?.let { put("focal", it.toString()) }
        latitude?.let { put("lat", it.toString()) }
        longitude?.let { put("lon", it.toString()) }
        altitude?.let { put("alt", it.toString()) }
        put("source", source)
    }

    companion object {
        fun of(
            kind: BurstKind, toneEvStep: Float, crop: IntArray?, rotationDegrees: Int, timestamp: Long,
            exposure: ExposureExif?, location: Location?, source: String,
        ) = MergeJob(
            kind, toneEvStep, crop, rotationDegrees, timestamp, exposure,
            location?.latitude, location?.longitude, location?.takeIf { it.hasAltitude() }?.altitude, source,
        )

        fun fromMap(m: Map<String, String>): MergeJob? = runCatching {
            MergeJob(
                kind = BurstKind.valueOf(m.getValue("kind")),
                toneEvStep = m.getValue("tone").toFloat(),
                crop = m["crop"]?.split(",")?.map { it.toInt() }?.toIntArray()?.takeIf { it.size == 4 },
                rotationDegrees = m["rotation"]?.toInt() ?: 0,
                timestamp = m.getValue("timestamp").toLong(),
                exposure = ExposureExif(
                    m["exposureNs"]?.toLong(), m["iso"]?.toInt(), m["aperture"]?.toFloat(), m["focal"]?.toFloat(),
                ),
                latitude = m["lat"]?.toDouble(),
                longitude = m["lon"]?.toDouble(),
                altitude = m["alt"]?.toDouble(),
                source = m["source"] ?: "YUV",
            )
        }.getOrNull()
    }
}

/** Merges a burst and saves the JPEG with EXIF; shared by live and restored bursts. */
object MergeRunner {
    private const val TAG = "MultiFrame"

    fun run(context: Context, burst: Burst, job: MergeJob, progress: ((Float) -> Unit)? = null): Uri? {
        val t0 = SystemClock.elapsedRealtime()
        val image = if (job.kind == BurstKind.SINGLE) {
            // One frame: no merge; a RAW frame at a zoom stop still needs its crop.
            if (job.crop != null) {
                MultiFrameProcessor.processOrFallback(burst, MultiFrameMode.HDR, 0f, null, job.crop)
            } else {
                burst.exportFrame(0)
            }
        } else {
            MultiFrameProcessor.processOrFallback(burst, job.mode, job.toneEvStep, { progress?.invoke(it * 0.85f) }, job.crop)
        } ?: return null
        val t1 = SystemClock.elapsedRealtime()
        val jpeg = image.toJpeg()
        progress?.invoke(0.95f)
        val t2 = SystemClock.elapsedRealtime()
        val uri = MultiFrameSaver.save(
            context, jpeg, image.width, image.height,
            MultiFrameSaver.Metadata(
                timestamp = job.timestamp,
                rotationDegrees = job.rotationDegrees,
                captureResult = null,
                location = job.location(),
                description = SourcePolicy.label(
                    ProcessingSource.OPEN,
                    if (job.kind == BurstKind.SINGLE) "Single ${job.source}" else
                        "${job.mode.name} ${image.frameCount} frames ${job.source}",
                ),
                exposure = job.exposure,
            )
        )
        val t3 = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "kind=${job.kind} source=${job.source} frames=${image.frameCount} size=${image.width}x${image.height} " +
                "crop=${job.crop?.joinToString("x")} align=${image.msAlign} merge=${image.msMerge} " +
                "finish=${image.msFinish} process=${t1 - t0}ms jpeg=${t2 - t1}ms save=${t3 - t2}ms " +
                "noise=${image.noiseSigma} weight=${image.meanWeight} ref=${image.referenceIndex}"
        )
        return uri
    }
}

/**
 * Bursts on disk under `cache/bursts`. A burst is archived while it merges and deleted once the
 * result is saved; archives left by a dead process are merged through the persistent queue.
 */
object BurstRecovery {
    const val SCHEME = "burst:"
    private const val TAG = "BurstRecovery"
    private const val KEY_OWNER = "owner"
    private const val STALE_INCOMPLETE_MS = 10 * 60 * 1000L

    /** Distinguishes archives of this process (still merging in memory) from orphans. */
    private val processToken = UUID.randomUUID().toString()

    fun root(context: Context) = File(context.cacheDir, "bursts")

    fun newDir(context: Context, timestamp: Long) = File(root(context), "$timestamp-${UUID.randomUUID()}")

    fun archive(burst: Burst, dir: File, job: MergeJob): Boolean = runCatching {
        val n = burst.frameCount
        val t0 = SystemClock.elapsedRealtime()
        BurstArchive.write(dir, burst.width, burst.height, n, job.toMap() + (KEY_OWNER to processToken)) {
            burst.exportFrame(it)?.nv21
        }
        Log.i(TAG, "Archived $n frames ${burst.width}x${burst.height} in ${SystemClock.elapsedRealtime() - t0} ms")
        true
    }.getOrElse {
        Log.w(TAG, "Cannot archive burst, merge is not crash safe", it)
        BurstArchive.delete(dir)
        false
    }

    /** Archives left by another (dead) process, ready to merge. Deletes stale partial ones. */
    fun orphans(context: Context): List<File> {
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.filter { dir ->
            val a = BurstArchive.open(dir)
            if (a == null) {
                if (System.currentTimeMillis() - dir.lastModified() > STALE_INCOMPLETE_MS) BurstArchive.delete(dir)
                false
            } else {
                a.meta[KEY_OWNER] != processToken
            }
        }
    }

    /** Restores and merges the archive in [dir]; deletes it once saved. */
    fun mergeArchived(context: Context, dir: File): Uri? {
        val archive = BurstArchive.open(dir) ?: return null
        val job = MergeJob.fromMap(archive.meta) ?: run {
            BurstArchive.delete(dir)
            return null
        }
        val uri = Burst(archive.width, archive.height, archive.frameCount).use { burst ->
            for (i in 0 until archive.frameCount) {
                if (!burst.addNv21(BurstArchive.readFrame(archive, i))) break
            }
            check(burst.frameCount > 0) { "No frame restored from $dir" }
            Log.i(TAG, "Restored ${burst.frameCount} frames from ${dir.name}")
            MergeRunner.run(context, burst, job)
        }
        if (uri != null) BurstArchive.delete(dir)
        return uri
    }
}
