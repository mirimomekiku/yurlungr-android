package dev.yurlungr.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Block(val text: String, val isCode: Boolean)

/** Splits on ``` fences. An unclosed fence (still streaming) renders as code. */
private fun splitFences(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val buf = StringBuilder()
    var inCode = false
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (buf.isNotEmpty() || inCode) blocks += Block(buf.toString().trimEnd('\n'), inCode)
            buf.clear()
            inCode = !inCode
            continue
        }
        buf.append(line).append('\n')
    }
    if (buf.isNotBlank()) blocks += Block(buf.toString().trimEnd('\n'), inCode)
    return blocks
}

private val INLINE = Regex("""`([^`]+)`|\*\*([^*]+)\*\*""")

private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    for ((i, rawLine) in text.lines().withIndex()) {
        if (i > 0) append('\n')
        var line = rawLine
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) line = line.substring(heading.value.length)
        if (heading != null) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        var pos = 0
        for (m in INLINE.findAll(line)) {
            append(line.substring(pos, m.range.first))
            val code = m.groups[1]?.value
            if (code != null) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(code) }
            } else {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groups[2]?.value.orEmpty()) }
            }
            pos = m.range.last + 1
        }
        append(line.substring(pos))
        if (heading != null) pop()
    }
}

/** Minimal markdown: fenced code blocks, inline code, bold, headings. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { splitFences(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            if (block.isCode) {
                CodeBlock(block.text)
            } else if (block.text.isNotBlank()) {
                Text(inline(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        maxLines = maxLines,
        softWrap = false,
        color = Color(0xFFE6EDF3),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(Color(0xFF0D1117))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    )
}
