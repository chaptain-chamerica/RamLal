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
    private val defaultAlignment: TextAlignment = TextAlignment.LEFT,
    private val fontResolver: (String) -> FontFamily? = { FontRegistry.resolveFontFamily(it) }
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty() || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val raw = text.text
        val textLen = raw.length
        val builder = AnnotatedString.Builder(raw)

        // Emit merged non-overlapping ParagraphStyle strictly on full line bounds [lineStart, paraEnd)
        // including trailing '\n' so Compose MultiParagraph never inserts an implicit extra blank line.
        val hasParagraphSpans = spans.any { it.style is SpanStyle.Align || it.style is SpanStyle.Indent }
        if (hasParagraphSpans) {
            var lineStart = 0
            while (lineStart <= textLen) {
                var lineEnd = lineStart
                while (lineEnd < textLen && raw[lineEnd] != '\n') lineEnd++
                val paraEnd = if (lineEnd < textLen && raw[lineEnd] == '\n') lineEnd + 1 else lineEnd
                if (lineStart < paraEnd) {
                    var lineAlign: TextAlignment? = null
                    var lineIndent = 0
                    for (sp in spans) {
                        val s = sp.start.coerceIn(0, textLen)
                        val e = sp.end.coerceIn(0, textLen)
                        if (s < paraEnd && e > lineStart) {
                            when (val st = sp.style) {
                                is SpanStyle.Align -> lineAlign = st.alignment
                                is SpanStyle.Indent -> if (st.level > lineIndent) lineIndent = st.level
                                else -> {}
                            }
                        }
                    }
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

        fun baseFontSizeSpAt(s: Int, e: Int): Float {
            var pxSize: Float? = null
            var headingLvl: Int? = null
            var hasCode = false
            for (other in spans) {
                if (other.start < e && other.end > s) {
                    when (val ost = other.style) {
                        is SpanStyle.SizePx -> pxSize = ost.sizePx
                        is SpanStyle.Heading -> if (headingLvl == null || ost.level < headingLvl) headingLvl = ost.level
                        is SpanStyle.Code -> hasCode = true
                        else -> {}
                    }
                }
            }
            return pxSize?.coerceIn(10f, 48f)
                ?: headingLvl?.let { HeadingTypographySpec.fontSize(it).value }
                ?: if (hasCode) 14.5f else 16f
        }

        // Apply base block styles first so explicit FontId / Color / Super / Sub override defaults
        val orderedSpans = spans.sortedBy {
            when (it.style) {
                is SpanStyle.Heading -> 0
                is SpanStyle.Quote -> 1
                is SpanStyle.Code -> 2
                is SpanStyle.SizePx -> 3
                is SpanStyle.FontId -> 4
                is SpanStyle.Bold,
                is SpanStyle.Italic,
                is SpanStyle.Underline,
                is SpanStyle.Strike,
                is SpanStyle.Color -> 5
                is SpanStyle.Superscript,
                is SpanStyle.Subscript -> 6
                is SpanStyle.Todo -> 7
                is SpanStyle.Bullet,
                is SpanStyle.Numbered -> 8
                else -> 9
            }
        }

        for (span in orderedSpans) {
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
                is SpanStyle.Quote -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = WikiFonts.serif,
                            fontStyle = FontStyle.Italic,
                            color = quoteTextColor
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.Code -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = WikiFonts.mono,
                            fontSize = 14.5.sp,
                            color = codeTextColor
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.FontId -> {
                    val family = fontResolver(style.fontId)
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
                is SpanStyle.Bold -> {
                    builder.addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), s, e)
                }
                is SpanStyle.Italic -> {
                    builder.addStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic), s, e)
                }
                is SpanStyle.Underline -> {
                    builder.addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                }
                is SpanStyle.Strike -> {
                    builder.addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = TextDecoration.LineThrough), s, e)
                }
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
                    val color = parseHexColor(style.hex)
                    if (color != null) {
                        builder.addStyle(androidx.compose.ui.text.SpanStyle(color = color), s, e)
                    }
                }
                is SpanStyle.Bullet -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = WikiFonts.sans,
                            color = Color.Transparent,
                            letterSpacing = 4.sp
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.Numbered -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = WikiFonts.sans,
                            fontWeight = FontWeight.SemiBold,
                            color = if (numberedColor != Color.Unspecified) numberedColor else defaultTextColor
                        ),
                        s,
                        e
                    )
                }
                is SpanStyle.Todo -> {
                    builder.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = WikiFonts.sans,
                            color = Color.Transparent,
                            letterSpacing = 3.sp
                        ),
                        s,
                        e
                    )
                    if (style.checked && e < textLen) {
                        var lineEnd = e
                        while (lineEnd < textLen && raw[lineEnd] != '\n') lineEnd++
                        if (e < lineEnd) {
                            builder.addStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = if (todoCheckedTextColor != Color.Unspecified) todoCheckedTextColor else Color.Unspecified,
                                    textDecoration = TextDecoration.LineThrough
                                ),
                                e,
                                lineEnd
                            )
                        }
                    }
                }
                is SpanStyle.Highlight -> {
                    // Handled by HighlightRenderer to bound highlight strictly from ascender to descender
                }
                is SpanStyle.Divider -> {
                    builder.addStyle(androidx.compose.ui.text.SpanStyle(color = Color.Transparent), s, e)
                }
                else -> {}
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
