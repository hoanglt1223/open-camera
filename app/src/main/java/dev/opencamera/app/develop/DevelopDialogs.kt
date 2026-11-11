/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.develop.export.CrsXmp
import dev.opencamera.app.develop.export.ExportFormat
import dev.opencamera.app.develop.export.ExportOptions
import dev.opencamera.app.develop.export.Exporter
import dev.opencamera.app.develop.export.MetadataPolicy
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.OriginalBackups
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.preset.CubeIo
import dev.opencamera.app.develop.preset.EditClipboard
import dev.opencamera.app.develop.preset.Preset
import dev.opencamera.app.develop.preset.PresetCodec
import dev.opencamera.app.develop.ui.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Menus and dialogs of the editor. */
class DevelopDialogs(private val a: DevelopActivity) {
    private var askedLocation = false

    private fun dialog() = AlertDialog.Builder(a)

    fun moreMenu(anchor: View) {
        val m = PopupMenu(a, anchor)
        val items = listOf(
            R.string.develop_history, R.string.develop_snapshots, R.string.develop_copy, R.string.develop_paste,
            R.string.develop_batch, R.string.develop_xmp_export, R.string.develop_xmp_import, R.string.develop_revert,
        )
        items.forEachIndexed { i, s -> m.menu.add(0, i, i, s) }
        m.setOnMenuItemClickListener {
            when (items[it.itemId]) {
                R.string.develop_history -> history()
                R.string.develop_snapshots -> snapshots()
                R.string.develop_copy -> copyEdits()
                R.string.develop_paste -> pasteEdits()
                R.string.develop_batch -> pickBatch()
                R.string.develop_xmp_export -> exportXmp()
                R.string.develop_xmp_import -> importXmp()
                R.string.develop_revert -> revert()
            }
            true
        }
        m.show()
    }

    private fun history() {
        val steps = a.history.steps
        dialog().setTitle(R.string.develop_history)
            .setItems(steps.reversed().toTypedArray().ifEmpty { arrayOf("—") }) { _, which ->
                repeat(which + 1) { a.history.undo() }
                a.onStackChanged(true)
            }.show()
    }

    internal fun snapshots() {
        val snaps = a.history.snapshots
        val names = arrayOf(a.getString(R.string.develop_snapshot_new)) + snaps.map { it.name }
        dialog().setTitle(R.string.develop_snapshots).setItems(names) { _, which ->
            if (which == 0) {
                textInput(R.string.develop_snapshot_new, "Snapshot ${snaps.size + 1}") { name ->
                    a.history.snapshot(name); a.persist()
                }
            } else {
                val s = snaps[which - 1]
                dialog().setTitle(s.name).setItems(arrayOf(a.getString(R.string.develop_ok), a.getString(R.string.develop_snapshot_compare))) { _, w ->
                    if (w == 0) { a.history.restore(s); a.onStackChanged(true) } else {
                        a.compareStack = s.stack
                        if (!a.compare) a.toggleCompare() else a.engine.renderBefore(a.beforeStack())
                    }
                }.show()
            }
        }.show()
    }

    private fun groupsPicker(title: Int, includeMasksOption: Boolean, done: (Set<Group>, Boolean) -> Unit) {
        val groups = Group.entries
        val checked = BooleanArray(groups.size) { groups[it] != Group.GEOMETRY }
        val labels = groups.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }.toMutableList()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24f).toInt(), 0, a.dp(24f).toInt(), 0) }
        val boxes = labels.mapIndexed { i, l -> CheckBox(a).apply { text = l; isChecked = checked[i] }.also { box.addView(it) } }
        val masks = if (includeMasksOption) CheckBox(a).apply { setText(R.string.develop_tab_masks) }.also { box.addView(it) } else null
        dialog().setTitle(title).setView(box)
            .setPositiveButton(R.string.develop_ok) { _, _ ->
                done(groups.filterIndexed { i, _ -> boxes[i].isChecked }.toSet(), masks?.isChecked == true)
            }
            .setNegativeButton(R.string.develop_cancel, null).show()
    }

    fun copyEdits() = groupsPicker(R.string.develop_copy, includeMasksOption = false) { groups, _ ->
        EditClipboard.content = a.stack.subset(groups) to groups
        a.toast(a.getString(R.string.develop_copied))
    }

    fun pasteEdits() {
        val (s, groups) = EditClipboard.content ?: run { a.toast(a.getString(R.string.develop_nothing_to_paste)); return }
        a.change(a.stack.pasteFrom(s, groups), "Paste")
        a.seal()
        a.refreshPanel()
    }

    fun pickBatch() {
        a.onPickPhotos = { uris -> batch(uris) }
        a.pickPhotos.launch(arrayOf("image/*"))
    }

    /** Applies the current edits (chosen groups) to [uris] in the background service. */
    fun batch(uris: List<Uri>) = groupsPicker(R.string.develop_batch, includeMasksOption = false) { groups, _ ->
        // The service reads the photos after this activity may be gone; keep read grants alive.
        for (u in uris) runCatching { a.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DevelopBatchService.start(a, uris, a.stack, groups)
    }

    fun savePreset(done: () -> Unit) = groupsPicker(R.string.develop_preset_save, includeMasksOption = false) { groups, _ ->
        textInput(R.string.develop_preset_name, "Preset") { name ->
            val stack = a.stack
            onIo({ a.presets.save(name, groups, stack) }) {
                a.toast(a.getString(R.string.develop_preset_saved))
                done()
            }
        }
    }

    fun importPreset(done: () -> Unit) {
        a.onImport = { uri ->
            a.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = a.contentResolver.openInputStream(uri)?.use { s ->
                            val bytes = s.readNBytesCompat(32 shl 20)
                            String(bytes)
                        } ?: error("Cannot read file")
                        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "LUT"
                        if (text.trimStart().startsWith("{")) {
                            a.presets.import(text) { n, lut -> a.looks.userLooks.save(n, "Imported", lut).id }.name
                        } else {
                            val lut = CubeIo.parse(text)
                            val d = a.looks.userLooks.save(name, "Imported .cube", lut)
                            a.looks.reloadUserLooks()
                            a.presets.save(name, setOf(Group.LOOK), EditStack(lookId = d.id)).name
                        }
                    }
                }
                a.looks.reloadUserLooks()
                r.onSuccess { a.toast(a.getString(R.string.develop_preset_imported, it)); done() }
                    .onFailure { a.toast(a.getString(R.string.develop_preset_invalid, it.message ?: it.javaClass.simpleName)) }
            }
        }
        a.openDocument.launch(arrayOf("*/*"))
    }

    /** Preset files are written off the main thread; [then] runs on it afterwards, also after a failure. */
    private fun onIo(block: () -> Unit, then: () -> Unit) {
        a.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching(block).onFailure { Log.w("DevelopDialogs", "Preset write failed", it) } }
            then()
        }
    }

    fun presetMenu(p: Preset, changed: () -> Unit) {
        val actions = mutableListOf(R.string.develop_preset_export)
        if (!p.builtIn) actions += listOf(R.string.develop_preset_rename, R.string.develop_preset_up, R.string.develop_preset_down, R.string.develop_preset_delete)
        dialog().setTitle(p.name).setItems(actions.map { a.getString(it) }.toTypedArray()) { _, w ->
            when (actions[w]) {
                R.string.develop_preset_export -> sharePreset(p)
                R.string.develop_preset_rename -> textInput(R.string.develop_preset_rename, p.name) { onIo({ a.presets.rename(p.id, it) }, changed) }
                R.string.develop_preset_up -> onIo({ a.presets.move(p.id, -1) }, changed)
                R.string.develop_preset_down -> onIo({ a.presets.move(p.id, 1) }, changed)
                R.string.develop_preset_delete -> onIo({ a.presets.delete(p.id) }, changed)
            }
        }.show()
    }

    /** Shares `.ocpreset` (user LUT inline) or, for a LUT-only look preset, a `.cube`. */
    private fun sharePreset(p: Preset) {
        a.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(a.cacheDir, "develop-share").apply { mkdirs() }
                    val lookId = p.stack.lookId
                    val lut = lookId?.let { id -> a.looks.descriptor(id)?.let { d -> a.looks.params(d.id, 1f)?.lut } }
                    val safe = p.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    if (lut != null && p.groups == setOf(Group.LOOK)) {
                        File(dir, "$safe.cube").apply { writeText(CubeIo.write(lut, p.name)) }
                    } else {
                        File(dir, "$safe.${PresetCodec.EXTENSION}").apply {
                            writeText(PresetCodec.encode(p, lut?.takeIf { lookId?.startsWith("user-") == true }?.let { CubeIo.write(it, p.name) }))
                        }
                    }
                }.getOrNull()
            } ?: return@launch
            share(file, "application/octet-stream")
        }
    }

    private fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.develop.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        a.startActivity(Intent.createChooser(send, null))
    }

    fun curvePoints(channel: String, done: () -> Unit) {
        val current = a.stack.curve(channel).joinToString("; ") { "${(it.x * 255).roundToInt()},${(it.y * 255).roundToInt()}" }
        textInput(R.string.develop_curve_points, current, a.getString(R.string.develop_curve_points_hint)) { text ->
            val pts = text.split(';').mapNotNull { pair ->
                val p = pair.split(',').map { it.trim().toFloatOrNull() }
                if (p.size == 2 && p[0] != null && p[1] != null) CurvePoint(p[0]!! / 255f, p[1]!! / 255f) else null
            }
            if (pts.size >= 2) { a.change(a.stack.withCurve(channel, pts), "Curve"); a.seal(); done() }
        }
    }

    fun gradeValues(range: String, done: () -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24f).toInt(), 0, a.dp(24f).toInt(), 0) }
        val fields = listOf("hue" to R.string.develop_grade_hue, "sat" to R.string.develop_grade_sat, "lum" to R.string.develop_grade_lum).map { (k, l) ->
            EditText(a).apply {
                hint = a.getString(l)
                contentDescription = a.getString(l)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(a.stack[Params.grade(range, k)].roundToInt().toString())
            }.also { box.addView(TextView(a).apply { setText(l) }); box.addView(it) } to k
        }
        dialog().setTitle(R.string.develop_grade_enter).setView(box).setPositiveButton(R.string.develop_ok) { _, _ ->
            var s = a.stack
            for ((f, k) in fields) f.text.toString().replace(',', '.').toFloatOrNull()?.let { s = s.with(Params.grade(range, k), it) }
            a.change(s, "Grading"); a.seal(); done()
        }.setNegativeButton(R.string.develop_cancel, null).show()
    }

    internal fun revert() {
        dialog().setMessage(R.string.develop_revert_confirm).setPositiveButton(R.string.develop_ok) { _, _ ->
            a.change(EditStack(source = a.stack.source), "Revert")
            a.seal()
            a.refreshPanel()
            a.info?.let { i -> a.lifecycleScope.launch(Dispatchers.IO) { runCatching { a.store.delete(i.ref) } } }
        }.setNegativeButton(R.string.develop_cancel, null).show()
    }

    internal fun exportXmp() {
        val i = a.info ?: return
        a.toast(a.getString(R.string.develop_xmp_note, CrsXmp.NOT_TRANSFERRED.joinToString("; ")))
        a.onCreateDocument = { uri ->
            runCatching { a.contentResolver.openOutputStream(uri)?.use { it.write(CrsXmp.write(a.stack).toByteArray()) } }
                .onSuccess { a.toast(a.getString(R.string.develop_xmp_saved, uri.lastPathSegment ?: "")) }
                .onFailure { a.toast(a.getString(R.string.develop_export_failed, it.message)) }
        }
        a.createDocument.launch(i.displayName.substringBeforeLast('.') + ".xmp")
    }

    internal fun importXmp() {
        a.onImport = { uri ->
            runCatching {
                val text = a.contentResolver.openInputStream(uri)?.use { String(it.readNBytesCompat(4 shl 20)) } ?: error("Cannot read")
                CrsXmp.read(text, a.stack)
            }.onSuccess {
                a.change(it, "Import XMP"); a.seal(); a.refreshPanel()
                a.toast(a.getString(R.string.develop_xmp_note, CrsXmp.NOT_TRANSFERRED.joinToString("; ")))
            }.onFailure { a.toast(a.getString(R.string.develop_preset_invalid, it.message)) }
        }
        a.openDocument.launch(arrayOf("*/*"))
    }

    private fun textInput(title: Int, initial: String, hint: String? = null, done: (String) -> Unit) {
        val edit = EditText(a).apply { setText(initial); this.hint = hint; selectAll() }
        val box = LinearLayout(a).apply { setPadding(a.dp(24f).toInt(), 0, a.dp(24f).toInt(), 0); addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        dialog().setTitle(title).setView(box).setPositiveButton(R.string.develop_ok) { _, _ -> done(edit.text.toString()) }
            .setNegativeButton(R.string.develop_cancel, null).show()
    }

    // ---- export ------------------------------------------------------------------------

    fun export() {
        val info = a.info ?: return
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24f).toInt(), a.dp(8f).toInt(), a.dp(24f).toInt(), 0) }
        fun title(t: Int) = box.addView(TextView(a).apply { setText(t); textSize = 15f })
        fun radios(options: List<String>, checked: Int): RadioGroup {
            val g = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
            options.forEachIndexed { i, o -> g.addView(RadioButton(a).apply { id = View.generateViewId(); text = o; isChecked = i == checked; minHeight = a.dp(48f).toInt() }) }
            box.addView(android.widget.HorizontalScrollView(a).apply { addView(g) })
            return g
        }
        fun index(g: RadioGroup) = (0 until g.childCount).indexOfFirst { (g.getChildAt(it) as RadioButton).isChecked }.coerceAtLeast(0)
        val formats = ExportFormat.available().filter { it != ExportFormat.HEIF || Build.VERSION.SDK_INT >= 28 }
        title(R.string.develop_export_format)
        val fmt = radios(formats.map { it.name }, 0)
        title(R.string.develop_export_quality)
        val quality = com.google.android.material.slider.Slider(a).apply { valueFrom = 50f; valueTo = 100f; stepSize = 1f; value = 92f }
        box.addView(quality)
        title(R.string.develop_export_size)
        val sizes = listOf(0, 4096, 2048, 1080)
        val size = radios(listOf(a.getString(R.string.develop_export_size_original), "4096", "2048", "1080"), 0)
        val custom = EditText(a).apply { hint = a.getString(R.string.develop_export_size_custom); inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(custom)
        title(R.string.develop_export_metadata)
        val meta = radios(listOf(R.string.develop_meta_keep, R.string.develop_meta_location, R.string.develop_meta_all).map { a.getString(it) }, 0)
        title(R.string.develop_export_sharpen)
        val sharpen = radios(listOf(R.string.develop_sharpen_none, R.string.develop_sharpen_screen, R.string.develop_sharpen_print).map { a.getString(it) }, 0)
        fun options(forShare: Boolean): ExportOptions {
            val customSide = custom.text.toString().toIntOrNull()?.coerceIn(64, 20000)
            val m = MetadataPolicy.entries[index(meta)]
            return ExportOptions(
                format = formats[index(fmt)],
                quality = quality.value.roundToInt(),
                longSide = customSide ?: sizes[index(size)],
                // Sharing removes location unless the user chose to strip everything.
                metadata = if (forShare && m == MetadataPolicy.KEEP_ALL) MetadataPolicy.STRIP_LOCATION else m,
                outputSharpen = index(sharpen),
            )
        }
        val scroll = android.widget.ScrollView(a).apply { addView(box) }
        dialog().setTitle(R.string.develop_export_title).setView(scroll)
            .setPositiveButton(R.string.develop_save_copy) { _, _ -> run(info, options(false), share = false, overwrite = false) }
            .setNeutralButton(R.string.develop_share) { _, _ -> run(info, options(true), share = true, overwrite = false) }
            .setNegativeButton(R.string.develop_overwrite) { _, _ ->
                dialog().setMessage(R.string.develop_overwrite_confirm).setPositiveButton(R.string.develop_ok) { _, _ ->
                    run(info, options(false).copy(format = if (info.mime == "image/png") ExportFormat.PNG else ExportFormat.JPEG), share = false, overwrite = true)
                }.setNegativeButton(R.string.develop_cancel, null).show()
            }
            .show()
    }

    internal fun confirmOverwrite(info: dev.opencamera.app.develop.io.SourceLoader.Info, options: ExportOptions) {
        dialog().setMessage(R.string.develop_overwrite_confirm).setPositiveButton(R.string.develop_ok) { _, _ ->
            run(info, options.copy(format = if (info.mime == "image/png") ExportFormat.PNG else ExportFormat.JPEG), share = false, overwrite = true)
        }.setNegativeButton(R.string.develop_cancel, null).show()
    }

    internal fun run(info: dev.opencamera.app.develop.io.SourceLoader.Info, options: ExportOptions, share: Boolean, overwrite: Boolean) {
        // Keeping the location needs ACCESS_MEDIA_LOCATION; without it the export simply has no GPS.
        if (options.metadata == MetadataPolicy.KEEP_ALL && Build.VERSION.SDK_INT >= 29 &&
            a.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !askedLocation
        ) {
            askedLocation = true
            a.onLocationResult = { run(info, options, share, overwrite) }
            a.locationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        val stack = a.stack
        val exporter = Exporter(a)
        a.persist()
        a.status.text = a.getString(R.string.develop_exporting, 0)
        a.lifecycleScope.launch {
            val look = withContext(Dispatchers.IO) { runCatching { a.looks.params(stack.lookId, 1f) }.getOrNull() }
            val assets = a.store.assetDir(info.ref)
            val shareFile = if (share || overwrite) File(File(a.cacheDir, "develop-share").apply { mkdirs() },
                info.displayName.substringBeforeLast('.') + "_edit." + options.format.ext) else null
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    exporter.export(info, stack, options, look, { a.ai.load(it, assets) }, { f ->
                        a.runOnUiThread { a.status.text = a.getString(R.string.develop_exporting, (f * 100).roundToInt()) }
                    }, shareFile)
                }
            }
            result.onSuccess { r ->
                a.status.text = a.getString(R.string.develop_exported, r.width, r.height, r.ms / 1000f)
                if (r.notes.contains("ultra_hdr_dropped")) a.toast(a.getString(R.string.develop_ultra_hdr))
                when {
                    share && r.file != null -> share(r.file, options.format.mime)
                    overwrite && r.file != null -> overwriteOriginal(info, r.file)
                    else -> a.toast(a.getString(R.string.develop_exported, r.width, r.height, r.ms / 1000f))
                }
            }.onFailure { e ->
                Log.w("DevelopExport", "Export failed", e)
                if (options.format != ExportFormat.JPEG && e !is OutOfMemoryError) {
                    // HEIF/AVIF/PNG encoders can fail on some devices: fall back to JPEG.
                    a.toast(a.getString(R.string.develop_export_failed, "${options.format.name} → JPEG"))
                    run(info, options.copy(format = ExportFormat.JPEG), share, overwrite)
                    return@launch
                }
                val msg = if (e is OutOfMemoryError) "out of memory" else e.message ?: e.javaClass.simpleName
                a.status.text = a.getString(R.string.develop_export_failed, msg)
                a.toast(a.getString(R.string.develop_export_failed, msg))
            }
        }
    }

    /**
     * Replaces the original's bytes after the user grants write access (createWriteRequest on
     * API 30+, RecoverableSecurityException on 29). The original is backed up for 30 days.
     */
    private fun overwriteOriginal(info: dev.opencamera.app.develop.io.SourceLoader.Info, file: File) {
        val backups = OriginalBackups(File(a.filesDir, "develop-originals"))
        // Null while waiting for the user to grant write access.
        fun write(): Boolean? = runCatching {
            backups.purge()
            if (backups.find(info.ref.hash) == null) {
                a.contentResolver.openInputStream(info.uri)?.let { backups.keep(info.ref.hash, info.displayName, it) }
            }
            a.contentResolver.openOutputStream(info.uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        }.getOrElse { e ->
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                a.onWriteGranted = { ok -> if (ok) write()?.let { finishOverwrite(it) } else a.toast(a.getString(R.string.develop_overwrite_denied)) }
                a.writeRequest.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                return@getOrElse null
            }
            Log.w("DevelopExport", "Overwrite failed", e)
            false
        }
        if (Build.VERSION.SDK_INT >= 30 && info.uri.authority == MediaStore.AUTHORITY) {
            a.onWriteGranted = { ok -> if (ok) write()?.let { finishOverwrite(it) } else a.toast(a.getString(R.string.develop_overwrite_denied)) }
            val pi = MediaStore.createWriteRequest(a.contentResolver, listOf(info.uri))
            a.writeRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            write()?.let { finishOverwrite(it) }
        }
    }

    private fun finishOverwrite(ok: Boolean) {
        a.toast(a.getString(if (ok) R.string.develop_overwrite_done else R.string.develop_overwrite_denied))
        if (ok) a.open(a.info?.uri ?: return)
    }
}

/** Reads at most [limit] bytes (API 26 has no readNBytes). */
fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1 shl 16)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
