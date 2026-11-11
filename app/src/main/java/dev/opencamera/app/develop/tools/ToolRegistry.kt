/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import dev.opencamera.app.develop.model.ToolStep
import java.io.File

/**
 * Integration point for tools that live outside the develop pipeline, such as the retouch tools
 * (healing, object removal, red eye, beauty, teeth whitening). A tool is a raster step: it
 * receives the upright source (before crop) and returns a modified copy. Steps run in stack
 * order after geometry is resolved and before tone, because their coordinates are stored in
 * source space (normalized 0..1), which keeps them valid at proxy and export resolution.
 *
 * Registering: `ToolRegistry.register(MyRetouchTool())` once at process start (for example from
 * the retouch package's initializer). The develop editor lists registered tools in its "Retouch"
 * tab and stores their steps as [ToolStep] (params JSON + file names in the edit's asset dir).
 */
interface DevelopTool {
    /** Stable id stored in edit stacks. */
    val id: String

    /** Label shown in the tool strip. */
    fun label(context: Context): String

    /**
     * Applies [step] to [image] (ARGB_8888, upright, uncropped). [scale] is image width / the
     * full source width, so pixel-sized params can be scaled; [assets] holds the step's files.
     * Must not throw for bad params: return the input unchanged instead.
     */
    fun apply(image: Bitmap, step: ToolStep, scale: Float, assets: File): Bitmap

    /**
     * Optional editing UI. The host calls [onStep] semantics through [ToolHost]; returning null
     * means the tool has no panel (applied with defaults).
     */
    fun createPanel(context: Context, host: ToolHost): View? = null
}

/** What a tool panel can do with the editor. */
interface ToolHost {
    /** Current proxy (upright, uncropped), for previews and hit testing. */
    val proxy: Bitmap?

    /** Adds or replaces a step of this tool; the editor re-renders and records history. */
    fun commit(step: ToolStep, label: String)

    /** Directory for the step's raster patches. */
    val assetDir: File
}

object ToolRegistry {
    private val tools = LinkedHashMap<String, DevelopTool>()

    @Synchronized
    fun register(tool: DevelopTool) {
        tools[tool.id] = tool
    }

    @Synchronized
    fun get(id: String): DevelopTool? = tools[id]

    @Synchronized
    fun all(): List<DevelopTool> = tools.values.toList()

    /**
     * Runs the enabled [steps] over [image]. Unknown tools (not installed in this build) and
     * failing tools are skipped and reported, never fatal.
     */
    fun applyAll(image: Bitmap, steps: List<ToolStep>, scale: Float, assets: File, skipped: MutableList<String>? = null): Bitmap {
        var out = image
        for (s in steps) {
            if (!s.enabled) continue
            val tool = get(s.toolId)
            if (tool == null) {
                skipped?.add(s.toolId)
                continue
            }
            out = try {
                tool.apply(out, s, scale, assets)
            } catch (e: Exception) {
                Log.w("ToolRegistry", "Tool ${s.toolId} failed", e)
                skipped?.add(s.toolId)
                out
            } catch (e: OutOfMemoryError) {
                skipped?.add(s.toolId)
                out
            }
        }
        return out
    }
}
