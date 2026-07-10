package ai.iris.node.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for assistant replies -- enough of CommonMark
 * to stop bold/headers/code/lists showing as raw source, without pulling a
 * parser dependency. Block level: ATX headings, fenced code, blockquote,
 * bullet/numbered lists, paragraphs. Inline: bold, italic, code, and links
 * (rendered as their text -- no link handling needed here).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code)
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    color = Mono.foreground,
                    fontSize = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 16.sp },
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 28.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                is MdBlock.Quote -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text("┃ ", color = Iris.amber, fontSize = 15.sp)
                    Text(inline(block.text), color = Mono.secondaryForeground, fontSize = 15.sp, lineHeight = 22.sp)
                }
                is MdBlock.ListItem -> Row(Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)) {
                    Text(block.marker, color = Iris.amber, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                    Text(inline(block.text), color = Mono.foreground, fontSize = 15.sp, lineHeight = 22.sp)
                }
                is MdBlock.Paragraph -> Text(
                    inline(block.text),
                    color = Mono.foreground,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Text(
        code,
        color = Mono.foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Mono.popover, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp)
    )
}

// ── Model ─────────────────────────────────────────────────────────────

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) out.add(MdBlock.Paragraph(para.toString().trim()))
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block
        if (trimmed.startsWith("```")) {
            flushPara()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code.append(lines[i]).append('\n'); i++
            }
            i++ // consume closing fence
            out.add(MdBlock.Code(code.toString().trimEnd('\n')))
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        val bullet = Regex("^[-*+]\\s+(.*)$").find(trimmed)
        val numbered = Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed)

        when {
            trimmed.isEmpty() -> flushPara()
            heading != null -> { flushPara(); out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])) }
            trimmed.startsWith(">") -> { flushPara(); out.add(MdBlock.Quote(trimmed.removePrefix(">").trim())) }
            bullet != null -> { flushPara(); out.add(MdBlock.ListItem("•", bullet.groupValues[1])) }
            numbered != null -> { flushPara(); out.add(MdBlock.ListItem("${numbered.groupValues[1]}.", numbered.groupValues[2])) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(trimmed) }
        }
        i++
    }
    flushPara()
    return out
}

// ── Inline ────────────────────────────────────────────────────────────

private val boldRe = Regex("\\*\\*(.+?)\\*\\*")
private val codeRe = Regex("`([^`]+?)`")
private val italicRe = Regex("(?<![*_])[*_]([^*_\\n]+?)[*_](?![*_])")
private val linkRe = Regex("\\[([^\\]]+?)]\\(([^)]+?)\\)")

/** Build an AnnotatedString applying inline spans. Precedence: code (opaque),
 *  then links→text, then bold, then italic. */
private fun inline(raw: String): AnnotatedString {
    // Links: replace [text](url) with just text before span parsing.
    val text = linkRe.replace(raw) { it.groupValues[1] }
    return buildAnnotatedString {
        var idx = 0
        // Tokenize on the first of code/bold/italic at each position.
        while (idx < text.length) {
            val code = codeRe.find(text, idx)
            val bold = boldRe.find(text, idx)
            val ital = italicRe.find(text, idx)
            val next = listOfNotNull(code, bold, ital).minByOrNull { it.range.first }
            if (next == null) { append(text.substring(idx)); break }
            if (next.range.first > idx) append(text.substring(idx, next.range.first))
            when (next) {
                code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Mono.popover, color = Iris.amber)) {
                    append(" ${next.groupValues[1]} ")
                }
                bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(next.groupValues[1]) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(next.groupValues[1]) }
            }
            idx = next.range.last + 1
        }
    }
}
