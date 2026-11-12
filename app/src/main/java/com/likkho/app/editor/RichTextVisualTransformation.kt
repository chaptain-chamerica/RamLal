package com.likkho.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle

private fun parseHexColor(hex: String): Color? = try {
    val clean = if (hex.startsWith("#")) hex else "#$hex"
    Color(android.graphics.Color.parseColor(clean))
} catch (e: Exception) {
    null
}

/**
 * VisualTransformation that renders bold, italic, underline, strike, text color, and highlight
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

        val builder = AnnotatedString.Builder(text.text)
        val textLen = text.length

        // Group spans by range to combine text decorations (e.g. underline + strike)
        for (span in spans) {
            val s = span.start.coerceIn(0, textLen)
            val e = span.end.coerceIn(0, textLen)
            if (s >= e) continue

            when (val style = span.style) {
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
                    val bg = parseHexColor(style.hex)
                    if (bg != null) {
                        builder.addStyle(androidx.compose.ui.text.SpanStyle(background = bg), s, e)
                    }
                }
                else -> {}
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
