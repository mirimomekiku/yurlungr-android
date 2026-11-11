package dev.yurlungr.app.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/** One change to the text: at [start], [removed] was replaced by [inserted]. */
internal data class TextEdit(val start: Int, val removed: String, val inserted: String) {
    fun undo(text: String): String = text.replaceRange(start, start + inserted.length, removed)
    fun redo(text: String): String = text.replaceRange(start, start + removed.length, inserted)
    val size: Int get() = removed.length + inserted.length

    companion object {
        /** The single edit that turns [before] into [after] (common prefix and suffix left out). */
        fun between(before: String, after: String): TextEdit {
            val max = minOf(before.length, after.length)
            var p = 0
            while (p < max && before[p] == after[p]) p++
            var s = 0
            while (s < max - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
            return TextEdit(p, before.substring(p, before.length - s), after.substring(p, after.length - s))
        }
    }
}

/** Edits undone or redone together (a burst of typing), with the selection around them. */
internal class EditGroup(val selectionBefore: TextRange) {
    val edits = ArrayList<TextEdit>()
    var selectionAfter: TextRange = selectionBefore
    val size: Int get() = edits.sumOf { it.size }
}

/**
 * Undo/redo as edits, not copies of the whole text: on a 1 MB file 300 snapshots were hundreds
 * of MB. Consecutive typing within [GROUP_MS] is one step. Kept under [MAX_CHARS] of edit text
 * and [MAX_GROUPS] steps; the oldest go first.
 */
internal class EditHistory {
    val undo = ArrayDeque<EditGroup>()
    val redo = ArrayDeque<EditGroup>()
    var version by mutableIntStateOf(0) // recompose when stacks change
    private var lastPush = 0L
    private var chars = 0L

    fun record(before: String, beforeSelection: TextRange, after: String, afterSelection: TextRange, boundary: Boolean, now: Long = System.currentTimeMillis()) {
        if (before == after) return
        val edit = TextEdit.between(before, after)
        val group = undo.lastOrNull()?.takeIf { !boundary && now - lastPush <= GROUP_MS }
            ?: EditGroup(beforeSelection).also { undo.addLast(it) }
        group.edits += edit
        group.selectionAfter = afterSelection
        chars += edit.size
        lastPush = now
        chars -= redo.sumOf { it.size.toLong() }
        redo.clear()
        while (undo.size > 1 && (undo.size > MAX_GROUPS || chars > MAX_CHARS)) chars -= undo.removeFirst().size
        version++
    }

    /** The text and selection before the last step, or null when there's nothing to undo. */
    fun undo(text: String): Pair<String, TextRange>? {
        val g = undo.removeLastOrNull() ?: return null
        redo.addLast(g)
        lastPush = 0
        version++
        return g.edits.asReversed().fold(text) { t, e -> e.undo(t) } to g.selectionBefore
    }

    fun redo(text: String): Pair<String, TextRange>? {
        val g = redo.removeLastOrNull() ?: return null
        undo.addLast(g)
        lastPush = 0
        version++
        return g.edits.fold(text) { t, e -> e.redo(t) } to g.selectionAfter
    }

    fun breakGroup() { lastPush = 0 }

    companion object {
        const val GROUP_MS = 800L
        const val MAX_GROUPS = 1_000
        const val MAX_CHARS = 4L * 1024 * 1024
    }
}

/**
 * Line start offsets, updated from the previous text where it can: lines before the change are
 * kept, lines after it shifted, and only the changed region is scanned for newlines. The three
 * places that ask for the same text get the cached array.
 */
internal object LineIndex {
    private var lastText: String? = null
    private var lastStarts: IntArray = intArrayOf(0)

    @Synchronized
    fun of(text: String): IntArray {
        val old = lastText
        if (old === text || old == text) return lastStarts
        val starts = if (old == null) full(text) else incremental(old, lastStarts, text)
        lastText = text
        lastStarts = starts
        return starts
    }

    fun full(text: String): IntArray {
        var count = 1
        for (c in text) if (c == '\n') count++
        val out = IntArray(count)
        var k = 1
        for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
        return out
    }

    internal fun incremental(old: String, oldStarts: IntArray, text: String): IntArray {
        val e = TextEdit.between(old, text)
        val oldEnd = e.start + e.removed.length
        val newEnd = e.start + e.inserted.length
        val delta = text.length - old.length
        // Lines starting at or before the change start stay; those starting after the old changed
        // region move by [delta]; the region in between is rescanned.
        val keep = lineIndexOf(oldStarts, e.start) + 1
        var after = keep
        while (after < oldStarts.size && oldStarts[after] <= oldEnd) after++
        val middle = ArrayList<Int>()
        for (i in e.start until newEnd) if (text[i] == '\n') middle += i + 1
        val out = IntArray(keep + middle.size + (oldStarts.size - after))
        System.arraycopy(oldStarts, 0, out, 0, keep)
        for ((j, v) in middle.withIndex()) out[keep + j] = v
        for (i in after until oldStarts.size) out[keep + middle.size + (i - after)] = oldStarts[i] + delta
        return out
    }
}
