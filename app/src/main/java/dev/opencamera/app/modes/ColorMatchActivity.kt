/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import dev.opencamera.app.R
import dev.opencamera.app.processing.BurstJobQueue
import dev.opencamera.app.processing.LookPreferences
import dev.opencamera.app.processing.LooksRepository
import dev.opencamera.pipeline.StillProcessor
import dev.opencamera.pipeline.creative.ColorSamples
import dev.opencamera.pipeline.creative.ColorTransfer
import dev.opencamera.pipeline.creative.ColorTransferMethod
import dev.opencamera.pipeline.looks.CubeParseException
import dev.opencamera.pipeline.looks.CubeParser
import dev.opencamera.pipeline.looks.CubeWriter
import dev.opencamera.pipeline.looks.LookMath
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.Lut3d
import dev.opencamera.pipeline.looks.NativeLookRenderer
import dev.opencamera.pipeline.looks.ToneTable
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Colour match: learns the colour of a reference photo, previews it on one of the user's
 * photos (hold to compare), saves it as a look for the camera, applies it to gallery photos,
 * and exports/imports `.cube` LUTs. All work runs on downscaled images; failures show a
 * message and leave everything as it was.
 */
class ColorMatchActivity : AppCompatActivity() {
    private val repository by lazy { LooksRepository.get(this) }

    private val referenceImage by lazy { findViewById<ImageView>(R.id.referenceImage) }
    private val sourceImage by lazy { findViewById<ImageView>(R.id.sourceImage) }
    private val previewImage by lazy { findViewById<ImageView>(R.id.previewImage) }
    private val methodGroup by lazy { findViewById<RadioGroup>(R.id.methodGroup) }
    private val skinSwitch by lazy { findViewById<MaterialSwitch>(R.id.skinSwitch) }
    private val intensitySeek by lazy { findViewById<SeekBar>(R.id.intensitySeek) }
    private val intensityLabel by lazy { findViewById<TextView>(R.id.intensityLabel) }
    private val lookName by lazy { findViewById<EditText>(R.id.lookName) }
    private val statusText by lazy { findViewById<TextView>(R.id.statusText) }

    private var reference: Bitmap? = null
    private var source: Bitmap? = null
    private var preview: Bitmap? = null
    private var lut: Lut3d? = null
    private var buildJob: Job? = null

    private val intensity get() = intensitySeek.progress / 100f

    private val pickReference = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadReference(it) }
    }
    private val pickSource = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadSource(it) }
    }
    private val pickBatch = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)) { uris ->
        if (uris.isNotEmpty()) applyToPhotos(uris)
    }
    private val exportCube = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { export(it) }
    }
    private val importCube = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { import(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_color_match)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.colorMatchRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val imagesOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        findViewById<Button>(R.id.pickReference).setOnClickListener { pickReference.launch(imagesOnly) }
        findViewById<Button>(R.id.pickSource).setOnClickListener { pickSource.launch(imagesOnly) }
        findViewById<Button>(R.id.saveLook).setOnClickListener { saveLook() }
        findViewById<Button>(R.id.applyPhotos).setOnClickListener {
            if (requireLut()) pickBatch.launch(imagesOnly)
        }
        findViewById<Button>(R.id.exportCube).setOnClickListener {
            if (requireLut()) exportCube.launch("${fileName()}.cube")
        }
        findViewById<Button>(R.id.importCube).setOnClickListener { importCube.launch(arrayOf("*/*")) }
        methodGroup.setOnCheckedChangeListener { _, _ -> rebuild() }
        skinSwitch.setOnCheckedChangeListener { _, _ -> rebuild() }
        intensitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                intensityLabel.text = getString(R.string.color_match_strength_value, progress)
                renderPreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        intensityLabel.text = getString(R.string.color_match_strength_value, intensitySeek.progress)
        // Hold the preview to see the photo before the change.
        previewImage.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> source?.let { previewImage.setImageBitmap(it) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> previewImage.setImageBitmap(preview ?: source)
            }
            true
        }

        sharedImage(intent)?.let { loadReference(it) }
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun loadReference(uri: Uri) = lifecycleScope.launch {
        val bmp = withContext(Dispatchers.IO) { decode(this@ColorMatchActivity, uri, PREVIEW_SIZE) }
        if (bmp == null) return@launch toast(R.string.color_match_cannot_open)
        reference = bmp
        referenceImage.setImageBitmap(bmp)
        if (lookName.text.isNullOrBlank()) lookName.setText(getString(R.string.color_match_default_name))
        rebuild()
    }

    private fun loadSource(uri: Uri) = lifecycleScope.launch {
        val bmp = withContext(Dispatchers.IO) { decode(this@ColorMatchActivity, uri, PREVIEW_SIZE) }
        if (bmp == null) return@launch toast(R.string.color_match_cannot_open)
        source = bmp
        sourceImage.setImageBitmap(bmp)
        rebuild()
    }

    private fun method() = when (methodGroup.checkedRadioButtonId) {
        R.id.methodReinhard -> ColorTransferMethod.REINHARD
        R.id.methodHistogram -> ColorTransferMethod.HISTOGRAM
        else -> ColorTransferMethod.SLICED_OT
    }

    /** Learns the change from the user's photo (source) towards the reference. */
    private fun rebuild() {
        val ref = reference ?: return
        val src = source
        if (src == null) {
            statusText.setText(R.string.color_match_need_source)
            return
        }
        val method = method()
        val skin = if (skinSwitch.isChecked) SKIN_PROTECTION else 0f
        buildJob?.cancel()
        statusText.setText(R.string.color_match_working)
        buildJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val t0 = System.currentTimeMillis()
                    val r = ColorTransfer.build(samples(src), samples(ref), method, skin)
                    r to System.currentTimeMillis() - t0
                }.onFailure { Log.e(TAG, "Colour match failed", it) }.getOrNull()
            }
            if (result == null) {
                statusText.setText(R.string.color_match_failed)
                return@launch
            }
            val (r, ms) = result
            lut = r.lut
            statusText.text = getString(R.string.color_match_done, methodLabel(r.method), ms)
            renderPreview()
        }
    }

    private fun methodLabel(m: ColorTransferMethod) = getString(
        when (m) {
            ColorTransferMethod.REINHARD -> R.string.color_match_method_fast
            ColorTransferMethod.HISTOGRAM -> R.string.color_match_method_histogram
            ColorTransferMethod.SLICED_OT -> R.string.color_match_method_best
        }
    )

    private fun renderPreview() {
        val src = source ?: return
        val l = lut ?: return
        val k = intensity
        lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching { render(src, l, k) }.onFailure { Log.e(TAG, "Preview failed", it) }.getOrNull()
            }
            preview = out
            previewImage.setImageBitmap(out ?: src)
        }
    }

    private fun lookParams(l: Lut3d, k: Float) =
        LookParams(l, ToneTable.build(listOf(0f to 0f, 1f to 1f), 0f), k, 0f, 1f, 0f, 0.5f)

    private fun render(src: Bitmap, l: Lut3d, k: Float): Bitmap {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true)
        val params = lookParams(l, k)
        try {
            NativeLookRenderer.apply(copy, params)
        } catch (e: Throwable) {
            // Same maths in Kotlin when the native renderer is unavailable.
            Log.w(TAG, "Native renderer failed, using Kotlin", e)
            val px = IntArray(copy.width * copy.height)
            copy.getPixels(px, 0, copy.width, 0, 0, copy.width, copy.height)
            LookMath.applyArgb(params, px, copy.width, copy.height)
            copy.setPixels(px, 0, copy.width, 0, 0, copy.width, copy.height)
        }
        return copy
    }

    private fun requireLut(): Boolean {
        if (lut != null) return true
        toast(R.string.color_match_need_look)
        return false
    }

    private fun fileName() = (lookName.text?.toString()?.trim().orEmpty().ifEmpty { "color-match" })
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")

    private fun bakedLut() = ColorTransfer.withIntensity(checkNotNull(lut), intensity)

    private fun saveLook() {
        if (!requireLut()) return
        val name = lookName.text?.toString().orEmpty()
        val baked = bakedLut()
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                runCatching {
                    repository.userLooks.save(name, getString(R.string.color_match_look_description), baked).also {
                        repository.reloadUserLooks()
                    }
                }.onFailure { Log.e(TAG, "Cannot save look", it) }.getOrNull()
            }
            if (d == null) return@launch toast(R.string.color_match_save_failed)
            LookPreferences(this@ColorMatchActivity).lookId = d.id
            toast(R.string.color_match_saved)
        }
    }

    private fun export(uri: Uri) {
        val lut = bakedLut()
        val name = lookName.text?.toString().orEmpty().ifEmpty { "Colour match" }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = CubeWriter.write(lut, name)
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("No stream")
                }.onFailure { Log.e(TAG, "Export failed", it) }.isSuccess
            }
            toast(if (ok) R.string.color_match_exported else R.string.color_match_export_failed)
        }
    }

    private fun import(uri: Uri) = lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                require(size in 1..MAX_CUBE_BYTES) { "File too large" }
                val text = contentResolver.openInputStream(uri)?.use { it.reader().readText() } ?: error("No stream")
                val parsed = CubeParser.parse(text)
                val title = Regex("""TITLE\s+"([^"]*)"""").find(text)?.groupValues?.get(1)
                val name = title?.takeIf { it.isNotBlank() } ?: displayName(uri) ?: "Imported LUT"
                repository.userLooks.save(name, getString(R.string.color_match_imported_description), parsed)
                    .also { repository.reloadUserLooks() }
            }
        }
        result.onSuccess { d ->
            lut = repository.userLooks.lut(d)
            lookName.setText(d.name)
            LookPreferences(this@ColorMatchActivity).lookId = d.id
            toast(R.string.color_match_imported)
            renderPreview()
        }.onFailure { e ->
            Log.w(TAG, "Import failed", e)
            val msg = (e as? CubeParseException)?.message ?: e.message ?: ""
            Toast.makeText(this@ColorMatchActivity, getString(R.string.color_match_import_failed, msg), Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()

    /** Renders each photo at full resolution on the shared processing queue; originals stay untouched. */
    private fun applyToPhotos(uris: List<Uri>) {
        val params = lookParams(bakedLut(), 1f)
        val app = applicationContext
        val suffix = fileName()
        for (uri in uris) {
            BurstJobQueue.get(app).submit {
                runCatching { renderCopy(app, uri, params, suffix) }
                    .onFailure { Log.e(TAG, "Batch colour match failed for $uri", it) }
            }
        }
        Toast.makeText(this, resources.getQuantityString(R.plurals.color_match_batch_queued, uris.size, uris.size), Toast.LENGTH_SHORT).show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "ColorMatch"
        private const val PREVIEW_SIZE = 1024
        private const val SAMPLE_SIZE = 256
        private const val SKIN_PROTECTION = 0.7f
        private const val MAX_BATCH = 50
        private const val MAX_CUBE_BYTES = 16L * 1024 * 1024

        private fun samples(bmp: Bitmap): ColorSamples {
            val scale = SAMPLE_SIZE.toFloat() / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            return ColorSamples.fromArgb(px)
        }

        /** Decodes [uri] to at most about [maxSide] pixels on the long side, upright. */
        fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: return@runCatching null
            val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }.onFailure { Log.w(TAG, "Cannot decode $uri", it) }.getOrNull()

        private fun renderCopy(context: Context, uri: Uri, params: LookParams, suffix: String) {
            val tmp = File.createTempFile("colormatch", ".jpg", context.cacheDir)
            try {
                StillProcessor(context.contentResolver).process(uri, params, WatermarkTemplate.NONE, Build.MODEL, tmp)
                val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + "_" + suffix.take(20)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Open Camera")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Insert failed")
                try {
                    resolver.openOutputStream(out)?.use { os -> tmp.inputStream().use { it.copyTo(os) } } ?: error("No stream")
                } catch (e: Exception) {
                    resolver.delete(out, null, null)
                    throw e
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                Log.i(TAG, "Colour matched copy of $uri saved as $out")
            } finally {
                tmp.delete()
            }
        }
    }
}
