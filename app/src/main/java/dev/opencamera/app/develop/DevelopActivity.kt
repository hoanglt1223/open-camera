/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import dev.opencamera.app.R
import dev.opencamera.app.ai.AiEditActivity
import dev.opencamera.app.develop.auto.Histogram
import dev.opencamera.app.develop.export.XmpWriter
import dev.opencamera.app.develop.io.SourceLoader
import dev.opencamera.app.develop.io.UnsupportedImageException
import dev.opencamera.app.develop.mask.AiMaskProvider
import dev.opencamera.app.develop.model.EditHistory
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.EditStore
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.preset.PresetRepository
import dev.opencamera.app.develop.render.Geometry
import dev.opencamera.app.develop.render.Mat3
import dev.opencamera.app.develop.ui.DevelopTheme
import dev.opencamera.app.develop.ui.HistogramView
import dev.opencamera.app.develop.ui.ParamSlider
import dev.opencamera.app.develop.ui.PreviewOverlay
import dev.opencamera.app.develop.ui.dp
import dev.opencamera.app.processing.LooksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.min

/**
 * The develop editor: global adjustments, curves, colour mixer, grading, detail, effects, crop,
 * local masks, presets, RAW, export. Opened from the share sheet / "Edit" action. Nothing is
 * written to the original unless the user picks "Overwrite original"; edits live in
 * [EditStore] and in the XMP of exports. Every failure ends in a message, never a crash.
 */
class DevelopActivity : AppCompatActivity(), DevelopEngine.Listener {
    enum class Mode { NONE, CROP, BRUSH, LINEAR, RADIAL, PICK_COLOR, PICK_HSL }

    internal val theme = DevelopTheme.current
    internal lateinit var engine: DevelopEngine
    internal val loader by lazy { SourceLoader(this) }
    internal val store by lazy { EditStore(File(filesDir, "edits")) }
    internal val looks by lazy { LooksRepository.get(this) }
    internal val presets by lazy { PresetRepository(File(filesDir, "develop-presets")) { looks.descriptors } }
    internal val ai by lazy { AiMaskProvider(this) }

    internal var info: SourceLoader.Info? = null
    internal var history = EditHistory(EditStack())
    internal val stack: EditStack get() = history.current
    internal var mode = Mode.NONE
    internal var selectedMask = -1
    internal var lastStats: DevelopEngine.RenderStats? = null
    private var loadedLook: String? = null

    internal lateinit var previewFrame: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var cpuView: ImageView
    internal lateinit var overlay: PreviewOverlay
    private lateinit var histogramView: HistogramView
    internal lateinit var status: TextView
    internal lateinit var panel: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    internal val sliders = HashMap<String, ParamSlider>()
    internal var refreshPanel: () -> Unit = {}
    internal var currentTab = 0

    private var surface: Surface? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    internal var compare = false
    internal var split = 0.5f
    private var warn = false
    private var holding = false
    internal var compareStack: EditStack? = null
    private var saveJob: Job? = null
    private var openedAt = 0L
    private var firstFrame = true

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val uri = pendingUri
        if (granted && uri != null) open(uri) else fail(getString(R.string.develop_no_access))
    }
    private var pendingUri: Uri? = null

    internal val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImport(it) } }
    internal var onImport: (Uri) -> Unit = {}
    internal val pickPhotos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onPickPhotos(uris) }
    internal var onPickPhotos: (List<Uri>) -> Unit = {}
    internal val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { onCreateDocument(it) } }
    internal var onCreateDocument: (Uri) -> Unit = {}
    internal val writeRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> onWriteGranted(r.resultCode == RESULT_OK) }
    internal var onWriteGranted: (Boolean) -> Unit = {}
    internal val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { onLocationResult() }
    internal var onLocationResult: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_EDITOR_V2, true)) {
            // Rollback switch: hand the photo to the previous editor.
            startActivity(Intent(intent).setClass(this, AiEditActivity::class.java))
            finish()
            return
        }
        engine = DevelopEngine(this)
        engine.prewarm()
        buildUi()
        val uris = when (intent?.action) {
            Intent.ACTION_SEND_MULTIPLE -> intentStreams()
            Intent.ACTION_SEND -> listOfNotNull(streamExtra())
            else -> listOfNotNull(intent?.data ?: streamExtra())
        }
        when {
            uris.isEmpty() -> { Toast.makeText(this, R.string.develop_no_image, Toast.LENGTH_LONG).show(); finish() }
            uris.size > 1 -> { open(uris.first()); pendingBatch = uris.drop(1) }
            else -> open(uris.first())
        }
    }

    internal var pendingBatch: List<Uri> = emptyList()
    internal var bench: DevelopBenchmark? = null

    @Suppress("DEPRECATION")
    private fun streamExtra(): Uri? = intent?.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun intentStreams(): List<Uri> = intent?.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }

    // ---- UI scaffold -------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(iconButton(android.R.drawable.ic_menu_revert, R.string.develop_back) { persist(); finish() })
        undoButton = iconButton(android.R.drawable.ic_menu_rotate, R.string.develop_undo) { history.undo()?.let { onStackChanged(true) } }.apply { scaleX = -1f }
        redoButton = iconButton(android.R.drawable.ic_menu_rotate, R.string.develop_redo) { history.redo()?.let { onStackChanged(true) } }
        bar.addView(undoButton); bar.addView(redoButton)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton(android.R.drawable.ic_menu_view, R.string.develop_compare) { toggleCompare() })
        bar.addView(iconButton(android.R.drawable.ic_menu_sort_by_size, R.string.develop_histogram) {
            histogramView.visibility = if (histogramView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        bar.addView(iconButton(android.R.drawable.ic_dialog_alert, R.string.develop_clipping) { warn = !warn; attachView() })
        bar.addView(iconButton(android.R.drawable.ic_menu_more, R.string.develop_more) { showMoreMenu(it) })
        bar.addView(Button(this).apply {
            setText(R.string.develop_export)
            setTextColor(theme.onAccent)
            setBackgroundColor(theme.accent)
            minimumHeight = dp(48f).toInt()
            setOnClickListener { showExportDialog() }
        })
        root.addView(HorizontalScrollView(this).apply { addView(bar); isFillViewport = true }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        previewFrame = FrameLayout(this)
        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(st); attachView()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = attachView()
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    engine.attach(null); surface?.release(); surface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        cpuView = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX; visibility = View.GONE }
        overlay = PreviewOverlay(this)
        histogramView = HistogramView(this).apply { visibility = View.GONE }
        previewFrame.addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        previewFrame.addView(cpuView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        previewFrame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        previewFrame.addView(histogramView, FrameLayout.LayoutParams(dp(160f).toInt(), dp(80f).toInt(), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(8f).toInt(), dp(8f).toInt(), 0)
        })
        previewFrame.setOnTouchListener { _, e -> onPreviewTouch(e) }
        previewFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> attachView() }
        root.addView(previewFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            setTextColor(theme.onSurfaceMuted)
            gravity = Gravity.CENTER
            setPadding(dp(8f).toInt(), dp(2f).toInt(), dp(8f).toInt(), dp(2f).toInt())
            setText(R.string.develop_loading)
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(tabs); setBackgroundColor(theme.surface) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }
        val scroll = ScrollView(this).apply { addView(panel); setBackgroundColor(theme.surface) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.36f).toInt()))
        setContentView(root)
        buildTabs()
        updateUndo()
    }

    internal fun iconButton(icon: Int, desc: Int, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(theme.onSurface)
        setBackgroundColor(0)
        contentDescription = getString(desc)
        minimumWidth = dp(48f).toInt(); minimumHeight = dp(48f).toInt()
        setOnClickListener(onClick)
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        DevelopPanels.TABS.forEachIndexed { i, (label, _) ->
            tabs.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                setText(label)
                setTextColor(if (i == currentTab) theme.accent else theme.onSurface)
                minimumHeight = dp(48f).toInt()
                setOnClickListener { selectTab(i) }
            })
        }
    }

    internal fun selectTab(i: Int) {
        val wasCrop = mode == Mode.CROP
        currentTab = i
        buildTabs()
        sliders.clear()
        panel.removeAllViews()
        switchMode(Mode.NONE)
        DevelopPanels.TABS[i].second(DevelopPanels(this))
        if (wasCrop || mode == Mode.CROP) render()
    }

    // ---- loading -----------------------------------------------------------------------

    internal fun open(uri: Uri) {
        openedAt = SystemClock.elapsedRealtime()
        firstFrame = true
        status.setText(R.string.develop_loading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val t0 = SystemClock.elapsedRealtime()
                    // Decode the proxy while the file is hashed.
                    val quick = loader.info(uri, hash = false)
                    val proxyJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { runCatching { loader.loadProxy(quick, loader.proxyLongSide()) } }
                    var i = loader.withHash(quick)
                    val t1 = SystemClock.elapsedRealtime()
                    var embedded: EditStack? = null
                    // An export of this app carries its stack; reopen the original when it still exists.
                    if (i.kind == SourceLoader.Kind.RASTER && (i.mime == "image/jpeg" || i.displayName.endsWith(".jpg", true))) {
                        embedded = contentResolver.openInputStream(uri)?.use { s -> XmpWriter.readStack(s.readBytes()) }
                        val src = embedded?.source
                        if (src != null && src.mediaId >= 0 && src.hash != i.ref.hash) {
                            val orig = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, src.mediaId)
                            runCatching { loader.info(orig) }.getOrNull()?.takeIf { it.ref.hash == src.hash }?.let { i = it } ?: run { embedded = null }
                        } else embedded = null
                    }
                    val saved = store.load(i.ref)
                    val t2 = SystemClock.elapsedRealtime()
                    val proxy = if (i.uri == quick.uri) proxyJob.await().getOrThrow() else { proxyJob.cancel(); loader.loadProxy(i, loader.proxyLongSide()) }
                    Log.i(TAG, "Open: info ${t1 - t0} ms, xmp+store ${t2 - t1} ms, proxy ${SystemClock.elapsedRealtime() - t2} ms")
                    Loaded(i, saved?.stack ?: embedded?.copy(source = i.ref) ?: EditStack(source = i.ref), saved?.snapshots.orEmpty(), proxy, embedded != null)
                }
            }
            result.onSuccess { l ->
                info = l.info
                history = EditHistory(l.stack)
                history.snapshots.addAll(l.snapshots)
                engine.wide = l.info.wide
                engine.aiMasks = { shape -> ai.load(shape, store.assetDir(l.info.ref)) }
                updateUndo()
                if (l.reopened) toast(getString(R.string.develop_reopened))
                if (l.info.hasGainmap) toast(getString(R.string.develop_ultra_hdr))
                ensureLook {
                    engine.open(applyTools(l.proxy, l.info)) {
                        Log.i(TAG, "engine ready at ${SystemClock.elapsedRealtime() - openedAt} ms")
                        Log.i(TAG, "Proxy ${l.proxy.width}x${l.proxy.height} ${l.info.kind} decoded in ${SystemClock.elapsedRealtime() - openedAt} ms")
                        selectTab(currentTab)
                        Log.i(TAG, "panel at ${SystemClock.elapsedRealtime() - openedAt} ms")
                        // First frame at screen resolution, the full proxy right after.
                        ensureLook { engine.renderProgressive(displayStack()) }
                        if (intent?.getBooleanExtra(EXTRA_BENCHMARK, false) == true) bench = DevelopBenchmark(this@DevelopActivity).also { it.start() }
                    }
                }
                if (pendingBatch.isNotEmpty()) DevelopDialogs(this@DevelopActivity).batch(pendingBatch).also { pendingBatch = emptyList() }
            }.onFailure { e ->
                Log.w(TAG, "Cannot open $uri", e)
                when {
                    e is SecurityException && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@DevelopActivity, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED -> {
                        pendingUri = uri
                        permission.launch(Manifest.permission.READ_MEDIA_IMAGES)
                    }
                    e is SecurityException -> fail(getString(R.string.develop_no_access))
                    e is UnsupportedImageException -> fail(getString(R.string.develop_unsupported, e.message))
                    e is FileNotFoundException -> fail(getString(R.string.develop_load_failed, e.message))
                    e is OutOfMemoryError -> fail(getString(R.string.develop_load_failed, "out of memory"))
                    else -> fail(getString(R.string.develop_load_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private class Loaded(val info: SourceLoader.Info, val stack: EditStack, val snapshots: List<EditHistory.Snapshot>, val proxy: Bitmap, val reopened: Boolean)

    /** Retouch steps are raster tools on the proxy; rerun when the tool list changes. */
    private var toolKey: Any? = null
    private var baseProxy: Bitmap? = null

    private fun applyTools(proxy: Bitmap, i: SourceLoader.Info): Bitmap {
        baseProxy = proxy
        toolKey = stack.tools
        if (stack.tools.isEmpty()) return proxy
        val skipped = ArrayList<String>()
        val out = dev.opencamera.app.develop.tools.ToolRegistry.applyAll(
            proxy.copy(proxy.config ?: Bitmap.Config.ARGB_8888, true), stack.tools,
            proxy.width / max(1f, i.width.toFloat()), store.assetDir(i.ref), skipped,
        )
        if (skipped.isNotEmpty()) toast(getString(R.string.develop_tools_skipped, skipped.distinct().joinToString()))
        return out
    }

    private fun fail(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (info == null) finish()
    }

    internal fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Loads the look's LUT off the main thread when the look changes, then runs [then]. */
    private fun ensureLook(then: () -> Unit) {
        val id = stack.lookId
        if (id == loadedLook) { then(); return }
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { looks.params(id, 1f) }.getOrNull() }
            loadedLook = id
            engine.look = p
            then()
        }
    }

    // ---- editing -----------------------------------------------------------------------

    /** Records a change and renders. Consecutive changes with the same [coalesce] key are one step. */
    internal fun change(next: EditStack, label: String, coalesce: String? = null) {
        history.push(next, label, coalesce)
        dragging = coalesce != null
        onStackChanged(false)
    }

    /** True while a slider/brush drag is in progress: previews render at screen resolution. */
    private var dragging = false

    internal fun seal() {
        history.seal()
        if (dragging) {
            dragging = false
            render()
        }
        updateUndo()
        schedulePersist()
    }

    internal fun onStackChanged(fromHistory: Boolean) {
        updateUndo()
        if (fromHistory) {
            refreshPanel()
            schedulePersist()
        }
        val i = info
        val base = baseProxy
        if (i != null && base != null && stack.tools != toolKey) {
            engine.open(applyTools(base, i)) { render() }
            return
        }
        render()
    }

    internal fun render() {
        if (info == null) return
        val draft = dragging
        ensureLook { engine.render(displayStack(), draft) }
    }

    /** In crop mode the whole straightened frame is shown with the crop as an overlay. */
    internal fun displayStack(): EditStack = if (mode == Mode.CROP) {
        stack.with(Params.CROP_LEFT, 0f).with(Params.CROP_TOP, 0f).with(Params.CROP_RIGHT, 1f).with(Params.CROP_BOTTOM, 1f)
    } else stack

    private fun updateUndo() {
        if (!::undoButton.isInitialized) return
        undoButton.isEnabled = history.canUndo; undoButton.alpha = if (history.canUndo) 1f else 0.4f
        redoButton.isEnabled = history.canRedo; redoButton.alpha = if (history.canRedo) 1f else 0.4f
    }

    private fun schedulePersist() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(800)
            persist()
        }
    }

    internal fun persist() {
        val i = info ?: return
        val s = stack
        val snaps = history.snapshots.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { if (s.isIdentity && snaps.isEmpty()) store.delete(i.ref) else store.save(i.ref, s, snaps) }
                .onFailure { Log.w(TAG, "Saving edit stack failed", it) }
        }
    }

    internal fun switchMode(m: Mode) {
        mode = m
        overlay.hint = when (m) {
            Mode.BRUSH -> getString(R.string.develop_mask_paint_hint)
            Mode.LINEAR, Mode.RADIAL -> getString(R.string.develop_mask_drag_hint)
            Mode.PICK_COLOR, Mode.PICK_HSL -> getString(R.string.develop_mask_tap_hint)
            else -> null
        }
        updateOverlay()
    }

    // ---- preview -----------------------------------------------------------------------

    override fun onRendered(stats: DevelopEngine.RenderStats, cpuFrame: Bitmap?) {
        bench?.onRendered(stats)
        val sizeChanged = lastStats?.let { it.outWidth != stats.outWidth || it.outHeight != stats.outHeight } ?: true
        lastStats = stats
        if (cpuFrame != null) {
            texture.visibility = View.GONE
            cpuView.visibility = View.VISIBLE
            cpuView.setImageBitmap(cpuFrame)
        }
        if (sizeChanged || cpuFrame != null) attachView()
        if (firstFrame) {
            firstFrame = false
            Log.i(TAG, "Open to first frame ${SystemClock.elapsedRealtime() - openedAt} ms (${stats.outWidth}x${stats.outHeight}, gpu=${stats.gpu})")
        }
        status.text = getString(R.string.develop_render_stats, stats.outWidth, stats.outHeight, stats.ms.toInt(), if (stats.gpu) "GPU" else "CPU")
        updateOverlay()
    }

    override fun onHistogram(h: Histogram) {
        histogramView.histogram = h
    }

    override fun onGpuUnavailable(reason: String) {
        toast(getString(R.string.develop_gpu_fallback, reason))
    }

    /** Image rectangle inside the preview for the current output aspect, zoom and pan. */
    internal fun imageRect(): android.graphics.RectF {
        val s = lastStats
        val vw = previewFrame.width.toFloat().coerceAtLeast(1f)
        val vh = previewFrame.height.toFloat().coerceAtLeast(1f)
        val iw = (s?.outWidth ?: 1).toFloat()
        val ih = (s?.outHeight ?: 1).toFloat()
        val fit = min(vw / iw, vh / ih) * 0.96f * zoom
        val w = iw * fit; val h = ih * fit
        val l = (vw - w) / 2 + panX; val t = (vh - h) / 2 + panY
        return android.graphics.RectF(l, t, l + w, t + h)
    }

    internal fun attachView() {
        if (!::engine.isInitialized) return
        val r = imageRect()
        overlay.image.set(r)
        val vw = previewFrame.width.toFloat().coerceAtLeast(1f)
        val vh = previewFrame.height.toFloat().coerceAtLeast(1f)
        if (cpuView.visibility == View.VISIBLE) {
            val d = cpuView.drawable
            if (d != null) cpuView.imageMatrix = Matrix().apply {
                setRectToRect(android.graphics.RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()), r, Matrix.ScaleToFit.FILL)
            }
        }
        val s = surface ?: return
        val viewToImage = Mat3.scale(1f / r.width(), 1f / r.height()) * Mat3.translate(-r.left, -r.top) * Mat3.scale(vw, vh)
        val sp = if (holding) 2f else if (compare) split else -1f
        engine.attach(DevelopEngine.View(s, previewFrame.width, previewFrame.height, viewToImage, sp, warn, theme.background))
        overlay.split = if (compare && !holding) split else -1f
        overlay.invalidate()
    }

    internal fun toggleCompare() {
        compare = !compare
        if (compare) engine.renderBefore(beforeStack()) else engine.clearBefore()
        attachView()
    }

    /** Original (or the chosen snapshot) with the current framing. */
    internal fun beforeStack(): EditStack = (compareStack ?: EditStack()).pasteFrom(displayStack(), setOf(Group.GEOMETRY))

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 8f)
                if (zoom == 1f) { panX = 0f; panY = 0f }
                attachView()
                return true
            }
        })
    }
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var multi = false
    internal val gesture = DevelopGestures(this)

    @SuppressLint("ClickableViewAccessibility")
    private fun onPreviewTouch(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false; lastX = e.x; lastY = e.y; downAt = SystemClock.elapsedRealtime()
                if (compare && kotlin.math.abs(e.x - (overlay.image.left + split * overlay.image.width())) < dp(32f)) {
                    gesture.draggingSplit = true
                } else gesture.down(e.x, e.y)
                previewFrame.postDelayed(holdCheck, HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; gesture.cancel(); endHold() }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.hypot(e.x - lastX, e.y - lastY) > dp(8f)) previewFrame.removeCallbacks(holdCheck)
                when {
                    multi && e.pointerCount >= 2 && zoom > 1f -> { panX += e.x - lastX; panY += e.y - lastY; attachView() }
                    gesture.draggingSplit -> {
                        split = ((e.x - overlay.image.left) / overlay.image.width()).coerceIn(0f, 1f); attachView()
                    }
                    !multi -> gesture.move(e.x, e.y)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                previewFrame.removeCallbacks(holdCheck)
                if (!multi && !gesture.draggingSplit) gesture.up(e.x, e.y, SystemClock.elapsedRealtime() - downAt < 250)
                gesture.draggingSplit = false
                endHold()
                previewFrame.performClick()
            }
        }
        return true
    }

    /** Press and hold (without a tool gesture) shows the original. */
    private val holdCheck = Runnable {
        if (gesture.active || multi) return@Runnable
        holding = true
        overlay.hint = getString(R.string.develop_hold_original)
        engine.renderBefore(EditStack().pasteFrom(displayStack(), setOf(Group.GEOMETRY)))
        attachView()
    }

    private fun endHold() {
        if (!holding) return
        holding = false
        switchMode(mode)
        if (compare) engine.renderBefore(beforeStack()) else engine.clearBefore()
        attachView()
    }

    internal fun updateOverlay() = gesture.updateOverlay()

    /** Maps a view point to normalized output coordinates of the displayed image. */
    internal fun viewToOutput(x: Float, y: Float): FloatArray {
        val r = overlay.image
        return floatArrayOf((x - r.left) / r.width(), (y - r.top) / r.height())
    }

    /** Maps a view point to normalized upright-source coordinates. */
    internal fun viewToSource(x: Float, y: Float): FloatArray {
        val o = viewToOutput(x, y)
        val f = Geometry.frame(displayStack(), max(1, engine.proxyWidth), max(1, engine.proxyHeight))
        val out = FloatArray(2)
        f.outputToSource.map(o[0], o[1], out)
        return out
    }

    internal fun sourceToView(u: Float, v: Float): FloatArray {
        val f = Geometry.frame(displayStack(), max(1, engine.proxyWidth), max(1, engine.proxyHeight))
        val out = FloatArray(2)
        f.outputToSource.inverse().map(u, v, out)
        val r = overlay.image
        return floatArrayOf(r.left + out[0] * r.width(), r.top + out[1] * r.height())
    }

    /** Proxy pixel under a view point (ARGB), for colour picking. */
    internal fun sampleAt(x: Float, y: Float): Int? {
        val px = engine.proxyPixels() ?: return null
        val s = viewToSource(x, y)
        if (s[0] !in 0f..1f || s[1] !in 0f..1f) return null
        val w = engine.proxyWidth; val h = engine.proxyHeight
        return px[(s[1] * (h - 1)).toInt() * w + (s[0] * (w - 1)).toInt()]
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val focused = currentFocus
            val target = sliders.values.firstOrNull { it.slider === focused || it.hasFocus() }
            if (target != null) {
                target.nudge(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) seal()
        return super.onKeyUp(keyCode, event)
    }

    private fun showMoreMenu(anchor: View) = DevelopDialogs(this).moreMenu(anchor)

    private fun showExportDialog() = DevelopDialogs(this).export()

    companion object {
        private const val TAG = "DevelopActivity"
        const val PREF_EDITOR_V2 = "editor_v2"
        /** Debug/measurement: runs [DevelopBenchmark] after opening (adb `--ez develop_benchmark true`). */
        const val EXTRA_BENCHMARK = "develop_benchmark"
        private const val HOLD_MS = 450L
    }
}
