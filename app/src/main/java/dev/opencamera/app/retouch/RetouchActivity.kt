/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.retouch

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.processing.ProcessingService
import dev.opencamera.pipeline.StillProcessor
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.ai.AiStage
import dev.opencamera.pipeline.retouch.BlemishTool
import dev.opencamera.pipeline.retouch.GeometryTool
import dev.opencamera.pipeline.retouch.InputKind
import dev.opencamera.pipeline.retouch.LensProfiles
import dev.opencamera.pipeline.retouch.NPoint
import dev.opencamera.pipeline.retouch.ReshapeTool
import dev.opencamera.pipeline.retouch.RetouchBenchmark
import dev.opencamera.pipeline.retouch.RetouchExif
import dev.opencamera.pipeline.retouch.RetouchParams
import dev.opencamera.pipeline.retouch.RetouchResult
import dev.opencamera.pipeline.retouch.RetouchSession
import dev.opencamera.pipeline.retouch.RetouchTool
import dev.opencamera.pipeline.retouch.RetouchToolbox
import dev.opencamera.pipeline.retouch.RetouchUnavailable
import dev.opencamera.pipeline.retouch.WhiteBalanceTool
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal host for the retouch tools: pick a tool, brush/tap/slide, apply, undo/redo, compare
 * methods side by side, hold to see the original, save a new copy. Edits are kept as a
 * [dev.opencamera.pipeline.retouch.RetouchStack] and replayed on the full-resolution original
 * at export (background queue for MediaStore photos). The photo editor shell can host the same
 * tools through [RetouchToolbox]; this screen exists so they are usable before it lands.
 */
class RetouchActivity : AppCompatActivity() {
    private val toolbox by lazy { RetouchToolbox(this) }
    // One worker: the session is not thread-safe and native ops are already multi-threaded.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private lateinit var canvas: BrushCanvasView
    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var saveButton: Button

    private var source: Uri? = null
    private var displayName = "photo"
    private var rotation = 0
    private var exif: ExifInterface? = null
    private var session: RetouchSession? = null
    private var tool: RetouchTool? = null
    private var preview: Pair<RetouchParams, RetouchResult>? = null
    private var previewJob: Job? = null
    private val values = HashMap<String, Float>()
    private var strength = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        @Suppress("DEPRECATION")
        val uri = intent?.data ?: (intent?.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        if (uri == null) {
            Toast.makeText(this, R.string.ai_editor_no_image, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        source = uri
        intent.getStringExtra(EXTRA_BENCHMARK)?.let { runBenchmark(it); return }
        load(uri)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff000000.toInt())
            fitsSystemWindows = true
        }
        canvas = BrushCanvasView(this, ::onTap).apply { contentDescription = getString(R.string.retouch_title) }
        root.addView(canvas, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, label) in TOOL_LABELS) {
            tools.addView(Button(this).apply {
                setText(label)
                setOnClickListener { toolbox.tool(id)?.let(::selectTool) }
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tools) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        undoButton = button(R.string.retouch_undo) { history(undo = true) }
        redoButton = button(R.string.retouch_redo) { history(undo = false) }
        val original = Button(this).apply {
            setText(R.string.retouch_hold_original)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> session?.let { canvas.setImageBitmap(originalPreview) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        showCurrent()
                        v.performClick()
                    }
                }
                true
            }
        }
        saveButton = button(R.string.ai_editor_save) { save() }
        listOf(undoButton, redoButton, original, saveButton).forEach { actions.addView(it) }
        root.addView(HorizontalScrollView(this).apply { addView(actions) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        title = getString(R.string.retouch_title)
    }

    private var originalPreview: Bitmap? = null

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }

    private fun load(uri: Uri) {
        status.setText(R.string.ai_editor_loading)
        lifecycleScope.launch {
            val work = withContext(worker) {
                runCatching {
                    displayName = queryName(uri) ?: "photo"
                    exif = open()?.use { ExifInterface(it) }
                    rotation = exif?.rotationDegrees ?: 0
                    decodeUpright(PREVIEW_SIDE)
                }.onFailure { Log.e(TAG, "Cannot load $uri", it) }.getOrNull()
            }
            if (work == null) {
                Toast.makeText(this@RetouchActivity, R.string.ai_editor_load_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            originalPreview = work
            session = RetouchSession(work, toolbox)
            showCurrent()
            selectTool(toolbox.tools.first())
        }
    }

    private fun open() = contentResolver.openInputStream(source!!)

    /** Decodes with a power-of-two sample and rotates upright; long side at most [side] (0 = full). */
    private fun decodeUpright(side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        if (side > 0) while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val raw = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null
        val scaled = if (side > 0) RetouchBenchmark.scaledTo(raw, side).also { if (it !== raw) raw.recycle() } else raw
        if (rotation == 0) return scaled
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true).also { if (it !== scaled) scaled.recycle() }
            .let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
    }

    private fun showCurrent() {
        val shown = preview?.second?.bitmap ?: session?.current ?: return
        canvas.setImageBitmap(shown)
        undoButton.isEnabled = session?.stack?.canUndo == true
        redoButton.isEnabled = session?.stack?.canRedo == true
    }

    // --- tools ---

    private fun selectTool(t: RetouchTool) {
        discardPreview()
        tool = t
        values.clear()
        strength = t.defaultStrength
        canvas.clearStrokes()
        canvas.mode = t.input
        canvas.eraseBrush = false
        panel.removeAllViews()
        val reason = t.unavailableReason()
        status.text = reason?.let { getString(R.string.retouch_unavailable, it) } ?: getString(hintFor(t))
        if (reason != null) return
        when (t.id) {
            "erase" -> eraseControls(t)
            "blemish" -> slider(R.string.retouch_spot_size, 30) { values["radius"] = BlemishTool.DEFAULT_RADIUS * (0.4f + it * 2f) }
            "skin", "teeth", "eyes" -> {
                slider(R.string.retouch_strength, (t.defaultStrength * 100).toInt()) { strength = it; schedulePreview() }
                applyRow()
                schedulePreview()
            }
            "redeye" -> panel.addView(button(R.string.retouch_auto) { commit(t, RetouchParams()) })
            "whitebalance" -> {
                slider(R.string.retouch_strength, 100) { strength = it }
                row(button(R.string.retouch_compare) { compare(t, RetouchParams(strength = strength)) })
            }
            "geometry" -> geometryControls(t)
            "reshape" -> reshapeControls(t)
        }
    }

    private fun hintFor(t: RetouchTool) = when (t.input) {
        InputKind.TAP -> R.string.retouch_hint_tap
        InputKind.BRUSH -> R.string.retouch_hint_brush
        InputKind.NONE -> R.string.retouch_hint_slider
    }

    private fun eraseControls(t: RetouchTool) {
        slider(R.string.retouch_brush_size, 40) { canvas.brushRadius = 0.005f + it * 0.06f }
        canvas.brushRadius = 0.005f + 0.4f * 0.06f
        row(
            button(R.string.retouch_brush) { canvas.mode = InputKind.BRUSH; canvas.eraseBrush = false },
            button(R.string.retouch_brush_erase) { canvas.mode = InputKind.BRUSH; canvas.eraseBrush = true },
            button(R.string.retouch_select_tap) { canvas.mode = InputKind.TAP },
        )
        row(
            button(R.string.retouch_remove) { commit(t, eraseParams(null)) },
            button(R.string.retouch_compare) { compare(t, eraseParams(null)) },
            button(R.string.retouch_hq) { removeHq(t) },
        )
    }

    private val selectTaps = ArrayList<NPoint>()

    private fun eraseParams(method: String?) = RetouchParams(method = method, strokes = canvas.strokes.toList(), taps = selectTaps.toList())

    /** big-lama: asks before downloading (size, source); declining keeps MI-GAN. */
    private fun removeHq(t: RetouchTool) {
        if (toolbox.inpainter.lamaDownloaded()) {
            commit(t, eraseParams("lama"))
            return
        }
        val spec = toolbox.lamaSpec ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.retouch_lama_title)
            .setMessage(getString(R.string.retouch_lama_message, Formatter.formatShortFileSize(this, spec.sizeBytes), spec.url, spec.license))
            .setPositiveButton(R.string.retouch_download) { _, _ -> downloadLama(t) }
            .setNegativeButton(R.string.retouch_use_migan) { _, _ -> commit(t, eraseParams("migan")) }
            .show()
    }

    private fun downloadLama(t: RetouchTool) {
        val job = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    toolbox.downloadLama { bytes, total ->
                        runOnUiThread { status.text = getString(R.string.retouch_downloading, (100 * bytes / total.coerceAtLeast(1)).toInt()) }
                    }
                }.onFailure { Log.w(TAG, "big-lama download failed", it) }.isSuccess
            }
            if (ok) {
                commit(t, eraseParams("lama"))
            } else {
                status.setText(R.string.retouch_download_failed)
            }
        }
        status.text = getString(R.string.retouch_downloading, 0)
        canvas.setOnLongClickListener { job.cancel(); true }
    }

    private fun geometryControls(t: RetouchTool) {
        val lens = LensProfiles.forPhoto(this, exif, originalPreview?.width ?: 0, originalPreview?.height ?: 0)
        slider(R.string.retouch_angle, 50) { values["angle"] = (it - 0.5f) * 30f; schedulePreview() }
        slider(R.string.retouch_vertical, 50) { values["vertical"] = (it - 0.5f) * 2f; schedulePreview() }
        slider(R.string.retouch_horizontal, 50) { values["horizontal"] = (it - 0.5f) * 2f; schedulePreview() }
        if (lens != null) {
            row(button(R.string.retouch_lens_profile) { values.putAll(lens); schedulePreview() })
        } else {
            slider(R.string.retouch_lens_manual, 50) { values["k1"] = (it - 0.5f) * 0.4f; schedulePreview() }
        }
        row(
            button(R.string.retouch_auto) { compare(t, RetouchParams(values = values.toMap())) },
            button(R.string.retouch_apply) { commitPreviewOr(t) },
        )
    }

    private fun reshapeControls(t: RetouchTool) {
        panel.addView(TextView(this).apply {
            setText(R.string.retouch_reshape_notice)
            setTextColor(0xffffcc66.toInt())
            setPadding(16, 4, 16, 4)
        })
        canvas.mode = InputKind.NONE
        slider(R.string.retouch_slim_face, 0) { values["slim"] = it; schedulePreview() }
        slider(R.string.retouch_bigger_eyes, 0) { values["eyes"] = it; schedulePreview() }
        slider(R.string.retouch_slim_nose, 0) { values["nose"] = it; schedulePreview() }
        row(
            button(R.string.retouch_liquify_push) { liquify(ReshapeTool.MODE_PUSH) },
            button(R.string.retouch_liquify_bloat) { liquify(ReshapeTool.MODE_BLOAT) },
            button(R.string.retouch_liquify_pinch) { liquify(ReshapeTool.MODE_PINCH) },
            button(R.string.retouch_apply) { if (canvas.strokes.isNotEmpty()) commit(t, RetouchParams(strength = 0.5f, strokes = canvas.strokes.toList(), values = mapOf("mode" to values["mode"]!!))) else commitPreviewOr(t) },
        )
    }

    private fun liquify(mode: Int) {
        values.clear()
        values["mode"] = mode.toFloat()
        canvas.clearStrokes()
        canvas.mode = InputKind.BRUSH
        canvas.brushRadius = 0.06f
        status.setText(R.string.retouch_hint_liquify)
    }

    private fun slider(label: Int, initial: Int, onChange: (Float) -> Unit) {
        panel.addView(TextView(this).apply {
            setText(label)
            setTextColor(0xffcccccc.toInt())
            setPadding(24, 4, 24, 0)
        })
        panel.addView(SeekBar(this).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p / 100f)
                }

                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(24, 0, 24, 4) })
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { r.addView(it) }
        panel.addView(HorizontalScrollView(this).apply { addView(r) })
    }

    private fun applyRow() = row(button(R.string.retouch_apply) { tool?.let(::commitPreviewOr) })

    private fun onTap(p: NPoint) {
        val t = tool ?: return
        when (t.id) {
            "erase" -> {
                selectTaps += p
                status.text = getString(R.string.retouch_selected, selectTaps.size)
            }
            "blemish" -> commit(t, RetouchParams(taps = listOf(p), values = values.toMap()))
            "redeye" -> commit(t, RetouchParams(taps = listOf(p)))
            "whitebalance" -> commit(t, RetouchParams(method = WhiteBalanceTool.TAP, strength = strength, taps = listOf(p)))
        }
    }

    private fun currentParams(t: RetouchTool) = RetouchParams(strength = strength, values = values.toMap())

    /** Live preview of slider tools on a copy; debounced, newest wins. */
    private fun schedulePreview() {
        val t = tool ?: return
        val s = session ?: return
        val params = currentParams(t)
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val r = withContext(worker) {
                if (!isActive) return@withContext null
                val copy = s.current.copy(Bitmap.Config.ARGB_8888, true)
                val t0 = SystemClock.elapsedRealtime()
                runCatching { t.apply(copy, params) to SystemClock.elapsedRealtime() - t0 }
                    .onFailure { copy.recycle() }
            } ?: return@launch
            r.onSuccess { (result, ms) ->
                discardPreview()
                preview = params to result
                status.text = getString(R.string.retouch_preview_status, result.method, ms)
                Log.i(TAG, "Preview ${t.id} ${result.method} $ms ms")
                showCurrent()
            }.onFailure { status.text = reasonOf(it) }
        }
    }

    private fun discardPreview() {
        previewJob?.cancel()
        preview?.second?.bitmap?.let { if (it !== session?.current) it.recycle() }
        preview = null
    }

    private fun commitPreviewOr(t: RetouchTool) {
        val p = preview
        if (p != null && p.first == currentParams(t)) {
            preview = null
            lifecycleScope.launch {
                withContext(worker) { session?.accept(t, p.first, p.second) }
                afterEdit(t, p.second, null)
            }
        } else {
            commit(t, currentParams(t))
        }
    }

    private fun commit(t: RetouchTool, params: RetouchParams) {
        val s = session ?: return
        discardPreview()
        status.setText(R.string.ai_editor_working)
        lifecycleScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val r = withContext(worker) { runCatching { s.apply(t, params) } }
            val ms = SystemClock.elapsedRealtime() - t0
            r.onSuccess { afterEdit(t, it, ms) }.onFailure {
                Log.w(TAG, "${t.id} failed", it)
                status.text = reasonOf(it)
            }
        }
    }

    private fun afterEdit(t: RetouchTool, r: RetouchResult, ms: Long?) {
        Log.i(TAG, "Applied ${t.id} ${r.method} ${r.note.orEmpty()} ${ms ?: "-"} ms")
        status.text = getString(R.string.retouch_applied, r.method, r.note.orEmpty())
        canvas.clearStrokes()
        selectTaps.clear()
        showCurrent()
    }

    private fun reasonOf(e: Throwable) = when (e) {
        is RetouchUnavailable -> getString(R.string.retouch_unavailable, e.message)
        is OutOfMemoryError -> getString(R.string.retouch_unavailable, "out of memory")
        else -> getString(R.string.ai_editor_failed)
    }

    /** Runs every available method of [t] on copies and shows them in the comparison grid. */
    private fun compare(t: RetouchTool, base: RetouchParams) {
        val s = session ?: return
        val methods = t.methods().filter { it.available }.take(4)
        if (methods.size < 2) {
            commit(t, base)
            return
        }
        val strict = base.copy(values = base.values + ("strict" to 1f))
        val results = arrayOfNulls<RetouchResult>(methods.size)
        lateinit var grid: CompareGrid
        val job = lifecycleScope.launch {
            methods.forEachIndexed { i, m ->
                val params = strict.copy(method = m.id)
                val t0 = SystemClock.elapsedRealtime()
                val r = withContext(worker) {
                    if (!isActive) return@withContext null
                    val copy = s.current.copy(Bitmap.Config.ARGB_8888, true)
                    runCatching { t.apply(copy, params) }.onFailure { copy.recycle() }
                } ?: return@launch
                val ms = SystemClock.elapsedRealtime() - t0
                r.onSuccess {
                    results[i] = it
                    Log.i(TAG, "Compare ${t.id} ${m.id} $ms ms")
                    grid.setResult(i, it.bitmap, "$ms ms")
                }.onFailure {
                    Log.w(TAG, "Compare ${t.id} ${m.id} dropped: ${it.message}")
                    grid.setResult(i, null, reasonOf(it))
                }
            }
        }
        grid = CompareGrid(this, s.current, methods.map { it.id }, onPick = { i ->
            val r = results[i] ?: return@CompareGrid
            lifecycleScope.launch {
                withContext(worker) { session?.accept(t, r.resolved ?: strict.copy(method = methods[i].id), r) }
                results.forEachIndexed { k, other -> if (k != i) other?.bitmap?.recycle() }
                afterEdit(t, r, null)
            }
        }, onDismiss = { job.cancel() })
        grid.show()
    }

    private fun history(undo: Boolean) {
        val s = session ?: return
        discardPreview()
        lifecycleScope.launch {
            val ok = withContext(worker) { runCatching { if (undo) s.undo() else s.redo() }.getOrDefault(false) }
            if (!ok) status.setText(R.string.retouch_nothing)
            showCurrent()
        }
    }

    // --- export ---

    private fun save() {
        val s = session ?: return
        val uri = source ?: return
        if (!s.stack.canUndo) {
            status.setText(R.string.retouch_nothing)
            return
        }
        val ops = AiOps(keepSource = true, budgetMs = JOB_BUDGET_MS, retouch = s.stack.toJson())
        if (uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ProcessingService.enqueue(this, uri, null, 1f, WatermarkTemplate.NONE.key, ops)
            Toast.makeText(this, R.string.ai_editor_queued, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        saveButton.isEnabled = false
        status.setText(R.string.ai_editor_saving)
        lifecycleScope.launch {
            val note = withContext(worker) { runCatching { exportInline(ops) }.onFailure { Log.e(TAG, "Export failed", it) }.getOrNull() }
            saveButton.isEnabled = true
            if (note == null) {
                status.setText(R.string.ai_editor_failed)
                return@launch
            }
            Toast.makeText(this@RetouchActivity, getString(R.string.ai_editor_saved, note), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Same replay as the queue job, for sources outside MediaStore (shared files). */
    private fun exportInline(ops: AiOps): String {
        val full = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("Cannot decode")
        val report = ArrayList<String>()
        val stage = AiStage(this)
        val tmp = File(cacheDir, "retouch-export.jpg")
        try {
            StillProcessor(contentResolver).processBitmap(full, exif, null, WatermarkTemplate.NONE, Build.MODEL, tmp) { b, rot ->
                stage.apply(b, rot, ops, report)
            }
            if (stage.retouchTags.isNotEmpty()) {
                val out = ExifInterface(tmp)
                RetouchExif.write(out, stage.retouchTags, out.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "Open Camera")
                out.saveAttributes()
            }
            val uri = insert(tmp, "${displayName.substringBeforeLast('.')}_retouch")
            Log.i(TAG, "Exported $uri tags=${stage.retouchTags}: $report")
        } finally {
            tmp.delete()
        }
        return report.joinToString()
    }

    private fun insert(file: File, name: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("Insert failed")
        try {
            contentResolver.openOutputStream(out, "w")!!.use { os -> file.inputStream().use { it.copyTo(os) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            contentResolver.delete(out, null, null)
            throw e
        }
        return out
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    // --- benchmark (debug entry point: am start ... --es retouch_benchmark "<tools or all>") ---

    private fun runBenchmark(which: String) {
        status.setText(R.string.ai_editor_working)
        lifecycleScope.launch {
            withContext(worker) {
                runCatching {
                    val runs = intent.getIntExtra(EXTRA_RUNS, 10)
                    val include = if (which == "all") emptySet() else which.split(',').toSet()
                    displayName = queryName(source!!) ?: "photo"
                    exif = open()?.use { ExifInterface(it) }
                    rotation = exif?.rotationDegrees ?: 0
                    val bench = RetouchBenchmark(toolbox)
                    val preview = decodeUpright(PREVIEW_SIDE) ?: error("decode")
                    bench.run(preview, "$displayName preview", runs, include).forEach(::benchLog)
                    if (intent.getBooleanExtra(EXTRA_FULL, true)) {
                        val full = decodeUpright(0) ?: error("decode")
                        bench.run(full, "$displayName full", maxOf(1, runs / 3), include).forEach(::benchLog)
                        full.recycle()
                    }
                    val psnr = bench.replayCheck(preview, listOf(
                        "skin" to RetouchParams(strength = 0.35f),
                        "whitebalance" to RetouchParams(method = WhiteBalanceTool.GRAY_WORLD, strength = 0.8f),
                        "geometry" to RetouchParams(method = GeometryTool.LEVEL),
                        "erase" to RetouchParams(strokes = listOf(dev.opencamera.pipeline.retouch.Stroke(listOf(NPoint(0.3f, 0.3f), NPoint(0.35f, 0.32f)), 0.02f))),
                    ))
                    if (intent.getBooleanExtra(EXTRA_FAULTS, false)) faultCheck(preview)
                    if (intent.getBooleanExtra(EXTRA_EXPORT, false)) exportCheck(preview)
                    benchLog("$displayName replay PSNR=%.1f dB (criterion > 45)".format(java.util.Locale.US, psnr))
                }.onFailure { Log.e(BENCH_TAG, "Benchmark failed", it); benchLog("FAILED $it") }
            }
            benchLog("DONE")
            finish()
        }
    }

    /** Erase with MI-GAN out of memory and PatchMatch throwing: must end in Telea, no crash. */
    private fun faultCheck(preview: Bitmap) {
        val disc = dev.opencamera.pipeline.retouch.Stroke(listOf(NPoint(0.5f, 0.5f)), 0.06f)
        toolbox.inpainter.faultInjector = { m ->
            when (m) {
                dev.opencamera.pipeline.retouch.InpaintMethod.MIGAN -> throw OutOfMemoryError("injected")
                dev.opencamera.pipeline.retouch.InpaintMethod.PATCHMATCH -> throw IllegalStateException("injected")
                else -> Unit
            }
        }
        try {
            val copy = preview.copy(Bitmap.Config.ARGB_8888, true)
            val r = toolbox.tool("erase")!!.apply(copy, RetouchParams(strokes = listOf(disc)))
            benchLog("faults: erase ended with ${r.method} (${r.note})")
            copy.recycle()
            val lama = runCatching { toolbox.tool("erase")!!.apply(preview.copy(Bitmap.Config.ARGB_8888, true), RetouchParams(method = "lama", strokes = listOf(disc))) }
            benchLog("faults: lama request -> ${lama.map { "${it.method} (${it.note})" }.getOrElse { "error $it" }}, lama available=${toolbox.inpainter.lamaDownloaded()}")
        } finally {
            toolbox.inpainter.faultInjector = null
        }
    }

    /** Full export of a small edit stack; the source bytes must not change. */
    private fun exportCheck(preview: Bitmap) {
        val before = open()!!.use { dev.opencamera.ml.ModelRegistry.sha256(it) }
        val s = RetouchSession(preview, toolbox)
        for ((id, p) in listOf(
            "skin" to RetouchParams(strength = 0.35f),
            "reshape" to RetouchParams(values = mapOf("slim" to 0.5f)),
            "erase" to RetouchParams(strokes = listOf(dev.opencamera.pipeline.retouch.Stroke(listOf(NPoint(0.2f, 0.8f), NPoint(0.25f, 0.82f)), 0.02f))),
        )) runCatching { s.apply(toolbox.tool(id)!!, p) }.onFailure { benchLog("export: $id skipped ${it.message}") }
        val t0 = SystemClock.elapsedRealtime()
        val note = exportInline(AiOps(keepSource = true, retouch = s.stack.toJson()))
        val after = open()!!.use { dev.opencamera.ml.ModelRegistry.sha256(it) }
        benchLog("export: ${SystemClock.elapsedRealtime() - t0} ms [$note] source unchanged=${before == after}")
    }

    /** Benchmark lines also go to files/bench/results.txt: logcat on a shared phone overflows. */
    private fun benchLog(line: String) {
        Log.i(BENCH_TAG, line)
        runCatching { File(filesDir, "bench").apply { mkdirs() }.resolve("results.txt").appendText("$line\n") }
    }

    private companion object {
        const val TAG = "RetouchActivity"
        const val BENCH_TAG = "RetouchBench"
        const val EXTRA_BENCHMARK = "retouch_benchmark"
        const val EXTRA_RUNS = "runs"
        const val EXTRA_FULL = "full"
        const val EXTRA_FAULTS = "faults"
        const val EXTRA_EXPORT = "export"
        const val PREVIEW_SIDE = 2048
        const val PREVIEW_DEBOUNCE_MS = 80L
        const val JOB_BUDGET_MS = 180_000L

        val TOOL_LABELS = listOf(
            "erase" to R.string.retouch_tool_erase,
            "blemish" to R.string.retouch_tool_blemish,
            "skin" to R.string.retouch_tool_skin,
            "redeye" to R.string.retouch_tool_redeye,
            "teeth" to R.string.retouch_tool_teeth,
            "eyes" to R.string.retouch_tool_eyes,
            "whitebalance" to R.string.retouch_tool_wb,
            "geometry" to R.string.retouch_tool_geometry,
            "reshape" to R.string.retouch_tool_reshape,
        )
    }
}
