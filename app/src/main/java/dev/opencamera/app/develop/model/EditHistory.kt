/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.model

/**
 * Unlimited in-session undo/redo. Consecutive changes with the same non-null [push] key (one
 * slider drag, one brush stroke) collapse into a single step.
 */
class EditHistory(initial: EditStack) {
    private val undo = ArrayList<Entry>()
    private val redo = ArrayList<Entry>()
    private var lastKey: String? = null

    data class Entry(val stack: EditStack, val label: String)

    data class Snapshot(val name: String, val stack: EditStack, val createdAt: Long)

    var current: EditStack = initial
        private set

    val snapshots = ArrayList<Snapshot>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Labels of the steps that can be undone, oldest first. */
    val steps: List<String> get() = undo.map { it.label }

    /** Labels of the steps that can be redone, the next one first. */
    val futureSteps: List<String> get() = redo.asReversed().map { it.label }

    fun push(next: EditStack, label: String, coalesceKey: String? = null) {
        if (next == current) return
        if (coalesceKey == null || coalesceKey != lastKey || undo.isEmpty()) {
            undo += Entry(current, label)
        }
        current = next
        redo.clear()
        lastKey = coalesceKey
    }

    /** Ends coalescing, e.g. when the finger lifts off a slider. */
    fun seal() {
        lastKey = null
    }

    fun undo(): EditStack? {
        val e = undo.removeLastOrNull() ?: return null
        redo += Entry(current, e.label)
        current = e.stack
        lastKey = null
        return current
    }

    fun redo(): EditStack? {
        val e = redo.removeLastOrNull() ?: return null
        undo += Entry(current, e.label)
        current = e.stack
        lastKey = null
        return current
    }

    fun snapshot(name: String, now: Long = System.currentTimeMillis()) {
        snapshots += Snapshot(name, current, now)
    }

    /** Applies a snapshot as a normal, undoable step. */
    fun restore(snapshot: Snapshot) = push(snapshot.stack.copy(source = current.source), "Snapshot ${snapshot.name}")
}
