package com.likkho.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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
 * VisualTransformation that renders H1/H2/H3 headings, custom/built-in fonts,
 * bold, italic, underline, strike, and text color in Compose text fields
 * without altering text indices or offsets (UTF-16 safe for English & Hindi).
 */
class RichTextVisualTransformation(
    private val spans: List<Span>,
    private val defaultTextColor: Color = Color.Unspecified,
    private val quoteTextColor: Color = Color.Unspecified,
    private val codeTextColor: Color = Color.Unspecified,
    private val fontResolver: (String) -> FontFamily? = { FontRegistry.resolveFontFamily(it) }
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty() || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val raw = text.text
        val textLen = raw.length
        val builder = AnnotatedString.Builder(raw)

        // Apply base block styles first so explicit FontId / Color override defaults
        val orderedSpans = spans.sortedBy {
            when (it.style) {
                is SpanStyle.Heading -> 0
                is SpanStyle.Quote -> 1
                is SpanStyle.Code -> 2
                is SpanStyle.SizePx -> 3
                is SpanStyle.FontId -> 4
                else -> 5
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

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
