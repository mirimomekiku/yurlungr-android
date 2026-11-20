package dev.yurlungr.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import java.io.File

/** Where a reply that's still streaming can be split into a finished part and the part being written. */
object StreamSplit {
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")
    private val DETAILS_OPEN = Regex("(?i)<details\\b")
    private val DETAILS_CLOSE = Regex("(?i)</details>")

    /**
     * The end of the finished part of [text]: just after the last blank line that is outside a
     * code fence and a `<details>` section and is followed by an unindented line (so it ends a
     * block rather than separating parts of a list item). 0 when there's none. Blocks before it
     * can't change any more, so they're parsed (and drawn) once.
     */
    fun stableEnd(text: String): Int {
        var fence: String? = null
        var details = 0
        var best = 0
        var pos = 0
        var previousBlank = false
        var blankEnd = -1
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            val end = if (nl < 0) text.length else nl
            val line = text.substring(pos, end)
            val complete = nl >= 0
            if (fence != null) {
                val open = fence!!
                val t = line.trim()
                if (t.length >= open.length && t.all { it == open[0] }) fence = null
                previousBlank = false
            } else {
                // A line after a blank one that starts a new block at the margin: everything before is final.
                if (previousBlank && details == 0 && line.isNotEmpty() && !line[0].isWhitespace()) best = blankEnd
                val m = FENCE.find(line)
                if (m != null) fence = m.groupValues[1]
                details += DETAILS_OPEN.findAll(line).count() - DETAILS_CLOSE.findAll(line).count()
                if (details < 0) details = 0
                val blank = line.isBlank()
                if (blank && complete) blankEnd = end + 1
                previousBlank = blank && complete
            }
            if (!complete) break
            pos = end + 1
        }
        return best
    }
}

/** Parsed blocks held by identity, so an unchanged part of a streaming reply isn't redrawn. */
@Immutable
private class StableBlocks(val blocks: List<MdBlock>, val host: MdHost)

@Composable
private fun Blocks(b: StableBlocks) {
    b.blocks.forEach { MdBlockView(it, b.host) }
}

/**
 * A reply that's still being written. [text] is read in a flow, not during composition, at most
 * about ten times a second: a streamed chunk doesn't recompose the caller, and only the part
 * after the last finished block is parsed again ([StreamSplit]). [MarkdownText] shows it once
 * it's done (one full parse, so references across the whole reply resolve).
 */
@Composable
fun MarkdownStream(text: () -> String, modifier: Modifier = Modifier, allowLocalFiles: Boolean = true, baseDirectory: File? = null) {
    val latest by rememberUpdatedState(text)
    val initial = remember { Snapshot.withoutReadObservation { text() } }
    val shown by produceState(initial) {
        snapshotFlow { latest() }.conflate().collect {
            value = it
            delay(STREAM_PARSE_MS)
        }
    }
    val split = remember(shown) { StreamSplit.stableEnd(shown) }
    val head = remember(shown, split) { shown.substring(0, split) }
    val host = rememberMdHost(allowLocalFiles, baseDirectory)
    val headBlocks = remember(head, host) { StableBlocks(MarkdownDoc.parse(RichBlocks.fenceDisplayMath(head)).blocks, host) }
    val tailBlocks = remember(shown, host) { StableBlocks(MarkdownDoc.parse(RichBlocks.fenceDisplayMath(shown.substring(split))).blocks, host) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Blocks(headBlocks)
        Blocks(tailBlocks)
    }
}

/** At most this often a streaming reply is parsed again (~10 Hz). */
private const val STREAM_PARSE_MS = 100L
