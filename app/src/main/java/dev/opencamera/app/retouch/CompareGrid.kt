/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.retouch

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.opencamera.app.R

/**
 * Side-by-side comparison of 2-4 results of the same edit (object removal backends, white
 * balance estimators, geometry variants; usable for any multi-method tool). Results arrive one
 * by one; a failed or timed-out variant is dropped from the grid. Tapping a cell opens it full
 * screen with a before/after divider (double tap zooms); "Use this" returns its index.
 */
class CompareGrid(
    private val context: Context,
    private val before: Bitmap,
    private val labels: List<String>,
    private val onPick: (Int) -> Unit,
    private val onDismiss: () -> Unit = {},
) {
    private class Cell(val root: LinearLayout, val image: ImageView, val status: TextView, val progress: ProgressBar)

    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val cells = ArrayList<Cell>()
    private val results = arrayOfNulls<Bitmap>(labels.size)
    private val grid = GridLayout(context).apply { columnCount = 2 }
    private val container = FrameLayout(context)

    fun show() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(context).apply {
            setText(R.string.retouch_compare_hint)
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        })
        val size = context.resources.displayMetrics.widthPixels / 2 - 16
        labels.forEachIndexed { i, label ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = label
                setImageBitmap(before)
                alpha = 0.4f
            }
            val status = TextView(context).apply {
                text = label
                setTextColor(0xffffffff.toInt())
                gravity = Gravity.CENTER
            }
            val progress = ProgressBar(context)
            val frame = FrameLayout(context).apply {
                addView(image, FrameLayout.LayoutParams(size, size))
                addView(progress, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
                addView(frame)
                addView(status)
                setOnClickListener { results[i]?.let { zoom(i, it) } }
            }
            cells += Cell(cell, image, status, progress)
            grid.addView(cell)
        }
        container.addView(grid)
        root.addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(Button(context).apply {
            setText(R.string.retouch_cancel)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    /** Shows variant [i]; null removes the cell with [status] as the reason (logged by the caller). */
    fun setResult(i: Int, bitmap: Bitmap?, status: String) {
        val cell = cells.getOrNull(i) ?: return
        cell.progress.visibility = android.view.View.GONE
        if (bitmap == null) {
            grid.removeView(cell.root)
            return
        }
        results[i] = bitmap
        cell.image.alpha = 1f
        cell.image.setImageBitmap(bitmap)
        cell.status.text = "${labels[i]} · $status"
    }

    val isShowing get() = dialog.isShowing

    private fun zoom(i: Int, bitmap: Bitmap) {
        container.removeAllViews()
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(BeforeAfterView(context, before, bitmap), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(context).apply {
            setText(R.string.retouch_back_to_grid)
            setOnClickListener {
                container.removeAllViews()
                container.addView(grid)
            }
        })
        buttons.addView(Button(context).apply {
            text = context.getString(R.string.retouch_use_this, labels[i])
            setOnClickListener {
                onPick(i)
                dialog.dismiss()
            }
        })
        col.addView(buttons)
        container.addView(col)
    }
}
