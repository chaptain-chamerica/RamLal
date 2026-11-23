package com.likkho.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.likkho.app.WikiFonts
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.model.TextAlignment

fun TextAlignment.toComposeTextAlign(): TextAlign = when (this) {
    TextAlignment.LEFT -> TextAlign.Left
    TextAlignment.CENTER -> TextAlign.Center
    TextAlignment.RIGHT -> TextAlign.Right
    TextAlignment.JUSTIFY -> TextAlign.Justify
}

object HeadingTypographySpec {
    fun fontSize(level: Int): TextUnit = when (level) {
        1 -> 26.sp
        2 -> 22.sp
        else -> 18.sp
    }

    fun lineHeight(level: Int): TextUnit = when (level) {
        1 -> 34.sp
        2 -> 30.sp
        else -> 27.sp
    }

    fun fontWeight(level: Int): FontWeight = when (level) {
        1 -> FontWeight.Bold
        2 -> FontWeight.Bold
        else -> FontWeight.SemiBold
    }

    val fontFamily: FontFamily = WikiFonts.serif
}

private fun parseHexColor(hex: String): Color? = try {
    val clean = if (hex.startsWith("#")) hex else "#$hex"
    Color(android.graphics.Color.parseColor(clean))
} catch (e: Exception) {
    null
}

/**
 * VisualTransformation that renders H1/H2/H3 headings, custom/built-in fonts,
 * bold, italic, underline, strike, and text color in Compose text fields
 * without altering text indices or offsets (UTF-16 safe for English & Hindi).
 */
class RichTextVisualTransformation(
    private val spans: List<Span>,
    private val defaultTextColor: Color = Color.Unspecified,
    private val quoteTextColor: Color = Color.Unspecified,
    private val codeTextColor: Color = Color.Unspecified,
    private val numberedColor: Color = Color.Unspecified,
    private val todoCheckedTextColor: Color = Color.Unspecified,
    private val linkColor: Color = Color(0xFF3366CC),
    private val unlockedSpoilerIds: Set<String> = emptySet(),
    private val defaultAlignment: TextAlignment = TextAlignment.LEFT,
    private val fontResolver: (String) -> FontFamily? = { FontRegistry.resolveFontFamily(it) }
) : VisualTransformation {

    private data class ParaEntry(
        val start: Int,
        val end: Int,
        val align: TextAlignment?,
        val indent: Int,
        val order: Int
    )

    private data class SizingEntry(
        val start: Int,
        val end: Int,
        val sizePx: Float?,
        val headingLevel: Int?,
        val isCode: Boolean,
        val order: Int
    )

    private class CompiledSpan(
        val span: Span,
        val resolvedColor: Color?,
        val resolvedFontFamily: FontFamily?
    )

    private val paragraphEntries: List<ParaEntry>
    private val sizingEntries: List<SizingEntry>
    private val maxSizingSpanLen: Int
    private val compiledOrderedSpans: List<CompiledSpan>

    init {
        if (spans.isEmpty()) {
            paragraphEntries = emptyList()
            sizingEntries = emptyList()
            maxSizingSpanLen = 0
            compiledOrderedSpans = emptyList()
        } else {
            val paras = ArrayList<ParaEntry>()
            val sizings = ArrayList<SizingEntry>()
            val toCompile = ArrayList<Pair<Int, Span>>(spans.size)
            val colorCache = HashMap<String, Color?>(8)
            val fontCache = HashMap<String, FontFamily?>(4)
            var maxSizeLen = 0

            spans.forEachIndexed { idx, sp ->
                when (val st = sp.style) {
                    is SpanStyle.Align -> {
                        if (sp.start < sp.end) {
                            paras.add(ParaEntry(sp.start, sp.end, align = st.alignment, indent = 0, order = idx))
                        }
                    }
                    is SpanStyle.Indent -> {
                        if (sp.start < sp.end) {
                            paras.add(ParaEntry(sp.start, sp.end, align = null, indent = st.level, order = idx))
                        }
                    }
                    is SpanStyle.Highlight -> {
                        // Rendered by HighlightRenderer
                    }
                    else -> {
                        when (st) {
                            is SpanStyle.SizePx -> {
                                val len = (sp.end - sp.start).coerceAtLeast(0)
                                if (len > maxSizeLen) maxSizeLen = len
                                sizings.add(SizingEntry(sp.start, sp.end, sizePx = st.sizePx, headingLevel = null, isCode = false, order = idx))
                            }
                            is SpanStyle.Heading -> {
                                val len = (sp.end - sp.start).coerceAtLeast(0)
                                if (len > maxSizeLen) maxSizeLen = len
                                sizings.add(SizingEntry(sp.start, sp.end, sizePx = null, headingLevel = st.level, isCode = false, order = idx))
                            }
                            is SpanStyle.Code -> {
                                val len = (sp.end - sp.start).coerceAtLeast(0)
                                if (len > maxSizeLen) maxSizeLen = len
                                sizings.add(SizingEntry(sp.start, sp.end, sizePx = null, headingLevel = null, isCode = true, order = idx))
                            }
                            else -> {}
                        }
                        val prio = when (st) {
                            is SpanStyle.Heading -> 0
                            is SpanStyle.Quote -> 1
                            is SpanStyle.Code -> 2
                            is SpanStyle.SizePx -> 3
                            is SpanStyle.FontId -> 4
                            is SpanStyle.Bold,
                            is SpanStyle.Italic,
                            is SpanStyle.Underline,
                            is SpanStyle.Strike,
                            is SpanStyle.Link,
                            is SpanStyle.Color -> 5
                            is SpanStyle.Superscript,
                            is SpanStyle.Subscript -> 6
                            is SpanStyle.Spoiler -> 7
                            is SpanStyle.Todo -> 8
                            is SpanStyle.Bullet,
                            is SpanStyle.Numbered -> 9
                            else -> 10
                        }
                        toCompile.add(prio to sp)
                    }
                }
            }

            if (paras.size > 1) paras.sortBy { it.start }
            if (sizings.size > 1) sizings.sortBy { it.start }
            if (toCompile.size > 1) toCompile.sortBy { it.first }

            paragraphEntries = paras
            sizingEntries = sizings
            maxSizingSpanLen = maxSizeLen

            val compiled = ArrayList<CompiledSpan>(toCompile.size)
            for ((_, sp) in toCompile) {
                val color = when (val st = sp.style) {
                    is SpanStyle.Color -> colorCache.getOrPut(st.hex) { parseHexColor(st.hex) }
                    else -> null
                }
                val ff = when (val st = sp.style) {
                    is SpanStyle.FontId -> fontCache.getOrPut(st.fontId) { fontResolver(st.fontId) }
                    else -> null
                }
                compiled.add(CompiledSpan(sp, color, ff))
            }
            compiledOrderedSpans = compiled
        }
    }

    private fun baseFontSizeSpAt(s: Int, e: Int): Float {
        if (sizingEntries.isEmpty()) return 16f
        val minPossibleStart = s - maxSizingSpanLen
        var lo = 0
        var hi = sizingEntries.size
        if (sizingEntries.size > 8 && minPossibleStart > 0) {
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sizingEntries[mid].start < minPossibleStart) lo = mid + 1 else hi = mid
            }
        }
        var bestPxOrder = -1
        var pxSize: Float? = null
        var headingLvl: Int? = null
        var hasCode = false
        for (i in lo until sizingEntries.size) {
            val other = sizingEntries[i]
            if (other.start >= e) break
            if (other.end > s) {
                if (other.sizePx != null && other.order > bestPxOrder) {
                    bestPxOrder = other.order
                    pxSize = other.sizePx
                }
                val hLvl = other.headingLevel
                if (hLvl != null && (headingLvl == null || hLvl < headingLvl)) {
                    headingLvl = hLvl
                }
                if (other.isCode) hasCode = true
            }
        }
        return pxSize?.coerceIn(10f, 48f)
            ?: headingLvl?.let { HeadingTypographySpec.fontSize(it).value }
            ?: if (hasCode) 14.5f else 16f
    }

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty() || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val raw = text.text
        val textLen = raw.length
        val builder = AnnotatedString.Builder(raw)

        // Emit merged non-overlapping ParagraphStyle strictly on full line bounds [lineStart, paraEnd)
        // including trailing '\n' in O(lines + P) via a single linear pointer sweep.
        if (paragraphEntries.isNotEmpty()) {
            var pIdx = 0
            val activeParas = ArrayList<ParaEntry>(4)
            var lineStart = 0
            while (lineStart <= textLen) {
                var lineEnd = lineStart
                while (lineEnd < textLen && raw[lineEnd] != '\n') lineEnd++
                val paraEnd = if (lineEnd < textLen && raw[lineEnd] == '\n') lineEnd + 1 else lineEnd
                if (lineStart < paraEnd) {
                    while (pIdx < paragraphEntries.size && paragraphEntries[pIdx].start.coerceIn(0, textLen) < paraEnd) {
                        val entry = paragraphEntries[pIdx++]
                        if (entry.end.coerceIn(0, textLen) > lineStart) {
                            activeParas.add(entry)
                        }
                    }
                    var bestAlignOrder = -1
                    var lineAlign: TextAlignment? = null
                    var lineIndent = 0
                    var writeIdx = 0
                    for (i in 0 until activeParas.size) {
                        val entry = activeParas[i]
                        val es = entry.start.coerceIn(0, textLen)
                        val ee = entry.end.coerceIn(0, textLen)
                        if (ee > lineStart) {
                            activeParas[writeIdx++] = entry
                            if (es < paraEnd) {
                                if (entry.align != null && entry.order > bestAlignOrder) {
                                    bestAlignOrder = entry.order
                                    lineAlign = entry.align
                                }
                                if (entry.indent > lineIndent) lineIndent = entry.indent
                            }
                        }
                    }
                    while (activeParas.size > writeIdx) activeParas.removeAt(activeParas.lastIndex)

                    if (lineAlign != null || lineIndent > 0) {
                        val indentSp = (lineIndent * 24).sp
                        builder.addStyle(
                            androidx.compose.ui.text.ParagraphStyle(
                                textAlign = (lineAlign ?: defaultAlignment).toComposeTextAlign(),
                                textIndent = if (lineIndent > 0) TextIndent(firstLine = indentSp, restLine = indentSp) else null
                            ),
                            lineStart,
                            paraEnd
                        )
                    }
                }
                if (lineEnd >= textLen) break
                lineStart = lineEnd + 1
            }
        }

        val quoteStyle = androidx.compose.ui.text.SpanStyle(
            fontFamily = WikiFonts.serif,
            fontStyle = FontStyle.Italic,
            color = quoteTextColor
        )
        val codeStyle = androidx.compose.ui.text.SpanStyle(
            fontFamily = WikiFonts.mono,
            fontSize = 14.5.sp,
            color = codeTextColor
        )
        val boldStyle = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
        val italicStyle = androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)
        val underlineStyle = androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.Underline)
        val strikeStyle = androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.LineThrough)
        val linkStyle = androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        val bulletStyle = androidx.compose.ui.text.SpanStyle(
            fontFamily = WikiFonts.sans,
            color = Color.Transparent,
            letterSpacing = 4.sp
        )
        val numberedStyle = androidx.compose.ui.text.SpanStyle(
            fontFamily = WikiFonts.sans,
            fontWeight = FontWeight.SemiBold,
            color = if (numberedColor != Color.Unspecified) numberedColor else defaultTextColor
        )
        val todoPrefixStyle = androidx.compose.ui.text.SpanStyle(
            fontFamily = WikiFonts.sans,
            color = Color.Transparent,
            letterSpacing = 3.sp
        )
        val todoCheckedStrikeStyle = androidx.compose.ui.text.SpanStyle(
            color = if (todoCheckedTextColor != Color.Unspecified) todoCheckedTextColor else Color.Unspecified,
            textDecoration = TextDecoration.LineThrough
        )
        val transparentStyle = androidx.compose.ui.text.SpanStyle(color = Color.Transparent)

        for (compiled in compiledOrderedSpans) {
            val span = compiled.span
            val s = span.start.coerceIn(0, textLen)
            val e = span.end.coerceIn(0, textLen)
            if (s >= e) continue

            when (val style = span.style) {
                is SpanStyle.Heading -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = HeadingTypographySpec.fontFamily,
                            fontSize = HeadingTypographySpec.fontSize(style.level),
                            fontWeight = HeadingTypographySpec.fontWeight(style.level)
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.Quote -> builder.addStyle(quoteStyle, s, e)
                is SpanStyle.Code -> builder.addStyle(codeStyle, s, e)
                is SpanStyle.FontId -> {
                    val family = compiled.resolvedFontFamily
                    if (family != null) {
                        builder.addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = family), s, e)
                    }
                }
                is SpanStyle.SizePx -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(fontSize = style.sizePx.coerceIn(10f, 48f).sp),
                        s,
                        e
                    )
                }
                is SpanStyle.Bold -> builder.addStyle(boldStyle, s, e)
                is SpanStyle.Italic -> builder.addStyle(italicStyle, s, e)
                is SpanStyle.Underline -> builder.addStyle(underlineStyle, s, e)
                is SpanStyle.Strike -> builder.addStyle(strikeStyle, s, e)
                is SpanStyle.Superscript -> {
                    val scaledSp = (baseFontSizeSpAt(s, e) * 0.72f).coerceAtLeast(8f).sp
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontSize = scaledSp,
                            baselineShift = BaselineShift(0.45f)
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.Subscript -> {
                    val scaledSp = (baseFontSizeSpAt(s, e) * 0.72f).coerceAtLeast(8f).sp
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontSize = scaledSp,
                            baselineShift = BaselineShift(-0.25f)
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.Color -> {
                    val color = compiled.resolvedColor
                    if (color != null) {
                        builder.addStyle(androidx.compose.ui.text.SpanStyle(color = color), s, e)
                    }
                }
                is SpanStyle.Link -> builder.addStyle(linkStyle, s, e)
                is SpanStyle.Bullet -> builder.addStyle(bulletStyle, s, e)
                is SpanStyle.Numbered -> builder.addStyle(numberedStyle, s, e)
                is SpanStyle.Todo -> {
                    builder.addStyle(todoPrefixStyle, s, e)
                    if (style.checked && e < textLen) {
                        var lineEnd = e
                        while (lineEnd < textLen && raw[lineEnd] != '\n') lineEnd++
                        if (e < lineEnd) {
                            builder.addStyle(todoCheckedStrikeStyle, e, lineEnd)
                        }
                    }
                }
                is SpanStyle.Spoiler -> {
                    if (style.id !in unlockedSpoilerIds) {
                        builder.addStyle(transparentStyle, s, e)
                    }
                }
                is SpanStyle.Divider -> builder.addStyle(transparentStyle, s, e)
                else -> {}
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
