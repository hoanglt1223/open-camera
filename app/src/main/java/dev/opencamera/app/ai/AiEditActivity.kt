/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.processing.PortraitOriginals
import dev.opencamera.app.processing.ProcessingService
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.ImageRestorer
import dev.opencamera.ml.tasks.Segmenter
import dev.opencamera.pipeline.StillProcessor
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.ai.BeautyProcessor
import dev.opencamera.pipeline.ai.BokehMath
import dev.opencamera.pipeline.ai.BokehProcessor
import dev.opencamera.pipeline.ai.EnhanceProcessor
import dev.opencamera.pipeline.ai.Masks
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photo editor for the ML tools: portrait blur (aperture slider, tap to choose the focus),
 * beauty, denoise and 2x upscale. Opened from a gallery's "Edit" action. Portrait and beauty
 * render here and save a new copy; denoise and upscale run as background jobs when the photo
 * is in MediaStore. Re-editing a portrait uses its kept unblurred original when available.
 */
class AiEditActivity : AppCompatActivity() {
    private enum class Tool { PORTRAIT, BEAUTY, DENOISE, UPSCALE }

    private lateinit var imageView: ImageView
    private lateinit var status: TextView
    private lateinit var slider: SeekBar
    private lateinit var saveButton: Button

    private val runtime by lazy { MlRuntime.get(this) }
    private val segmenter by lazy { Segmenter(runtime) }
    private val bokeh by lazy { BokehProcessor(segmenter) }
    private val beauty by lazy { BeautyProcessor(FaceMesh(runtime), segmenter) }

    private var source: Uri? = null
    private var originalFile: File? = null
    private var displayName = "photo"
    private var rotation = 0
    private var work: Bitmap? = null
    private var prepared: BokehProcessor.Prepared? = null
    private var tool = Tool.PORTRAIT
    private var focus: Pair<Float, Float>? = null
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val uri = intent?.data ?: (intent?.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        if (uri == null) {
            Toast.makeText(this, R.string.ai_editor_no_image, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        source = uri
        load(uri)
    }

    override fun onDestroy() {
        prepared?.release()
        work?.recycle()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff000000.toInt())
            fitsSystemWindows = true
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.ai_editor_title)
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP && tool == Tool.PORTRAIT) {
                    onTap(e.x, e.y)
                    v.performClick()
                }
                true
            }
        }
        root.addView(imageView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        slider = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) refresh()
                }

                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        root.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(24, 8, 24, 8) })
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, label) in listOf(
            Tool.PORTRAIT to R.string.ai_tool_portrait,
            Tool.BEAUTY to R.string.ai_tool_beauty,
            Tool.DENOISE to R.string.ai_tool_denoise,
            Tool.UPSCALE to R.string.ai_tool_upscale,
        )) {
            tools.addView(Button(this).apply {
                setText(label)
                setOnClickListener { selectTool(t) }
            })
        }
        saveButton = Button(this).apply {
            setText(R.string.ai_editor_save)
            setOnClickListener { save() }
        }
        tools.addView(saveButton)
        root.addView(HorizontalScrollView(this).apply { addView(tools) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        title = getString(R.string.ai_editor_title)
    }

    private fun load(uri: Uri) {
        status.setText(R.string.ai_editor_loading)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    displayName = queryName(uri) ?: "photo"
                    originalFile = PortraitOriginals.find(this@AiEditActivity, displayName)
                    rotation = open()?.use { ExifInterface(it).rotationDegrees } ?: 0
                    work = decode(WORK_LONG_SIDE)
                    work != null
                }.onFailure { Log.e(TAG, "Cannot load $uri", it) }.getOrDefault(false)
            }
            if (!ok) {
                Toast.makeText(this@AiEditActivity, R.string.ai_editor_load_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            selectTool(Tool.PORTRAIT)
        }
    }

    private fun open() = originalFile?.inputStream() ?: contentResolver.openInputStream(source!!)

    /** Decodes the source with a power-of-two sample so the long side is near [longSide]; 0 = full. */
    private fun decode(longSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        if (longSide > 0) {
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
        }
        return open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    private fun selectTool(t: Tool) {
        tool = t
        slider.progress = when (t) {
            Tool.PORTRAIT -> AiSettings(this).portraitAperture.times(100).toInt()
            Tool.BEAUTY -> 40
            Tool.DENOISE -> 70
            Tool.UPSCALE -> 100
        }
        slider.isEnabled = t != Tool.UPSCALE
        refresh()
    }

    private fun onTap(x: Float, y: Float) {
        val drawable = imageView.drawable ?: return
        val inverse = Matrix()
        if (!imageView.imageMatrix.invert(inverse)) return
        val p = floatArrayOf(x - imageView.paddingLeft, y - imageView.paddingTop)
        inverse.mapPoints(p)
        val u = p[0] / drawable.intrinsicWidth
        val v = p[1] / drawable.intrinsicHeight
        if (u !in 0f..1f || v !in 0f..1f) return
        focus = Masks.unrotatePoint(u, v, rotation)
        refresh()
    }

    private fun refresh() {
        val base = work ?: return
        renderJob?.cancel()
        val value = slider.progress / 100f
        val t = tool
        renderJob = lifecycleScope.launch {
            status.setText(R.string.ai_editor_working)
            val (shown, message) = withContext(Dispatchers.Default) { preview(base, t, value) }
            imageView.setImageBitmap(Masks.rotated(shown, rotation))
            status.text = message
        }
    }

    private fun preview(base: Bitmap, t: Tool, value: Float): Pair<Bitmap, String> = try {
        when (t) {
            Tool.PORTRAIT -> {
                val p = prepared ?: bokeh.prepare(base, rotation).also { prepared = it }
                if (p == null) {
                    base to getString(R.string.ai_portrait_no_subject)
                } else {
                    val t0 = SystemClock.elapsedRealtime()
                    val out = bokeh.renderPreview(p, BokehProcessor.Params(value, focus?.first, focus?.second)) ?: base
                    out to getString(
                        R.string.ai_portrait_status, BokehMath.fNumber(value), p.model, SystemClock.elapsedRealtime() - t0,
                    )
                }
            }
            Tool.BEAUTY -> {
                val copy = base.copy(Bitmap.Config.ARGB_8888, true)
                val outcome = beauty.process(copy, rotation, value)
                copy to outcome.toString()
            }
            Tool.DENOISE -> base to getString(
                if (ImageRestorer(runtime).denoiseAvailable) R.string.ai_denoise_model else R.string.ai_denoise_classical
            )
            Tool.UPSCALE -> base to getString(
                if (ImageRestorer(runtime).superResAvailable) R.string.ai_upscale_model else R.string.ai_upscale_classical
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Preview failed", e)
        base to getString(R.string.ai_editor_failed)
    }

    private fun save() {
        val uri = source ?: return
        val value = slider.progress / 100f
        if ((tool == Tool.DENOISE || tool == Tool.UPSCALE) && uri.authority == MediaStore.AUTHORITY &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val ops = if (tool == Tool.DENOISE) {
                AiOps(denoise = value, keepSource = true, budgetMs = JOB_BUDGET_MS)
            } else {
                AiOps(upscale = 2, keepSource = true, budgetMs = JOB_BUDGET_MS)
            }
            ProcessingService.enqueue(this, uri, null, 1f, WatermarkTemplate.NONE.key, ops)
            Toast.makeText(this, R.string.ai_editor_queued, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        saveButton.isEnabled = false
        status.setText(R.string.ai_editor_saving)
        val t = tool
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { renderFull(t, value) }.onFailure { Log.e(TAG, "Save failed", it) }.getOrNull()
            }
            saveButton.isEnabled = true
            if (result == null) {
                status.setText(R.string.ai_editor_failed)
                return@launch
            }
            Toast.makeText(this@AiEditActivity, getString(R.string.ai_editor_saved, result), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Full-resolution render and save as a new photo; returns a short description. */
    private fun renderFull(t: Tool, value: Float): String {
        val full = decode(0) ?: error("Cannot decode")
        val exif = open()?.use { ExifInterface(it) }
        var bitmap = full
        val note: String
        when (t) {
            Tool.PORTRAIT -> {
                val p = prepared ?: error("No subject")
                check(bokeh.render(bitmap, p, BokehProcessor.Params(value, focus?.first, focus?.second))) { "Render failed" }
                note = "f/%.1f".format(BokehMath.fNumber(value))
            }
            Tool.BEAUTY -> note = beauty.process(bitmap, rotation, value).toString()
            Tool.DENOISE, Tool.UPSCALE -> {
                val enhance = EnhanceProcessor(ImageRestorer(runtime))
                val r = if (t == Tool.DENOISE) {
                    enhance.denoise(bitmap, value, JOB_BUDGET_MS)
                } else {
                    enhance.upscale(bitmap, 2, JOB_BUDGET_MS)
                }
                if (r.bitmap !== bitmap) bitmap.recycle()
                bitmap = r.bitmap
                note = r.method.name.lowercase()
            }
        }
        val tmp = File(cacheDir, "ai-edit.jpg")
        try {
            StillProcessor(contentResolver).processBitmap(bitmap, exif, null, WatermarkTemplate.NONE, Build.MODEL, tmp)
            insert(tmp, "${displayName.substringBeforeLast('.')}_edit")
        } finally {
            tmp.delete()
        }
        return note
    }

    private fun insert(file: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Insert failed")
        try {
            contentResolver.openOutputStream(out, "w")!!.use { os -> file.inputStream().use { it.copyTo(os) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            contentResolver.delete(out, null, null)
            throw e
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val TAG = "AiEditActivity"
        const val WORK_LONG_SIDE = 1024
        const val JOB_BUDGET_MS = 180_000L
    }
}
