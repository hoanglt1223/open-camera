/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.opencamera.app.R
import dev.opencamera.app.develop.model.ToolStep
import dev.opencamera.pipeline.retouch.RetouchParams
import dev.opencamera.pipeline.retouch.RetouchTool
import dev.opencamera.pipeline.retouch.RetouchToolbox
import org.json.JSONObject
import java.io.File

/**
 * Hosts the retouch tools inside the develop editor. A step's params are the tool's
 * [RetouchParams] JSON (resolved values, normalized coordinates), so the same step replays on the
 * proxy and on the full-resolution export.
 */
class RetouchDevelopTool(val retouch: RetouchTool) : DevelopTool {
    override val id: String get() = retouch.id

    override fun label(context: Context): String = context.getString(RetouchTools.labelOf(id))

    override fun apply(image: Bitmap, step: ToolStep, scale: Float, assets: File): Bitmap {
        val params = RetouchTools.params(step) ?: throw IllegalArgumentException("bad $id params")
        // Tools edit in place; decoded export sources are immutable (or hardware) bitmaps.
        val src = if (image.isMutable && image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, true)
        // Failures propagate so ToolRegistry skips this step and reports it to the user.
        return retouch.apply(src, params).bitmap.also { Log.d("RetouchDevelopTool", "$id applied") }
    }
}

object RetouchTools {
    @Volatile
    private var toolbox: RetouchToolbox? = null

    /** Ids in the order of the editor's Retouch category (reshape is opt-in, last). */
    val ORDER = listOf("erase", "blemish", "skin", "redeye", "teeth", "eyes", "geometry", "whitebalance", "reshape")

    fun labelOf(id: String) = when (id) {
        "erase" -> R.string.retouch_tool_erase
        "blemish" -> R.string.retouch_tool_blemish
        "skin" -> R.string.retouch_tool_skin
        "redeye" -> R.string.retouch_tool_redeye
        "teeth" -> R.string.retouch_tool_teeth
        "eyes" -> R.string.retouch_tool_eyes
        "whitebalance" -> R.string.retouch_tool_wb
        "geometry" -> R.string.retouch_tool_geometry
        else -> R.string.retouch_tool_reshape
    }

    /** Creates the toolbox once and registers every tool; false when retouch is unavailable. */
    @Synchronized
    fun install(context: Context): Boolean {
        if (toolbox != null) return true
        if (!context.resources.getBoolean(R.bool.retouch_enabled)) return false
        return runCatching {
            val box = RetouchToolbox(context)
            box.tools.sortedBy { ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
                .forEach { ToolRegistry.register(RetouchDevelopTool(it)) }
            // Loads the native libraries and checks the models here, off main, for the panels.
            box.tools.forEach { t -> reasons[t.id] = java.util.Optional.ofNullable(runCatching { t.unavailableReason() }.getOrDefault("unavailable")) }
            toolbox = box
        }.onFailure { Log.w("RetouchTools", "Retouch tools unavailable", it) }.isSuccess
    }

    fun toolbox(): RetouchToolbox? = toolbox

    private val reasons = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()

    /** [RetouchTool.unavailableReason], resolved at [install] time. */
    fun unavailableReason(tool: dev.opencamera.pipeline.retouch.RetouchTool): String? {
        val cached = reasons[tool.id] ?: return tool.unavailableReason()
        return cached.orElse(null)
    }

    fun step(tool: String, params: RetouchParams) = ToolStep(tool, params.toJson().toString())

    fun params(step: ToolStep): RetouchParams? = runCatching { RetouchParams.fromJson(JSONObject(step.params)) }.getOrNull()

    /** Metadata tags (reshape, generated) the export must disclose. */
    fun tags(steps: List<ToolStep>): Set<String> {
        val box = toolbox ?: return emptySet()
        return steps.filter { it.enabled }.flatMapTo(HashSet()) { s ->
            val p = params(s) ?: return@flatMapTo emptySet()
            box.tool(s.toolId)?.tagsFor(p).orEmpty()
        }
    }
}
