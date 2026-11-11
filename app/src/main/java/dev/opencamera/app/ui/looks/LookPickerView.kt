/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.looks

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Horizontal strip of look names; the selected one is highlighted. */
class LookPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(12)
        setPadding(pad, dp(8), pad, dp(8))
    }
    private var selectedId: String? = null

    var onLookSelected: ((String) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0x99000000.toInt())
        addView(row)
    }

    /** [items] are (id, label) pairs in display order. */
    fun setLooks(items: List<Pair<String, String>>, selected: String) {
        row.removeAllViews()
        for ((id, label) in items) {
            row.addView(TextView(context).apply {
                text = label
                tag = id
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                contentDescription = label
                setOnClickListener { onLookSelected?.invoke(id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) })
        }
        setSelected(selected)
    }

    fun setSelected(id: String) {
        selectedId = id
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as TextView
            val on = chip.tag == id
            chip.isSelected = on
            chip.setTextColor(if (on) Color.BLACK else Color.WHITE)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (on) setColor(Color.WHITE) else setStroke(dp(1), 0x80ffffff.toInt())
            }
            if (on) post { smoothScrollTo((chip.left - (width - chip.width) / 2).coerceAtLeast(0), 0) }
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
