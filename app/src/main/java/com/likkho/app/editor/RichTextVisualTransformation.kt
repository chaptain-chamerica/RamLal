package com.likkho.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.likkho.app.WikiFonts
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle

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
 * VisualTransformation that renders H1/H2/H3 headings, bold, italic, underline, strike, and text color
 * in Compose text fields without altering text indices or offsets.
 */
class RichTextVisualTransformation(
    private val spans: List<Span>,
    private val defaultTextColor: Color = Color.Unspecified
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty() || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val raw = text.text
        val textLen = raw.length
        val builder = AnnotatedString.Builder(raw)

        for (span in spans) {
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
                is SpanStyle.Color -> {
                    val color = parseHexColor(style.hex)
                    if (color != null) {
                        builder.addStyle(androidx.compose.ui.text.SpanStyle(color = color), s, e)
                    }
                }
                is SpanStyle.Highlight -> {
                    // Handled by HighlightRenderer to bound highlight strictly from ascender to descender
                }
                else -> {}
            }
        }

        // Apply non-overlapping ParagraphStyle line heights for paragraphs containing headings
        val headingSpans = spans.filter { it.style is SpanStyle.Heading }
        if (headingSpans.isNotEmpty()) {
            var lineStart = 0
            while (lineStart < textLen) {
                val nlIdx = raw.indexOf('\n', lineStart)
                val lineEnd = if (nlIdx == -1) textLen else nlIdx
                val paraEnd = if (nlIdx == -1) textLen else (nlIdx + 1).coerceAtMost(textLen)

                if (lineStart < lineEnd && lineStart < paraEnd) {
                    var bestLevel: Int? = null
                    for (sp in headingSpans) {
                        val s = sp.start.coerceIn(0, textLen)
                        val e = sp.end.coerceIn(0, textLen)
                        if (s < lineEnd && e > lineStart) {
                            val lvl = (sp.style as SpanStyle.Heading).level
                            if (bestLevel == null || lvl < bestLevel) bestLevel = lvl
                        }
                    }
                    if (bestLevel != null) {
                        builder.addStyle(
                            ParagraphStyle(lineHeight = HeadingTypographySpec.lineHeight(bestLevel)),
                            lineStart,
                            paraEnd
                        )
                    }
                }
                if (nlIdx == -1) break
                lineStart = nlIdx + 1
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
